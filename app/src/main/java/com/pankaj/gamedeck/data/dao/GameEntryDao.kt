package com.pankaj.gamedeck.data.dao

import androidx.room.*
import com.pankaj.gamedeck.data.model.GameEntry

/**
 * Data Access Object for game entries.
 * Provides CRUD operations scoped by widget ID.
 */
@Dao
interface GameEntryDao {

    /** Get all game entries for a specific widget, ordered by position */
    @Query("SELECT * FROM game_entries WHERE widgetId = :widgetId ORDER BY position ASC")
    suspend fun getByWidgetId(widgetId: Int): List<GameEntry>

    /** Get all game entries for a specific widget (blocking — for RemoteViewsFactory) */
    @Query("SELECT * FROM game_entries WHERE widgetId = :widgetId ORDER BY position ASC")
    fun getByWidgetIdSync(widgetId: Int): List<GameEntry>

    /** Insert a single game entry */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: GameEntry): Long

    /** Insert multiple game entries */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<GameEntry>)

    /** Update a game entry */
    @Update
    suspend fun update(entry: GameEntry)

    /** Delete a game entry */
    @Delete
    suspend fun delete(entry: GameEntry)

    /** Delete all entries for a specific widget (cleanup on widget removal) */
    @Query("DELETE FROM game_entries WHERE widgetId = :widgetId")
    suspend fun deleteByWidgetId(widgetId: Int)

    /** Delete all entries for a specific widget (blocking — for Provider) */
    @Query("DELETE FROM game_entries WHERE widgetId = :widgetId")
    fun deleteByWidgetIdSync(widgetId: Int)
}
