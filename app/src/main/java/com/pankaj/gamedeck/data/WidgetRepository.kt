package com.pankaj.gamedeck.data

import android.content.Context
import com.pankaj.gamedeck.data.model.GameEntry

/**
 * Repository that wraps Room DAO calls.
 * Provides a clean API for ViewModels and widget components.
 */
class WidgetRepository(context: Context) {

    private val dao = GameDeckDatabase.getInstance(context).gameEntryDao()

    /** Get all games configured for a specific widget */
    suspend fun getGamesForWidget(widgetId: Int): List<GameEntry> =
        dao.getByWidgetId(widgetId)

    /** Blocking variant for RemoteViewsFactory (runs on binder thread) */
    fun getGamesForWidgetSync(widgetId: Int): List<GameEntry> =
        dao.getByWidgetIdSync(widgetId)

    /** Save a complete list of games for a widget (replaces existing) */
    suspend fun saveGamesForWidget(widgetId: Int, games: List<GameEntry>) {
        dao.deleteByWidgetId(widgetId)
        val indexed = games.mapIndexed { index, game ->
            game.copy(widgetId = widgetId, position = index)
        }
        dao.insertAll(indexed)
    }

    /** Add a single game entry */
    suspend fun addGame(entry: GameEntry): Long = dao.insert(entry)

    /** Remove a game entry */
    suspend fun removeGame(entry: GameEntry) = dao.delete(entry)

    /** Clean up all data for a deleted widget */
    suspend fun deleteWidget(widgetId: Int) = dao.deleteByWidgetId(widgetId)

    /** Blocking cleanup for widget deletion (called from Provider) */
    fun deleteWidgetSync(widgetId: Int) = dao.deleteByWidgetIdSync(widgetId)
}
