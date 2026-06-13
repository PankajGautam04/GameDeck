package com.pankaj.gamedeck.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pankaj.gamedeck.data.dao.GameEntryDao
import com.pankaj.gamedeck.data.model.GameEntry

@Database(entities = [GameEntry::class], version = 6, exportSchema = false)
abstract class GameDeckDatabase : RoomDatabase() {

    abstract fun gameEntryDao(): GameEntryDao

    companion object {
        @Volatile
        private var INSTANCE: GameDeckDatabase? = null

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE game_entries ADD COLUMN customPlayText TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                addColumnIfMissing(database, "gifPath", "TEXT DEFAULT NULL")
                addColumnIfMissing(database, "isGif", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(database, "gifFrameCount", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                addColumnIfMissing(database, "isGif", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(database, "gifFrameCount", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                addColumnIfMissing(database, "animationFps", "INTEGER NOT NULL DEFAULT 24")
            }
        }

        private fun addColumnIfMissing(database: SupportSQLiteDatabase, columnName: String, definition: String) {
            database.query("PRAGMA table_info(game_entries)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == columnName) return
                }
            }
            database.execSQL("ALTER TABLE game_entries ADD COLUMN $columnName $definition")
        }

        fun getInstance(context: Context): GameDeckDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    GameDeckDatabase::class.java,
                    "gamedeck_db"
                ).allowMainThreadQueries()
                 .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                 .fallbackToDestructiveMigration()
                 .build().also { INSTANCE = it }
            }
        }
    }
}
