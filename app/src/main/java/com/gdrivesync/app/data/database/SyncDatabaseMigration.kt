package com.gdrivesync.app.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object SyncDatabaseMigration {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Ajouter les colonnes fileHash et syncStatus
            // Note: SQLite ne supporte pas NOT NULL DEFAULT dans ALTER TABLE ADD COLUMN
            // On ajoute d'abord la colonne, puis on met à jour les valeurs existantes
            database.execSQL("ALTER TABLE sync_files ADD COLUMN fileHash TEXT")
            database.execSQL("ALTER TABLE sync_files ADD COLUMN syncStatus TEXT")
            // Mettre à jour les valeurs existantes avec la valeur par défaut
            database.execSQL("UPDATE sync_files SET syncStatus = 'synced' WHERE syncStatus IS NULL")
        }
    }
}


