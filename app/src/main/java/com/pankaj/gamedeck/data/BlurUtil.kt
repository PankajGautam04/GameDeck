package com.pankaj.gamedeck.data

import android.graphics.*

/**
 * Fast stack blur implementation for creating frosted glass widget backgrounds.
 * No RenderScript dependency — pure Kotlin.
 */
object BlurUtil {

    /**
     * Apply stack blur to a bitmap.
     * @param src Source bitmap (will not be modified)
     * @param radius Blur radius 1-25
     * @return New blurred bitmap
     */
    fun blur(src: Bitmap, radius: Int): Bitmap {
        val r = radius.coerceIn(1, 25)
        val w = src.width
        val h = src.height
        val pix = IntArray(w * h)
        src.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1; val hm = h - 1; val wh = w * h
        val div = r + r + 1

        val rArr = IntArray(wh); val gArr = IntArray(wh); val bArr = IntArray(wh)
        var rsum: Int; var gsum: Int; var bsum: Int
        var rinsum: Int; var ginsum: Int; var binsum: Int
        var routsum: Int; var goutsum: Int; var boutsum: Int
        var p: Int; var yp: Int; var yi: Int
        val vmin = IntArray(maxOf(w, h))
        val divsum = (div + 1) shr 1
        val dv = IntArray(256 * divsum * divsum)
        for (i in dv.indices) dv[i] = i / (divsum * divsum)

        val stack = Array(div) { IntArray(3) }
        var sir: IntArray; var rbs: Int
        val r1 = r + 1
        var p1: Int; var p2: Int

        yi = 0
        for (y in 0 until h) {
            rinsum = 0; ginsum = 0; binsum = 0
            routsum = 0; goutsum = 0; boutsum = 0
            rsum = 0; gsum = 0; bsum = 0
            for (i in -r..r) {
                p = pix[yi + minOf(wm, maxOf(i, 0))]
                sir = stack[i + r]
                sir[0] = (p and 0xff0000) shr 16; sir[1] = (p and 0x00ff00) shr 8; sir[2] = p and 0x0000ff
                rbs = r1 - Math.abs(i)
                rsum += sir[0] * rbs; gsum += sir[1] * rbs; bsum += sir[2] * rbs
                if (i > 0) { rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2] }
                else { routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2] }
            }
            var stackpointer = r
            for (x in 0 until w) {
                rArr[yi] = dv.getOrElse(rsum) { rsum / (divsum * divsum) }
                gArr[yi] = dv.getOrElse(gsum) { gsum / (divsum * divsum) }
                bArr[yi] = dv.getOrElse(bsum) { bsum / (divsum * divsum) }
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                val stackstart = stackpointer - r + div
                sir = stack[stackstart % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
                if (y == 0) vmin[x] = minOf(x + r1, wm)
                p = pix[vmin[x] + y * w]
                sir[0] = (p and 0xff0000) shr 16; sir[1] = (p and 0x00ff00) shr 8; sir[2] = p and 0x0000ff
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                yi++
            }
        }

        for (x in 0 until w) {
            rinsum = 0; ginsum = 0; binsum = 0
            routsum = 0; goutsum = 0; boutsum = 0
            rsum = 0; gsum = 0; bsum = 0
            yp = -r * w
            for (i in -r..r) {
                yi = maxOf(0, yp) + x
                sir = stack[i + r]
                sir[0] = rArr[yi]; sir[1] = gArr[yi]; sir[2] = bArr[yi]
                rbs = r1 - Math.abs(i)
                rsum += rArr[yi] * rbs; gsum += gArr[yi] * rbs; bsum += bArr[yi] * rbs
                if (i > 0) { rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2] }
                else { routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2] }
                if (i < hm) yp += w
            }
            yi = x
            var stackpointer = r
            for (y in 0 until h) {
                val ra = dv.getOrElse(rsum) { rsum / (divsum * divsum) }
                val ga = dv.getOrElse(gsum) { gsum / (divsum * divsum) }
                val ba = dv.getOrElse(bsum) { bsum / (divsum * divsum) }
                pix[yi] = (0xff000000.toInt()) or (ra shl 16) or (ga shl 8) or ba
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                val stackstart = stackpointer - r + div
                sir = stack[stackstart % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
                if (x == 0) vmin[y] = minOf(y + r1, hm) * w
                p1 = x + vmin[y]
                sir[0] = rArr[p1]; sir[1] = gArr[p1]; sir[2] = bArr[p1]
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                yi += w
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pix, 0, w, 0, 0, w, h)
        return result
    }

    /** Create a rounded rectangle bitmap from source. */
    fun roundBitmap(src: Bitmap, radiusPx: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawRoundRect(
            RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()),
            radiusPx, radiusPx,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
        )
        return out
    }

    /**
     * Blur + tint + round for widget background from wallpaper.
     * @param tintColor base tint color (RGB, alpha ignored)
     * @param tintAlpha 0-100 tint opacity percentage
     */
    fun createBlurredBackground(
        wallpaper: Bitmap, blurRadius: Int, cornerRadiusPx: Float,
        width: Int, height: Int,
        tintColor: Int = 0x000000, tintAlpha: Int = 30
    ): Bitmap {
        val scale = minOf(width.toFloat() / wallpaper.width, height.toFloat() / wallpaper.height)
        val sw = (wallpaper.width * scale).toInt().coerceAtLeast(1)
        val sh = (wallpaper.height * scale).toInt().coerceAtLeast(1)
        var scaled = Bitmap.createScaledBitmap(wallpaper, sw, sh, true)

        val targetAspect = width.toFloat() / height
        val srcAspect = scaled.width.toFloat() / scaled.height
        scaled = if (srcAspect > targetAspect) {
            val cropW = (scaled.height * targetAspect).toInt()
            Bitmap.createBitmap(scaled, (scaled.width - cropW) / 2, 0, cropW, scaled.height)
        } else {
            val cropH = (scaled.width / targetAspect).toInt()
            Bitmap.createBitmap(scaled, 0, (scaled.height - cropH) / 2, scaled.width, cropH)
        }

        scaled = Bitmap.createScaledBitmap(scaled, width, height, true)

        var blurred = scaled
        repeat(3) { blurred = blur(blurred, blurRadius) }

        // Apply user-chosen tint color + alpha
        val alpha = ((tintAlpha.coerceIn(0, 100) / 100f) * 255).toInt()
        val tint = (alpha shl 24) or (tintColor and 0x00FFFFFF)
        Canvas(blurred).drawColor(tint)

        return roundBitmap(blurred, cornerRadiusPx)
    }
}
