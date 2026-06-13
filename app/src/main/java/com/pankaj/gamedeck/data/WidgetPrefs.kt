package com.pankaj.gamedeck.data

import android.content.Context

object WidgetPrefs {
    private const val P = "gamedeck_widget_prefs"
    const val MODE_STACK = "stack"
    const val MODE_LIST = "list"

    private fun p(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    // ─── Carousel ───
    fun getCurrentIndex(c: Context, id: Int) = p(c).getInt("idx_$id", 0)
    fun setCurrentIndex(c: Context, id: Int, v: Int) = p(c).edit().putInt("idx_$id", v).apply()

    // ─── GIF ───
    fun getCurrentFrame(c: Context, id: Int) = p(c).getInt("frame_$id", 0)
    fun setCurrentFrame(c: Context, id: Int, v: Int) = p(c).edit().putInt("frame_$id", v).apply()

    // ─── Appearance: SEPARATE card & background radii ───
    fun getCardCornerRadius(c: Context, id: Int) = p(c).getInt("card_corner_$id", 18)
    fun setCardCornerRadius(c: Context, id: Int, v: Int) = p(c).edit().putInt("card_corner_$id", v).apply()

    fun getBgCornerRadius(c: Context, id: Int) = p(c).getInt("bg_corner_$id", 24)
    fun setBgCornerRadius(c: Context, id: Int, v: Int) = p(c).edit().putInt("bg_corner_$id", v).apply()

    fun getShowBackground(c: Context, id: Int) = p(c).getBoolean("bg_$id", true)
    fun setShowBackground(c: Context, id: Int, v: Boolean) = p(c).edit().putBoolean("bg_$id", v).apply()

    fun getBlurRadius(c: Context, id: Int) = p(c).getInt("blur_$id", 20)
    fun setBlurRadius(c: Context, id: Int, v: Int) = p(c).edit().putInt("blur_$id", v).apply()

    fun getWallpaperPath(c: Context, id: Int): String? = p(c).getString("wp_$id", null)
    fun setWallpaperPath(c: Context, id: Int, v: String?) = p(c).edit().putString("wp_$id", v).apply()

    fun getShowTitle(c: Context, id: Int) = p(c).getBoolean("title_$id", true)
    fun setShowTitle(c: Context, id: Int, v: Boolean) = p(c).edit().putBoolean("title_$id", v).apply()

    fun getShowIcon(c: Context, id: Int) = p(c).getBoolean("icon_$id", true)
    fun setShowIcon(c: Context, id: Int, v: Boolean) = p(c).edit().putBoolean("icon_$id", v).apply()

    fun getShowStats(c: Context, id: Int) = p(c).getBoolean("stats_$id", true)
    fun setShowStats(c: Context, id: Int, v: Boolean) = p(c).edit().putBoolean("stats_$id", v).apply()

    fun getIconSize(c: Context, id: Int) = p(c).getInt("icon_size_$id", 24)
    fun setIconSize(c: Context, id: Int, v: Int) = p(c).edit().putInt("icon_size_$id", v).apply()

    // ─── Tint ───
    fun getTintColor(c: Context, id: Int) = p(c).getInt("tint_$id", 0x000000) // default: black (alpha controlled separately)
    fun setTintColor(c: Context, id: Int, v: Int) = p(c).edit().putInt("tint_$id", v).apply()

    fun getTintAlpha(c: Context, id: Int) = p(c).getInt("tint_a_$id", 15) // 0-100, default 15% (barely visible)
    fun setTintAlpha(c: Context, id: Int, v: Int) = p(c).edit().putInt("tint_a_$id", v).apply()

    fun cleanup(c: Context, id: Int) = p(c).edit()
        .remove("idx_$id").remove("frame_$id")
        .remove("card_corner_$id").remove("bg_corner_$id")
        .remove("bg_$id").remove("blur_$id").remove("wp_$id")
        .remove("tint_$id").remove("tint_a_$id").apply()
}
