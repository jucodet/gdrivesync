package com.gdrivesync.app.util

import java.io.File
import java.security.MessageDigest

object FileHashUtil {
    /**
     * Calcule le hash SHA-256 d'un fichier pour détecter les modifications
     */
    fun calculateFileHash(file: File): String? {
        return try {
            if (!file.exists() || file.isDirectory) {
                return null
            }
            
            val digest = MessageDigest.getInstance("SHA-256")
            val inputStream = file.inputStream()
            val buffer = ByteArray(8192)
            var bytesRead: Int
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            
            inputStream.close()
            
            val hashBytes = digest.digest()
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Calcule un hash rapide basé sur la taille et la date de modification
     * Utile pour les gros fichiers où on veut éviter de lire tout le contenu
     */
    fun calculateQuickHash(file: File): String {
        return "${file.length()}_${file.lastModified()}"
    }
}


