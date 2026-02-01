package com.gdrivesync.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sync_preferences")

class PreferencesManager(private val context: Context) {
    companion object {
        private val DRIVE_FOLDER_ID = stringPreferencesKey("drive_folder_id")
        private val DRIVE_FOLDER_NAME = stringPreferencesKey("drive_folder_name")
        private val LOCAL_FOLDER_PATH = stringPreferencesKey("local_folder_path")
        private val LAST_SYNC_TIME = longPreferencesKey("last_sync_time")
        private val AUTO_SYNC_ENABLED = booleanPreferencesKey("auto_sync_enabled")
        private val SYNC_INTERVAL_MINUTES = longPreferencesKey("sync_interval_minutes")
        private val CHANGE_TOKEN = stringPreferencesKey("change_token")
        private val CHANGE_MONITORING_ENABLED = booleanPreferencesKey("change_monitoring_enabled")
    }
    
    val driveFolderId: Flow<String?> = context.dataStore.data.map { it[DRIVE_FOLDER_ID] }
    val driveFolderName: Flow<String?> = context.dataStore.data.map { it[DRIVE_FOLDER_NAME] }
    val localFolderPath: Flow<String?> = context.dataStore.data.map { it[LOCAL_FOLDER_PATH] }
    val lastSyncTime: Flow<Long?> = context.dataStore.data.map { it[LAST_SYNC_TIME] }
    val autoSyncEnabled: Flow<Boolean> = context.dataStore.data.map { it[AUTO_SYNC_ENABLED] ?: false }
    val syncIntervalMinutes: Flow<Long> = context.dataStore.data.map { it[SYNC_INTERVAL_MINUTES] ?: 60L }
    val changeToken: Flow<String?> = context.dataStore.data.map { it[CHANGE_TOKEN] }
    val changeMonitoringEnabled: Flow<Boolean> = context.dataStore.data.map { it[CHANGE_MONITORING_ENABLED] ?: true }
    
    suspend fun setDriveFolder(id: String, name: String) {
        context.dataStore.edit { preferences ->
            preferences[DRIVE_FOLDER_ID] = id
            preferences[DRIVE_FOLDER_NAME] = name
        }
    }
    
    suspend fun setLocalFolderPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[LOCAL_FOLDER_PATH] = path
        }
    }
    
    suspend fun setLastSyncTime(time: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_SYNC_TIME] = time
        }
    }
    
    suspend fun setAutoSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_SYNC_ENABLED] = enabled
        }
    }
    
    suspend fun setSyncIntervalMinutes(minutes: Long) {
        context.dataStore.edit { preferences ->
            preferences[SYNC_INTERVAL_MINUTES] = minutes
        }
    }
    
    suspend fun getDriveFolderIdSync(): String? {
        return context.dataStore.data.map { it[DRIVE_FOLDER_ID] }.first()
    }
    
    suspend fun getLocalFolderPathSync(): String? {
        return context.dataStore.data.map { it[LOCAL_FOLDER_PATH] }.first()
    }
    
    suspend fun setChangeToken(token: String) {
        context.dataStore.edit { preferences ->
            preferences[CHANGE_TOKEN] = token
        }
    }
    
    suspend fun getChangeTokenSync(): String? {
        return context.dataStore.data.map { it[CHANGE_TOKEN] }.first()
    }
    
    suspend fun setChangeMonitoringEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[CHANGE_MONITORING_ENABLED] = enabled
        }
    }
}

