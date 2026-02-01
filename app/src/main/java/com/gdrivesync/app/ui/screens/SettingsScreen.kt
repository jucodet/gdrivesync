package com.gdrivesync.app.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gdrivesync.app.ui.viewmodel.SettingsViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToFolderSelection: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    
    LaunchedEffect(Unit) {
        viewModel.loadSettings()
    }
    
    // Écouter la sélection de dossier depuis FolderSelectionScreen
    LaunchedEffect(Unit) {
        // Cette logique sera gérée par le ViewModel via un callback
    }
    
    // Gérer le résultat de la connexion Google
    val activity = context as? Activity
    LaunchedEffect(Unit) {
        activity?.let {
            val account = GoogleSignIn.getLastSignedInAccount(it)
            if (account != null) {
                viewModel.onGoogleSignInSuccess(account)
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paramètres") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Authentification Google",
                        style = MaterialTheme.typography.titleLarge
                    )
                    
                    if (uiState.isSignedIn) {
                        Text(
                            text = "Connecté: ${uiState.accountEmail}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = { viewModel.signOut() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Se déconnecter")
                        }
                    } else {
                        Text(
                            text = "Non connecté",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(
                            onClick = {
                                val signInIntent = viewModel.getSignInIntent()
                                activity?.startActivityForResult(signInIntent, 1001)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Se connecter à Google")
                        }
                    }
                }
            }
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Configuration de synchronisation",
                        style = MaterialTheme.typography.titleLarge
                    )
                    
                    if (uiState.driveFolderName != null) {
                        Text(
                            text = "Dossier Drive: ${uiState.driveFolderName}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            text = "Aucun dossier sélectionné",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    if (uiState.localFolderPath != null) {
                        Text(
                            text = "Dossier local: ${uiState.localFolderPath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Button(
                        onClick = { 
                            onNavigateToFolderSelection()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = uiState.isSignedIn
                    ) {
                        Text("Sélectionner un dossier Google Drive")
                    }
                }
            }
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Synchronisation automatique",
                        style = MaterialTheme.typography.titleLarge
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Activer la synchronisation automatique",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = uiState.autoSyncEnabled,
                            onCheckedChange = { viewModel.setAutoSyncEnabled(it) }
                        )
                    }
                    
                    if (uiState.autoSyncEnabled) {
                        Text(
                            text = "Intervalle (minutes): ${uiState.syncIntervalMinutes}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Slider(
                            value = uiState.syncIntervalMinutes.toFloat(),
                            onValueChange = { viewModel.setSyncIntervalMinutes(it.toLong()) },
                            valueRange = 15f..1440f,
                            steps = 19
                        )
                    }
                }
            }
        }
    }
}

