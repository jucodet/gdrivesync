package com.gdrivesync.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gdrivesync.app.data.google.GoogleDriveService
import com.google.api.services.drive.model.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FolderItem(
    val id: String,
    val name: String
)

data class FolderSelectionUiState(
    val folders: List<FolderItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class FolderSelectionViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(FolderSelectionUiState())
    val uiState: StateFlow<FolderSelectionUiState> = _uiState.asStateFlow()
    
    private val driveService = GoogleDriveService(application)
    
    fun loadFolders() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            try {
                val files = driveService.listFiles()
                val folders = files
                    .filter { it.mimeType == "application/vnd.google-apps.folder" }
                    .map { FolderItem(it.id, it.name) }
                
                _uiState.value = _uiState.value.copy(
                    folders = folders,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Erreur lors du chargement: ${e.message}"
                )
            }
        }
    }
}


