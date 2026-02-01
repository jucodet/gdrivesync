package com.gdrivesync.app.data.database

import androidx.room.*
import com.gdrivesync.app.data.model.SyncFile
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncFileDao {
    @Query("SELECT * FROM sync_files")
    fun getAllFiles(): Flow<List<SyncFile>>
    
    @Query("SELECT * FROM sync_files WHERE driveFileId = :fileId")
    suspend fun getFileById(fileId: String): SyncFile?
    
    @Query("SELECT * FROM sync_files WHERE parentFolderId = :folderId")
    suspend fun getFilesByFolderId(folderId: String?): List<SyncFile>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: SyncFile)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFiles(files: List<SyncFile>)
    
    @Delete
    suspend fun deleteFile(file: SyncFile)
    
    @Query("DELETE FROM sync_files WHERE driveFileId = :fileId")
    suspend fun deleteFileById(fileId: String)
    
    @Query("DELETE FROM sync_files")
    suspend fun deleteAll()
}


