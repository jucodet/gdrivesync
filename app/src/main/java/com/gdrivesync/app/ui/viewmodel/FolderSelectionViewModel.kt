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

data class BreadcrumbItem(
    val id: String,
    val name: String
)

data class FolderSelectionUiState(
    val folders: List<FolderItem> = emptyList(),
    val breadcrumbs: List<BreadcrumbItem> = emptyList(),
    val currentFolderId: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

class FolderSelectionViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(FolderSelectionUiState())
    val uiState: StateFlow<FolderSelectionUiState> = _uiState.asStateFlow()
    
    private val driveService = GoogleDriveService(application)
    
    init {
        loadFolders()
    }
    
    fun loadFolders(folderId: String? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            try {
                // Vérifier que l'utilisateur est connecté
                if (!driveService.isSignedIn()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Non connecté à Google Drive. Veuillez vous connecter dans les paramètres."
                    )
                    return@launch
                }
                
                // Récupérer les fichiers dans le dossier spécifié (ou à la racine)
                val files = driveService.listFiles(folderId)
                
                // Filtrer uniquement les dossiers
                val folders = files
                    .filter { it.mimeType == "application/vnd.google-apps.folder" }
                    .map { FolderItem(it.id, it.name) }
                    .sortedBy { it.name.lowercase() }
                
                // Mettre à jour les breadcrumbs si on navigue dans un dossier
                val breadcrumbs = if (folderId != null && folderId != "root") {
                    // Récupérer le nom du dossier actuel
                    val currentFolder = driveService.getFileMetadata(folderId)
                    val currentName = currentFolder?.name ?: "Dossier"
                    
                    // Construire les breadcrumbs (pour l'instant, on garde juste le dossier actuel)
                    // TODO: Récupérer le chemin complet si nécessaire
                    listOf(
                        BreadcrumbItem("root", "Racine"),
                        BreadcrumbItem(folderId, currentName)
                    )
                } else {
                    listOf(BreadcrumbItem("root", "Racine"))
                }
                
                _uiState.value = _uiState.value.copy(
                    folders = folders,
                    breadcrumbs = breadcrumbs,
                    currentFolderId = folderId,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Erreur lors du chargement: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }
    
    fun navigateToFolder(folderId: String) {
        loadFolders(folderId)
    }
    
    fun navigateToBreadcrumb(breadcrumbId: String) {
        if (breadcrumbId == "root") {
            loadFolders(null)
        } else {
            loadFolders(breadcrumbId)
        }
    }
}


