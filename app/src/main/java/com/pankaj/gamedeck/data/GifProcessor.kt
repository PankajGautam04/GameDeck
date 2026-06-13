package com.pankaj.gamedeck.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class GifProcessor(private val context: Context) {

    companion object {
        private const val TAG = "GifProcessor"
        private const val MAX_FRAME_SIZE = 400
        private const val DEFAULT_FPS = 24
        private const val MAX_DURATION_SECONDS = 5
        private const val MAX_FPS = 60
    }

    data class GifResult(val frameKey: String, val frameCount: Int, val fps: Int)

    fun extractFrames(uri: Uri, frameKey: String): GifResult? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val bytes = input.readBytes()
            input.close()

            val dir = File(context.filesDir, "gif_frames/$frameKey")
            dir.mkdirs(); dir.listFiles()?.forEach { it.delete() }

            val gifDrawable = pl.droidsonroids.gif.GifDrawable(bytes)
            val frameCount = gifDrawable.numberOfFrames
            if (frameCount <= 0) return null

            val width = gifDrawable.intrinsicWidth
            val height = gifDrawable.intrinsicHeight
            val scale = if (maxOf(width, height) > MAX_FRAME_SIZE) MAX_FRAME_SIZE.toFloat() / maxOf(width, height) else 1f
            val outW = (width * scale).toInt().coerceAtLeast(1)
            val outH = (height * scale).toInt().coerceAtLeast(1)

            val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)

            val limitCount = minOf(frameCount, MAX_DURATION_SECONDS * 60) // Max 5 seconds at up to 60fps
            for (i in 0 until limitCount) {
                gifDrawable.seekToFrame(i)
                bmp.eraseColor(android.graphics.Color.TRANSPARENT)
                gifDrawable.setBounds(0, 0, outW, outH)
                gifDrawable.draw(canvas)

                FileOutputStream(File(dir, "f_${String.format("%04d", i)}.webp")).use {
                    bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it)
                }
            }

            val avgDelay = if (limitCount > 0) gifDrawable.duration / frameCount else 100
            val targetFps = (1000f / avgDelay.coerceAtLeast(10)).toInt().coerceIn(1, MAX_FPS)

            gifDrawable.recycle()

            Log.d(TAG, "android-gif-drawable extracted $limitCount frames for $frameKey fps=$targetFps")
            GifResult(frameKey, limitCount, targetFps)
        } catch (e: Exception) {
            Log.e(TAG, "GIF extraction failed key=$frameKey", e)
            null
        }
    }

    fun extractVideoFrames(path: String, frameKey: String): GifResult? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(1L) ?: return null
            val fps = detectVideoFps(retriever).coerceIn(1, MAX_FPS)
            val playbackDurationMs = minOf(durationMs, MAX_DURATION_SECONDS * 1000L)
            val frameCount = ((playbackDurationMs * fps + 999L) / 1000L).toInt().coerceAtLeast(1)

            val dir = File(context.filesDir, "gif_frames/$frameKey")
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }

            var sampleIndex = 0
            var savedIndex = 0
            while (sampleIndex < frameCount) {
                val timeUs = ((sampleIndex * 1_000_000L) / fps)
                    .coerceAtMost((playbackDurationMs - 1L).coerceAtLeast(0L) * 1000L)
                val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                if (frame == null) {
                    Log.w(TAG, "Missing video frame key=$frameKey idx=$sampleIndex timeUs=$timeUs")
                    sampleIndex++
                    continue
                }
                val scaled = scaleDown(frame)
                FileOutputStream(File(dir, "f_${String.format("%04d", savedIndex)}.webp")).use {
                    scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it)
                }
                if (scaled !== frame) frame.recycle()
                scaled.recycle()
                sampleIndex++
                savedIndex++
            }

            Log.d(TAG, "Extracted $savedIndex video frames for key=$frameKey fps=$fps durationMs=$playbackDurationMs")
            if (savedIndex > 0) GifResult(frameKey, savedIndex, fps) else null
        } catch (e: Exception) {
            Log.e(TAG, "Video extract failed key=$frameKey path=$path", e)
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /** Load a frame by the stable key + index. */
    fun loadFrame(frameKey: String, index: Int): Bitmap? {
        val f = File(context.filesDir, "gif_frames/$frameKey/f_${String.format("%04d", index)}.webp")
        if (!f.exists()) return null
        return try { android.graphics.BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) { null }
    }

    fun cleanup(frameKey: String) {
        File(context.filesDir, "gif_frames/$frameKey").deleteRecursively()
    }

    private fun detectVideoFps(retriever: MediaMetadataRetriever): Int {
        val captureFps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()
        } else {
            null
        }
        val inferredFps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toFloatOrNull()
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toFloatOrNull()
            if (frameCount != null && durationMs != null && durationMs > 0f) {
                frameCount * 1000f / durationMs
            } else {
                null
            }
        } else {
            null
        }
        return (captureFps ?: inferredFps ?: DEFAULT_FPS.toFloat()).toInt().coerceIn(1, MAX_FPS)
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        val largestSide = maxOf(src.width, src.height)
        if (largestSide <= MAX_FRAME_SIZE) return src
        val scale = MAX_FRAME_SIZE.toFloat() / largestSide
        val outW = (src.width * scale).toInt().coerceAtLeast(1)
        val outH = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, outW, outH, true)
    }
}
