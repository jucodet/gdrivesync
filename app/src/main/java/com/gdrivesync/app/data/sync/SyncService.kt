package com.gdrivesync.app.data.sync

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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

            // Lancer une synchronisation récursive à partir du dossier racine choisi
            val stats = SyncStats()
            syncFolder(driveFolderId, localFileHelper, stats)
            
            // Mettre à jour le temps de dernière synchronisation
            preferencesManager.setLastSyncTime(System.currentTimeMillis())
            
            SyncResult.Success(
                filesDownloaded = stats.filesDownloaded,
                filesUploaded = stats.filesUploaded,
                filesUpdated = stats.filesUpdated,
                filesDeleted = stats.filesDeleted
            )
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Erreur inconnue")
        }
    }

    /**
     * Synchronise récursivement un dossier Drive avec son dossier local correspondant.
     */
    private suspend fun syncFolder(
        driveFolderId: String,
        localFileHelper: LocalFileHelper,
        stats: SyncStats
    ) {
        // Récupérer les fichiers depuis Drive pour ce dossier
        val driveFiles = driveService.listFiles(driveFolderId).toMutableList()

        // Télécharger / mettre à jour les fichiers depuis Drive
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
                        stats.filesDownloaded++
                    }
                }

                // Enregistrer dans la base de données
                val fileHash = if (!localFileHelper.isDirectory(fileName) && localFileHelper.exists(fileName)) {
                    calculateHash(localFileHelper, fileName)
                } else null
                syncFileDao.insertFile(createSyncFile(driveFile, localFileHelper, fileName, driveFolderId, fileHash))
            } else {
                // Détection d'un déplacement côté Drive : même ID, mais parentFolderId différent
                val isMovedOnDrive = existingSyncFile.parentFolderId != driveFolderId
                // Détection d'un renommage côté Drive : l'ID est identique mais le nom a changé
                val isRenamedOnDrive = existingSyncFile.fileName != fileName

                // Si un fichier a été déplacé dans un autre dossier Drive, il faut le déplacer localement
                // (sinon il sera considéré "absent" dans le nouveau dossier et pourra être supprimé au nettoyage).
                if (isMovedOnDrive && !existingSyncFile.isDirectory && !localFileHelper.exists(fileName)) {
                    val moved = moveLocalFileByStoredPathToFolder(
                        sourcePath = existingSyncFile.localPath,
                        targetFolderHelper = localFileHelper,
                        targetFileName = fileName
                    )
                    if (!moved && driveFile.mimeType != "application/vnd.google-apps.folder") {
                        // Si on ne peut pas déplacer le fichier local (ex: source introuvable),
                        // on retélécharge dans le nouvel emplacement.
                        downloadFileToLocal(driveService, driveFile.id, localFileHelper, fileName)
                    }

                    val updatedHash = if (localFileHelper.exists(fileName) && !localFileHelper.isDirectory(fileName)) {
                        calculateHash(localFileHelper, fileName)
                    } else null
                    syncFileDao.insertFile(
                        createSyncFile(
                            driveFile = driveFile,
                            localFileHelper = localFileHelper,
                            fileName = fileName,
                            parentFolderId = driveFolderId,
                            fileHash = updatedHash
                        )
                    )
                }

                val localExists = localFileHelper.exists(fileName)
                
                // Si le dossier a été supprimé localement mais existe toujours sur Drive,
                // on propage la suppression vers Drive (pour les répertoires uniquement).
                if (existingSyncFile.isDirectory && !localExists) {
                    val deletedOnDrive = driveService.deleteFile(driveFile.id)
                    if (deletedOnDrive) {
                        syncFileDao.deleteFile(existingSyncFile)
                        stats.filesDeleted++
                    }
                    // Ne pas continuer la synchronisation sur ce dossier supprimé
                    continue
                }

                // Si un fichier a été supprimé localement mais existe toujours sur Drive,
                // on propage également la suppression vers Drive.
                // Attention : ne pas confondre avec un renommage effectué sur Drive.
                // Dans le cas d'un renommage distant, le fichier peut encore exister localement
                // sous son ancien nom (existingSyncFile.fileName), donc on ne doit pas le supprimer.
                if (!existingSyncFile.isDirectory && !localExists && !isRenamedOnDrive) {
                    val deletedOnDrive = driveService.deleteFile(driveFile.id)
                    if (deletedOnDrive) {
                        syncFileDao.deleteFile(existingSyncFile)
                        stats.filesDeleted++
                    }
                    // Ne pas tenter de résoudre des conflits sur un fichier supprimé localement
                    continue
                }

                // Logique "Last Modified Wins" pour résoudre les conflits
                val driveModifiedTime = driveFile.modifiedTime?.value ?: 0
                val localModifiedTime = if (localExists) {
                    localFileHelper.lastModified(fileName)
                } else 0

                // Calculer les hash pour détecter les vraies modifications (uniquement pour les fichiers)
                val currentLocalHash = if (localExists && !localFileHelper.isDirectory(fileName)) {
                    calculateHash(localFileHelper, fileName)
                } else null

                // Un déplacement local (copie + suppression) peut modifier lastModified sans changer le contenu.
                // On ne doit pas uploader uniquement à cause d'un move détecté côté Drive.
                val hasLocalContentChanged = currentLocalHash != null && currentLocalHash != existingSyncFile.fileHash
                val hasLocalChanged = if (isMovedOnDrive) {
                    hasLocalContentChanged
                } else {
                    currentLocalHash != null && (hasLocalContentChanged || localModifiedTime > existingSyncFile.modifiedTime)
                }
                val hasDriveChanged = driveModifiedTime > existingSyncFile.driveModifiedTime

                when {
                    // Conflit : les deux ont été modifiés
                    hasLocalChanged && hasDriveChanged -> {
                        // Last Modified Wins : utiliser la version la plus récente
                        if (driveModifiedTime >= localModifiedTime) {
                            // Drive est plus récent, télécharger
                            if (driveFile.mimeType != "application/vnd.google-apps.folder") {
                                if (downloadFileToLocal(driveService, driveFile.id, localFileHelper, fileName)) {
                                    stats.filesUpdated++
                                }
                            }
                        } else {
                            // Local est plus récent, uploader
                            if (driveFile.mimeType != "application/vnd.google-apps.folder") {
                                if (uploadFileFromLocal(driveService, driveFile.id, localFileHelper, fileName)) {
                                    stats.filesUploaded++
                                }
                            }
                        }
                        syncFileDao.insertFile(createSyncFile(driveFile, localFileHelper, fileName, driveFolderId, currentLocalHash))
                    }
                    // Drive modifié uniquement
                    hasDriveChanged -> {
                        if (driveFile.mimeType != "application/vnd.google-apps.folder") {
                            if (downloadFileToLocal(driveService, driveFile.id, localFileHelper, fileName)) {
                                stats.filesUpdated++
                                // Si le fichier a été renommé sur Drive, supprimer l'ancien fichier local
                                if (isRenamedOnDrive && localFileHelper.exists(existingSyncFile.fileName)) {
                                    localFileHelper.delete(existingSyncFile.fileName)
                                    stats.filesDeleted++
                                }
                            }
                        }
                        syncFileDao.insertFile(createSyncFile(driveFile, localFileHelper, fileName, driveFolderId, currentLocalHash))
                    }
                    // Local modifié uniquement
                    hasLocalChanged -> {
                        if (driveFile.mimeType != "application/vnd.google-apps.folder") {
                            if (uploadFileFromLocal(driveService, driveFile.id, localFileHelper, fileName)) {
                                stats.filesUploaded++
                            }
                        }
                        syncFileDao.insertFile(createSyncFile(driveFile, localFileHelper, fileName, driveFolderId, currentLocalHash))
                    }
                    // Aucun changement détecté
                    else -> {
                        // Si le parent a changé (move côté Drive), on met à jour la DB même si le contenu n'a pas changé.
                        // Sinon, le nettoyage du dossier parent supprimera l'entrée et potentiellement le fichier local.
                        if (isMovedOnDrive || currentLocalHash != existingSyncFile.fileHash) {
                            syncFileDao.insertFile(
                                createSyncFile(
                                    driveFile = driveFile,
                                    localFileHelper = localFileHelper,
                                    fileName = fileName,
                                    parentFolderId = driveFolderId,
                                    fileHash = currentLocalHash
                                )
                            )
                        }
                    }
                }
            }

            // Si c'est un dossier, synchroniser récursivement son contenu
            if (driveFile.mimeType == "application/vnd.google-apps.folder") {
                val subLocalPath = localFileHelper.getAbsolutePath(fileName)
                val subLocalHelper = LocalFileHelper(context, subLocalPath)
                syncFolder(driveFile.id, subLocalHelper, stats)
            }
        }

        // Supprimer les fichiers locaux qui n'existent plus sur Drive pour ce dossier
        val driveFileIds = driveFiles.map { it.id }.toSet()
        val localSyncFiles = syncFileDao.getFilesByFolderId(driveFolderId)

        for (syncFile in localSyncFiles) {
            if (!driveFileIds.contains(syncFile.driveFileId)) {
                val fileName = syncFile.fileName
                if (localFileHelper.exists(fileName)) {
                    localFileHelper.delete(fileName)
                }
                syncFileDao.deleteFile(syncFile)
                stats.filesDeleted++
            }
        }

        // Uploader les nouveaux fichiers locaux qui n'existent pas encore sur Drive pour ce dossier
        val localFiles = localFileHelper.listFiles()
        for (fileName in localFiles) {
            // Si le fichier local n'est associé à aucun fichier Drive (par le nom),
            // on le crée sur Drive, ou on considère que c'est un renommage local
            val existingDriveFile = driveFiles.firstOrNull { it.name == fileName }
            if (existingDriveFile == null) {
                // Tenter de détecter un renommage local : même contenu qu'un fichier déjà connu
                val currentLocalHash = if (localFileHelper.exists(fileName) && !localFileHelper.isDirectory(fileName)) {
                    calculateHash(localFileHelper, fileName)
                } else null

                val renamedSyncFile = if (currentLocalHash != null) {
                    localSyncFiles.firstOrNull { syncFile ->
                        !syncFile.isDirectory &&
                            syncFile.fileHash == currentLocalHash &&
                            driveFileIds.contains(syncFile.driveFileId)
                    }
                } else null

                if (renamedSyncFile != null) {
                    // On considère qu'il s'agit d'un renommage sur le smartphone :
                    // renommer le fichier côté Drive au lieu de créer un doublon
                    val renamedOnDrive = driveService.renameFile(renamedSyncFile.driveFileId, fileName)
                    if (renamedOnDrive) {
                        val updatedSyncFile = renamedSyncFile.copy(
                            fileName = fileName,
                            drivePath = fileName,
                            localPath = localFileHelper.getAbsolutePath(fileName)
                        )
                        syncFileDao.insertFile(updatedSyncFile)
                        // Pas de filesUploaded++ : c'est un renommage, pas un nouvel upload
                    }
                } else {
                    if (localFileHelper.isDirectory(fileName)) {
                        // Créer le dossier sur Drive
                        val folderId = driveService.createFolder(fileName, driveFolderId)
                        if (folderId != null) {
                            val createdDriveFile = driveService.getFileMetadata(folderId)
                            if (createdDriveFile != null) {
                                driveFiles.add(createdDriveFile)
                                syncFileDao.insertFile(
                                    createSyncFile(
                                        createdDriveFile,
                                        localFileHelper,
                                        fileName,
                                        driveFolderId,
                                        null
                                    )
                                )
                                // Synchroniser récursivement le sous-dossier local nouvellement créé
                                val subLocalPath = localFileHelper.getAbsolutePath(fileName)
                                val subLocalHelper = LocalFileHelper(context, subLocalPath)
                                syncFolder(createdDriveFile.id, subLocalHelper, stats)
                            }
                        }
                    } else {
                        val createdDriveFile = uploadNewFileFromLocal(
                            driveService,
                            localFileHelper,
                            fileName,
                            driveFolderId
                        )
                        if (createdDriveFile != null) {
                            driveFiles.add(createdDriveFile)
                            stats.filesUploaded++
                        }
                    }
                }
            }
        }
    }
    private suspend fun downloadFileToLocal(
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
    
    private suspend fun uploadNewFileFromLocal(
        driveService: GoogleDriveService,
        localFileHelper: LocalFileHelper,
        fileName: String,
        parentFolderId: String
    ): DriveFile? {
        return try {
            // Créer un fichier temporaire pour uploader
            val tempFile = File(context.cacheDir, "temp_$fileName")
            localFileHelper.getInputStream(fileName)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val uploadedId = driveService.uploadFile(tempFile, fileName, parentFolderId)
            tempFile.delete()
            
            if (uploadedId != null) {
                // Récupérer les métadonnées complètes pour enregistrer correctement dans la base
                val driveFile = driveService.getFileMetadata(uploadedId)
                if (driveFile != null) {
                    val fileHash = calculateHash(localFileHelper, fileName)
                    syncFileDao.insertFile(
                        createSyncFile(
                            driveFile,
                            localFileHelper,
                            fileName,
                            parentFolderId,
                            fileHash
                        )
                    )
                    driveFile
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("SyncService", "Erreur lors de l'upload (création) de $fileName", e)
            null
        }
    }
    
    private suspend fun uploadFileFromLocal(
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
            size = driveFile.getSize()?.toLong() ?: 0L,
            modifiedTime = if (localFileHelper.exists(fileName)) localFileHelper.lastModified(fileName) else 0,
            driveModifiedTime = driveFile.modifiedTime?.value ?: 0,
            isDirectory = driveFile.mimeType == "application/vnd.google-apps.folder",
            parentFolderId = parentFolderId,
            fileHash = hash,
            syncStatus = "synced"
        )
    }

    private fun moveLocalFileByStoredPathToFolder(
        sourcePath: String,
        targetFolderHelper: LocalFileHelper,
        targetFileName: String
    ): Boolean {
        return try {
            val output = targetFolderHelper.getOutputStream(targetFileName) ?: return false
            output.use { out ->
                val input: InputStream? = if (sourcePath.startsWith("content://")) {
                    context.contentResolver.openInputStream(Uri.parse(sourcePath))
                } else {
                    val f = File(sourcePath)
                    if (!f.exists() || !f.isFile) null else f.inputStream()
                }

                input?.use { inp ->
                    inp.copyTo(out)
                } ?: return false
            }

            // Supprimer la source après copie
            if (sourcePath.startsWith("content://")) {
                val doc = DocumentFile.fromSingleUri(context, Uri.parse(sourcePath))
                doc?.delete() ?: false
            } else {
                File(sourcePath).delete()
            }
        } catch (e: Exception) {
            android.util.Log.e("SyncService", "Erreur lors du déplacement local de $sourcePath vers $targetFileName", e)
            false
        }
    }

    private data class SyncStats(
        var filesDownloaded: Int = 0,
        var filesUploaded: Int = 0,
        var filesUpdated: Int = 0,
        var filesDeleted: Int = 0
    )
    
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

