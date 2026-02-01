package com.gdrivesync.app.util

import android.os.FileObserver
import android.util.Log
import java.io.File

/**
 * FileObserver pour détecter les changements de fichiers locaux en temps réel
 * Déclenche une synchronisation quand un fichier est modifié localement
 */
class LocalFileObserver(
    private val path: String,
    private val onFileChanged: (String) -> Unit
) : FileObserver(path, FileObserver.CLOSE_WRITE or FileObserver.MODIFY or FileObserver.DELETE or FileObserver.CREATE) {
    
    companion object {
        private const val TAG = "LocalFileObserver"
    }
    
    override fun onEvent(event: Int, path: String?) {
        if (path == null) return
        
        when (event) {
            FileObserver.CLOSE_WRITE -> {
                Log.d(TAG, "Fichier modifié: $path")
                onFileChanged(path)
            }
            FileObserver.MODIFY -> {
                // Ignorer les modifications en cours (on attend CLOSE_WRITE)
            }
            FileObserver.DELETE -> {
                Log.d(TAG, "Fichier supprimé: $path")
                onFileChanged(path)
            }
            FileObserver.CREATE -> {
                Log.d(TAG, "Fichier créé: $path")
                onFileChanged(path)
            }
        }
    }
    
    /**
     * Crée un FileObserver récursif pour surveiller un dossier et ses sous-dossiers
     */
    fun observeRecursively(rootPath: String): List<FileObserver> {
        val observers = mutableListOf<FileObserver>()
        val rootFile = File(rootPath)
        
        if (!rootFile.exists() || !rootFile.isDirectory) {
            return observers
        }
        
        // Observer le dossier racine
        observers.add(this)
        
        // Observer récursivement les sous-dossiers
        rootFile.walkTopDown().forEach { file ->
            if (file.isDirectory && file != rootFile) {
                val observer = LocalFileObserver(file.absolutePath, onFileChanged)
                observers.add(observer)
            }
        }
        
        return observers
    }
}


