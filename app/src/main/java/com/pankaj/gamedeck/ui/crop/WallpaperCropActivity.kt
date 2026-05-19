package com.pankaj.gamedeck.ui.crop

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.pankaj.gamedeck.R
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Built-in wallpaper crop activity with pinch-to-zoom and pan.
 * The visible portion of the image = the crop area.
 * User positions their wallpaper so the widget area is visible, then taps Done.
 */
class WallpaperCropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
        const val EXTRA_OUTPUT_PATH = "output_path"
        const val RESULT_CROP_PATH = "crop_path"
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }

    private lateinit var imageView: ImageView
    private val matrix = Matrix()
    private val savedMatrix = Matrix()

    private var touchMode = NONE
    private val start = PointF()
    private val mid = PointF()
    private var oldDist = 1f

    private var imagePath: String? = null
    private var outputPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallpaper_crop)

        imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        outputPath = intent.getStringExtra(EXTRA_OUTPUT_PATH)
        if (imagePath == null || outputPath == null) { finish(); return }

        imageView = findViewById(R.id.crop_image)
        val bitmap = BitmapFactory.decodeFile(imagePath)
        if (bitmap == null) { finish(); return }

        imageView.setImageBitmap(bitmap)
        imageView.scaleType = ImageView.ScaleType.MATRIX

        // Scale image to fill the view initially
        imageView.post {
            val vw = imageView.width.toFloat()
            val vh = imageView.height.toFloat()
            val scale = max(vw / bitmap.width, vh / bitmap.height)
            matrix.setScale(scale, scale)
            val sw = bitmap.width * scale; val sh = bitmap.height * scale
            matrix.postTranslate((vw - sw) / 2f, (vh - sh) / 2f)
            imageView.imageMatrix = matrix
        }

        imageView.setOnTouchListener { _, event -> handleTouch(event) }
        findViewById<View>(R.id.btn_crop_done).setOnClickListener { cropAndFinish() }
        findViewById<View>(R.id.btn_crop_cancel).setOnClickListener {
            setResult(Activity.RESULT_CANCELED); finish()
        }
    }

    @Suppress("ClickableViewAccessibility")
    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(matrix); start.set(event.x, event.y); touchMode = DRAG
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                oldDist = spacing(event)
                if (oldDist > 10f) { savedMatrix.set(matrix); midPoint(mid, event); touchMode = ZOOM }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> { touchMode = NONE }
            MotionEvent.ACTION_MOVE -> {
                if (touchMode == DRAG) {
                    matrix.set(savedMatrix)
                    matrix.postTranslate(event.x - start.x, event.y - start.y)
                } else if (touchMode == ZOOM && event.pointerCount >= 2) {
                    val nd = spacing(event)
                    if (nd > 10f) { matrix.set(savedMatrix); matrix.postScale(nd / oldDist, nd / oldDist, mid.x, mid.y) }
                }
                imageView.imageMatrix = matrix
            }
        }
        return true
    }

    private fun spacing(ev: MotionEvent): Float {
        if (ev.pointerCount < 2) return 0f
        val dx = ev.getX(0) - ev.getX(1); val dy = ev.getY(0) - ev.getY(1)
        return sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun midPoint(pt: PointF, ev: MotionEvent) {
        if (ev.pointerCount < 2) return
        pt.set((ev.getX(0) + ev.getX(1)) / 2f, (ev.getY(0) + ev.getY(1)) / 2f)
    }

    private fun cropAndFinish() {
        try {
            val w = imageView.width; val h = imageView.height
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            imageView.draw(Canvas(bmp))

            val out = File(outputPath!!); out.parentFile?.mkdirs()
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()

            setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_CROP_PATH, out.absolutePath))
        } catch (e: Exception) {
            setResult(Activity.RESULT_CANCELED)
        }
        finish()
    }
}
