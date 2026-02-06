package com.gdrivesync.app.data.sync

import android.content.Context
import com.gdrivesync.app.data.database.SyncDatabase
import com.gdrivesync.app.data.database.SyncFileDao
import com.gdrivesync.app.data.google.GoogleDriveService
import com.gdrivesync.app.data.model.SyncFile
import com.gdrivesync.app.data.preferences.PreferencesManager
import com.gdrivesync.app.util.FileHashUtil
import com.gdrivesync.app.util.LocalFileHelper
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

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
            
            val localFileHelper = LocalFileHelper(context, localFolderPath)
            
            // Récupérer les fichiers depuis Drive
            val driveFiles = driveService.listFiles(driveFolderId)
            
            // Récupérer les fichiers locaux
            val localFiles = getLocalFiles(localFileHelper)
            
            // Synchroniser
            var filesDownloaded = 0
            var filesUploaded = 0
            var filesUpdated = 0
            var filesDeleted = 0
            
            // Télécharger les nouveaux fichiers depuis Drive
            for (driveFile in driveFiles) {
                val fileName = driveFile.name
                val existingSyncFile = syncFileDao.getFileById(driveFile.id)
                
                if (existingSyncFile == null) {
                    // Nouveau fichier à télécharger
                    if (driveFile.mimeType == "application/vnd.google-apps.folder") {
                        // Créer le dossier local
                        localFileHelper.createDirectory(fileName)
                    } else {
                        if (downloadFileToLocal(driveService, driveFile.id, localFileHelper, fileName)) {
                            filesDownloaded++
                        }
                    }
                    
                    // Enregistrer dans la base de données
                    val fileHash = if (!localFileHelper.isDirectory(fileName) && localFileHelper.exists(fileName)) {
                        calculateHash(localFileHelper, fileName)
                    } else null
                    syncFileDao.insertFile(createSyncFile(driveFile, localFileHelper, fileName, driveFolderId, fileHash))
                } else {
                    // Logique "Last Modified Wins" pour résoudre les conflits
                    val driveModifiedTime = driveFile.modifiedTime?.value ?: 0
                    val localModifiedTime = if (localFileHelper.exists(fileName)) {
                        localFileHelper.lastModified(fileName)
                    } else 0
                    
                    // Calculer les hash pour détecter les vraies modifications
                    val currentLocalHash = if (localFileHelper.exists(fileName) && !localFileHelper.isDirectory(fileName)) {
                        calculateHash(localFileHelper, fileName)
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
                                    if (downloadFileToLocal(driveService, driveFile.id, localFileHelper, fileName)) {
                                        filesUpdated++
                                    }
                                }
                            } else {
                                // Local est plus récent, uploader
                                if (driveFile.mimeType != "application/vnd.google-apps.folder") {
                                    if (uploadFileFromLocal(driveService, driveFile.id, localFileHelper, fileName)) {
                                        filesUploaded++
                                    }
                                }
                            }
                            syncFileDao.insertFile(createSyncFile(driveFile, localFileHelper, fileName, driveFolderId, currentLocalHash))
                        }
                        // Drive modifié uniquement
                        hasDriveChanged -> {
                            if (driveFile.mimeType != "application/vnd.google-apps.folder") {
                                if (downloadFileToLocal(driveService, driveFile.id, localFileHelper, fileName)) {
                                    filesUpdated++
                                }
                            }
                            syncFileDao.insertFile(createSyncFile(driveFile, localFileHelper, fileName, driveFolderId, currentLocalHash))
                        }
                        // Local modifié uniquement
                        hasLocalChanged -> {
                            if (driveFile.mimeType != "application/vnd.google-apps.folder") {
                                if (uploadFileFromLocal(driveService, driveFile.id, localFileHelper, fileName)) {
                                    filesUploaded++
                                }
                            }
                            syncFileDao.insertFile(createSyncFile(driveFile, localFileHelper, fileName, driveFolderId, currentLocalHash))
                        }
                        // Aucun changement détecté
                        else -> {
                            // Mettre à jour le hash si nécessaire
                            if (currentLocalHash != existingSyncFile.fileHash) {
                                syncFileDao.insertFile(createSyncFile(driveFile, localFileHelper, fileName, driveFolderId, currentLocalHash))
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
                    // Extraire le nom du fichier depuis le chemin
                    val fileName = syncFile.fileName
                    if (localFileHelper.exists(fileName)) {
                        localFileHelper.delete(fileName)
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
    
    private fun getLocalFiles(localFileHelper: LocalFileHelper): List<String> {
        return localFileHelper.listFiles()
    }
    
    private fun downloadFileToLocal(
        driveService: GoogleDriveService,
        fileId: String,
        localFileHelper: LocalFileHelper,
        fileName: String
    ): Boolean {
        return try {
            // Créer un fichier temporaire pour télécharger
            val tempFile = File(context.cacheDir, "temp_$fileName")
            if (driveService.downloadFile(fileId, tempFile)) {
                // Copier vers le dossier local
                localFileHelper.getOutputStream(fileName)?.use { output ->
                    tempFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
                tempFile.delete()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("SyncService", "Erreur lors du téléchargement de $fileName", e)
            false
        }
    }
    
    private fun uploadFileFromLocal(
        driveService: GoogleDriveService,
        fileId: String,
        localFileHelper: LocalFileHelper,
        fileName: String
    ): Boolean {
        return try {
            // Créer un fichier temporaire pour uploader
            val tempFile = File(context.cacheDir, "temp_$fileName")
            localFileHelper.getInputStream(fileName)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val result = driveService.updateFile(fileId, tempFile)
            tempFile.delete()
            result
        } catch (e: Exception) {
            android.util.Log.e("SyncService", "Erreur lors de l'upload de $fileName", e)
            false
        }
    }
    
    private fun calculateHash(localFileHelper: LocalFileHelper, fileName: String): String? {
        return try {
            // Pour DocumentFile, on utilise la taille et la date de modification
            if (localFileHelper.exists(fileName)) {
                val size = localFileHelper.getInputStream(fileName)?.use { input ->
                    var totalBytes = 0L
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        totalBytes += bytesRead
                    }
                    totalBytes
                } ?: 0L
                "${size}_${localFileHelper.lastModified(fileName)}"
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("SyncService", "Erreur lors du calcul du hash pour $fileName", e)
            null
        }
    }
    
    private fun createSyncFile(
        driveFile: DriveFile,
        localFileHelper: LocalFileHelper,
        fileName: String,
        parentFolderId: String?,
        fileHash: String? = null
    ): SyncFile {
        val hash = fileHash ?: if (!localFileHelper.isDirectory(fileName) && localFileHelper.exists(fileName)) {
            calculateHash(localFileHelper, fileName)
        } else null
        
        return SyncFile(
            driveFileId = driveFile.id,
            fileName = driveFile.name,
            localPath = localFileHelper.getAbsolutePath(fileName),
            drivePath = driveFile.name,
            mimeType = driveFile.mimeType ?: "",
            size = (driveFile.size ?: 0L).toLong(),
            modifiedTime = if (localFileHelper.exists(fileName)) localFileHelper.lastModified(fileName) else 0,
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

