# Code du Worker Principal et Configuration AndroidManifest

## MainSyncWorker.kt

Le Worker principal qui gère la synchronisation avec les contraintes WiFi + batterie en charge.

```kotlin
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
                    
                    Result.success()
                }
                is SyncService.SyncResult.Error -> {
                    Log.e(TAG, "Erreur de synchronisation: ${syncResult.message}")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception lors de la synchronisation", e)
            Result.retry()
        }
    }
}
```

## AndroidManifest.xml

Configuration complète du manifest avec toutes les permissions nécessaires.

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Permissions réseau -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    
    <!-- Permissions de stockage (Android 12 et inférieur) -->
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />
    
    <!-- Permissions de stockage (Android 13+) -->
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
    
    <!-- Permissions pour WorkManager et services en arrière-plan -->
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:name=".GDriveSyncApplication"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.GDriveSync"
        android:usesCleartextTraffic="true"
        tools:targetApi="31">
        
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.GDriveSync">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
        
        <!-- Service d'authentification OAuth2 (optionnel, géré par Play Services) -->
        <!-- Les services Google Play Services gèrent l'authentification automatiquement -->
    </application>

</manifest>
```

## Caractéristiques principales

### 1. Authentification OAuth2
- Utilise `GoogleSignInOptions` avec le scope `DRIVE_FILE`
- Connexion en un clic via Google Play Services
- Gestion automatique des tokens et refresh

### 2. Détection des changements
- **FileObserver** : Détecte les changements locaux en temps réel
- **ChangeMonitorWorker** : Surveille les changements Google Drive via l'API Changes
- Vérification périodique toutes les 5 minutes

### 3. Synchronisation avec contraintes
- **WiFi uniquement** : `NetworkType.UNMETERED`
- **Batterie en charge** : `setRequiresCharging(true)`
- Fonctionne en arrière-plan de manière invisible
- Utilise WorkManager pour la gestion automatique

### 4. Résolution de conflits
- **Logique "Last Modified Wins"** : La version la plus récente gagne
- Comparaison des timestamps Drive vs Local
- Utilisation de hash de fichiers pour détecter les vraies modifications
- Pas de popups d'alerte à l'utilisateur

### 5. Base de données Room
- Stocke l'état de synchronisation de chaque fichier
- Hash des fichiers (SHA-256) pour éviter les doublons
- Statut de synchronisation (synced, pending, error)
- Migration automatique de la version 1 à 2

## Utilisation

### Planifier une synchronisation immédiate
```kotlin
SyncScheduler.scheduleSyncNow(context)
```

### Planifier une synchronisation périodique
```kotlin
SyncScheduler.schedulePeriodicSync(context, 60) // Toutes les 60 minutes
```

### Planifier la surveillance des changements
```kotlin
SyncScheduler.scheduleChangeMonitoring(context, 5) // Toutes les 5 minutes
```

## Notes importantes

- La synchronisation ne se déclenche que si :
  - L'appareil est connecté au WiFi
  - La batterie est en charge
  - L'utilisateur est connecté à Google Drive
  - Un dossier est configuré

- Les contraintes sont gérées automatiquement par WorkManager
- Si les contraintes ne sont pas respectées, la synchronisation est reportée automatiquement
- WorkManager réessaie avec un backoff exponentiel en cas d'erreur


