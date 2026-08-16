package com.anas_mugally.videodownloader.data

import android.content.Context
import android.net.Uri
import android.os.Process
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.io.RandomAccessFile
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
    private val updateMutex = Mutex()
    private val _state = MutableStateFlow(EngineState())
    val state = _state.asStateFlow()

    suspend fun ensureReady() {
        if (!_state.value.ready) {
            initializationMutex.withLock {
                if (_state.value.ready) return@withLock
                _state.value = EngineState(initializing = true)
                try {
                    withContext(Dispatchers.IO) {
                        repairRuntimePackageIfNeeded()
                        YoutubeDL.getInstance().init(appContext)
                        writeRuntimeMarker()
                    }
                    _state.value = EngineState(
                        ready = true,
                        version = currentVersion(),
                    )
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    _state.value = EngineState(error = error.message ?: "Engine initialization failed")
                    throw error
                }
            }
        }
        updateExtractorIfDue()
    }

    suspend fun recoverFromDownloadError(error: Throwable): Boolean {
        if (!isRecoverableExtractorError(error)) return false
        ensureInitializedWithoutUpdate()
        return updateMutex.withLock {
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().updateYoutubeDL(
                    appContext,
                    YoutubeDL.UpdateChannel.NIGHTLY,
                )
                recordUpdateAttempt(successful = true)
                _state.value = _state.value.copy(version = currentVersion(), error = null)
                true
            }
        }
    }

    private suspend fun ensureInitializedWithoutUpdate() {
        if (_state.value.ready) return
        initializationMutex.withLock {
            if (_state.value.ready) return
            _state.value = EngineState(initializing = true)
            try {
                withContext(Dispatchers.IO) {
                    repairRuntimePackageIfNeeded()
                    YoutubeDL.getInstance().init(appContext)
                    writeRuntimeMarker()
                }
                _state.value = EngineState(
                    ready = true,
                    version = currentVersion(),
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _state.value = EngineState(error = error.message ?: "Engine initialization failed")
                throw error
            }
        }
    }

    private suspend fun updateExtractorIfDue() {
        val preferences = appContext.getSharedPreferences(RUNTIME_PREFERENCES, Context.MODE_PRIVATE)
        val lastAttempt = preferences.getLong(KEY_LAST_UPDATE_ATTEMPT, 0L)
        if (System.currentTimeMillis() - lastAttempt < UPDATE_INTERVAL_MS) return
        updateMutex.withLock {
            val latestAttempt = preferences.getLong(KEY_LAST_UPDATE_ATTEMPT, 0L)
            if (System.currentTimeMillis() - latestAttempt < UPDATE_INTERVAL_MS) return@withLock
            recordUpdateAttempt(successful = false)
            runCatching {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().updateYoutubeDL(
                        appContext,
                        YoutubeDL.UpdateChannel.NIGHTLY,
                    )
                }
            }.onSuccess {
                recordUpdateAttempt(successful = true)
                _state.value = _state.value.copy(version = currentVersion(), error = null)
            }.onFailure { error ->
                Log.w(TAG, "Unable to refresh yt-dlp; continuing with the bundled version", error)
            }
        }
    }

    private fun repairRuntimePackageIfNeeded() {
        val runtimeDirectory = File(appContext.noBackupFilesDir, YoutubeDL.baseName)
        if (!runtimeDirectory.exists()) return
        val markerMatches = File(runtimeDirectory, RUNTIME_MARKER).runCatching { readText() }
            .getOrNull() == expectedRuntimeMarker()
        val cryptoLibrary = File(runtimeDirectory, "packages/python/usr/lib/libcrypto.so.3")
        val cryptoMatches = elfClassMatchesProcess(cryptoLibrary)
        if (markerMatches && cryptoMatches) return

        check(runtimeDirectory.deleteRecursively()) {
            "Unable to replace the incompatible yt-dlp runtime"
        }
        appContext.getSharedPreferences(YOUTUBE_DL_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        Log.i(TAG, "Removed an incompatible yt-dlp runtime for ${expectedRuntimeMarker()}")
    }

    private fun writeRuntimeMarker() {
        val runtimeDirectory = File(appContext.noBackupFilesDir, YoutubeDL.baseName)
        check(runtimeDirectory.isDirectory || runtimeDirectory.mkdirs()) {
            "Unable to access the yt-dlp runtime directory"
        }
        val marker = File(runtimeDirectory, RUNTIME_MARKER)
        val temporary = File(runtimeDirectory, "$RUNTIME_MARKER.tmp")
        temporary.writeText(expectedRuntimeMarker())
        if (marker.exists() && !marker.delete()) error("Unable to replace the runtime marker")
        check(temporary.renameTo(marker)) { "Unable to save the runtime marker" }
    }

    private fun elfClassMatchesProcess(library: File): Boolean {
        if (!library.isFile) return false
        return runCatching {
            RandomAccessFile(library, "r").use { file ->
                val header = ByteArray(5)
                if (file.read(header) != header.size) return@use false
                val validElf = header[0] == 0x7f.toByte() &&
                    header[1] == 'E'.code.toByte() &&
                    header[2] == 'L'.code.toByte() &&
                    header[3] == 'F'.code.toByte()
                val expectedClass = if (Process.is64Bit()) ELF_CLASS_64 else ELF_CLASS_32
                validElf && header[4].toInt() == expectedClass
            }
        }.getOrDefault(false)
    }

    private fun expectedRuntimeMarker(): String =
        "$RUNTIME_SCHEMA:${if (Process.is64Bit()) "64" else "32"}"

    private fun currentVersion(): String? =
        YoutubeDL.getInstance().versionName(appContext)
            ?: YoutubeDL.getInstance().version(appContext)

    private fun recordUpdateAttempt(successful: Boolean) {
        appContext.getSharedPreferences(RUNTIME_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_UPDATE_ATTEMPT, System.currentTimeMillis())
            .putBoolean(KEY_LAST_UPDATE_SUCCESSFUL, successful)
            .apply()
    }

    private fun isRecoverableExtractorError(error: Throwable): Boolean {
        val details = generateSequence(error) { it.cause }
            .mapNotNull(Throwable::message)
            .joinToString("\n")
            .lowercase()
        return RECOVERABLE_ERROR_MARKERS.any(details::contains)
    }

    fun cookiesFile(): File = File(File(appContext.filesDir, "cookies"), "cookies.txt")

    fun hasCookies(): Boolean = cookiesFile().isFile && cookiesFile().length() > 0L

    suspend fun importCookies(uri: Uri) = withContext(Dispatchers.IO) {
        val destination = cookiesFile()
        val directory = destination.parentFile ?: error("Cookies directory is unavailable")
        directory.mkdirs()
        val temporary = File(directory, "cookies.txt.tmp")
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
        const val TAG = "YtDlpRuntime"
        const val MAX_COOKIE_FILE_SIZE = 10L * 1024L * 1024L
        const val RUNTIME_SCHEMA = 2
        const val RUNTIME_MARKER = ".runtime-architecture"
        const val RUNTIME_PREFERENCES = "yt_dlp_runtime"
        const val YOUTUBE_DL_PREFERENCES = "youtubedl-android"
        const val KEY_LAST_UPDATE_ATTEMPT = "last_update_attempt"
        const val KEY_LAST_UPDATE_SUCCESSFUL = "last_update_successful"
        const val UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L
        const val ELF_CLASS_32 = 1
        const val ELF_CLASS_64 = 2
        val RECOVERABLE_ERROR_MARKERS = listOf(
            "http error 403",
            "forbidden",
            "signature extraction failed",
            "challenge solving failed",
            "requested format is not available",
        )
    }
}
