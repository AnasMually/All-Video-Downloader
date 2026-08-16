package com.anas_mugally.videodownloader.ui

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anas_mugally.videodownloader.R
import com.anas_mugally.videodownloader.VideoDownloaderApp
import com.anas_mugally.videodownloader.data.YtDlpExtractor
import com.anas_mugally.videodownloader.domain.AppSettings
import com.anas_mugally.videodownloader.domain.AudioFormat
import com.anas_mugally.videodownloader.domain.DownloadKind
import com.anas_mugally.videodownloader.domain.DownloadStatus
import com.anas_mugally.videodownloader.domain.DownloadTask
import com.anas_mugally.videodownloader.domain.FileNameMode
import com.anas_mugally.videodownloader.domain.MediaFormat
import com.anas_mugally.videodownloader.domain.MediaInfo
import com.anas_mugally.videodownloader.domain.ThemeMode
import com.anas_mugally.videodownloader.domain.UrlTools
import com.anas_mugally.videodownloader.download.DownloadController
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val url: String = "",
    val analyzing: Boolean = false,
    val media: MediaInfo? = null,
    val selectedKind: DownloadKind = DownloadKind.VIDEO,
    val selectedVideoFormatId: String? = null,
    val selectedAudioFormatId: String? = null,
    val error: String? = null,
    val cookiesImported: Boolean = false,
)

data class UiEvent(@StringRes val message: Int)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as VideoDownloaderApp
    private val repository = app.repository
    private val runtime = app.ytDlpRuntime
    private val extractor = YtDlpExtractor(runtime)
    private val _state = MutableStateFlow(MainUiState(cookiesImported = runtime.hasCookies()))
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    private var analysisJob: Job? = null

    val state = _state.asStateFlow()
    val events = _events.asSharedFlow()
    val settings = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )
    val tasks = repository.tasks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val engineState = runtime.state

    fun setUrl(value: String) {
        analysisJob?.cancel()
        _state.value = _state.value.copy(
            url = value,
            analyzing = false,
            media = null,
            selectedVideoFormatId = null,
            selectedAudioFormatId = null,
            error = null,
        )
    }

    fun consumeSharedText(value: String) {
        val url = UrlTools.extractHttpUrl(value) ?: return
        setUrl(url)
    }

    fun analyze() {
        analysisJob?.cancel()
        val url = UrlTools.extractHttpUrl(_state.value.url)
        if (url == null) {
            _state.value = _state.value.copy(error = app.getString(R.string.invalid_url))
            return
        }
        analysisJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                url = url,
                analyzing = true,
                media = null,
                error = null,
            )
            try {
                val media = extractor.extract(url)
                val video = media.formats.firstOrNull(MediaFormat::hasVideo)
                val audio = media.formats
                    .filter { it.hasAudio && !it.hasVideo }
                    .maxByOrNull { it.audioBitrateKbps ?: 0 }
                    ?: media.formats.firstOrNull(MediaFormat::hasAudio)
                _state.value = _state.value.copy(
                    analyzing = false,
                    media = media,
                    selectedKind = if (video != null) DownloadKind.VIDEO else DownloadKind.AUDIO,
                    selectedVideoFormatId = video?.formatId,
                    selectedAudioFormatId = audio?.formatId,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _state.value = _state.value.copy(
                    analyzing = false,
                    media = null,
                    error = userFacingError(error),
                )
            }
        }
    }

    fun selectKind(kind: DownloadKind) {
        _state.value = _state.value.copy(selectedKind = kind)
    }

    fun selectFormat(formatId: String) {
        _state.value = when (_state.value.selectedKind) {
            DownloadKind.VIDEO -> _state.value.copy(selectedVideoFormatId = formatId)
            DownloadKind.AUDIO -> _state.value.copy(selectedAudioFormatId = formatId)
        }
    }

    fun enqueueSelectedDownload() {
        val current = _state.value
        val media = current.media ?: return
        val selectedId = when (current.selectedKind) {
            DownloadKind.VIDEO -> current.selectedVideoFormatId
            DownloadKind.AUDIO -> current.selectedAudioFormatId
        } ?: return
        val format = media.formats.firstOrNull { it.formatId == selectedId } ?: return
        viewModelScope.launch {
            val duplicate = tasks.value.any { task ->
                task.isActive &&
                    task.sourceUrl == media.sourceUrl &&
                    task.formatId == selectedId &&
                    task.kind == current.selectedKind
            }
            if (duplicate) {
                _events.emit(UiEvent(R.string.download_already_queued))
                return@launch
            }
            val currentSettings = settings.value
            val task = DownloadTask(
                id = UUID.randomUUID().toString(),
                sourceUrl = media.sourceUrl,
                title = media.title,
                thumbnailUrl = media.thumbnailUrl,
                formatId = format.formatId,
                formatLabel = format.label,
                formatHasAudio = format.hasAudio,
                kind = current.selectedKind,
                requestedAudioFormat = currentSettings.audioFormat,
                fileNameMode = currentSettings.fileNameMode,
            )
            repository.upsertTask(task)
            DownloadController.enqueue(app, task.id)
            _events.emit(UiEvent(R.string.download_added_to_queue))
        }
    }

    fun pause(taskId: String) = DownloadController.pause(app, taskId)

    fun resume(taskId: String) = DownloadController.resume(app, taskId)

    fun retry(taskId: String) = DownloadController.retry(app, taskId)

    fun cancel(taskId: String) = DownloadController.cancel(app, taskId)

    fun delete(taskId: String) {
        viewModelScope.launch {
            val task = repository.task(taskId) ?: return@launch
            if (task.isActive) DownloadController.cancel(app, taskId)
            repository.deleteTask(taskId)
            DownloadController.cleanTaskFiles(app, taskId)
            _events.emit(UiEvent(R.string.history_item_deleted))
        }
    }

    fun clearFinished() {
        viewModelScope.launch {
            tasks.value.filterNot { it.isActive || it.status == DownloadStatus.PAUSED }
                .forEach { DownloadController.cleanTaskFiles(app, it.id) }
            repository.clearFinished()
            _events.emit(UiEvent(R.string.history_cleared))
        }
    }

    fun setWifiOnly(enabled: Boolean) = updateSettings { it.copy(wifiOnly = enabled) }

    fun setDynamicColor(enabled: Boolean) = updateSettings { it.copy(dynamicColor = enabled) }

    fun setThemeMode(mode: ThemeMode) = updateSettings { it.copy(themeMode = mode) }

    fun setOutputFolder(folder: String) = updateSettings { it.copy(outputFolder = folder) }

    fun setFileNameMode(mode: FileNameMode) = updateSettings { it.copy(fileNameMode = mode) }

    fun setAudioFormat(format: AudioFormat) = updateSettings { it.copy(audioFormat = format) }

    fun importCookies(uri: Uri) {
        viewModelScope.launch {
            runCatching { runtime.importCookies(uri) }
                .onSuccess {
                    _state.value = _state.value.copy(cookiesImported = true)
                    _events.emit(UiEvent(R.string.cookies_imported))
                }
                .onFailure { _events.emit(UiEvent(R.string.cookies_import_failed)) }
        }
    }

    fun clearCookies() {
        viewModelScope.launch {
            runtime.clearCookies()
            _state.value = _state.value.copy(cookiesImported = false)
            _events.emit(UiEvent(R.string.cookies_removed))
        }
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repository.updateSettings(transform) }
    }

    private fun userFacingError(error: Throwable): String {
        if (error is IllegalArgumentException) return app.getString(R.string.invalid_url)
        val technical = error.message
            ?.lineSequence()
            ?.lastOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(240)
        return if (technical.isNullOrBlank()) {
            app.getString(R.string.analysis_failed)
        } else {
            app.getString(R.string.analysis_failed_with_reason, technical)
        }
    }
}
