package com.pankaj.gamedeck.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.pankaj.gamedeck.R
import com.pankaj.gamedeck.data.BlurUtil
import com.pankaj.gamedeck.data.GifProcessor
import com.pankaj.gamedeck.data.WidgetPrefs
import com.pankaj.gamedeck.data.WidgetRepository
import com.pankaj.gamedeck.data.model.GameEntry
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class GameDeckWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "GameDeckWidget"
        const val ACTION_LAUNCH = "com.pankaj.gamedeck.ACTION_LAUNCH"
        const val ACTION_NEXT_GAME = "com.pankaj.gamedeck.ACTION_NEXT_GAME"
        const val ACTION_PREV_GAME = "com.pankaj.gamedeck.ACTION_PREV_GAME"
        const val ACTION_JUMP_TO_GAME = "com.pankaj.gamedeck.ACTION_JUMP_TO_GAME"
        const val ACTION_UNLOCK_PLAY_GIFS = "com.pankaj.gamedeck.ACTION_UNLOCK_PLAY_GIFS"
        private const val ACTION_KEEP_ALIVE = "com.pankaj.gamedeck.ACTION_KEEP_ALIVE"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_WIDGET_ID = "extra_widget_id"
        const val EXTRA_TARGET_INDEX = "extra_target_index"
        private const val GIF_MAX_DURATION_MS = 5_000L
        private const val WIDGET_ANIMATION_MAX_FPS = 60 // Respect GIF's native FPS
        private const val FADE_DURATION_MS = 0L // No fade, snappy transition
        
        private val executor = Executors.newSingleThreadExecutor()
        private val widgetSessions = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicInteger>()
        private fun getSession(id: Int) = widgetSessions.getOrPut(id) { java.util.concurrent.atomic.AtomicInteger(0) }
        // Per-widget animation lock to prevent concurrent animation threads
        private val animatingWidgets = java.util.concurrent.ConcurrentHashMap<Int, AtomicBoolean>()
        private fun isAnimating(id: Int) = animatingWidgets.getOrPut(id) { AtomicBoolean(false) }

        private data class WidgetRenderSize(val widthPx: Int, val heightPx: Int)

        fun notifyWidgetsChanged(context: Context) {
            try {
                ensureMonitorService(context)
                scheduleKeepAlive(context)
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, GameDeckWidgetProvider::class.java))
                for (id in ids) updateWidget(context, mgr, id)
            } catch (e: Exception) { Log.e(TAG, "notify", e) }
        }

        fun updateWidget(
            context: Context,
            mgr: AppWidgetManager,
            widgetId: Int,
            useAnimationStartFrame: Boolean = false
        ) {
            ensureMonitorService(context)
            scheduleKeepAlive(context)
            executor.execute {
                try {
                    val views = RemoteViews(context.packageName, R.layout.widget_layout)
                    val density = context.resources.displayMetrics.density
                    
                    val repo = WidgetRepository(context)
                    val games = repo.getGamesForWidgetSync(widgetId)

                    if (games.isEmpty()) {
                        views.setViewVisibility(R.id.widget_empty_view, View.VISIBLE)
                        views.setViewVisibility(R.id.widget_game_cover, View.GONE)
                        views.setViewVisibility(R.id.widget_bottom_area, View.GONE)
                        views.setViewVisibility(R.id.widget_dots_container, View.GONE)
                        views.setViewVisibility(R.id.widget_blur_bg, View.GONE)
                        mgr.updateAppWidget(widgetId, views)
                        return@execute
                    }

                    views.setViewVisibility(R.id.widget_empty_view, View.GONE)
                    views.setViewVisibility(R.id.widget_game_cover, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_bottom_area, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_dots_container, View.VISIBLE)

                    var currentIndex = WidgetPrefs.getCurrentIndex(context, widgetId)
                    if (currentIndex >= games.size) {
                        currentIndex = 0
                        WidgetPrefs.setCurrentIndex(context, widgetId, currentIndex)
                    }
                    val game = games[currentIndex]

                    val widgetSize = getWidgetRenderSize(mgr, widgetId, density, 800)
                    val widgetWPx = widgetSize.widthPx
                    val widgetHPx = widgetSize.heightPx

                    // ── Background handling ──
                    val showBg = WidgetPrefs.getShowBackground(context, widgetId)
                    val bgCornerPx = WidgetPrefs.getBgCornerRadius(context, widgetId) * density

                    if (!showBg) {
                        views.setViewVisibility(R.id.widget_blur_bg, View.GONE)
                        views.setInt(R.id.widget_glass_container, "setBackgroundColor", 0x00000000)
                        views.setViewPadding(R.id.widget_glass_container, 0, 0, 0, 0)
                    } else {
                        val wpPath = WidgetPrefs.getWallpaperPath(context, widgetId)
                        if (wpPath != null && File(wpPath).exists()) {
                            val wallpaper = BitmapFactory.decodeFile(wpPath)
                            if (wallpaper != null) {
                                val blur = WidgetPrefs.getBlurRadius(context, widgetId)
                                val tintColor = WidgetPrefs.getTintColor(context, widgetId)
                                val tintAlpha = WidgetPrefs.getTintAlpha(context, widgetId)
                                val blurred = BlurUtil.createBlurredBackground(
                                    wallpaper, blur, bgCornerPx, widgetWPx, widgetHPx, tintColor, tintAlpha
                                )
                                views.setImageViewBitmap(R.id.widget_blur_bg, blurred)
                                views.setViewVisibility(R.id.widget_blur_bg, View.VISIBLE)
                                views.setInt(R.id.widget_glass_container, "setBackgroundColor", 0x00000000)
                            }
                        } else {
                            views.setViewVisibility(R.id.widget_blur_bg, View.GONE)
                        }
                        val pad = (4 * density).toInt()
                        views.setViewPadding(R.id.widget_glass_container, pad, pad, pad, pad)
                    }

                    // ── Game Cover ──
                    val rawCover = loadAnimationStartFrame(context, game, useAnimationStartFrame)
                        ?: loadRawCover(context, game)
                    if (rawCover != null) {
                        val cardCornerPx = WidgetPrefs.getCardCornerRadius(context, widgetId) * density
                        val renderW = widgetWPx
                        val renderH = widgetHPx
                        val cropped = centerCrop(rawCover, renderW, renderH)
                        
                        val mutableBitmap = cropped.copy(Bitmap.Config.ARGB_8888, true)
                        
                        // Extract Palette
                        val palette = androidx.palette.graphics.Palette.from(mutableBitmap).generate()
                        val baseColor = palette.getVibrantColor(0x000000)
                        val c55 = (baseColor and 0x00FFFFFF) or 0x55000000
                        val cCC = (baseColor and 0x00FFFFFF) or 0xCC000000.toInt()
                        
                        val showTitle = WidgetPrefs.getShowTitle(context, widgetId)
                        val showIcon = WidgetPrefs.getShowIcon(context, widgetId)
                        val hasBottomContent = showTitle || showIcon || games.size > 1
                        if (hasBottomContent) {
                            val canvas = Canvas(mutableBitmap)
                            val gradientHeight = 100f * density
                            val paint = Paint()
                            paint.shader = LinearGradient(
                                0f, mutableBitmap.height - gradientHeight,
                                0f, mutableBitmap.height.toFloat(),
                                intArrayOf(Color.TRANSPARENT, c55, cCC),
                                floatArrayOf(0f, 0.5f, 1f),
                                Shader.TileMode.CLAMP
                            )
                            canvas.drawRect(
                                0f, mutableBitmap.height - gradientHeight,
                                mutableBitmap.width.toFloat(), mutableBitmap.height.toFloat(),
                                paint
                            )
                            views.setViewVisibility(R.id.widget_bottom_area, View.VISIBLE)
                            views.setViewVisibility(R.id.widget_info_container, View.VISIBLE)
                            
                            if (showTitle) {
                                views.setViewVisibility(R.id.widget_game_title, View.VISIBLE)
                                views.setTextViewText(R.id.widget_game_title, game.appLabel)
                                
                                val playtime = com.pankaj.gamedeck.data.UsageStatsHelper.getPlaytimeFormatted(context, game.packageName)
                                if (playtime != null) {
                                    views.setTextViewText(R.id.widget_playtime, playtime)
                                    views.setViewVisibility(R.id.widget_playtime, View.VISIBLE)
                                } else {
                                    views.setViewVisibility(R.id.widget_playtime, View.GONE)
                                }
                            } else {
                                views.setViewVisibility(R.id.widget_game_title, View.GONE)
                                views.setViewVisibility(R.id.widget_playtime, View.GONE)
                            }
                            
                            // Set App Icon
                            if (showIcon) {
                                try {
                                    val pm = context.packageManager
                                    val iconDrawable = pm.getApplicationIcon(game.packageName)
                                    val iconSizePx = (WidgetPrefs.getIconSize(context, widgetId) * density).toInt()
                                    val iconBitmap = android.graphics.Bitmap.createBitmap(iconSizePx.coerceAtLeast(1), iconSizePx.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
                                    val iconCanvas = android.graphics.Canvas(iconBitmap)
                                    iconDrawable.setBounds(0, 0, iconCanvas.width, iconCanvas.height)
                                    iconDrawable.draw(iconCanvas)
                                    views.setImageViewBitmap(R.id.widget_app_icon, iconBitmap)
                                    views.setViewVisibility(R.id.widget_app_icon, View.VISIBLE)
                                } catch(e: Exception) {
                                    views.setViewVisibility(R.id.widget_app_icon, View.GONE)
                                }
                            } else {
                                views.setViewVisibility(R.id.widget_app_icon, View.GONE)
                            }
                        } else {
                            views.setViewVisibility(R.id.widget_info_container, View.GONE)
                            if (games.size <= 1) {
                                views.setViewVisibility(R.id.widget_bottom_area, View.GONE)
                            }
                        }

                        // ── Dynamic Stats (Top Left) ──
                        val showStats = WidgetPrefs.getShowStats(context, widgetId)
                        if (showStats) {
                            val customGoal = game.customPlayText
                            val weeklyHeat = com.pankaj.gamedeck.data.UsageStatsHelper.getWeeklyPlaytimeFormatted(context, game.packageName)
                            val lastSession = com.pankaj.gamedeck.data.UsageStatsHelper.getLastSessionFormatted(context, game.packageName)
                            val updateBadge = com.pankaj.gamedeck.data.UsageStatsHelper.getFreshUpdateBadge(context, game.packageName)

                            var hasStats = false
                            if (!customGoal.isNullOrEmpty()) {
                                views.setTextViewText(R.id.widget_stat_goal, "🏆 Goal: $customGoal")
                                views.setViewVisibility(R.id.widget_stat_goal, View.VISIBLE)
                                hasStats = true
                            } else {
                                views.setViewVisibility(R.id.widget_stat_goal, View.GONE)
                            }

                            if (weeklyHeat != null) {
                                views.setTextViewText(R.id.widget_stat_heat, weeklyHeat)
                                views.setViewVisibility(R.id.widget_stat_heat, View.VISIBLE)
                                hasStats = true
                            } else {
                                views.setViewVisibility(R.id.widget_stat_heat, View.GONE)
                            }

                            if (lastSession != null) {
                                views.setTextViewText(R.id.widget_stat_session, lastSession)
                                views.setViewVisibility(R.id.widget_stat_session, View.VISIBLE)
                                hasStats = true
                            } else {
                                views.setViewVisibility(R.id.widget_stat_session, View.GONE)
                            }

                            if (updateBadge != null) {
                                views.setTextViewText(R.id.widget_stat_update, updateBadge)
                                views.setViewVisibility(R.id.widget_stat_update, View.VISIBLE)
                                hasStats = true
                            } else {
                                views.setViewVisibility(R.id.widget_stat_update, View.GONE)
                            }

                            if (hasStats) {
                                views.setViewVisibility(R.id.widget_stats_container, View.VISIBLE)
                            } else {
                                views.setViewVisibility(R.id.widget_stats_container, View.GONE)
                            }
                        } else {
                            views.setViewVisibility(R.id.widget_stats_container, View.GONE)
                        }
                        
                        val processed = BlurUtil.roundBitmap(mutableBitmap, cardCornerPx)
                        views.setImageViewBitmap(R.id.widget_game_cover, processed)
                    } else {
                        views.setImageViewResource(R.id.widget_game_cover, R.drawable.ic_placeholder)
                    }

                    // ── Game Thumbnail Navigation Strip ──
                    if (games.size > 1) {
                        views.removeAllViews(R.id.widget_dots_container)
                        for (i in games.indices) {
                            val thumbView = RemoteViews(context.packageName, R.layout.widget_thumb)
                            val thumbBitmap = renderGameThumb(context, games[i], i == currentIndex, density)
                            if (thumbBitmap != null) {
                                thumbView.setImageViewBitmap(R.id.widget_thumb_img, thumbBitmap)
                            }
                            views.addView(R.id.widget_dots_container, thumbView)
                        }
                        views.setViewVisibility(R.id.widget_dots_container, View.VISIBLE)
                    } else {
                        views.setViewVisibility(R.id.widget_dots_container, View.GONE)
                    }

                    // ── Intents ──
                    // Tap cover to cycle to next game
                    bindClickIntents(context, views, widgetId, game)

                    mgr.updateAppWidget(widgetId, views)
                } catch (e: Exception) { Log.e(TAG, "updateWidget $widgetId", e) }
            }
        }

        private fun centerCrop(src: Bitmap, outW: Int, outH: Int): Bitmap {
            val srcRatio = src.width.toFloat() / src.height
            val outRatio = outW.toFloat() / outH
            val cropW: Int; val cropH: Int
            if (srcRatio > outRatio) {
                cropH = src.height; cropW = (cropH * outRatio).toInt()
            } else {
                cropW = src.width; cropH = (cropW / outRatio).toInt()
            }
            val x = ((src.width - cropW) / 2).coerceAtLeast(0)
            val y = ((src.height - cropH) / 2).coerceAtLeast(0)
            val cropped = Bitmap.createBitmap(src, x, y, cropW.coerceAtMost(src.width - x), cropH.coerceAtMost(src.height - y))
            return Bitmap.createScaledBitmap(cropped, outW, outH, true)
        }

        private fun getWidgetRenderSize(
            mgr: AppWidgetManager,
            widgetId: Int,
            density: Float,
            maxSidePx: Int
        ): WidgetRenderSize {
            val opts = mgr.getAppWidgetOptions(widgetId)
            var wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            var hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            val maxWDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
            val maxHDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)

            if (wDp < 100 && maxWDp >= 100) wDp = maxWDp
            if (hDp < 100 && maxHDp >= 100) hDp = maxHDp
            if (wDp < 100 || hDp < 100) {
                wDp = 250
                hDp = 200
            }

            val rawW = (wDp * density).toInt().coerceAtLeast(200)
            val rawH = (hDp * density).toInt().coerceAtLeast(200)
            val scale = minOf(maxSidePx.toFloat() / rawW, maxSidePx.toFloat() / rawH, 1f)
            return WidgetRenderSize(
                (rawW * scale).toInt().coerceAtLeast(200),
                (rawH * scale).toInt().coerceAtLeast(200)
            )
        }

        private fun loadRawCover(context: Context, game: GameEntry): Bitmap? {
            val path = game.imagePath
            if (!path.isNullOrEmpty() && File(path).exists()) {
                try {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, opts)
                    var s = 1
                    while (opts.outWidth / s > 1440 || opts.outHeight / s > 1440) s *= 2
                    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
                } catch (e: Exception) { Log.e(TAG, "decode $path", e) }
            }
            return try {
                val info = context.packageManager.getApplicationInfo(game.packageName, 0)
                val d = context.packageManager.getApplicationIcon(info)
                val w = d.intrinsicWidth.coerceIn(1, 256); val h = d.intrinsicHeight.coerceIn(1, 256)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    Canvas(it).apply { d.setBounds(0, 0, w, h); d.draw(this) }
                }
            } catch (e: Exception) { null }
        }

        private fun renderGameThumb(
            context: Context,
            game: GameEntry,
            isActive: Boolean,
            density: Float
        ): Bitmap? {
            try {
                // Active: 6dp wide, 16dp tall pill. Inactive: 6dp circle.
                val widthPx = (6 * density).toInt().coerceAtLeast(6)
                val heightPx = if (isActive) (16 * density).toInt().coerceAtLeast(16) else widthPx
                
                val output = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(output)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (isActive) Color.WHITE else Color.argb(128, 255, 255, 255)
                    // Add subtle shadow for visibility against light backgrounds
                    setShadowLayer(2f * density, 0f, 1f * density, Color.argb(100, 0, 0, 0))
                }
                
                val radius = widthPx / 2f
                val rect = android.graphics.RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
                canvas.drawRoundRect(rect, radius, radius, paint)
                
                return output
            } catch (e: Exception) {
                Log.e(TAG, "renderGameThumb", e)
                return null
            }
        }

        private fun ensureMonitorService(context: Context) {
            try {
                val intent = Intent(context, LockUnlockMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to start lock/unlock monitor", e)
            }
        }

        private fun stopMonitorService(context: Context) {
            try {
                context.stopService(Intent(context, LockUnlockMonitorService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Unable to stop lock/unlock monitor", e)
            }
        }

        private fun loadAnimationStartFrame(
            context: Context,
            game: GameEntry,
            enabled: Boolean
        ): Bitmap? {
            if (!enabled || !game.isGif || game.gifFrameCount <= 0 || game.gifPath == null) return null
            val frameKey = File(game.gifPath).nameWithoutExtension
            return GifProcessor(context).loadFrame(frameKey, 0)
        }

        private fun bindClickIntents(
            context: Context,
            views: RemoteViews,
            widgetId: Int,
            game: GameEntry
        ) {
            val gameLaunchIntent = context.packageManager
                .getLaunchIntentForPackage(game.packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            val launchPi = if (gameLaunchIntent != null) {
                PendingIntent.getActivity(
                    context,
                    widgetId * 10 + 1,
                    gameLaunchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                val launchIntent = Intent(context, GameDeckWidgetProvider::class.java).apply {
                    action = ACTION_LAUNCH
                    putExtra(EXTRA_WIDGET_ID, widgetId)
                    putExtra(EXTRA_PACKAGE_NAME, game.packageName)
                }
                PendingIntent.getBroadcast(
                    context,
                    widgetId * 10 + 1,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            views.setOnClickPendingIntent(R.id.widget_launch_target, launchPi)

            val prevIntent = Intent(context, GameDeckWidgetProvider::class.java).apply {
                action = ACTION_PREV_GAME
                putExtra(EXTRA_WIDGET_ID, widgetId)
            }
            val prevPi = PendingIntent.getBroadcast(
                context,
                widgetId * 10 + 3,
                prevIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_prev_target, prevPi)

            val nextIntent = Intent(context, GameDeckWidgetProvider::class.java).apply {
                action = ACTION_NEXT_GAME
                putExtra(EXTRA_WIDGET_ID, widgetId)
            }
            val nextPi = PendingIntent.getBroadcast(
                context,
                widgetId * 10 + 2,
                nextIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_next_target, nextPi)
        }

        private fun bindAppIcon(
            context: Context,
            views: RemoteViews,
            widgetId: Int,
            game: GameEntry,
            density: Float
        ) {
            if (!WidgetPrefs.getShowIcon(context, widgetId)) {
                views.setViewVisibility(R.id.widget_app_icon, View.GONE)
                return
            }
            try {
                val iconDrawable = context.packageManager.getApplicationIcon(game.packageName)
                val iconSizePx = (WidgetPrefs.getIconSize(context, widgetId) * density).toInt().coerceAtLeast(1)
                val iconBitmap = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
                val iconCanvas = Canvas(iconBitmap)
                iconDrawable.setBounds(0, 0, iconCanvas.width, iconCanvas.height)
                iconDrawable.draw(iconCanvas)
                views.setImageViewBitmap(R.id.widget_app_icon, iconBitmap)
                views.setViewVisibility(R.id.widget_app_icon, View.VISIBLE)
            } catch (e: Exception) {
                views.setViewVisibility(R.id.widget_app_icon, View.GONE)
            }
        }

        private fun bindGameText(
            context: Context,
            views: RemoteViews,
            widgetId: Int,
            game: GameEntry
        ) {
            if (WidgetPrefs.getShowTitle(context, widgetId)) {
                views.setViewVisibility(R.id.widget_game_title, View.VISIBLE)
                views.setTextViewText(R.id.widget_game_title, game.appLabel)

                val playtime = com.pankaj.gamedeck.data.UsageStatsHelper.getPlaytimeFormatted(context, game.packageName)
                if (playtime != null) {
                    views.setTextViewText(R.id.widget_playtime, playtime)
                    views.setViewVisibility(R.id.widget_playtime, View.VISIBLE)
                } else {
                    views.setTextViewText(R.id.widget_playtime, "")
                    views.setViewVisibility(R.id.widget_playtime, View.GONE)
                }
            } else {
                views.setViewVisibility(R.id.widget_game_title, View.GONE)
                views.setTextViewText(R.id.widget_playtime, "")
                views.setViewVisibility(R.id.widget_playtime, View.GONE)
            }
        }

        private fun showAnimationStartFrame(
            context: Context,
            mgr: AppWidgetManager,
            widgetId: Int,
            games: List<GameEntry>,
            currentIndex: Int,
            game: GameEntry
        ) {
            val frame = loadAnimationStartFrame(context, game, enabled = true) ?: return
            val density = context.resources.displayMetrics.density
            val size = getWidgetRenderSize(mgr, widgetId, density, 800)
            val cardCornerPx = WidgetPrefs.getCardCornerRadius(context, widgetId) * density
            val bitmap = BlurUtil.roundBitmap(centerCrop(frame, size.widthPx, size.heightPx), cardCornerPx)
            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            views.setImageViewBitmap(R.id.widget_gif_overlay, bitmap)
            views.setViewVisibility(R.id.widget_gif_overlay, View.VISIBLE)
            views.setViewVisibility(R.id.widget_empty_view, View.GONE)

            views.setViewVisibility(R.id.widget_bottom_area, View.VISIBLE)
            views.setViewVisibility(R.id.widget_info_container, View.VISIBLE)
            bindGameText(context, views, widgetId, game)
            bindAppIcon(context, views, widgetId, game, density)

            // ── Game Thumbnail Navigation Strip ──
            if (games.size > 1) {
                views.removeAllViews(R.id.widget_dots_container)
                for (i in games.indices) {
                    val thumbView = RemoteViews(context.packageName, R.layout.widget_thumb)
                    val thumbBitmap = renderGameThumb(context, games[i], i == currentIndex, density)
                    if (thumbBitmap != null) {
                        thumbView.setImageViewBitmap(R.id.widget_thumb_img, thumbBitmap)
                    }
                    views.addView(R.id.widget_dots_container, thumbView)
                }
                views.setViewVisibility(R.id.widget_dots_container, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_dots_container, View.GONE)
            }

            bindClickIntents(context, views, widgetId, game)
            mgr.partiallyUpdateAppWidget(widgetId, views)
        }

        private fun scheduleKeepAlive(context: Context) {
            try {
                val intent = Intent(context, GameDeckWidgetProvider::class.java).apply {
                    action = ACTION_KEEP_ALIVE
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    1000,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                alarmManager.setInexactRepeating(
                    android.app.AlarmManager.RTC,
                    System.currentTimeMillis() + 15 * 60 * 1000L,
                    15 * 60 * 1000L,
                    pendingIntent
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unable to schedule widget keep-alive", e)
            }
        }

        private fun cancelKeepAlive(context: Context) {
            try {
                val intent = Intent(context, GameDeckWidgetProvider::class.java).apply {
                    action = ACTION_KEEP_ALIVE
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    1000,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                alarmManager.cancel(pendingIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to cancel widget keep-alive", e)
            }
        }

        private fun applyAnimationBackgroundPrefs(
            context: Context,
            views: RemoteViews,
            widgetId: Int,
            density: Float
        ) {
            if (!WidgetPrefs.getShowBackground(context, widgetId)) {
                views.setViewVisibility(R.id.widget_blur_bg, View.GONE)
                views.setInt(R.id.widget_glass_container, "setBackgroundColor", 0x00000000)
                views.setViewPadding(R.id.widget_glass_container, 0, 0, 0, 0)
            } else {
                val pad = (4 * density).toInt()
                views.setViewPadding(R.id.widget_glass_container, pad, pad, pad, pad)
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ensureMonitorService(context)
        scheduleKeepAlive(context)
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ensureMonitorService(ctx)
        scheduleKeepAlive(ctx)
        for (id in ids) updateWidget(ctx, mgr, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive action=${intent.action}")
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_LAUNCH -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                launchActiveGame(context, widgetId, packageName)
            }
            ACTION_PREV_GAME -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val targetSession = getSession(widgetId).incrementAndGet()
                    isAnimating(widgetId).set(false) // Release lock so new animation can start
                    executor.execute {
                        val repo = WidgetRepository(context)
                        val games = repo.getGamesForWidgetSync(widgetId)
                        if (games.isNotEmpty()) {
                            val oldIdx = WidgetPrefs.getCurrentIndex(context, widgetId)
                            val oldGame = if (oldIdx < games.size) games[oldIdx] else games[0]
                            var currentIdx = WidgetPrefs.getCurrentIndex(context, widgetId)
                            currentIdx = if (currentIdx - 1 < 0) games.size - 1 else currentIdx - 1
                            WidgetPrefs.setCurrentIndex(context, widgetId, currentIdx)
                            val activeGame = games[currentIdx]
                            
                            try {
                                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                                if (vibrator.hasVibrator()) {
                                    vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK))
                                }
                            } catch (e: Exception) {}

                            val mgr = AppWidgetManager.getInstance(context)

                            // Perform optimized slide transition (6 frames)
                            runSlideTransition(context, mgr, widgetId, targetSession, oldGame, activeGame, isNext = false)

                            val shouldAnimate = activeGame.isGif && activeGame.gifFrameCount > 0 && activeGame.gifPath != null
                            if (shouldAnimate) {
                                Thread { animateGifs(context, listOf(widgetId)) }.start()
                            } else {
                                updateWidget(context, mgr, widgetId)
                            }
                        }
                    }
                }
            }
            ACTION_NEXT_GAME -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val targetSession = getSession(widgetId).incrementAndGet() // Cancel any ongoing animation immediately
                    isAnimating(widgetId).set(false) // Release lock so new animation can start
                    executor.execute {
                        val repo = WidgetRepository(context)
                        val games = repo.getGamesForWidgetSync(widgetId)
                        if (games.isNotEmpty()) {
                            val oldIdx = WidgetPrefs.getCurrentIndex(context, widgetId)
                            val oldGame = if (oldIdx < games.size) games[oldIdx] else games[0]
                            var currentIdx = WidgetPrefs.getCurrentIndex(context, widgetId)
                            currentIdx = (currentIdx + 1) % games.size
                            WidgetPrefs.setCurrentIndex(context, widgetId, currentIdx)
                            val activeGame = games[currentIdx]
                            
                            // Trigger haptics
                            try {
                                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                                if (vibrator.hasVibrator()) {
                                    vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK))
                                }
                            } catch (e: Exception) {}

                            Log.d(TAG, "Next game widget=$widgetId index=$currentIdx label=${activeGame.appLabel} isGif=${activeGame.isGif} frames=${activeGame.gifFrameCount} gifPath=${activeGame.gifPath}")
                            val mgr = AppWidgetManager.getInstance(context)

                            // Perform optimized slide transition (6 frames)
                            runSlideTransition(context, mgr, widgetId, targetSession, oldGame, activeGame, isNext = true)

                            val shouldAnimate = activeGame.isGif && activeGame.gifFrameCount > 0 && activeGame.gifPath != null
                            if (shouldAnimate) {
                                Thread { animateGifs(context, listOf(widgetId)) }.start()
                            } else {
                                updateWidget(context, mgr, widgetId)
                            }
                        }
                    }
                }
            }
            ACTION_JUMP_TO_GAME -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val targetIndex = intent.getIntExtra(EXTRA_TARGET_INDEX, -1)
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID && targetIndex >= 0) {
                    getSession(widgetId).incrementAndGet() // Cancel any ongoing animation
                    isAnimating(widgetId).set(false) // Release lock
                    executor.execute {
                        val repo = WidgetRepository(context)
                        val games = repo.getGamesForWidgetSync(widgetId)
                        if (games.isNotEmpty() && targetIndex < games.size) {
                            val currentIdx = WidgetPrefs.getCurrentIndex(context, widgetId)
                            if (targetIndex == currentIdx) return@execute // Already on this card

                            WidgetPrefs.setCurrentIndex(context, widgetId, targetIndex)
                            val activeGame = games[targetIndex]

                            // Trigger haptics
                            try {
                                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                                if (vibrator.hasVibrator()) {
                                    vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK))
                                }
                            } catch (e: Exception) {}

                            Log.d(TAG, "Jump to game widget=$widgetId index=$targetIndex label=${activeGame.appLabel}")
                            val mgr = AppWidgetManager.getInstance(context)
                            val shouldAnimate = activeGame.isGif && activeGame.gifFrameCount > 0 && activeGame.gifPath != null
                            if (shouldAnimate) {
                                Thread { animateGifs(context, listOf(widgetId)) }.start()
                            } else {
                                updateWidget(context, mgr, widgetId)
                            }
                        }
                    }
                }
            }
            Intent.ACTION_USER_PRESENT -> {
                Log.d(TAG, "Unlock received; starting GIF animation for active GIF widgets")
                val r = goAsync()
                Thread {
                    try { animateGifs(context, null) } finally { r.finish() }
                }.start()
            }
            ACTION_UNLOCK_PLAY_GIFS -> {
                Log.d(TAG, "Monitor forwarded unlock; starting GIF animation for active GIF widgets")
                val r = goAsync()
                Thread {
                    try { animateGifs(context, null) } finally { r.finish() }
                }.start()
            }
            ACTION_KEEP_ALIVE -> {
                ensureMonitorService(context)
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, GameDeckWidgetProvider::class.java))
                // Skip widgets that are actively animating to prevent flicker
                for (id in ids) {
                    if (!isAnimating(id).get()) updateWidget(context, mgr, id)
                }
            }
        }
    }

    private fun launchActiveGame(context: Context, widgetId: Int, packageNameFromIntent: String?) {
        executor.execute {
            try {
                val packageName = packageNameFromIntent ?: run {
                    if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return@execute
                    val games = WidgetRepository(context).getGamesForWidgetSync(widgetId)
                    if (games.isEmpty()) return@execute
                    val currentIndex = WidgetPrefs.getCurrentIndex(context, widgetId).coerceIn(0, games.lastIndex)
                    games[currentIndex].packageName
                }
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent == null) {
                    Log.w(TAG, "No launch intent for package=$packageName")
                    return@execute
                }
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to launch game", e)
            }
        }
    }

    private fun animateGifs(context: Context, targetWidgetIds: List<Int>?) {
        try {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = targetWidgetIds?.toIntArray() ?: mgr.getAppWidgetIds(ComponentName(context, GameDeckWidgetProvider::class.java))
            val repo = WidgetRepository(context); val gifProc = GifProcessor(context)

            val targets = mutableListOf<GifTarget>()
            for (id in ids) {
                val games = repo.getGamesForWidgetSync(id)
                if (games.isEmpty()) continue
                val currentIndex = WidgetPrefs.getCurrentIndex(context, id)
                val activeGame = if (currentIndex < games.size) games[currentIndex] else games[0]
                Log.d(TAG, "GIF check widget=$id index=$currentIndex label=${activeGame.appLabel} isGif=${activeGame.isGif} frames=${activeGame.gifFrameCount} gifPath=${activeGame.gifPath}")
                if (activeGame.isGif && activeGame.gifFrameCount > 0 && activeGame.gifPath != null) {
                    targets.add(GifTarget(id, activeGame))
                }
            }
            if (targets.isEmpty()) {
                Log.d(TAG, "GIF animation skipped: no active GIF targets")
                return
            }

            // Acquire animation lock — skip widgets already animating
            val lockedTargets = targets.filter { isAnimating(it.widgetId).compareAndSet(false, true) }
            if (lockedTargets.isEmpty()) {
                Log.d(TAG, "GIF animation skipped: all targets already animating")
                return
            }

            try { // try-finally to guarantee lock release

            lockedTargets.forEach { t -> WidgetPrefs.setCurrentFrame(context, t.widgetId, 0) }

            val density = context.resources.displayMetrics.density
            var activeTargets = lockedTargets.toList()
            val startedAt = System.currentTimeMillis()
            
            val sessionSnapshots = mutableMapOf<Int, Int>()
            lockedTargets.forEach { t -> sessionSnapshots[t.widgetId] = getSession(t.widgetId).get() }

            // ── PRE-LOAD all frames into memory AND pre-process to widget size ──
            data class PreloadedAnimation(
                val frames: List<Bitmap>,
                val fps: Int,
                val game: GameEntry,
                val widgetId: Int
            )
            val animations = mutableListOf<PreloadedAnimation>()
            for (t in lockedTargets) {
                val frameKey = File(t.game.gifPath!!).nameWithoutExtension
                val widgetSize = getWidgetRenderSize(mgr, t.widgetId, density, 800)
                val cardCornerPx = WidgetPrefs.getCardCornerRadius(context, t.widgetId) * density
                val frameList = mutableListOf<Bitmap>()
                for (i in 0 until t.game.gifFrameCount) {
                    val frame = gifProc.loadFrame(frameKey, i) ?: continue
                    // Pre-process to exact widget size so every frame is pixel-identical format
                    val cropped = centerCrop(frame, widgetSize.widthPx, widgetSize.heightPx)
                    val processed = BlurUtil.roundBitmap(cropped, cardCornerPx)
                    frameList.add(processed)
                }
                if (frameList.isNotEmpty()) {
                    val fps = t.game.animationFps.coerceIn(1, WIDGET_ANIMATION_MAX_FPS)
                    animations.add(PreloadedAnimation(frameList, fps, t.game, t.widgetId))
                    Log.d(TAG, "Pre-loaded ${frameList.size} frames for widget=${t.widgetId} fps=$fps size=${widgetSize.widthPx}x${widgetSize.heightPx}")
                }
            }

            if (animations.isEmpty()) return

            // ── Show overlay + bind text/icons/intents + first frame — ONE single update ──
            for (anim in animations) {
                val initViews = RemoteViews(context.packageName, R.layout.widget_layout)
                initViews.setImageViewBitmap(R.id.widget_gif_overlay, anim.frames[0])
                initViews.setViewVisibility(R.id.widget_gif_overlay, View.VISIBLE)
                initViews.setViewVisibility(R.id.widget_bottom_area, View.VISIBLE)
                initViews.setViewVisibility(R.id.widget_info_container, View.VISIBLE)
                bindGameText(context, initViews, anim.widgetId, anim.game)
                bindAppIcon(context, initViews, anim.widgetId, anim.game, density)
                bindClickIntents(context, initViews, anim.widgetId, anim.game)
                mgr.partiallyUpdateAppWidget(anim.widgetId, initViews)
            }

            // ── Frame loop: just index into pre-processed array ──
            val frameIndices = mutableMapOf<Int, Int>()
            animations.forEach { frameIndices[it.widgetId] = 1 } // Start from 1, frame 0 already shown
            val sleepMs = animations.minOf { 1000L / it.fps }.coerceAtLeast(16L)

            while (animations.any { frameIndices[it.widgetId]!! < it.frames.size } 
                   && System.currentTimeMillis() - startedAt < GIF_MAX_DURATION_MS) {
                
                for (anim in animations) {
                    if (getSession(anim.widgetId).get() != sessionSnapshots[anim.widgetId]) continue
                    val idx = frameIndices[anim.widgetId] ?: continue
                    if (idx >= anim.frames.size) continue

                    val frameV = RemoteViews(context.packageName, R.layout.widget_layout)
                    frameV.setImageViewBitmap(R.id.widget_gif_overlay, anim.frames[idx])
                    mgr.partiallyUpdateAppWidget(anim.widgetId, frameV)
                    
                    frameIndices[anim.widgetId] = idx + 1
                }
                Thread.sleep(sleepMs)
            }

            // Hide overlay and restore static view
            lockedTargets.forEach { t -> 
                if (getSession(t.widgetId).get() == sessionSnapshots[t.widgetId]) {
                    val hideOverlay = RemoteViews(context.packageName, R.layout.widget_layout)
                    hideOverlay.setViewVisibility(R.id.widget_gif_overlay, View.GONE)
                    mgr.partiallyUpdateAppWidget(t.widgetId, hideOverlay)
                    updateWidget(context, mgr, t.widgetId) 
                }
            }

            } finally {
                // Always release animation locks
                lockedTargets.forEach { isAnimating(it.widgetId).set(false) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "GIF anim", e)
        }
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val repo = WidgetRepository(ctx)
        executor.execute { ids.forEach { repo.deleteWidgetSync(it); WidgetPrefs.cleanup(ctx, it) } }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        cancelKeepAlive(context)
        stopMonitorService(context)
    }

    private fun runSlideTransition(
        context: Context,
        mgr: AppWidgetManager,
        widgetId: Int,
        targetSession: Int,
        oldGame: GameEntry,
        newGame: GameEntry,
        isNext: Boolean
    ) {
        try {
            val density = context.resources.displayMetrics.density
            val size = getWidgetRenderSize(mgr, widgetId, density, 800)
            val oldBmp = loadRawCover(context, oldGame)?.let { centerCrop(it, size.widthPx, size.heightPx) }
            val newBmp = loadRawCover(context, newGame)?.let { centerCrop(it, size.widthPx, size.heightPx) }
            if (oldBmp == null || newBmp == null) return

            // 10 frames: 6 for the main slide, 4 for the bounce-back
            val totalFrames = 10
            val cardCornerPx = WidgetPrefs.getCardCornerRadius(context, widgetId) * density
            val combined = Bitmap.createBitmap(size.widthPx, size.heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(combined)

            for (i in 1..totalFrames) {
                if (getSession(widgetId).get() != targetSession) break

                val t = i.toFloat() / totalFrames
                // Spring overshoot interpolation:
                // Goes to ~1.08 (overshoots by 8%) then settles back to 1.0
                // Formula: 1 + (1.0) * e^(-6t) * sin(π * t * 1.5)  — but simplified:
                val progress = if (t <= 0.6f) {
                    // Phase 1: Fast ease-out to overshoot (reach ~1.08 at t=0.6)
                    val p = t / 0.6f
                    val eased = 1f - Math.pow((1f - p).toDouble(), 3.0).toFloat()
                    eased * 1.08f
                } else {
                    // Phase 2: Elastic settle from 1.08 back to 1.0
                    val p = (t - 0.6f) / 0.4f
                    val eased = 1f - Math.pow((1f - p).toDouble(), 2.0).toFloat()
                    1.08f - 0.08f * eased
                }

                val offset = progress * size.widthPx

                combined.eraseColor(Color.TRANSPARENT)
                if (isNext) {
                    canvas.drawBitmap(oldBmp, -offset, 0f, null)
                    canvas.drawBitmap(newBmp, size.widthPx - offset, 0f, null)
                } else {
                    canvas.drawBitmap(oldBmp, offset, 0f, null)
                    canvas.drawBitmap(newBmp, -size.widthPx + offset, 0f, null)
                }

                val processed = BlurUtil.roundBitmap(combined, cardCornerPx)
                val views = RemoteViews(context.packageName, R.layout.widget_layout)
                views.setImageViewBitmap(R.id.widget_game_cover, processed)
                mgr.partiallyUpdateAppWidget(widgetId, views)
                Thread.sleep(16)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Slide transition failed", e)
        }
    }

    private data class GifTarget(val widgetId: Int, val game: GameEntry)
}
