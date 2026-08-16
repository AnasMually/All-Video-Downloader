package com.anas_mugally.videodownloader.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anas_mugally.videodownloader.BuildConfig
import com.anas_mugally.videodownloader.R
import com.anas_mugally.videodownloader.data.EngineState
import com.anas_mugally.videodownloader.domain.AppSettings
import com.anas_mugally.videodownloader.domain.AudioFormat
import com.anas_mugally.videodownloader.domain.FileNameMode
import com.anas_mugally.videodownloader.domain.ThemeMode

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    settings: AppSettings,
    engineState: EngineState,
    cookiesImported: Boolean,
    notificationsGranted: Boolean,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onOutputFolderSaved: (String) -> Unit,
    onFileNameModeChanged: (FileNameMode) -> Unit,
    onAudioFormatChanged: (AudioFormat) -> Unit,
    onImportCookies: () -> Unit,
    onClearCookies: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    val context = LocalContext.current
    var outputFolder by rememberSaveable { mutableStateOf(settings.outputFolder) }
    LaunchedEffect(settings.outputFolder) { outputFolder = settings.outputFolder }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.settings_downloads_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.settings_downloads_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            SettingsCard(
                title = stringResource(R.string.network),
                icon = Icons.Default.Wifi,
            ) {
                SettingSwitchRow(
                    title = stringResource(R.string.wifi_only),
                    description = stringResource(R.string.wifi_only_description),
                    checked = settings.wifiOnly,
                    onCheckedChange = onWifiOnlyChanged,
                )
            }
        }

        item {
            SettingsCard(
                title = stringResource(R.string.appearance),
                icon = Icons.Default.Palette,
            ) {
                SettingSwitchRow(
                    title = stringResource(R.string.dynamic_colors),
                    description = stringResource(R.string.dynamic_colors_description),
                    checked = settings.dynamicColor,
                    onCheckedChange = onDynamicColorChanged,
                )
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.theme),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                ThemeMode.entries.forEach { mode ->
                    RadioSettingRow(
                        title = when (mode) {
                            ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                            ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                            ThemeMode.DARK -> stringResource(R.string.theme_dark)
                        },
                        selected = settings.themeMode == mode,
                        onClick = { onThemeModeChanged(mode) },
                        leadingIcon = if (mode == ThemeMode.DARK) Icons.Default.DarkMode else null,
                    )
                }
            }
        }

        item {
            SettingsCard(
                title = stringResource(R.string.storage_and_naming),
                icon = Icons.Default.Folder,
            ) {
                Text(
                    text = stringResource(R.string.output_folder_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = outputFolder,
                    onValueChange = { outputFolder = it },
                    label = { Text(stringResource(R.string.output_folder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onOutputFolderSaved(outputFolder) },
                    enabled = outputFolder.isNotBlank(),
                ) {
                    Text(stringResource(R.string.save))
                }
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.file_name),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                FileNameMode.entries.forEach { mode ->
                    RadioSettingRow(
                        title = when (mode) {
                            FileNameMode.TITLE -> stringResource(R.string.file_name_title)
                            FileNameMode.TITLE_AND_ID -> stringResource(R.string.file_name_title_id)
                            FileNameMode.MEDIA_ID -> stringResource(R.string.file_name_id)
                        },
                        selected = settings.fileNameMode == mode,
                        onClick = { onFileNameModeChanged(mode) },
                    )
                }
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.audio_output_format),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                AudioFormat.entries.forEach { format ->
                    RadioSettingRow(
                        title = format.extension.uppercase(),
                        selected = settings.audioFormat == format,
                        onClick = { onAudioFormatChanged(format) },
                    )
                }
            }
        }

        item {
            SettingsCard(
                title = stringResource(R.string.notifications),
                icon = Icons.Default.Notifications,
            ) {
                Text(
                    text = if (notificationsGranted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        stringResource(R.string.notifications_enabled)
                    } else {
                        stringResource(R.string.notifications_disabled)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        FilledTonalButton(onClick = onRequestNotifications) {
                            Text(stringResource(R.string.allow_notifications))
                        }
                    }
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                },
                            )
                        },
                    ) {
                        Text(stringResource(R.string.open_system_settings))
                    }
                }
            }
        }

        item {
            SettingsCard(
                title = stringResource(R.string.cookies_title),
                icon = Icons.Default.Cookie,
            ) {
                Text(
                    text = stringResource(R.string.cookies_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (cookiesImported) {
                        stringResource(R.string.cookies_ready)
                    } else {
                        stringResource(R.string.cookies_not_imported)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (cookiesImported) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onImportCookies) {
                        Text(
                            if (cookiesImported) {
                                stringResource(R.string.replace_cookies)
                            } else {
                                stringResource(R.string.import_cookies)
                            },
                        )
                    }
                    if (cookiesImported) {
                        OutlinedButton(onClick = onClearCookies) {
                            Text(stringResource(R.string.remove))
                        }
                    }
                }
            }
        }

        item {
            SettingsCard(
                title = stringResource(R.string.privacy_and_engine),
                icon = Icons.Default.Security,
            ) {
                Text(
                    text = stringResource(R.string.no_storage_permission),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Text(
                    text = stringResource(
                        R.string.engine_status,
                        when {
                            engineState.ready -> stringResource(R.string.engine_ready)
                            engineState.initializing -> stringResource(R.string.engine_initializing)
                            else -> stringResource(R.string.engine_unavailable)
                        },
                    ),
                )
                engineState.version?.let { version ->
                    Text(
                        text = stringResource(R.string.engine_version, version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.legal_download_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RadioSettingRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    leadingIcon: ImageVector? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        leadingIcon?.let {
            Icon(it, contentDescription = null)
            Spacer(Modifier.width(8.dp))
        }
        TextButton(onClick = onClick) {
            Text(title)
        }
    }
}
