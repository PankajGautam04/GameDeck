package com.pankaj.gamedeck.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pankaj.gamedeck.data.dao.GameEntryDao
import com.pankaj.gamedeck.data.model.GameEntry

@Database(entities = [GameEntry::class], version = 4, exportSchema = false)
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
                database.execSQL("ALTER TABLE game_entries ADD COLUMN gifPath TEXT DEFAULT NULL")
            }
        }

        fun getInstance(context: Context): GameDeckDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    GameDeckDatabase::class.java,
                    "gamedeck_db"
                ).allowMainThreadQueries()
                 .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                 .fallbackToDestructiveMigration()
                 .build().also { INSTANCE = it }
            }
        }
    }
}
