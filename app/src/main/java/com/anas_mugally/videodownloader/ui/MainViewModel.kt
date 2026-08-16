package com.anas_mugally.videodownloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.anas_mugally.videodownloader.data.YtDlpExtractor
import com.anas_mugally.videodownloader.domain.MediaInfo
import com.anas_mugally.videodownloader.download.DownloadWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MainUiState(val url:String="", val loading:Boolean=false, val media:MediaInfo?=null, val error:String?=null)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val extractor = YtDlpExtractor()
    private val _state = MutableStateFlow(MainUiState())
    val state = _state.asStateFlow()
    fun setUrl(value:String) { _state.value = _state.value.copy(url=value, error=null) }
    fun analyze() = viewModelScope.launch {
        _state.value = _state.value.copy(loading=true, error=null, media=null)
        _state.value = try { _state.value.copy(loading=false, media=extractor.extract(_state.value.url.trim())) }
        catch (e:Exception) { _state.value.copy(loading=false, error=e.message ?: "تعذر تحليل الرابط") }
    }
    fun download(formatId:String, audio:Boolean) {
        val media = _state.value.media ?: return
        val request = OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(workDataOf(
            DownloadWorker.KEY_URL to media.sourceUrl, DownloadWorker.KEY_FORMAT to formatId, DownloadWorker.KEY_AUDIO to audio)).build()
        WorkManager.getInstance(getApplication()).enqueueUniqueWork("download-${request.id}", ExistingWorkPolicy.KEEP, request)
    }
}
