package com.gdrivesync.app.data.google

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
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
        private val SCOPES = Collections.singletonList(DriveScopes.DRIVE_FILE)
    }
    
    private var driveService: Drive? = null
    
    fun getSignInIntent() = GoogleSignIn.getClient(
        context,
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
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
        
        // Si le service n'est pas initialisé mais qu'un compte est connecté, l'initialiser
        if (driveService == null) {
            try {
                initializeDriveService(account)
            } catch (e: Exception) {
                return false
            }
        }
        
        return driveService != null
    }
    
    /**
     * S'assure que le service Drive est initialisé si un compte est connecté
     */
    private fun ensureDriveServiceInitialized() {
        if (driveService == null) {
            val account = GoogleSignIn.getLastSignedInAccount(context)
            account?.let {
                initializeDriveService(it)
            }
        }
    }
    
    fun signOut() {
        GoogleSignIn.getClient(context, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut()
        driveService = null
    }
    
    suspend fun listFiles(folderId: String? = null): List<File> = withContext(Dispatchers.IO) {
        try {
            // S'assurer que le service est initialisé
            ensureDriveServiceInitialized()
            
            val service = driveService ?: throw IllegalStateException("Service Drive non initialisé")
            
            val query = if (folderId != null) {
                "'$folderId' in parents and trashed = false"
            } else {
                // Pour la racine, récupérer uniquement les fichiers qui ont "root" comme parent
                "'root' in parents and trashed = false"
            }
            
            // Récupérer toutes les pages si nécessaire
            val allFiles = mutableListOf<File>()
            var pageToken: String? = null
            
            do {
                val request = service.files().list()
                    .setQ(query)
                    .setFields("files(id, name, mimeType, size, modifiedTime, parents)")
                    .setPageSize(100) // Limiter à 100 résultats par page
                
                if (pageToken != null) {
                    request.setPageToken(pageToken)
                }
                
                val pageResult: FileList = request.execute()
                pageResult.files?.let { allFiles.addAll(it) }
                pageToken = pageResult.nextPageToken
            } while (pageToken != null)
            
            allFiles
        } catch (e: Exception) {
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

