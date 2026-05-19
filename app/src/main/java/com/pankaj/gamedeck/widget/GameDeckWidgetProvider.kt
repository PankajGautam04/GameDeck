package com.pankaj.gamedeck.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
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
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_WIDGET_ID = "extra_widget_id"
        
        private val executor = Executors.newSingleThreadExecutor()
        private val animating = AtomicBoolean(false)

        fun notifyWidgetsChanged(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, GameDeckWidgetProvider::class.java))
                for (id in ids) updateWidget(context, mgr, id)
            } catch (e: Exception) { Log.e(TAG, "notify", e) }
        }

        fun updateWidget(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            executor.execute {
                try {
                    val views = RemoteViews(context.packageName, R.layout.widget_layout)
                    val density = context.resources.displayMetrics.density
                    
                    val repo = WidgetRepository(context)
                    val games = repo.getGamesForWidgetSync(widgetId)

                    if (games.isEmpty()) {
                        views.setViewVisibility(R.id.widget_empty_view, View.VISIBLE)
                        views.setViewVisibility(R.id.widget_game_cover, View.GONE)
                        views.setViewVisibility(R.id.widget_btn_play, View.GONE)
                        views.setViewVisibility(R.id.widget_dots_container, View.GONE)
                        views.setViewVisibility(R.id.widget_blur_bg, View.GONE)
                        mgr.updateAppWidget(widgetId, views)
                        return@execute
                    }

                    views.setViewVisibility(R.id.widget_empty_view, View.GONE)
                    views.setViewVisibility(R.id.widget_game_cover, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_btn_play, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_dots_container, View.VISIBLE)

                    var currentIndex = WidgetPrefs.getCurrentIndex(context, widgetId)
                    if (currentIndex >= games.size) {
                        currentIndex = 0
                        WidgetPrefs.setCurrentIndex(context, widgetId, currentIndex)
                    }
                    val game = games[currentIndex]

                    // Get actual widget dimensions
                    val opts = mgr.getAppWidgetOptions(widgetId)
                    var wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                    var hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
                    if (wDp < 100 || hDp < 100) {
                        wDp = 250; hDp = 200 // Fallback
                    }
                    val widgetWPx = (wDp * density).toInt().coerceIn(200, 1200)
                    val widgetHPx = (hDp * density).toInt().coerceIn(200, 1200)

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
                    val rawCover = loadRawCover(context, game)
                    if (rawCover != null) {
                        val cardCornerPx = WidgetPrefs.getCardCornerRadius(context, widgetId) * density
                        val renderW = widgetWPx.coerceIn(200, 800)
                        val renderH = widgetHPx.coerceIn(200, 800)
                        val cropped = centerCrop(rawCover, renderW, renderH)
                        
                        val mutableBitmap = cropped.copy(Bitmap.Config.ARGB_8888, true)
                        val showTitle = WidgetPrefs.getShowTitle(context, widgetId)
                        val playPadX = WidgetPrefs.getPlayPadX(context, widgetId)
                        val playPadY = WidgetPrefs.getPlayPadY(context, widgetId)
                        val playWeight = WidgetPrefs.getPlayWeight(context, widgetId)
                        drawUIOnBitmap(mutableBitmap, game.appLabel, showTitle, game.customPlayText, playPadX, playPadY, playWeight, density)
                        
                        val processed = BlurUtil.roundBitmap(mutableBitmap, cardCornerPx)
                        views.setImageViewBitmap(R.id.widget_game_cover, processed)
                    } else {
                        views.setImageViewResource(R.id.widget_game_cover, R.drawable.ic_placeholder)
                    }
                    views.setViewVisibility(R.id.widget_gif_overlay, View.GONE)

                // ── Pagination Dots ──
                if (games.size > 1) {
                    views.removeAllViews(R.id.widget_dots_container)
                    for (i in games.indices) {
                        val dotView = RemoteViews(context.packageName, R.layout.widget_dot)
                        val dotRes = if (i == currentIndex) R.drawable.ic_dot_active else R.drawable.ic_dot_inactive
                        dotView.setImageViewResource(R.id.widget_dot_img, dotRes)
                        views.addView(R.id.widget_dots_container, dotView)
                    }
                    views.setViewVisibility(R.id.widget_dots_container, View.VISIBLE)
                    } else {
                        views.setViewVisibility(R.id.widget_dots_container, View.GONE)
                    }

                    // ── Intents ──
                    // Tap cover to cycle to next game
                    val nextIntent = Intent(context, GameDeckWidgetProvider::class.java).apply { 
                        action = ACTION_NEXT_GAME
                        putExtra(EXTRA_WIDGET_ID, widgetId)
                    }
                    val nextPi = PendingIntent.getBroadcast(context, widgetId, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    views.setOnClickPendingIntent(R.id.widget_click_target, nextPi)

                    // Tap Play button to launch game
                    val playIntent = Intent(context, GameDeckWidgetProvider::class.java).apply {
                        action = ACTION_LAUNCH
                        putExtra(EXTRA_PACKAGE_NAME, game.packageName)
                    }
                    val playPi = PendingIntent.getBroadcast(context, widgetId, playIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    views.setOnClickPendingIntent(R.id.widget_btn_play, playPi)

                    mgr.updateAppWidget(widgetId, views)
                } catch (e: Exception) { Log.e(TAG, "updateWidget $widgetId", e) }
            }
        }

        private fun drawUIOnBitmap(bitmap: Bitmap, title: String, showTitle: Boolean, customPlayText: String?, prefPlayPadX: Int, prefPlayPadY: Int, playWeight: Int, density: Float) {
            val canvas = Canvas(bitmap)
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()

            if (showTitle) {
                val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 16f * density
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setShadowLayer(4f, 0f, 2f, 0x80000000.toInt())
                }
                val titlePadX = 12f * density
                val titlePadY = 6f * density
                val titleMarginX = 16f * density
                val titleMarginY = 16f * density
                val maxTitleWidth = w - (titleMarginX * 2) - (titlePadX * 2)
                var textToDraw = title
                if (titlePaint.measureText(textToDraw) > maxTitleWidth) {
                    textToDraw = android.text.TextUtils.ellipsize(title, android.text.TextPaint(titlePaint), maxTitleWidth, android.text.TextUtils.TruncateAt.END).toString()
                }
                val textBounds = Rect()
                titlePaint.getTextBounds(textToDraw, 0, textToDraw.length, textBounds)
                val titleRect = RectF(
                    titleMarginX, titleMarginY,
                    titleMarginX + textBounds.width() + titlePadX * 2,
                    titleMarginY + textBounds.height() + titlePadY * 2
                )
                drawBlurredPill(bitmap, canvas, titleRect, 20f * density, density)
                val textCenterOffset = (titlePaint.descent() + titlePaint.ascent()) / 2
                val baselineY = titleRect.centerY() - textCenterOffset
                canvas.drawText(textToDraw, titleRect.left + titlePadX, baselineY, titlePaint)
            }

            val playText = customPlayText ?: "PLAY"
            val playPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 16f * density
                typeface = Typeface.create(Typeface.DEFAULT, playWeight, false)
                letterSpacing = 0.1f
                setShadowLayer(4f, 0f, 2f, 0x80000000.toInt())
            }
            val playBounds = Rect()
            playPaint.getTextBounds(playText, 0, playText.length, playBounds)
            val playPadX = prefPlayPadX * density
            val playPadY = prefPlayPadY * density
            val playMarginBottom = 16f * density
            val playW = playBounds.width() + playPadX * 2
            val playH = playBounds.height() + playPadY * 2
            val playRect = RectF(
                (w - playW) / 2f, h - playMarginBottom - playH,
                (w + playW) / 2f, h - playMarginBottom
            )
            drawBlurredPill(bitmap, canvas, playRect, 100f * density, density)
            val playCenterOffset = (playPaint.descent() + playPaint.ascent()) / 2
            val playBaselineY = playRect.centerY() - playCenterOffset
            canvas.drawText(playText, playRect.left + playPadX, playBaselineY, playPaint)
        }

        private fun drawBlurredPill(source: Bitmap, canvas: Canvas, rect: RectF, radius: Float, density: Float) {
            val srcRect = Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
            if (srcRect.left < 0) srcRect.left = 0
            if (srcRect.top < 0) srcRect.top = 0
            if (srcRect.right > source.width) srcRect.right = source.width
            if (srcRect.bottom > source.height) srcRect.bottom = source.height
            if (srcRect.width() <= 0 || srcRect.height() <= 0) return

            val region = Bitmap.createBitmap(source, srcRect.left, srcRect.top, srcRect.width(), srcRect.height())
            val blurredRegion = BlurUtil.blur(region, 20)

            val shader = BitmapShader(blurredRegion, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val matrix = Matrix()
            matrix.setTranslate(srcRect.left.toFloat(), srcRect.top.toFloat())
            shader.setLocalMatrix(matrix)

            canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
            canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF })
            canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x66FFFFFF
                style = Paint.Style.STROKE
                strokeWidth = 1f * density
            })
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
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateWidget(ctx, mgr, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_NEXT_GAME -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    executor.execute {
                        val repo = WidgetRepository(context)
                        val games = repo.getGamesForWidgetSync(widgetId)
                        if (games.isNotEmpty()) {
                            var currentIdx = WidgetPrefs.getCurrentIndex(context, widgetId)
                            currentIdx = (currentIdx + 1) % games.size
                            WidgetPrefs.setCurrentIndex(context, widgetId, currentIdx)
                            updateWidget(context, AppWidgetManager.getInstance(context), widgetId)
                            
                            // Trigger haptics
                            try {
                                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                                if (vibrator.hasVibrator()) {
                                    vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK))
                                }
                            } catch (e: Exception) {}

                            // Trigger GIF animation for this specific widget if the new game is a GIF
                            val activeGame = games[currentIdx]
                            if (activeGame.isGif && activeGame.gifFrameCount > 0) {
                                Thread { animateGifs(context, listOf(widgetId)) }.start()
                            }
                        }
                    }
                }
            }
            ACTION_LAUNCH -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
                // Trigger haptics
                try {
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                    if (vibrator.hasVibrator()) {
                        vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_HEAVY_CLICK))
                    }
                } catch (e: Exception) {}

                try {
                    context.packageManager.getLaunchIntentForPackage(pkg)?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(it)
                    }
                } catch (e: Exception) { Log.e(TAG, "Launch $pkg", e) }
            }
            Intent.ACTION_USER_PRESENT -> {
                val r = goAsync()
                Thread {
                    try { animateGifs(context, null) } finally { r.finish() }
                }.start()
            }
        }
    }

    private fun animateGifs(context: Context, targetWidgetIds: List<Int>?) {
        if (!animating.compareAndSet(false, true)) return
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
                if (activeGame.isGif && activeGame.gifFrameCount > 0 && activeGame.gifPath != null) {
                    targets.add(GifTarget(id, activeGame))
                }
            }
            if (targets.isEmpty()) return

            targets.forEach { t ->
                val v = RemoteViews(context.packageName, R.layout.widget_layout)
                v.setViewVisibility(R.id.widget_gif_overlay, View.VISIBLE)
                v.setViewVisibility(R.id.widget_game_cover, View.INVISIBLE)
                mgr.updateAppWidget(t.widgetId, v)
                // Reset frame to 0 for a fresh 1-shot playback
                WidgetPrefs.setCurrentFrame(context, t.widgetId, 0)
            }

            val density = context.resources.displayMetrics.density
            var activeTargets = targets.toList()

            while (activeTargets.isNotEmpty()) {
                val nextActive = mutableListOf<GifTarget>()
                for (t in activeTargets) {
                    val frameKey = File(t.game.gifPath!!).nameWithoutExtension
                    val f = WidgetPrefs.getCurrentFrame(context, t.widgetId)

                    // Draw frame with Canvas UI overlay
                    gifProc.loadFrame(frameKey, f)?.let { bmp ->
                        val opts = mgr.getAppWidgetOptions(t.widgetId)
                        var wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                        var hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
                        if (wDp < 100 || hDp < 100) { wDp = 250; hDp = 200 }
                        val widgetWPx = (wDp * density).toInt().coerceIn(100, 400) // Aggressive downscale for IPC limit
                        val widgetHPx = (hDp * density).toInt().coerceIn(100, 400)

                        val renderW = widgetWPx
                        val renderH = widgetHPx
                        val cropped = centerCrop(bmp, renderW, renderH)
                        val mutableBitmap = cropped.copy(Bitmap.Config.ARGB_8888, true)

                        val showTitle = WidgetPrefs.getShowTitle(context, t.widgetId)
                        val playPadX = WidgetPrefs.getPlayPadX(context, t.widgetId)
                        val playPadY = WidgetPrefs.getPlayPadY(context, t.widgetId)
                        val playWeight = WidgetPrefs.getPlayWeight(context, t.widgetId)
                        drawUIOnBitmap(mutableBitmap, t.game.appLabel, showTitle, t.game.customPlayText, playPadX, playPadY, playWeight, density)

                        val cardCornerPx = WidgetPrefs.getCardCornerRadius(context, t.widgetId) * density
                        val processed = BlurUtil.roundBitmap(mutableBitmap, cardCornerPx)

                        val v = RemoteViews(context.packageName, R.layout.widget_layout)
                        // Fully populate the RemoteViews to ensure the launcher doesn't ignore or break it
                        v.setImageViewBitmap(R.id.widget_gif_overlay, processed)
                        v.setViewVisibility(R.id.widget_gif_overlay, View.VISIBLE)
                        v.setViewVisibility(R.id.widget_game_cover, View.INVISIBLE)
                        
                        // We must re-bind the click intents so the widget remains interactive during animation!
                        val nextIntent = Intent(context, GameDeckWidgetProvider::class.java).apply {
                            action = ACTION_NEXT_GAME
                            putExtra(EXTRA_WIDGET_ID, t.widgetId)
                        }
                        v.setOnClickPendingIntent(
                            R.id.widget_click_target,
                            PendingIntent.getBroadcast(context, t.widgetId, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        )
                        
                        mgr.updateAppWidget(t.widgetId, v)
                    }

                    val nextFrame = f + 1
                    if (nextFrame < t.game.gifFrameCount) {
                        WidgetPrefs.setCurrentFrame(context, t.widgetId, nextFrame)
                        nextActive.add(t) // Keep looping
                    } else {
                        // Animation finished, reset frame to 0 for static state
                        WidgetPrefs.setCurrentFrame(context, t.widgetId, 0)
                    }
                }
                activeTargets = nextActive
                Thread.sleep(66) // ~15 fps to prevent IPC frame drops
            }

            // Cleanup: Restore static cover view and hide overlay
            targets.forEach { t ->
                val v = RemoteViews(context.packageName, R.layout.widget_layout)
                v.setViewVisibility(R.id.widget_gif_overlay, View.GONE)
                v.setViewVisibility(R.id.widget_game_cover, View.VISIBLE)
                mgr.updateAppWidget(t.widgetId, v)
                // Force a full update to redraw the static frame 0
                updateWidget(context, mgr, t.widgetId)
            }
        } catch (e: Exception) { Log.e(TAG, "GIF anim", e) }
        finally { animating.set(false) }
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val repo = WidgetRepository(ctx)
        executor.execute { ids.forEach { repo.deleteWidgetSync(it); WidgetPrefs.cleanup(ctx, it) } }
    }

    private data class GifTarget(val widgetId: Int, val game: GameEntry)
}
