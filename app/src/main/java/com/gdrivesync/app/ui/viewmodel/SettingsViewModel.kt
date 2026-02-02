package com.gdrivesync.app.ui.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gdrivesync.app.data.google.GoogleDriveService
import com.gdrivesync.app.data.preferences.PreferencesManager
import com.gdrivesync.app.util.SyncScheduler
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val isSignedIn: Boolean = false,
    val accountEmail: String? = null,
    val driveFolderName: String? = null,
    val localFolderPath: String? = null,
    val autoSyncEnabled: Boolean = false,
    val syncIntervalMinutes: Long = 60,
    val isSigningIn: Boolean = false,
    val signInError: String? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    
    private val preferencesManager = PreferencesManager(application)
    private val driveService = GoogleDriveService(application)
    
    init {
        viewModelScope.launch {
            preferencesManager.driveFolderName.collect { folderName ->
                _uiState.value = _uiState.value.copy(driveFolderName = folderName)
            }
        }
        
        viewModelScope.launch {
            preferencesManager.localFolderPath.collect { localPath ->
                _uiState.value = _uiState.value.copy(localFolderPath = localPath)
            }
        }
        
        viewModelScope.launch {
            preferencesManager.autoSyncEnabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(autoSyncEnabled = enabled)
            }
        }
        
        viewModelScope.launch {
            preferencesManager.syncIntervalMinutes.collect { interval ->
                _uiState.value = _uiState.value.copy(syncIntervalMinutes = interval)
            }
        }
    }
    
    fun loadSettings() {
        viewModelScope.launch {
            val isSignedIn = driveService.isSignedIn()
            val account = GoogleSignIn.getLastSignedInAccount(getApplication())
            
            _uiState.value = _uiState.value.copy(
                isSignedIn = isSignedIn,
                accountEmail = account?.email
            )
        }
    }
    
    fun getSignInIntent(): Intent {
        return driveService.getSignInIntent()
    }
    
    fun onGoogleSignInSuccess(account: GoogleSignInAccount) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSigningIn = true, signInError = null)
            try {
                // Init Drive peut être un peu lourd, on évite de bloquer le thread UI.
                withContext(Dispatchers.Default) {
                    driveService.initializeDriveService(account)
                }

                _uiState.value = _uiState.value.copy(
                    isSignedIn = true,
                    accountEmail = account.email,
                    isSigningIn = false,
                    signInError = null
                )

                // Si un dossier est déjà configuré, démarrer la surveillance des changements
                val driveFolderId = preferencesManager.getDriveFolderIdSync()
                if (driveFolderId != null && preferencesManager.changeMonitoringEnabled.first()) {
                    SyncScheduler.scheduleChangeMonitoring(getApplication())
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSigningIn = false,
                    signInError = e.message ?: "Erreur inconnue lors de la connexion"
                )
            }
        }
    }

    fun onGoogleSignInStart() {
        _uiState.value = _uiState.value.copy(isSigningIn = true, signInError = null)
    }

    fun onGoogleSignInCancelled() {
        _uiState.value = _uiState.value.copy(isSigningIn = false, signInError = "Connexion annulée")
    }

    fun onGoogleSignInError(message: String) {
        _uiState.value = _uiState.value.copy(isSigningIn = false, signInError = message)
    }
    
    fun signOut() {
        viewModelScope.launch {
            driveService.signOut()
            _uiState.value = _uiState.value.copy(
                isSignedIn = false,
                accountEmail = null
            )
            
            // Arrêter la surveillance des changements lors de la déconnexion
            SyncScheduler.cancelChangeMonitoring(getApplication())
        }
    }
    
    fun selectDriveFolder(folderId: String, folderName: String) {
        viewModelScope.launch {
            preferencesManager.setDriveFolder(folderId, folderName)
            
            // Définir le dossier local par défaut
            val localPath = getApplication<Application>().getExternalFilesDir(null)?.absolutePath
                ?.plus("/gdrive_sync")
            if (localPath != null) {
                preferencesManager.setLocalFolderPath(localPath)
            }
            
            // Réinitialiser le token de changement pour commencer une nouvelle surveillance
            preferencesManager.setChangeToken("")
            
            // Démarrer la surveillance des changements si elle est activée
            if (preferencesManager.changeMonitoringEnabled.first()) {
                SyncScheduler.scheduleChangeMonitoring(getApplication())
            }
        }
    }
    
    fun selectDriveFolder() {
        // Utiliser "root" comme dossier par défaut
        selectDriveFolder("root", "Racine")
    }
    
    fun setAutoSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setAutoSyncEnabled(enabled)
            if (enabled) {
                // Utiliser la valeur actuelle de l'état
                SyncScheduler.schedulePeriodicSync(getApplication(), _uiState.value.syncIntervalMinutes)
            } else {
                SyncScheduler.cancelSync(getApplication())
            }
            
            // Toujours activer la surveillance des changements pour la synchronisation automatique
            preferencesManager.setChangeMonitoringEnabled(true)
            SyncScheduler.scheduleChangeMonitoring(getApplication())
        }
    }
    
    fun setSyncIntervalMinutes(minutes: Long) {
        viewModelScope.launch {
            preferencesManager.setSyncIntervalMinutes(minutes)
            if (_uiState.value.autoSyncEnabled) {
                SyncScheduler.schedulePeriodicSync(getApplication(), minutes)
            }
        }
    }
}

