package com.anas_mugally.videodownloader.data

import android.content.Context
import android.net.Uri
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class EngineState(
    val initializing: Boolean = false,
    val ready: Boolean = false,
    val version: String? = null,
    val error: String? = null,
)

class YtDlpRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val initializationMutex = Mutex()
    private val _state = MutableStateFlow(EngineState())
    val state = _state.asStateFlow()

    suspend fun ensureReady() {
        if (_state.value.ready) return
        initializationMutex.withLock {
            if (_state.value.ready) return
            _state.value = EngineState(initializing = true)
            try {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().init(appContext)
                }
                _state.value = EngineState(
                    ready = true,
                    version = YoutubeDL.getInstance().versionName(appContext)
                        ?: YoutubeDL.getInstance().version(appContext),
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _state.value = EngineState(error = error.message ?: "Engine initialization failed")
                throw error
            }
        }
    }

    fun cookiesFile(): File = File(File(appContext.filesDir, "cookies"), "cookies.txt")

    fun hasCookies(): Boolean = cookiesFile().isFile && cookiesFile().length() > 0L

    suspend fun importCookies(uri: Uri) = withContext(Dispatchers.IO) {
        val destination = cookiesFile()
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "cookies.txt.tmp")
        try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: error("Unable to open cookies file")
            input.use { source ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_COOKIE_FILE_SIZE) { "Cookies file is too large" }
                        output.write(buffer, 0, count)
                    }
                    require(total > 0L) { "Cookies file is empty" }
                }
            }
            if (destination.exists() && !destination.delete()) {
                error("Unable to replace cookies file")
            }
            require(temporary.renameTo(destination)) { "Unable to save cookies file" }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    suspend fun clearCookies() = withContext(Dispatchers.IO) {
        cookiesFile().delete()
    }

    private companion object {
        const val MAX_COOKIE_FILE_SIZE = 10L * 1024L * 1024L
    }
}
