package com.anas_mugally.videodownloader.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.anas_mugally.videodownloader.domain.AppSettings
import com.anas_mugally.videodownloader.domain.DownloadFormatTools
import com.anas_mugally.videodownloader.domain.DownloadStatus
import com.anas_mugally.videodownloader.domain.DownloadTask
import com.anas_mugally.videodownloader.domain.FileNameMode
import com.anas_mugally.videodownloader.domain.ThemeMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.appDataStore by preferencesDataStore(name = "all_video_downloader")

class AppRepository(context: Context) {
    private val dataStore = context.applicationContext.appDataStore

    val settings: Flow<AppSettings> = dataStore.data
        .safePreferences()
        .map(::decodeSettings)

    val tasks: Flow<List<DownloadTask>> = dataStore.data
        .safePreferences()
        .map { preferences ->
            TaskJsonCodec.decode(preferences[Keys.tasks])
                .sortedByDescending(DownloadTask::createdAt)
        }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { preferences ->
            val transformed = transform(decodeSettings(preferences))
            val next = transformed.copy(
                outputFolder = DownloadFormatTools.safeFolderName(
                    transformed.outputFolder,
                ),
            )
            preferences[Keys.wifiOnly] = next.wifiOnly
            preferences[Keys.dynamicColor] = next.dynamicColor
            preferences[Keys.themeMode] = next.themeMode.name
            preferences[Keys.outputFolder] = next.outputFolder
            preferences[Keys.fileNameMode] = next.fileNameMode.name
        }
    }

    suspend fun upsertTask(task: DownloadTask) {
        updateTasks { current ->
            val withoutExisting = current.filterNot { it.id == task.id }
            retainHistory(listOf(task) + withoutExisting)
        }
    }

    suspend fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        updateTasks { current ->
            current.map { task ->
                if (task.id == id) transform(task).copy(updatedAt = System.currentTimeMillis()) else task
            }
        }
    }

    suspend fun task(id: String): DownloadTask? = tasks.first().firstOrNull { it.id == id }

    suspend fun deleteTask(id: String) {
        updateTasks { current -> current.filterNot { it.id == id } }
    }

    suspend fun clearFinished() {
        updateTasks { current ->
            current.filterNot { task ->
                task.status == DownloadStatus.COMPLETED ||
                    task.status == DownloadStatus.FAILED ||
                    task.status == DownloadStatus.CANCELLED
            }
        }
    }

    suspend fun recoverInterruptedTasks() {
        updateTasks { current ->
            current.map { task ->
                if (task.status == DownloadStatus.DOWNLOADING) {
                    task.copy(status = DownloadStatus.QUEUED, error = null)
                } else {
                    task
                }
            }
        }
    }

    private suspend fun updateTasks(transform: (List<DownloadTask>) -> List<DownloadTask>) {
        dataStore.edit { preferences ->
            val current = TaskJsonCodec.decode(preferences[Keys.tasks])
            preferences[Keys.tasks] = TaskJsonCodec.encode(retainHistory(transform(current)))
        }
    }

    private fun retainHistory(tasks: List<DownloadTask>): List<DownloadTask> {
        val sorted = tasks.distinctBy(DownloadTask::id).sortedByDescending(DownloadTask::createdAt)
        val active = sorted.filter { it.isActive || it.status == DownloadStatus.PAUSED }
        val history = sorted.filterNot { it in active }.take(MAX_HISTORY_ITEMS)
        return (active + history).distinctBy(DownloadTask::id)
    }

    private fun decodeSettings(preferences: Preferences): AppSettings {
        return AppSettings(
            wifiOnly = preferences[Keys.wifiOnly] ?: false,
            dynamicColor = preferences[Keys.dynamicColor] ?: true,
            themeMode = preferences.enumValue(Keys.themeMode, ThemeMode.SYSTEM),
            outputFolder = DownloadFormatTools.safeFolderName(
                preferences[Keys.outputFolder] ?: AppSettings.DEFAULT_OUTPUT_FOLDER,
            ),
            fileNameMode = preferences.enumValue(Keys.fileNameMode, FileNameMode.TITLE_AND_ID),
        )
    }

    private fun Flow<Preferences>.safePreferences(): Flow<Preferences> = catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    private inline fun <reified T : Enum<T>> Preferences.enumValue(
        key: Preferences.Key<String>,
        fallback: T,
    ): T = runCatching { enumValueOf<T>(this[key].orEmpty()) }.getOrDefault(fallback)

    private object Keys {
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val themeMode = stringPreferencesKey("theme_mode")
        val outputFolder = stringPreferencesKey("output_folder")
        val fileNameMode = stringPreferencesKey("file_name_mode")
        val tasks = stringPreferencesKey("download_tasks_v1")
    }

    private companion object {
        const val MAX_HISTORY_ITEMS = 100
    }
}
