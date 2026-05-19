package com.pankaj.gamedeck.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "game_entries")
data class GameEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val widgetId: Int,
    val packageName: String,
    val appLabel: String,
    val imagePath: String? = null,
    val gifPath: String? = null,
    val position: Int = 0,
    val isGif: Boolean = false,
    val gifFrameCount: Int = 0,
    val customPlayText: String? = null
)
