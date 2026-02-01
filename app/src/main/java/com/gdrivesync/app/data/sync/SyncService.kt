package com.gdrivesync.app.data.sync

import android.content.Context
import com.gdrivesync.app.data.database.SyncDatabase
import com.gdrivesync.app.data.database.SyncFileDao
import com.gdrivesync.app.data.google.GoogleDriveService
import com.gdrivesync.app.data.model.SyncFile
import com.gdrivesync.app.data.preferences.PreferencesManager
import com.gdrivesync.app.util.FileHashUtil
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SyncService(
    private val context: Context,
    private val driveService: GoogleDriveService,
    private val preferencesManager: PreferencesManager,
    private val syncFileDao: SyncFileDao
) {
    
    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        try {
            if (!driveService.isSignedIn()) {
                return@withContext SyncResult.Error("Non connecté à Google Drive")
            }
            
            val driveFolderId = preferencesManager.getDriveFolderIdSync()
            val localFolderPath = preferencesManager.getLocalFolderPathSync()
            
            if (driveFolderId == null || localFolderPath == null) {
                return@withContext SyncResult.Error("Dossier non configuré")
            }
            
            val localFolder = File(localFolderPath)
            if (!localFolder.exists()) {
                localFolder.mkdirs()
            }
            
            // Récupérer les fichiers depuis Drive
            val driveFiles = driveService.listFiles(driveFolderId)
            
            // Récupérer les fichiers locaux
            val localFiles = getLocalFiles(localFolder)
            
            // Synchroniser
            var filesDownloaded = 0
            var filesUploaded = 0
            var filesUpdated = 0
            var filesDeleted = 0
            
            // Télécharger les nouveaux fichiers depuis Drive
            for (driveFile in driveFiles) {
                val localFile = File(localFolder, driveFile.name)
                val existingSyncFile = syncFileDao.getFileById(driveFile.id)
                
                if (existingSyncFile == null) {
                    // Nouveau fichier à télécharger
                    if (driveFile.mimeType == "application/vnd.google-apps.folder") {
                        // Créer le dossier local
                        localFile.mkdirs()
                    } else {
                        if (driveService.downloadFile(driveFile.id, localFile)) {
                            filesDownloaded++
                        }
                    }
                    
                    // Enregistrer dans la base de données
                    val fileHash = if (!localFile.isDirectory && localFile.exists()) {
                        FileHashUtil.calculateQuickHash(localFile)
                    } else null
                    syncFileDao.insertFile(createSyncFile(driveFile, localFile, driveFolderId, fileHash))
                } else {
                    // Logique "Last Modified Wins" pour résoudre les conflits
                    val driveModifiedTime = driveFile.modifiedTime?.value ?: 0
                    val localModifiedTime = if (localFile.exists()) localFile.lastModified() else 0
                    
                    // Calculer les hash pour détecter les vraies modifications
                    val currentLocalHash = if (localFile.exists() && !localFile.isDirectory) {
                        FileHashUtil.calculateQuickHash(localFile)
                    } else null
                    
                    val hasLocalChanged = currentLocalHash != null && 
                        (currentLocalHash != existingSyncFile.fileHash || 
                         localModifiedTime > existingSyncFile.modifiedTime)
                    val hasDriveChanged = driveModifiedTime > existingSyncFile.driveModifiedTime
                    
                    when {
                        // Conflit : les deux ont été modifiés
                        hasLocalChanged && hasDriveChanged -> {
                            // Last Modified Wins : utiliser la version la plus récente
                            if (driveModifiedTime >= localModifiedTime) {
                                // Drive est plus récent, télécharger
                                if (driveFile.mimeType != "application/vnd.google-apps.folder") {
                                    if (driveService.downloadFile(driveFile.id, localFile)) {
                                        filesUpdated++
                                    }
                                }
                            } else {
                                // Local est plus récent, uploader
                                if (driveFile.mimeType != "application/vnd.google-apps.folder") {
                                    if (driveService.updateFile(driveFile.id, localFile)) {
                                        filesUploaded++
                                    }
                                }
                            }
                            syncFileDao.insertFile(createSyncFile(driveFile, localFile, driveFolderId, currentLocalHash))
                        }
                        // Drive modifié uniquement
                        hasDriveChanged -> {
                            if (driveFile.mimeType != "application/vnd.google-apps.folder") {
                                if (driveService.downloadFile(driveFile.id, localFile)) {
                                    filesUpdated++
                                }
                            }
                            syncFileDao.insertFile(createSyncFile(driveFile, localFile, driveFolderId, currentLocalHash))
                        }
                        // Local modifié uniquement
                        hasLocalChanged -> {
                            if (driveFile.mimeType != "application/vnd.google-apps.folder") {
                                if (driveService.updateFile(driveFile.id, localFile)) {
                                    filesUploaded++
                                }
                            }
                            syncFileDao.insertFile(createSyncFile(driveFile, localFile, driveFolderId, currentLocalHash))
                        }
                        // Aucun changement détecté
                        else -> {
                            // Mettre à jour le hash si nécessaire
                            if (currentLocalHash != existingSyncFile.fileHash) {
                                syncFileDao.insertFile(createSyncFile(driveFile, localFile, driveFolderId, currentLocalHash))
                            }
                        }
                    }
                }
            }
            
            // Supprimer les fichiers locaux qui n'existent plus sur Drive
            val driveFileIds = driveFiles.map { it.id }.toSet()
            val localSyncFiles = syncFileDao.getFilesByFolderId(driveFolderId)
            
            for (syncFile in localSyncFiles) {
                if (!driveFileIds.contains(syncFile.driveFileId)) {
                    val localFile = File(syncFile.localPath)
                    if (localFile.exists()) {
                        localFile.delete()
                    }
                    syncFileDao.deleteFile(syncFile)
                    filesDeleted++
                }
            }
            
            // Mettre à jour le temps de dernière synchronisation
            preferencesManager.setLastSyncTime(System.currentTimeMillis())
            
            SyncResult.Success(
                filesDownloaded = filesDownloaded,
                filesUploaded = filesUploaded,
                filesUpdated = filesUpdated,
                filesDeleted = filesDeleted
            )
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Erreur inconnue")
        }
    }
    
    private fun getLocalFiles(folder: File): List<File> {
        val files = mutableListOf<File>()
        if (folder.exists() && folder.isDirectory) {
            folder.listFiles()?.forEach { file ->
                files.add(file)
                if (file.isDirectory) {
                    files.addAll(getLocalFiles(file))
                }
            }
        }
        return files
    }
    
    private fun createSyncFile(
        driveFile: DriveFile,
        localFile: File,
        parentFolderId: String?,
        fileHash: String? = null
    ): SyncFile {
        val hash = fileHash ?: if (!localFile.isDirectory && localFile.exists()) {
            FileHashUtil.calculateQuickHash(localFile)
        } else null
        
        return SyncFile(
            driveFileId = driveFile.id,
            fileName = driveFile.name,
            localPath = localFile.absolutePath,
            drivePath = driveFile.name,
            mimeType = driveFile.mimeType ?: "",
            size = (driveFile.size ?: 0L).toLong(),
            modifiedTime = if (localFile.exists()) localFile.lastModified() else 0,
            driveModifiedTime = driveFile.modifiedTime?.value ?: 0,
            isDirectory = driveFile.mimeType == "application/vnd.google-apps.folder",
            parentFolderId = parentFolderId,
            fileHash = hash,
            syncStatus = "synced"
        )
    }
    
    sealed class SyncResult {
        data class Success(
            val filesDownloaded: Int,
            val filesUploaded: Int,
            val filesUpdated: Int,
            val filesDeleted: Int
        ) : SyncResult()
        
        data class Error(val message: String) : SyncResult()
    }
}

