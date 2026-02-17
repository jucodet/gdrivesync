package com.gdrivesync.app.data.google

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.Change
import com.google.api.services.drive.model.ChangeList
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.FileList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException
import java.util.Collections

class GoogleDriveService(private val context: Context) {
    
    companion object {
        private const val REQUEST_CODE_SIGN_IN = 1001
        // Utiliser DRIVE pour lecture/écriture complète sur Drive
        // (obligatoire pour pouvoir uploader / mettre à jour / supprimer des fichiers)
        private val SCOPES = Collections.singletonList(DriveScopes.DRIVE)
    }
    
    private var driveService: Drive? = null
    
    fun getSignInIntent() = GoogleSignIn.getClient(
        context,
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            // On demande le scope DRIVE complet pour autoriser la synchro bidirectionnelle
            .requestScopes(Scope(DriveScopes.DRIVE))
            .build()
    ).signInIntent
    
    fun initializeDriveService(account: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            SCOPES
        )
        credential.selectedAccount = account.account
        
        driveService = Drive.Builder(
            AndroidHttp.newCompatibleTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("GDriveSync")
            .build()
    }
    
    fun isSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            return false
        }
        
        // Vérifier si le compte a les bons scopes
        val grantedScopes = account.grantedScopes
        // Pour pouvoir écrire sur Drive, on doit avoir le scope DRIVE complet
        val requiredScope = DriveScopes.DRIVE
        val hasCorrectScope = grantedScopes?.any { 
            it.toString() == requiredScope 
        } ?: false
        
        android.util.Log.d("GoogleDriveService", "Compte connecté: ${account.email}")
        android.util.Log.d("GoogleDriveService", "Scopes accordés: ${grantedScopes?.joinToString()}")
        android.util.Log.d("GoogleDriveService", "Scope requis: $requiredScope")
        android.util.Log.d("GoogleDriveService", "A le bon scope: $hasCorrectScope")
        
        // Si le compte n'a pas le bon scope, il faut se reconnecter
        if (!hasCorrectScope) {
            android.util.Log.w("GoogleDriveService", "Le compte n'a pas le bon scope. Reconnexion nécessaire.")
            return false
        }
        
        // Si le service n'est pas initialisé mais qu'un compte est connecté, l'initialiser
        if (driveService == null) {
            try {
                initializeDriveService(account)
            } catch (e: Exception) {
                android.util.Log.e("GoogleDriveService", "Erreur lors de l'initialisation", e)
                return false
            }
        }
        
        return driveService != null
    }
    
    /**
     * Vérifie si le compte connecté a le bon scope
     */
    fun hasCorrectScope(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
        val grantedScopes = account.grantedScopes
        val requiredScope = DriveScopes.DRIVE
        return grantedScopes?.any { it.toString() == requiredScope } ?: false
    }
    
    /**
     * S'assure que le service Drive est initialisé si un compte est connecté
     */
    private fun ensureDriveServiceInitialized() {
        if (driveService == null) {
            val account = GoogleSignIn.getLastSignedInAccount(context)
            if (account != null) {
                android.util.Log.d("GoogleDriveService", "Initialisation du service avec le compte: ${account.email}")
                initializeDriveService(account)
                android.util.Log.d("GoogleDriveService", "Service initialisé avec scope: ${SCOPES.joinToString()}")
            } else {
                android.util.Log.w("GoogleDriveService", "Aucun compte connecté")
            }
        }
    }
    
    fun signOut() {
        GoogleSignIn.getClient(context, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut()
        driveService = null
    }
    
    suspend fun listFiles(folderId: String? = null): List<File> = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("GoogleDriveService", "listFiles appelé, folderId: $folderId")
            
            // S'assurer que le service est initialisé
            ensureDriveServiceInitialized()
            
            val service = driveService ?: throw IllegalStateException("Service Drive non initialisé")
            android.util.Log.d("GoogleDriveService", "Service Drive initialisé")
            
            val query = if (folderId != null && folderId != "root") {
                "'$folderId' in parents and trashed = false"
            } else {
                // Pour la racine, récupérer uniquement les fichiers qui ont "root" comme parent
                // Note: Certains fichiers peuvent ne pas avoir "root" dans parents si partagés
                // On essaie d'abord avec 'root' in parents, sinon on peut essayer sans filtre
                "trashed = false and 'root' in parents"
            }
            
            android.util.Log.d("GoogleDriveService", "Requête: $query, folderId: $folderId")
            
            // Si aucun résultat avec 'root' in parents, essayer sans ce filtre
            var allFiles = mutableListOf<File>()
            var pageToken: String? = null
            var hasResults = false
            
            do {
                val request = service.files().list()
                    .setQ(query)
                    .setFields("files(id, name, mimeType, size, modifiedTime, parents)")
                    .setPageSize(100)
                
                if (pageToken != null) {
                    request.setPageToken(pageToken)
                }
                
                val pageResult: FileList = request.execute()
                android.util.Log.d("GoogleDriveService", "Page récupérée: ${pageResult.files?.size ?: 0} fichiers")
                
                if (pageResult.files != null && pageResult.files!!.isNotEmpty()) {
                    hasResults = true
                    pageResult.files?.let { 
                        allFiles.addAll(it)
                        it.forEach { file ->
                            android.util.Log.d("GoogleDriveService", "  - ${file.name} (${file.mimeType})")
                        }
                    }
                }
                pageToken = pageResult.nextPageToken
            } while (pageToken != null)
            
            // Si aucun résultat avec 'root' in parents et qu'on est à la racine, essayer sans filtre parent
            if (!hasResults && (folderId == null || folderId == "root")) {
                android.util.Log.d("GoogleDriveService", "Aucun résultat avec 'root' in parents, essai sans filtre parent")
                val alternativeQuery = "trashed = false"
                pageToken = null
                
                do {
                    val request = service.files().list()
                        .setQ(alternativeQuery)
                        .setFields("files(id, name, mimeType, size, modifiedTime, parents)")
                        .setPageSize(100)
                    
                    if (pageToken != null) {
                        request.setPageToken(pageToken)
                    }
                    
                    val pageResult: FileList = request.execute()
                    android.util.Log.d("GoogleDriveService", "Page alternative récupérée: ${pageResult.files?.size ?: 0} fichiers")
                    
                    pageResult.files?.let { 
                        // Filtrer pour ne garder que ceux qui ont "root" dans parents ou pas de parents
                        val rootFiles = it.filter { file ->
                            file.parents == null || file.parents.isEmpty() || file.parents.contains("root")
                        }
                        allFiles.addAll(rootFiles)
                        rootFiles.forEach { file ->
                            android.util.Log.d("GoogleDriveService", "  - ${file.name} (${file.mimeType}), parents: ${file.parents}")
                        }
                    }
                    pageToken = pageResult.nextPageToken
                } while (pageToken != null)
            }
            
            android.util.Log.d("GoogleDriveService", "Total fichiers récupérés: ${allFiles.size}")
            return@withContext allFiles
        } catch (e: UserRecoverableAuthIOException) {
            android.util.Log.e("GoogleDriveService", "Autorisation requise - reconnexion nécessaire", e)
            throw Exception("Autorisation requise. Veuillez vous déconnecter et vous reconnecter dans les paramètres pour autoriser l'accès à Google Drive.", e)
        } catch (e: Exception) {
            android.util.Log.e("GoogleDriveService", "Erreur lors de la récupération des fichiers", e)
            // Propager l'exception pour permettre une meilleure gestion des erreurs
            throw Exception("Erreur lors de la récupération des fichiers: ${e.message}", e)
        }
    }
    
    suspend fun getFileMetadata(fileId: String): File? = withContext(Dispatchers.IO) {
        try {
            ensureDriveServiceInitialized()
            val service = driveService ?: return@withContext null
            service.files().get(fileId)
                .setFields("id, name, mimeType, size, modifiedTime, parents")
                .execute()
        } catch (e: Exception) {
            null
        }
    }
    
    suspend fun downloadFile(fileId: String, localFile: java.io.File): Boolean = withContext(Dispatchers.IO) {
        try {
            ensureDriveServiceInitialized()
            val service = driveService ?: return@withContext false
            val outputStream = FileOutputStream(localFile)
            service.files().get(fileId).executeMediaAndDownloadTo(outputStream)
            outputStream.close()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    suspend fun uploadFile(localFile: java.io.File, fileName: String, parentFolderId: String?): String? = withContext(Dispatchers.IO) {
        try {
            ensureDriveServiceInitialized()
            val service = driveService ?: return@withContext null
            val fileMetadata = File().apply {
                name = fileName
                if (parentFolderId != null) {
                    parents = listOf(parentFolderId)
                }
            }
            
            val mediaContent = FileContent("application/octet-stream", localFile)
            val file = service.files().create(fileMetadata, mediaContent)
                .setFields("id")
                .execute()
            
            file.id
        } catch (e: Exception) {
            null
        }
    }
    
    suspend fun updateFile(fileId: String, localFile: java.io.File): Boolean = withContext(Dispatchers.IO) {
        try {
            ensureDriveServiceInitialized()
            val service = driveService ?: return@withContext false
            val mediaContent = FileContent("application/octet-stream", localFile)
            service.files().update(fileId, null, mediaContent).execute()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Renomme un fichier côté Drive sans modifier son contenu
     */
    suspend fun renameFile(fileId: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            ensureDriveServiceInitialized()
            val service = driveService ?: return@withContext false
            val fileMetadata = File().apply {
                name = newName
            }
            service.files().update(fileId, fileMetadata).execute()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    suspend fun deleteFile(fileId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            ensureDriveServiceInitialized()
            val service = driveService ?: return@withContext false
            service.files().delete(fileId).execute()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    suspend fun createFolder(folderName: String, parentFolderId: String?): String? = withContext(Dispatchers.IO) {
        try {
            ensureDriveServiceInitialized()
            val service = driveService ?: return@withContext null
            val fileMetadata = File().apply {
                name = folderName
                mimeType = "application/vnd.google-apps.folder"
                if (parentFolderId != null) {
                    parents = listOf(parentFolderId)
                }
            }
            
            val file = service.files().create(fileMetadata)
                .setFields("id")
                .execute()
            
            file.id
        } catch (e: Exception) {
            null
        }
    }
    
    suspend fun searchFolder(folderName: String, parentId: String? = null): File? = withContext(Dispatchers.IO) {
        try {
            ensureDriveServiceInitialized()
            val service = driveService ?: return@withContext null
            val query = buildString {
                append("mimeType = 'application/vnd.google-apps.folder'")
                append(" and name = '$folderName'")
                append(" and trashed = false")
                if (parentId != null) {
                    append(" and '$parentId' in parents")
                }
            }
            
            val result: FileList = service.files().list()
                .setQ(query)
                .setFields("files(id, name)")
                .setPageSize(1)
                .execute()
            
            result.files?.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Récupère le token de changement actuel pour commencer la surveillance
     */
    suspend fun getStartPageToken(): String? = withContext(Dispatchers.IO) {
        try {
            ensureDriveServiceInitialized()
            val service = driveService ?: return@withContext null
            val response = service.changes().getStartPageToken().execute()
            response.startPageToken
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Récupère la liste des changements depuis un token donné
     * Retourne la liste des changements et le nouveau token
     */
    suspend fun getChanges(pageToken: String, folderId: String? = null): Pair<List<Change>, String?> = withContext(Dispatchers.IO) {
        try {
            ensureDriveServiceInitialized()
            val service = driveService ?: return@withContext Pair(emptyList(), null)
            val request = service.changes().list(pageToken)
                .setFields("nextPageToken, changes(fileId, file(id, name, mimeType, size, modifiedTime, parents, trashed)))")
            
            val result: ChangeList = request.execute()
            
            // Filtrer les changements pour ne garder que ceux du dossier surveillé
            val changes = result.changes?.filter { change ->
                if (folderId == null) return@filter true
                val file = change.file
                if (file == null || file.trashed == true) return@filter false
                // Vérifier si le fichier est dans le dossier surveillé
                file.parents?.contains(folderId) == true || file.id == folderId
            } ?: emptyList()
            
            Pair(changes, result.nextPageToken)
        } catch (e: Exception) {
            Pair(emptyList(), null)
        }
    }
    
    /**
     * Vérifie s'il y a des changements dans le dossier spécifié
     */
    suspend fun hasChanges(pageToken: String, folderId: String?): Boolean {
        val (changes, _) = getChanges(pageToken, folderId)
        return changes.isNotEmpty()
    }
}

