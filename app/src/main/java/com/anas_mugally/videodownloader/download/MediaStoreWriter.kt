package com.anas_mugally.videodownloader.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.anas_mugally.videodownloader.domain.DownloadFormatTools
import java.io.File

data class SavedMedia(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
)

object MediaStoreWriter {
    fun save(context: Context, source: File, audioOnly: Boolean, folderName: String): SavedMedia {
        require(source.isFile && source.length() > 0L) { "Downloaded file is empty" }
        val collection = if (audioOnly) {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val baseFolder = if (audioOnly) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        val mimeType = DownloadFormatTools.mimeType(source.extension, audioOnly)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$baseFolder/$folderName")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: error("Unable to create MediaStore item")
        try {
            val output = resolver.openOutputStream(uri, "w")
                ?: error("Unable to open MediaStore output")
            output.use { destination ->
                source.inputStream().buffered().use { input -> input.copyTo(destination) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) == 1) {
                "Unable to publish downloaded media"
            }
            return SavedMedia(uri, source.name, mimeType)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
