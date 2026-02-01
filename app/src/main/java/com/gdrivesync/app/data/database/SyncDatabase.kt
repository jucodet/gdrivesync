package com.gdrivesync.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.gdrivesync.app.data.database.SyncDatabaseMigration
import com.gdrivesync.app.data.model.SyncFile

@Database(entities = [SyncFile::class], version = 2, exportSchema = false)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun syncFileDao(): SyncFileDao
    
    companion object {
        @Volatile
        private var INSTANCE: SyncDatabase? = null
        
        fun getDatabase(context: Context): SyncDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SyncDatabase::class.java,
                    "sync_database"
                )
                    .addMigrations(SyncDatabaseMigration.MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

