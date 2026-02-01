package com.gdrivesync.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_files")
data class SyncFile(
    @PrimaryKey
    val driveFileId: String,
    val fileName: String,
    val localPath: String,
    val drivePath: String,
    val mimeType: String,
    val size: Long,
    val modifiedTime: Long,
    val driveModifiedTime: Long,
    val isDirectory: Boolean = false,
    val parentFolderId: String? = null,
    val fileHash: String? = null, // Hash MD5/SHA256 pour détecter les modifications
    val syncStatus: String = "synced" // synced, pending, error
)

