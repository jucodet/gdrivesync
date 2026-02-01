package com.gdrivesync.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gdrivesync.app.data.database.SyncDatabase
import com.gdrivesync.app.data.google.GoogleDriveService
import com.gdrivesync.app.data.preferences.PreferencesManager
import com.gdrivesync.app.data.sync.SyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class HomeUiState(
    val isConfigured: Boolean = false,
    val driveFolderName: String? = null,
    val lastSyncTime: Long? = null,
    val isSyncing: Boolean = false,
    val syncResult: SyncResult? = null
)

data class SyncResult(
    val isSuccess: Boolean,
    val filesDownloaded: Int = 0,
    val filesUploaded: Int = 0,
    val filesUpdated: Int = 0,
    val filesDeleted: Int = 0,
    val errorMessage: String = ""
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    
    private val preferencesManager = PreferencesManager(application)
    private val driveService = GoogleDriveService(application)
    private val database = SyncDatabase.getDatabase(application)
    private val syncService = SyncService(
        application,
        driveService,
        preferencesManager,
        database.syncFileDao()
    )
    
    init {
        viewModelScope.launch {
            preferencesManager.driveFolderName.collect { folderName ->
                _uiState.value = _uiState.value.copy(
                    isConfigured = folderName != null,
                    driveFolderName = folderName
                )
            }
        }
        
        viewModelScope.launch {
            preferencesManager.lastSyncTime.collect { lastSyncTime ->
                _uiState.value = _uiState.value.copy(lastSyncTime = lastSyncTime)
            }
        }
    }
    
    fun loadStatus() {
        viewModelScope.launch {
            // Le statut est déjà chargé via les flows
        }
    }
    
    fun syncNow() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isSyncing = true,
                syncResult = null
            )
            
            // Utiliser WorkManager pour la synchronisation avec contraintes
            com.gdrivesync.app.util.SyncScheduler.scheduleSyncNow(getApplication())
            
            // Effectuer aussi une synchronisation immédiate pour l'UI
            val result = syncService.sync()
            
            _uiState.value = _uiState.value.copy(
                isSyncing = false,
                syncResult = when (result) {
                    is SyncService.SyncResult.Success -> SyncResult(
                        isSuccess = true,
                        filesDownloaded = result.filesDownloaded,
                        filesUploaded = result.filesUploaded,
                        filesUpdated = result.filesUpdated,
                        filesDeleted = result.filesDeleted
                    )
                    is SyncService.SyncResult.Error -> SyncResult(
                        isSuccess = false,
                        errorMessage = result.message
                    )
                }
            )
        }
    }
}

