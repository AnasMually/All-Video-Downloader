package com.anas_mugally.videodownloader.download

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.anas_mugally.videodownloader.data.MediaFragment
import com.anas_mugally.videodownloader.data.MediaStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Lightweight on-device processing backed by Google Media3 and Android MediaMuxer. */
@OptIn(markerClass = [UnstableApi::class])
class OnDeviceMediaProcessor(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var activeCancellation: (() -> Unit)? = null

    suspend fun convertToM4a(source: File, output: File) {
        require(source.isFile && source.length() > 0L) { "Downloaded audio is empty" }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        exportWithTransformer(
            transformer = Transformer.Builder(appContext)
                .setAudioMimeType(MimeTypes.AUDIO_AAC),
            item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                .setRemoveVideo(true)
                .build(),
            output = output,
            emptyOutputMessage = "Media3 produced an empty M4A file",
        )
    }

    /** Downloads and materializes adaptive HLS/DASH media on the phone. */
    suspend fun materializeAdaptiveStream(stream: MediaStream, output: File) {
        require(stream.isAdaptiveManifest) { "The stream is not adaptive media" }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        // yt-dlp can parse Facebook's DASH XML embedded in the page and return a
        // representation/base URL plus an explicit fragment plan. That URL is not
        // necessarily a complete file and the public dash_mpd_debug endpoint may
        // return login HTML. Rebuild the fragmented MP4 locally instead of asking
        // Media3 to treat the representation URL as an MPD or standalone file.
        if (stream.fragments.isNotEmpty()) {
            materializeFragments(stream, output)
            return
        }

        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(60_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(stream.headers)

        val protocol = stream.protocol.lowercase()
        val cleanUrl = stream.url.substringBefore('?').lowercase()
        val isHls = protocol.contains("m3u8") || cleanUrl.endsWith(".m3u8")
        val isDash = protocol.contains("dash") || cleanUrl.endsWith(".mpd")
        val sourceMime = when {
            isHls -> MimeTypes.APPLICATION_M3U8
            isDash -> MimeTypes.APPLICATION_MPD
            else -> null
        }

        // Do not rely on DefaultMediaSourceFactory discovering optional modules at
        // runtime. Facebook commonly resolves the companion audio as DASH; using
        // DashMediaSource directly guarantees that the MPD and its media segments
        // are actually read instead of treating the manifest as a normal file.
        val mediaSourceFactory: MediaSource.Factory = when {
            isDash -> DashMediaSource.Factory(httpFactory)
            isHls -> HlsMediaSource.Factory(httpFactory)
            else -> DefaultMediaSourceFactory(httpFactory)
        }

        val decoderFactory = DefaultDecoderFactory.Builder(appContext).build()
        val assetLoaderFactory = ExoPlayerAssetLoader.Factory(
            appContext,
            decoderFactory,
            Clock.DEFAULT,
            mediaSourceFactory,
        )

        val mediaItemBuilder = MediaItem.Builder().setUri(stream.url)
        if (sourceMime != null) mediaItemBuilder.setMimeType(sourceMime)

        val editedItemBuilder = EditedMediaItem.Builder(mediaItemBuilder.build())
        if (stream.isVideoOnly) editedItemBuilder.setRemoveAudio(true)
        if (stream.isAudioOnly) editedItemBuilder.setRemoveVideo(true)

        val transformerBuilder = Transformer.Builder(appContext)
            .setAssetLoaderFactory(assetLoaderFactory)
        if (stream.isAudioOnly) transformerBuilder.setAudioMimeType(MimeTypes.AUDIO_AAC)

        exportWithTransformer(
            transformer = transformerBuilder,
            item = editedItemBuilder.build(),
            output = output,
            emptyOutputMessage = "Media3 produced an empty adaptive media file",
        )
    }

    private suspend fun materializeFragments(stream: MediaStream, output: File) = withContext(Dispatchers.IO) {
        require(stream.fragments.isNotEmpty()) { "Fragmented media plan is empty" }
        val partDirectory = File(output.parentFile, output.name + ".fragments").apply { mkdirs() }
        val assembled = File(output.parentFile, output.name + ".assembling")
        if (assembled.exists()) assembled.delete()

        val cancelled = AtomicBoolean(false)
        val activeConnection = AtomicReference<HttpURLConnection?>(null)
        activeCancellation = {
            cancelled.set(true)
            activeConnection.getAndSet(null)?.disconnect()
            assembled.delete()
            output.delete()
        }

        try {
            val localParts = ArrayList<File>(stream.fragments.size)
            stream.fragments.forEachIndexed { index, fragment ->
                currentCoroutineContext().ensureActive()
                if (cancelled.get()) throw MediaProcessingStoppedException()

                val target = File(partDirectory, "fragment-${index.toString().padStart(6, '0')}.bin")
                val expected = fragment.fileSize
                if (target.isFile && expected != null && target.length() == expected) {
                    localParts += target
                    return@forEachIndexed
                }
                if (target.exists()) target.delete()

                val partial = File(partDirectory, target.name + ".part")
                if (partial.exists()) partial.delete()
                val fragmentUrl = resolveFragmentUrl(stream, fragment)
                val connection = openFragmentConnection(fragmentUrl, stream.headers)
                activeConnection.set(connection)
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        throw IllegalStateException("HTTP $code while downloading adaptive media fragment")
                    }
                    connection.inputStream.buffered(FRAGMENT_BUFFER_BYTES).use { input ->
                        FileOutputStream(partial, false).buffered(FRAGMENT_BUFFER_BYTES).use { sink ->
                            val buffer = ByteArray(FRAGMENT_BUFFER_BYTES)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                if (cancelled.get()) throw MediaProcessingStoppedException()
                                val count = input.read(buffer)
                                if (count < 0) break
                                sink.write(buffer, 0, count)
                            }
                            sink.flush()
                        }
                    }
                } catch (error: Throwable) {
                    if (cancelled.get()) throw MediaProcessingStoppedException()
                    throw error
                } finally {
                    activeConnection.compareAndSet(connection, null)
                    connection.disconnect()
                }

                if (expected != null && partial.length() != expected) {
                    partial.delete()
                    throw InvalidDownloadedMediaException(
                        "Adaptive media fragment $index has ${partial.length()} bytes; expected $expected",
                    )
                }
                if (!partial.isFile || partial.length() <= 0L) {
                    partial.delete()
                    throw InvalidDownloadedMediaException("Adaptive media fragment $index is empty")
                }
                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                localParts += target
            }

            FileOutputStream(assembled, false).buffered(FRAGMENT_BUFFER_BYTES).use { sink ->
                localParts.forEach { part ->
                    currentCoroutineContext().ensureActive()
                    if (cancelled.get()) throw MediaProcessingStoppedException()
                    part.inputStream().buffered(FRAGMENT_BUFFER_BYTES).use { source ->
                        source.copyTo(sink, FRAGMENT_BUFFER_BYTES)
                    }
                }
                sink.flush()
            }
            if (!assembled.isFile || assembled.length() <= 0L) {
                throw InvalidDownloadedMediaException("Fragmented media assembly produced an empty file")
            }

            validateFragmentedSamples(stream, assembled)
            if (output.exists()) output.delete()
            if (!assembled.renameTo(output)) {
                assembled.copyTo(output, overwrite = true)
                assembled.delete()
            }
            partDirectory.deleteRecursively()
        } catch (error: Throwable) {
            assembled.delete()
            output.delete()
            throw error
        } finally {
            activeConnection.getAndSet(null)?.disconnect()
            activeCancellation = null
        }
    }

    private fun resolveFragmentUrl(stream: MediaStream, fragment: MediaFragment): String {
        fragment.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { return it }
        val path = fragment.path?.takeIf(String::isNotBlank)
            ?: throw InvalidDownloadedMediaException("Adaptive media fragment has no URL or path")
        val base = stream.fragmentBaseUrl?.takeIf(String::isNotBlank) ?: stream.url
        return runCatching { URL(URL(base), path).toString() }
            .getOrElse { throw InvalidDownloadedMediaException("Invalid adaptive media fragment URL") }
    }

    private fun openFragmentConnection(url: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            useCaches = false
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Connection", "Keep-Alive")
            headers.forEach { (name, value) ->
                if (name.isBlank() || value.isBlank()) return@forEach
                if (
                    name.equals("Host", true) ||
                    name.equals("Content-Length", true) ||
                    name.equals("Range", true) ||
                    name.equals("Accept-Encoding", true)
                ) {
                    return@forEach
                }
                setRequestProperty(name, value)
            }
        }

    private fun validateFragmentedSamples(stream: MediaStream, file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val prefix = when {
                stream.isAudioOnly -> "audio/"
                stream.isVideoOnly -> "video/"
                else -> null
            }
            if (prefix != null) {
                val track = extractor.selectFirstTrack(prefix)
                extractor.selectTrack(track)
                if (extractor.sampleTime < 0L) {
                    throw InvalidDownloadedMediaException(
                        "Fragmented ${prefix.removeSuffix("/")} stream contains no media samples",
                    )
                }
            } else {
                var foundSamples = false
                for (track in 0 until extractor.trackCount) {
                    extractor.selectTrack(track)
                    if (extractor.sampleTime >= 0L) {
                        foundSamples = true
                        break
                    }
                    extractor.unselectTrack(track)
                }
                if (!foundSamples) throw InvalidDownloadedMediaException("Fragmented stream contains no media samples")
            }
        } finally {
            extractor.release()
        }
    }

    private suspend fun exportWithTransformer(
        transformer: Transformer.Builder,
        item: EditedMediaItem,
        output: File,
        emptyOutputMessage: String,
    ) {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                var finished = false
                lateinit var builtTransformer: Transformer

                fun clearActive() {
                    if (activeCancellation != null) activeCancellation = null
                }

                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (finished) return
                        finished = true
                        clearActive()
                        if (output.isFile && output.length() > 0L) {
                            continuation.resume(Unit)
                        } else {
                            continuation.resumeWithException(InvalidDownloadedMediaException(emptyOutputMessage))
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        if (finished) return
                        finished = true
                        clearActive()
                        output.delete()
                        continuation.resumeWithException(exportException)
                    }
                }

                builtTransformer = transformer
                    .addListener(listener)
                    .build()

                activeCancellation = cancel@{
                    if (finished) return@cancel
                    finished = true
                    builtTransformer.cancel()
                    clearActive()
                    output.delete()
                    continuation.resumeWithException(MediaProcessingStoppedException())
                }
                continuation.invokeOnCancellation {
                    mainHandler.post {
                        if (!finished) {
                            finished = true
                            builtTransformer.cancel()
                            clearActive()
                            output.delete()
                        }
                    }
                }

                builtTransformer.start(item, output.absolutePath)
            }
        }
    }

    fun cancelActive() {
        mainHandler.post { activeCancellation?.invoke() }
    }

    suspend fun muxMp4(
        video: File,
        audio: File,
        output: File,
        shouldStop: () -> Boolean = { false },
    ) = withContext(Dispatchers.IO) {
        require(video.isFile && video.length() > 0L) { "Downloaded video is empty" }
        require(audio.isFile && audio.length() > 0L) { "Converted audio is empty" }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            videoExtractor.setDataSource(video.absolutePath)
            audioExtractor.setDataSource(audio.absolutePath)
            val videoTrack = videoExtractor.selectFirstTrack("video/")
            val audioTrack = audioExtractor.selectFirstTrack("audio/")
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)

            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)

            val videoStartTimeUs = videoExtractor.sampleTime
            val audioStartTimeUs = audioExtractor.sampleTime
            if (videoStartTimeUs < 0L) {
                throw InvalidDownloadedMediaException("Downloaded video contains no media samples")
            }
            if (audioStartTimeUs < 0L) {
                throw InvalidDownloadedMediaException("Downloaded audio contains no media samples")
            }

            val mediaMuxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mediaMuxer
            videoFormat.integerOrNull(MediaFormat.KEY_ROTATION)?.let(mediaMuxer::setOrientationHint)
            val outputVideoTrack = mediaMuxer.addTrack(videoFormat)
            val outputAudioTrack = mediaMuxer.addTrack(audioFormat)
            mediaMuxer.start()
            muxerStarted = true

            val bufferSize = maxOf(
                DEFAULT_MUX_BUFFER_SIZE,
                videoFormat.integerOrNull(MediaFormat.KEY_MAX_INPUT_SIZE) ?: 0,
                audioFormat.integerOrNull(MediaFormat.KEY_MAX_INPUT_SIZE) ?: 0,
            ).coerceAtMost(MAX_MUX_BUFFER_SIZE)
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val info = MediaCodec.BufferInfo()
            var videoDone = false
            var audioDone = false
            var videoSamplesWritten = 0L
            var audioSamplesWritten = 0L

            while (!videoDone || !audioDone) {
                if (shouldStop()) throw MediaProcessingStoppedException()

                val rawVideoTime = if (videoDone) -1L else videoExtractor.sampleTime
                val rawAudioTime = if (audioDone) -1L else audioExtractor.sampleTime
                if (rawVideoTime < 0L) videoDone = true
                if (rawAudioTime < 0L) audioDone = true
                if (videoDone && audioDone) break

                val videoTime = if (videoDone) {
                    Long.MAX_VALUE
                } else {
                    (rawVideoTime - videoStartTimeUs).coerceAtLeast(0L)
                }
                val audioTime = if (audioDone) {
                    Long.MAX_VALUE
                } else {
                    (rawAudioTime - audioStartTimeUs).coerceAtLeast(0L)
                }

                val useVideo = !videoDone && (audioDone || videoTime <= audioTime)
                val extractor = if (useVideo) videoExtractor else audioExtractor
                val outputTrack = if (useVideo) outputVideoTrack else outputAudioTrack
                val startTimeUs = if (useVideo) videoStartTimeUs else audioStartTimeUs
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    if (useVideo) videoDone = true else audioDone = true
                    continue
                }
                val extractorFlags = extractor.sampleFlags
                if (extractorFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                    throw InvalidDownloadedMediaException("Encrypted media samples are not supported")
                }
                val presentationTimeUs = (extractor.sampleTime - startTimeUs).coerceAtLeast(0L)
                info.set(
                    0,
                    sampleSize,
                    presentationTimeUs,
                    extractorFlags.toMediaCodecFlags(),
                )
                mediaMuxer.writeSampleData(outputTrack, buffer, info)
                if (useVideo) videoSamplesWritten++ else audioSamplesWritten++
                extractor.advance()
            }

            if (videoSamplesWritten <= 0L) {
                throw InvalidDownloadedMediaException("No video samples were written to the final MP4")
            }
            if (audioSamplesWritten <= 0L) {
                throw InvalidDownloadedMediaException("No audio samples were written to the final MP4")
            }
            mediaMuxer.stop()
            muxerStarted = false
            if (!output.isFile || output.length() <= 0L) {
                throw InvalidDownloadedMediaException("Android produced an empty MP4 file")
            }
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun MediaExtractor.selectFirstTrack(mimePrefix: String): Int {
        for (index in 0 until trackCount) {
            val mime = getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) return index
        }
        throw InvalidDownloadedMediaException("No ${mimePrefix.removeSuffix("/")} track was found")
    }

    private fun MediaFormat.integerOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun Int.toMediaCodecFlags(): Int {
        var codecFlags = 0
        if (this and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if (this and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return codecFlags
    }

    private companion object {
        const val DEFAULT_MUX_BUFFER_SIZE = 8 * 1024 * 1024
        const val MAX_MUX_BUFFER_SIZE = 64 * 1024 * 1024
        const val FRAGMENT_BUFFER_BYTES = 256 * 1024
    }
}

class MediaProcessingStoppedException : IllegalStateException("Media processing was stopped")
class InvalidDownloadedMediaException(message: String) : IllegalStateException(message)
