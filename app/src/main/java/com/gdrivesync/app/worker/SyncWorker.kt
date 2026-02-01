package com.gdrivesync.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gdrivesync.app.data.database.SyncDatabase
import com.gdrivesync.app.data.google.GoogleDriveService
import com.gdrivesync.app.data.preferences.PreferencesManager
import com.gdrivesync.app.data.sync.SyncService

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        return try {
            val preferencesManager = PreferencesManager(applicationContext)
            val driveService = GoogleDriveService(applicationContext)
            val database = SyncDatabase.getDatabase(applicationContext)
            val syncService = SyncService(
                applicationContext,
                driveService,
                preferencesManager,
                database.syncFileDao()
            )
            
            val result = syncService.sync()
            
            when (result) {
                is com.gdrivesync.app.data.sync.SyncService.SyncResult.Success -> {
                    Result.success()
                }
                is com.gdrivesync.app.data.sync.SyncService.SyncResult.Error -> {
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}


