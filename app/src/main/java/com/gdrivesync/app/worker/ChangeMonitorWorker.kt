package com.gdrivesync.app.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gdrivesync.app.data.database.SyncDatabase
import com.gdrivesync.app.data.google.GoogleDriveService
import com.gdrivesync.app.data.preferences.PreferencesManager
import com.gdrivesync.app.data.sync.SyncService
import kotlinx.coroutines.flow.first

/**
 * Worker qui surveille les changements sur Google Drive et déclenche la synchronisation
 * automatiquement quand des modifications sont détectées.
 */
class ChangeMonitorWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "ChangeMonitorWorker"
    }
    
    override suspend fun doWork(): Result {
        return try {
            val preferencesManager = PreferencesManager(applicationContext)
            val driveService = GoogleDriveService(applicationContext)
            
            // Vérifier si la surveillance est activée
            val monitoringEnabled = preferencesManager.changeMonitoringEnabled.first()
            if (!monitoringEnabled) {
                Log.d(TAG, "Surveillance des changements désactivée")
                return Result.success()
            }
            
            // Vérifier si l'utilisateur est connecté
            if (!driveService.isSignedIn()) {
                Log.d(TAG, "Utilisateur non connecté")
                return Result.retry()
            }
            
            val driveFolderId = preferencesManager.getDriveFolderIdSync()
            if (driveFolderId == null) {
                Log.d(TAG, "Aucun dossier configuré")
                return Result.success()
            }
            
            // Récupérer le token de changement actuel
            var changeToken = preferencesManager.getChangeTokenSync()
            
            // Si pas de token, obtenir un nouveau token de départ
            if (changeToken == null) {
                changeToken = driveService.getStartPageToken()
                if (changeToken != null) {
                    preferencesManager.setChangeToken(changeToken)
                    Log.d(TAG, "Nouveau token de changement initialisé: $changeToken")
                    return Result.success()
                } else {
                    Log.e(TAG, "Impossible d'obtenir le token de changement")
                    return Result.retry()
                }
            }
            
            // Vérifier s'il y a des changements
            val (changes, nextPageToken) = driveService.getChanges(changeToken, driveFolderId)
            
            if (changes.isNotEmpty()) {
                Log.d(TAG, "${changes.size} changement(s) détecté(s), déclenchement de la synchronisation")
                
                // Mettre à jour le token
                if (nextPageToken != null) {
                    preferencesManager.setChangeToken(nextPageToken)
                }
                
                // Déclencher la synchronisation
                val database = SyncDatabase.getDatabase(applicationContext)
                val syncService = SyncService(
                    applicationContext,
                    driveService,
                    preferencesManager,
                    database.syncFileDao()
                )
                
                val syncResult = syncService.sync()
                when (syncResult) {
                    is com.gdrivesync.app.data.sync.SyncService.SyncResult.Success -> {
                        Log.d(TAG, "Synchronisation réussie: ${syncResult.filesDownloaded} téléchargés, " +
                                "${syncResult.filesUploaded} uploadés, ${syncResult.filesUpdated} mis à jour, " +
                                "${syncResult.filesDeleted} supprimés")
                        Result.success()
                    }
                    is com.gdrivesync.app.data.sync.SyncService.SyncResult.Error -> {
                        Log.e(TAG, "Erreur de synchronisation: ${syncResult.message}")
                        Result.retry()
                    }
                }
            } else {
                Log.d(TAG, "Aucun changement détecté")
                // Mettre à jour le token même s'il n'y a pas de changements
                if (nextPageToken != null) {
                    preferencesManager.setChangeToken(nextPageToken)
                }
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur dans ChangeMonitorWorker", e)
            Result.retry()
        }
    }
}

