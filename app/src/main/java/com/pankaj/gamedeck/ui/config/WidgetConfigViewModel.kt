package com.pankaj.gamedeck.ui.config

import android.app.Application
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import androidx.lifecycle.*
import com.pankaj.gamedeck.data.WidgetRepository
import com.pankaj.gamedeck.data.model.GameEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(val packageName: String, val label: String, val icon: Drawable)

class WidgetConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = WidgetRepository(application)

    private val _selectedGames = MutableLiveData<MutableList<GameEntry>>(mutableListOf())
    val selectedGames: LiveData<MutableList<GameEntry>> = _selectedGames

    private val _installedApps = MutableLiveData<List<AppInfo>>()
    val installedApps: LiveData<List<AppInfo>> = _installedApps

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    fun loadInstalledApps() {
        viewModelScope.launch {
            _isLoading.value = true
            val apps = withContext(Dispatchers.IO) {
                val pm = getApplication<Application>().packageManager
                val mainIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                pm.queryIntentActivities(mainIntent, 0)
                    .filter { it.activityInfo.packageName != getApplication<Application>().packageName }
                    .map { ri -> AppInfo(ri.activityInfo.packageName, ri.loadLabel(pm).toString(), ri.loadIcon(pm)) }
                    .sortedBy { it.label.lowercase() }
                    .distinctBy { it.packageName }
            }
            _installedApps.value = apps
            _isLoading.value = false
        }
    }

    fun loadExistingConfig(widgetId: Int) {
        viewModelScope.launch {
            val existing = withContext(Dispatchers.IO) { repository.getGamesForWidget(widgetId) }
            if (existing.isNotEmpty()) _selectedGames.value = existing.toMutableList()
        }
    }

    fun addGame(appInfo: AppInfo) {
        val current = _selectedGames.value ?: mutableListOf()
        if (current.any { it.packageName == appInfo.packageName }) return
        current.add(GameEntry(widgetId = 0, packageName = appInfo.packageName, appLabel = appInfo.label, position = current.size))
        _selectedGames.value = current
    }

    fun removeGame(position: Int) {
        val current = _selectedGames.value ?: return
        if (position in current.indices) { current.removeAt(position); _selectedGames.value = current }
    }

    fun updateGameImage(position: Int, imagePath: String) {
        val current = _selectedGames.value ?: return
        if (position in current.indices) {
            current[position] = current[position].copy(imagePath = imagePath)
            _selectedGames.value = current
        }
    }

    fun updateGameGif(position: Int, gifPath: String?, gifFrameCount: Int, animationFps: Int = 24) {
        val current = _selectedGames.value ?: return
        if (position in current.indices) {
            current[position] = current[position].copy(
                gifPath = gifPath,
                isGif = gifPath != null,
                gifFrameCount = gifFrameCount,
                animationFps = animationFps.coerceIn(1, 60)
            )
            _selectedGames.value = current
        }
    }

    fun updateCustomPlayText(position: Int, text: String?) {
        val current = _selectedGames.value ?: return
        if (position in current.indices) {
            current[position] = current[position].copy(customPlayText = text)
            _selectedGames.value = current
        }
    }

    fun moveGame(from: Int, to: Int) {
        val current = _selectedGames.value ?: return
        if (from in current.indices && to in current.indices) {
            val item = current.removeAt(from); current.add(to, item); _selectedGames.value = current
        }
    }

    fun saveConfig(widgetId: Int, onComplete: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.saveGamesForWidget(widgetId, _selectedGames.value ?: emptyList()) }
            onComplete()
        }
    }
}
