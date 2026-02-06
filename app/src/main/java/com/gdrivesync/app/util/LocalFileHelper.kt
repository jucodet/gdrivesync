package com.gdrivesync.app.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Classe utilitaire pour gérer les fichiers locaux, qu'ils soient dans un File standard
 * ou dans un DocumentFile (via Storage Access Framework)
 */
class LocalFileHelper(
    private val context: Context,
    private val localFolderPath: String
) {
    private val isUri: Boolean = localFolderPath.startsWith("content://")
    private val documentFolder: DocumentFile? = if (isUri) {
        DocumentFile.fromTreeUri(context, Uri.parse(localFolderPath))
    } else {
        null
    }
    private val fileFolder: File? = if (!isUri) {
        File(localFolderPath).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    } else {
        null
    }
    
    fun exists(fileName: String): Boolean {
        return if (isUri) {
            documentFolder?.findFile(fileName)?.exists() ?: false
        } else {
            File(fileFolder, fileName).exists()
        }
    }
    
    fun isDirectory(fileName: String): Boolean {
        return if (isUri) {
            documentFolder?.findFile(fileName)?.isDirectory ?: false
        } else {
            File(fileFolder, fileName).isDirectory
        }
    }
    
    fun lastModified(fileName: String): Long {
        return if (isUri) {
            documentFolder?.findFile(fileName)?.lastModified() ?: 0
        } else {
            File(fileFolder, fileName).lastModified()
        }
    }
    
    fun createDirectory(fileName: String): Boolean {
        return if (isUri) {
            documentFolder?.createDirectory(fileName) != null
        } else {
            File(fileFolder, fileName).mkdirs()
        }
    }
    
    fun getInputStream(fileName: String): InputStream? {
        return if (isUri) {
            val file = documentFolder?.findFile(fileName)
            file?.let { context.contentResolver.openInputStream(it.uri) }
        } else {
            File(fileFolder, fileName).inputStream()
        }
    }
    
    fun getOutputStream(fileName: String): OutputStream? {
        return if (isUri) {
            val file = documentFolder?.findFile(fileName)
            if (file == null || !file.exists()) {
                // Créer le fichier - déterminer le type MIME
                val mimeType = when {
                    fileName.endsWith(".txt", ignoreCase = true) -> "text/plain"
                    fileName.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
                    fileName.endsWith(".jpg", ignoreCase = true) || fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                    fileName.endsWith(".png", ignoreCase = true) -> "image/png"
                    else -> "application/octet-stream"
                }
                val newFile = documentFolder?.createFile(mimeType, fileName)
                newFile?.let { context.contentResolver.openOutputStream(it.uri, "wt") }
            } else {
                context.contentResolver.openOutputStream(file.uri, "wt")
            }
        } else {
            File(fileFolder, fileName).outputStream()
        }
    }
    
    fun delete(fileName: String): Boolean {
        return if (isUri) {
            documentFolder?.findFile(fileName)?.delete() ?: false
        } else {
            File(fileFolder, fileName).delete()
        }
    }
    
    fun listFiles(): List<String> {
        return if (isUri) {
            documentFolder?.listFiles()?.mapNotNull { it.name } ?: emptyList()
        } else {
            fileFolder?.listFiles()?.map { it.name } ?: emptyList()
        }
    }
    
    fun getAbsolutePath(fileName: String): String {
        return if (isUri) {
            // Pour les URIs, on retourne l'URI complet
            documentFolder?.findFile(fileName)?.uri?.toString() ?: "$localFolderPath/$fileName"
        } else {
            File(fileFolder, fileName).absolutePath
        }
    }
    
    fun getBasePath(): String {
        return localFolderPath
    }
}
