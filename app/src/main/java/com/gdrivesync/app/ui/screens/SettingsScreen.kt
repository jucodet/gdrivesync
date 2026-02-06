package com.gdrivesync.app.ui.screens

import android.app.Activity
import android.app.Activity.RESULT_OK
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.text.style.TextOverflow
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
import com.google.android.gms.common.api.ApiException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToFolderSelection: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    
    // Launcher pour gérer le résultat de l'authentification Google
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        
        // Même si resultCode n'est pas OK, on essaie quand même de récupérer le compte
        // car Google Sign-In peut parfois retourner un compte valide même avec un code différent
        if (data != null) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                if (account != null) {
                    viewModel.onGoogleSignInSuccess(account)
                    return@rememberLauncherForActivityResult
                }
            } catch (e: ApiException) {
                // Si c'est une erreur d'authentification, afficher le code d'erreur
                val errorMessage = when (e.statusCode) {
                    12500 -> "Erreur de configuration Google Play Services"
                    7 -> "Erreur réseau lors de la connexion"
                    8 -> "Erreur interne"
                    10 -> "Erreur de développement (vérifier la configuration OAuth)"
                    else -> "Erreur Google Sign-In (code: ${e.statusCode})"
                }
                viewModel.onGoogleSignInError(errorMessage)
                return@rememberLauncherForActivityResult
            } catch (e: Exception) {
                viewModel.onGoogleSignInError("Erreur: ${e.message ?: "inconnue"}")
                return@rememberLauncherForActivityResult
            }
        }
        
        // Si on arrive ici, c'est que l'utilisateur a probablement annulé
        // ou qu'il n'y a pas de données dans l'intent
        // Mais vérifions quand même si un compte est déjà connecté
        val activity = context as? Activity
        if (activity != null) {
            val lastAccount = GoogleSignIn.getLastSignedInAccount(activity)
            if (lastAccount != null) {
                // Un compte est connecté, utilisons-le
                viewModel.onGoogleSignInSuccess(lastAccount)
                return@rememberLauncherForActivityResult
            }
        }
        
        if (result.resultCode != RESULT_OK) {
            viewModel.onGoogleSignInCancelled()
        } else {
            viewModel.onGoogleSignInError("Réponse Google invalide")
        }
    }
    
    // Launcher pour sélectionner un dossier local
    val localFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            viewModel.onLocalFolderSelected(uri)
        }
    }
    
    LaunchedEffect(Unit) {
        viewModel.loadSettings()
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Authentification Google",
                        style = MaterialTheme.typography.titleMedium
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
                        val signInError = uiState.signInError
                        if (signInError != null) {
                            Text(
                                text = signInError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Button(
                            onClick = {
                                viewModel.onGoogleSignInStart()
                                val signInIntent = viewModel.getSignInIntent()
                                signInLauncher.launch(signInIntent)
                            },
                            modifier = Modifier.fillMaxWidth()
                            ,
                            enabled = !uiState.isSigningIn
                        ) {
                            if (uiState.isSigningIn) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Connexion…")
                            } else {
                                Text("Se connecter à Google")
                            }
                        }
                    }
                }
            }
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Configuration de synchronisation",
                        style = MaterialTheme.typography.titleMedium
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
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Dossier local:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                            ) {
                                val isUri = uiState.localFolderPath?.startsWith("content://") == true
                                val displayPath = if (isUri) {
                                    // Pour les URIs, essayer d'extraire un nom plus lisible
                                    try {
                                        val uri = Uri.parse(uiState.localFolderPath)
                                        val displayName = DocumentFile.fromTreeUri(
                                            context,
                                            uri
                                        )?.name ?: "Dossier sélectionné"
                                        displayName
                                    } catch (e: Exception) {
                                        "Dossier sélectionné"
                                    }
                                } else {
                                    uiState.localFolderPath ?: ""
                                }
                                Text(
                                    text = displayPath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontFamily = if (!isUri) androidx.compose.ui.text.font.FontFamily.Monospace else null,
                                    maxLines = 1
                                )
                            }
                            // Afficher une note différente selon le type de chemin
                            val isUri = uiState.localFolderPath?.startsWith("content://") == true
                            if (!isUri) {
                                Text(
                                    text = "Note: Dossier privé. Accès via Android Studio (Device File Explorer) ou explorateur root.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else {
                                Text(
                                    text = "Dossier accessible publiquement via l'explorateur de fichiers.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { 
                                onNavigateToFolderSelection()
                            },
                            modifier = Modifier.weight(1f),
                            enabled = uiState.isSignedIn
                        ) {
                            Text("Dossier Drive", style = MaterialTheme.typography.bodySmall)
                        }
                        
                        Button(
                            onClick = {
                                val intent = viewModel.getLocalFolderSelectionIntent()
                                localFolderLauncher.launch(intent)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Dossier local", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Synchronisation automatique",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Activer la synchronisation automatique",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
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

