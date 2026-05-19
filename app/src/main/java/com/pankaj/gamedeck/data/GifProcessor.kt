package com.pankaj.gamedeck.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

@Suppress("DEPRECATION")
class GifProcessor(private val context: Context) {

    companion object {
        private const val TAG = "GifProcessor"
        private const val MAX_FRAME_SIZE = 400
        private const val MAX_FRAMES = 150
    }

    data class GifResult(val frameKey: String, val frameCount: Int)

    /**
     * Extract GIF frames using a stable key derived from the image path.
     * This key is stored alongside the image path, so both config and widget
     * can locate frames reliably (no dependency on Room auto-generated IDs).
     */
    fun extractFrames(uri: Uri, frameKey: String): GifResult? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val bytes = input.readBytes(); input.close()

            val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: return null
            if (movie.duration() <= 0) return null

            val dir = File(context.filesDir, "gif_frames/$frameKey")
            dir.mkdirs(); dir.listFiles()?.forEach { it.delete() }

            val scale = minOf(MAX_FRAME_SIZE.toFloat() / movie.width(), MAX_FRAME_SIZE.toFloat() / movie.height()).coerceAtMost(1f)
            val w = (movie.width() * scale).toInt().coerceAtLeast(1)
            val h = (movie.height() * scale).toInt().coerceAtLeast(1)

            val interval = 42 // ~24fps
            var time = 0; var idx = 0

            while (time < movie.duration() && idx < MAX_FRAMES) {
                movie.setTime(time)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Canvas(bmp).apply { scale(scale, scale); movie.draw(this, 0f, 0f) }
                FileOutputStream(File(dir, "f_${String.format("%04d", idx)}.webp")).use {
                    bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it)
                }
                bmp.recycle(); time += interval; idx++
            }

            Log.d(TAG, "Extracted $idx frames for key=$frameKey (${w}x${h})")
            GifResult(frameKey, idx)
        } catch (e: Exception) {
            Log.e(TAG, "Extract failed key=$frameKey", e); null
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
}
