package com.gdrivesync.app

import android.app.Application
import android.util.Log
import com.gdrivesync.app.data.google.GoogleDriveService
import com.gdrivesync.app.data.preferences.PreferencesManager
import com.gdrivesync.app.util.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class GDriveSyncApplication : Application() {
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    override fun onCreate() {
        super.onCreate()
        
        // Démarrer automatiquement la surveillance des changements si configuré
        applicationScope.launch {
            try {
                val preferencesManager = PreferencesManager(this@GDriveSyncApplication)
                val driveService = GoogleDriveService(this@GDriveSyncApplication)
                
                // Vérifier si un dossier est configuré et si l'utilisateur est connecté
                val driveFolderId = preferencesManager.getDriveFolderIdSync()
                val isSignedIn = driveService.isSignedIn()
                val monitoringEnabled = preferencesManager.changeMonitoringEnabled.first()
                
                if (driveFolderId != null && isSignedIn && monitoringEnabled) {
                    Log.d("GDriveSyncApplication", "Démarrage automatique de la surveillance des changements")
                    SyncScheduler.scheduleChangeMonitoring(this@GDriveSyncApplication)
                }
            } catch (e: Exception) {
                Log.e("GDriveSyncApplication", "Erreur lors du démarrage de la surveillance", e)
            }
        }
    }
}

