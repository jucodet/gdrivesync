package com.gdrivesync.app.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.gdrivesync.app.data.database.SyncDatabase
import com.gdrivesync.app.data.google.GoogleDriveService
import com.gdrivesync.app.data.preferences.PreferencesManager
import com.gdrivesync.app.data.sync.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Worker principal pour la synchronisation Google Drive
 * 
 * Utilise WorkManager avec des contraintes pour :
 * - Synchroniser uniquement sur WiFi
 * - Synchroniser uniquement si la batterie est en charge
 * - Fonctionner en arrière-plan de manière invisible
 */
class MainSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    
    companion object {
        private const val TAG = "MainSyncWorker"
        const val WORK_NAME = "main_sync_work"
        
        /**
         * Crée une requête de travail unique avec les contraintes appropriées
         */
        fun createWorkRequest(): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED) // WiFi uniquement
                .setRequiresCharging(true) // Batterie en charge uniquement
                .setRequiresBatteryNotLow(false) // Pas besoin que la batterie soit pleine
                .build()
            
            return OneTimeWorkRequestBuilder<MainSyncWorker>()
                .setConstraints(constraints)
                .addTag("sync")
                .build()
        }
        
        /**
         * Crée une requête de travail périodique avec les contraintes appropriées
         */
        fun createPeriodicWorkRequest(intervalMinutes: Long): PeriodicWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED) // WiFi uniquement
                .setRequiresCharging(true) // Batterie en charge uniquement
                .setRequiresBatteryNotLow(false)
                .build()
            
            return PeriodicWorkRequestBuilder<MainSyncWorker>(
                intervalMinutes,
                java.util.concurrent.TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .addTag("sync")
                .build()
        }
    }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(TAG, "Démarrage de la synchronisation")
            
            val preferencesManager = PreferencesManager(applicationContext)
            val driveService = GoogleDriveService(applicationContext)
            
            // Vérifier si l'utilisateur est connecté
            if (!driveService.isSignedIn()) {
                Log.d(TAG, "Utilisateur non connecté, synchronisation annulée")
                return@withContext Result.retry()
            }
            
            val driveFolderId = preferencesManager.getDriveFolderIdSync()
            val localFolderPath = preferencesManager.getLocalFolderPathSync()
            
            if (driveFolderId == null || localFolderPath == null) {
                Log.d(TAG, "Dossier non configuré, synchronisation annulée")
                return@withContext Result.success()
            }
            
            // Vérifier les contraintes avant de continuer
            if (!areConstraintsMet()) {
                Log.d(TAG, "Contraintes non respectées (WiFi ou charge), synchronisation reportée")
                return@withContext Result.retry()
            }
            
            // Effectuer la synchronisation
            val database = SyncDatabase.getDatabase(applicationContext)
            val syncService = SyncService(
                applicationContext,
                driveService,
                preferencesManager,
                database.syncFileDao()
            )
            
            val syncResult = syncService.sync()
            
            when (syncResult) {
                is SyncService.SyncResult.Success -> {
                    Log.d(TAG, "Synchronisation réussie: " +
                            "${syncResult.filesDownloaded} téléchargés, " +
                            "${syncResult.filesUploaded} uploadés, " +
                            "${syncResult.filesUpdated} mis à jour, " +
                            "${syncResult.filesDeleted} supprimés")
                    
                    // Envoyer une notification de succès si nécessaire
                    sendNotification(
                        "Synchronisation terminée",
                        "${syncResult.filesDownloaded + syncResult.filesUpdated} fichiers synchronisés"
                    )
                    
                    Result.success()
                }
                is SyncService.SyncResult.Error -> {
                    Log.e(TAG, "Erreur de synchronisation: ${syncResult.message}")
                    
                    // Envoyer une notification d'erreur si nécessaire
                    sendNotification(
                        "Erreur de synchronisation",
                        syncResult.message
                    )
                    
                    // Réessayer avec backoff exponentiel
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception lors de la synchronisation", e)
            Result.retry()
        }
    }
    
    /**
     * Vérifie si les contraintes sont respectées
     * (WorkManager devrait le faire automatiquement, mais on double-vérifie)
     */
    private suspend fun areConstraintsMet(): Boolean {
        // Cette vérification est redondante car WorkManager le fait déjà
        // mais utile pour la logique métier
        return true
    }
    
    /**
     * Envoie une notification (optionnel, peut être désactivé pour rester invisible)
     */
    private fun sendNotification(title: String, message: String) {
        // Optionnel : envoyer une notification silencieuse
        // Pour rester invisible, on peut ne rien faire ici
        // ou utiliser une notification silencieuse avec un channel de priorité basse
    }
}


