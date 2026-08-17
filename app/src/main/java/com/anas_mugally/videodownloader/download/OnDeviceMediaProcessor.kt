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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.anas_mugally.videodownloader.data.MediaStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Lightweight on-device processing backed by Google Media3 and Android MediaMuxer. */
@OptIn(markerClass = [UnstableApi::class])
class OnDeviceMediaProcessor(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeCancellation: (() -> Unit)? = null

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

    /**
     * Downloads and remuxes an adaptive HLS/DASH stream entirely on the phone.
     * Media3 reads the manifest/segments from the source CDN with the yt-dlp
     * supplied HTTP headers and writes a normal local MP4 track for MediaMuxer.
     */
    suspend fun materializeAdaptiveStream(stream: MediaStream, output: File) {
        require(stream.isAdaptiveManifest) { "The stream is not adaptive media" }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(60_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(stream.headers)
        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)
        val decoderFactory = DefaultDecoderFactory.Builder(appContext).build()
        val assetLoaderFactory = ExoPlayerAssetLoader.Factory(
            appContext,
            decoderFactory,
            Clock.DEFAULT,
            mediaSourceFactory,
        )

        val protocol = stream.protocol.lowercase()
        val sourceMime = when {
            protocol.contains("m3u8") || stream.url.substringBefore('?').lowercase().endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            protocol.contains("dash") || stream.url.substringBefore('?').lowercase().endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
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
                            continuation.resumeWithException(IllegalStateException(emptyOutputMessage))
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

            // Separate Facebook/DASH representations can carry non-zero timeline
            // origins. Writing those absolute timestamps into a fresh MP4 can put
            // the audio far outside the visible video timeline, producing a file
            // that technically contains an audio track but plays silently. Treat
            // each downloaded representation as a standalone clip and rebase its
            // first sample to t=0 before interleaving the tracks.
            val videoStartTimeUs = videoExtractor.sampleTime
            val audioStartTimeUs = audioExtractor.sampleTime
            require(videoStartTimeUs >= 0L) { "Downloaded video contains no media samples" }
            require(audioStartTimeUs >= 0L) { "Downloaded audio contains no media samples" }

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
                require(extractorFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) {
                    "Encrypted media samples are not supported"
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

            require(videoSamplesWritten > 0L) { "No video samples were written to the final MP4" }
            require(audioSamplesWritten > 0L) { "No audio samples were written to the final MP4" }
            mediaMuxer.stop()
            muxerStarted = false
            require(output.isFile && output.length() > 0L) { "Android produced an empty MP4 file" }
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
        error("No ${mimePrefix.removeSuffix("/")} track was found")
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
    }
}

class MediaProcessingStoppedException : IllegalStateException("Media processing was stopped")
