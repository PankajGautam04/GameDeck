package com.pankaj.gamedeck.ui.config

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.pankaj.gamedeck.R
import com.pankaj.gamedeck.data.GifProcessor
import com.pankaj.gamedeck.data.WidgetPrefs
import com.pankaj.gamedeck.ui.crop.WallpaperCropActivity
import com.pankaj.gamedeck.widget.GameDeckWidgetProvider
import java.io.File
import java.io.FileOutputStream

class WidgetConfigActivity : AppCompatActivity() {

    private lateinit var viewModel: WidgetConfigViewModel
    private lateinit var selectedAdapter: SelectedGameAdapter
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var pendingImagePosition = -1
    private var isPickingWallpaper = false
    private var isPickingGifSlot = false

    // Tint presets: chipId → RGB color (-1 means 'None' / transparent)
    private val tintPresets = mapOf(
        R.id.chip_tint_none to -1,
        R.id.chip_tint_dark to 0x000000,
        R.id.chip_tint_blue to 0x1A237E,
        R.id.chip_tint_purple to 0x4A148C,
        R.id.chip_tint_teal to 0x004D40,
        R.id.chip_tint_rose to 0x880E4F,
        R.id.chip_tint_warm to 0x3E2723
    )

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                if (isPickingWallpaper) {
                    launchBuiltInCrop(uri)
                } else if (pendingImagePosition >= 0) {
                    handleImagePicked(uri)
                }
            }
        }
        if (!isPickingWallpaper) pendingImagePosition = -1
        isPickingWallpaper = false
    }

    // Built-in crop result
    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val path = result.data?.getStringExtra(WallpaperCropActivity.RESULT_CROP_PATH)
            if (path != null) {
                WidgetPrefs.setWallpaperPath(this, appWidgetId, path)
                updateWallpaperStatus()
                Toast.makeText(this, R.string.wallpaper_saved, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchImagePicker()
        else Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        setContentView(R.layout.activity_widget_config)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        viewModel = ViewModelProvider(this)[WidgetConfigViewModel::class.java]
        viewModel.loadExistingConfig(appWidgetId)
        viewModel.loadInstalledApps()

        setupToolbar()
        setupAppearanceControls()
        setupTintControls()
        setupRecyclerView()
        setupFabs()
        observeViewModel()
    }

    private fun setupToolbar() {
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { setResult(RESULT_CANCELED); finish() }
    }

    private fun setupAppearanceControls() {
        // Switches
        val bgSwitch = findViewById<MaterialSwitch>(R.id.switch_show_bg)
        bgSwitch.isChecked = WidgetPrefs.getShowBackground(this, appWidgetId)

        val titleSwitch = findViewById<MaterialSwitch>(R.id.switch_show_title)
        titleSwitch.isChecked = WidgetPrefs.getShowTitle(this, appWidgetId)

        // Separate sliders
        findViewById<Slider>(R.id.slider_card_corner).value = WidgetPrefs.getCardCornerRadius(this, appWidgetId).toFloat()
        findViewById<Slider>(R.id.slider_bg_corner).value = WidgetPrefs.getBgCornerRadius(this, appWidgetId).toFloat()
        findViewById<Slider>(R.id.slider_blur_radius).value = WidgetPrefs.getBlurRadius(this, appWidgetId).toFloat()
        findViewById<Slider>(R.id.slider_tint_alpha).value = WidgetPrefs.getTintAlpha(this, appWidgetId).toFloat()
        findViewById<Slider>(R.id.slider_play_width).value = WidgetPrefs.getPlayPadX(this, appWidgetId).toFloat()
        findViewById<Slider>(R.id.slider_play_height).value = WidgetPrefs.getPlayPadY(this, appWidgetId).toFloat()
        findViewById<Slider>(R.id.slider_play_weight).value = WidgetPrefs.getPlayWeight(this, appWidgetId).toFloat()

        // Wallpaper buttons
        findViewById<View>(R.id.btn_pick_wallpaper).setOnClickListener {
            isPickingWallpaper = true; requestImagePermission()
        }
        findViewById<View>(R.id.btn_remove_wallpaper).setOnClickListener {
            WidgetPrefs.setWallpaperPath(this, appWidgetId, null)
            updateWallpaperStatus()
            Toast.makeText(this, R.string.wallpaper_removed, Toast.LENGTH_SHORT).show()
        }

        updateWallpaperStatus()
    }

    private fun setupTintControls() {
        val chipGroup = findViewById<ChipGroup>(R.id.chip_group_tint)
        // Determine which chip to select based on saved tint
        val currentTint = WidgetPrefs.getTintColor(this, appWidgetId)
        var matched = false
        for ((chipId, color) in tintPresets) {
            if (color == currentTint) { chipGroup.check(chipId); matched = true; break }
        }
        if (!matched) chipGroup.check(R.id.chip_tint_none)

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val color = tintPresets[checkedIds[0]]
                if (color != null) {
                    if (color == -1) {
                        // 'None' selected → set alpha to 0
                        WidgetPrefs.setTintColor(this, appWidgetId, 0x000000)
                        WidgetPrefs.setTintAlpha(this, appWidgetId, 0)
                        findViewById<Slider>(R.id.slider_tint_alpha).value = 0f
                    } else {
                        WidgetPrefs.setTintColor(this, appWidgetId, color)
                    }
                }
            }
        }
    }

    private fun updateWallpaperStatus() {
        val tv = findViewById<TextView>(R.id.tv_wallpaper_status)
        val path = WidgetPrefs.getWallpaperPath(this, appWidgetId)
        val hasWp = path != null && File(path).exists()
        tv.text = if (hasWp) getString(R.string.wallpaper_set) else getString(R.string.wallpaper_not_set)
        findViewById<View>(R.id.btn_remove_wallpaper).visibility = if (hasWp) View.VISIBLE else View.GONE
    }

    private fun setupRecyclerView() {
        val rv = findViewById<RecyclerView>(R.id.rv_selected_games)
        selectedAdapter = SelectedGameAdapter(
            onPickCover = { pos -> pendingImagePosition = pos; isPickingWallpaper = false; isPickingGifSlot = false; requestImagePermission() },
            onPickGif = { pos -> pendingImagePosition = pos; isPickingWallpaper = false; isPickingGifSlot = true; requestImagePermission() },
            onRemove = { pos -> viewModel.removeGame(pos) },
            onEditPlayText = { pos -> showEditPlayTextDialog(pos) }
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = selectedAdapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean {
                viewModel.moveGame(vh.bindingAdapterPosition, t.bindingAdapterPosition); return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, d: Int) {}
        }).attachToRecyclerView(rv)
    }

    private fun setupFabs() {
        findViewById<View>(R.id.fab_add_game).setOnClickListener {
            InstalledAppPickerDialog().show(supportFragmentManager, "app_picker")
        }
        findViewById<View>(R.id.fab_save).setOnClickListener { saveAndFinish() }
    }

    private fun showEditPlayTextDialog(position: Int) {
        val games = viewModel.selectedGames.value ?: return
        if (position !in games.indices) return
        val game = games[position]
        val currentText = game.customPlayText ?: "PLAY"

        val input = android.widget.EditText(this).apply {
            setText(currentText)
            setSelection(currentText.length)
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, 0, padding, 0)
            addView(input)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.label_play_width).replace("Width", "Text")) // Quick hack for title
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newText = input.text.toString().trim()
                viewModel.updateCustomPlayText(position, if (newText.isEmpty()) null else newText)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun observeViewModel() {
        val rv = findViewById<RecyclerView>(R.id.rv_selected_games)
        val empty = findViewById<View>(R.id.tv_empty_state)
        val gifInfo = findViewById<View>(R.id.gif_info_container)

        viewModel.selectedGames.observe(this) { games ->
            selectedAdapter.submitList(games.toList())
            rv.visibility = if (games.isEmpty()) View.GONE else View.VISIBLE
            empty.visibility = if (games.isEmpty()) View.VISIBLE else View.GONE
            gifInfo.visibility = if (games.any { it.isGif }) View.VISIBLE else View.GONE
        }
    }

    private fun requestImagePermission() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) launchImagePicker()
        else permissionLauncher.launch(perm)
    }

    private fun launchImagePicker() {
        imagePickerLauncher.launch(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply { type = "image/*" })
    }

    /** After picking wallpaper, launch built-in crop activity (not system crop intent). */
    private fun launchBuiltInCrop(uri: Uri) {
        // First copy the picked image to internal storage
        val tempPath = copyToInternal(uri, "wp_temp_${appWidgetId}.jpg") ?: return
        val outputPath = File(filesDir, "covers/wp_crop_${appWidgetId}.jpg").absolutePath

        val cropIntent = Intent(this, WallpaperCropActivity::class.java).apply {
            putExtra(WallpaperCropActivity.EXTRA_IMAGE_PATH, tempPath)
            putExtra(WallpaperCropActivity.EXTRA_OUTPUT_PATH, outputPath)
        }
        cropLauncher.launch(cropIntent)
    }

    private fun handleImagePicked(uri: Uri) {
        val mime = contentResolver.getType(uri)
        val isGif = mime == "image/gif"
        val ext = if (isGif) "gif" else "jpg"
        val path = copyToInternal(uri, "cover_${appWidgetId}_${System.currentTimeMillis()}.$ext") ?: return

        if (isPickingGifSlot) {
            if (isGif) {
                val frameKey = File(path).nameWithoutExtension
                Thread {
                    val result = GifProcessor(this).extractFrames(uri, frameKey)
                    runOnUiThread {
                        if (result != null && result.frameCount > 0) {
                            viewModel.updateGameGif(pendingImagePosition, path, result.frameCount)
                            Toast.makeText(this, getString(R.string.gif_extracted, result.frameCount), Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.updateGameGif(pendingImagePosition, null, 0)
                        }
                    }
                }.start()
            } else {
                Toast.makeText(this, "Please select a valid GIF file.", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Picking cover slot
            viewModel.updateGameImage(pendingImagePosition, path)
        }
    }

    private fun copyToInternal(uri: Uri, filename: String): String? {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            val dir = File(filesDir, "covers"); dir.mkdirs()
            val file = File(dir, filename)
            FileOutputStream(file).use { out -> input.copyTo(out) }; input.close()
            file.absolutePath
        } catch (e: Exception) { e.printStackTrace(); null }
    }

    private fun saveAndFinish() {
        val games = viewModel.selectedGames.value
        if (games.isNullOrEmpty()) {
            Toast.makeText(this, R.string.add_at_least_one_game, Toast.LENGTH_SHORT).show(); return
        }

        // Save all prefs
        // Save all prefs
        WidgetPrefs.setShowBackground(this, appWidgetId, findViewById<MaterialSwitch>(R.id.switch_show_bg).isChecked)
        WidgetPrefs.setShowTitle(this, appWidgetId, findViewById<MaterialSwitch>(R.id.switch_show_title).isChecked)
        WidgetPrefs.setCardCornerRadius(this, appWidgetId, findViewById<Slider>(R.id.slider_card_corner).value.toInt())
        WidgetPrefs.setBgCornerRadius(this, appWidgetId, findViewById<Slider>(R.id.slider_bg_corner).value.toInt())
        WidgetPrefs.setBlurRadius(this, appWidgetId, findViewById<Slider>(R.id.slider_blur_radius).value.toInt())
        WidgetPrefs.setTintAlpha(this, appWidgetId, findViewById<Slider>(R.id.slider_tint_alpha).value.toInt())
        WidgetPrefs.setPlayPadX(this, appWidgetId, findViewById<Slider>(R.id.slider_play_width).value.toInt())
        WidgetPrefs.setPlayPadY(this, appWidgetId, findViewById<Slider>(R.id.slider_play_height).value.toInt())
        WidgetPrefs.setPlayWeight(this, appWidgetId, findViewById<Slider>(R.id.slider_play_weight).value.toInt())

        viewModel.saveConfig(appWidgetId) {
            val mgr = AppWidgetManager.getInstance(this)
            GameDeckWidgetProvider.updateWidget(this, mgr, appWidgetId)
            
            // Delayed update: When the widget is first placed, its actual layout dimensions aren't available immediately.
            // A delayed update ensures the factory gets the real dimensions (not fallbacks) and crops perfectly.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { GameDeckWidgetProvider.updateWidget(this, mgr, appWidgetId) } catch (e: Exception) {}
            }, 800)

            setResult(RESULT_OK, Intent().apply { putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId) })
            finish()
        }
    }
}
