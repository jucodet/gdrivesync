package com.gdrivesync.app.util

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.gdrivesync.app.worker.ChangeMonitorWorker
import com.gdrivesync.app.worker.MainSyncWorker
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val SYNC_WORK_NAME = "gdrive_sync_work"
    private const val CHANGE_MONITOR_WORK_NAME = "gdrive_change_monitor_work"
    
    /**
     * Planifie une synchronisation unique immédiate
     * Utilise les contraintes WiFi + batterie en charge
     */
    fun scheduleSyncNow(context: Context) {
        val workRequest = MainSyncWorker.createWorkRequest()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "sync_now_${System.currentTimeMillis()}",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
    }
    
    /**
     * Planifie la synchronisation périodique avec contraintes WiFi + charge
     */
    fun schedulePeriodicSync(context: Context, intervalMinutes: Long) {
        val workRequest = MainSyncWorker.createPeriodicWorkRequest(intervalMinutes)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }
    
    /**
     * Planifie la surveillance des changements Google Drive
     * Cette méthode vérifie périodiquement les changements et déclenche la synchronisation
     * automatiquement quand des modifications sont détectées.
     */
    fun scheduleChangeMonitoring(context: Context, intervalMinutes: Long = 5) {
        val workRequest = androidx.work.PeriodicWorkRequestBuilder<ChangeMonitorWorker>(
            intervalMinutes.coerceAtLeast(5), // Minimum 5 minutes
            TimeUnit.MINUTES
        )
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            CHANGE_MONITOR_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }
    
    fun cancelSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
    }
    
    fun cancelChangeMonitoring(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(CHANGE_MONITOR_WORK_NAME)
    }
    
    fun cancelAll(context: Context) {
        cancelSync(context)
        cancelChangeMonitoring(context)
    }
}

