package com.anas_mugally.videodownloader.ui

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.anas_mugally.videodownloader.R
import com.anas_mugally.videodownloader.data.EngineState
import com.anas_mugally.videodownloader.domain.DownloadKind
import com.anas_mugally.videodownloader.domain.MediaFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    state: MainUiState,
    engineState: EngineState,
    onUrlChange: (String) -> Unit,
    onAnalyze: () -> Unit,
    onKindSelected: (DownloadKind) -> Unit,
    onFormatSelected: (String) -> Unit,
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.hero_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.supported_platforms),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (engineState.initializing) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.preparing_download_engine))
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = state.url,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.video_link)) },
                placeholder = { Text(stringResource(R.string.video_link_hint)) },
                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                val text = clipboard.primaryClip
                                    ?.getItemAt(0)
                                    ?.coerceToText(context)
                                    ?.toString()
                                    .orEmpty()
                                if (text.isNotBlank()) onUrlChange(text)
                            },
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.paste))
                        }
                        if (state.url.isNotBlank()) {
                            IconButton(onClick = { onUrlChange("") }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear))
                            }
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { onAnalyze() }),
            )
        }

        item {
            Button(
                onClick = onAnalyze,
                enabled = !state.analyzing && state.url.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                if (state.analyzing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                } else {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (state.analyzing) {
                        stringResource(R.string.analyzing_link)
                    } else {
                        stringResource(R.string.show_qualities)
                    },
                )
            }
        }

        state.error?.let { error ->
            item {
                OutlinedCard(
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        state.media?.let { media ->
            item {
                ElevatedCard(shape = RoundedCornerShape(24.dp)) {
                    Column {
                        AsyncImage(
                            model = media.thumbnailUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                        )
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = media.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (media.extractor.isNotBlank()) {
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(media.extractor) },
                                    )
                                }
                                media.durationSeconds?.let {
                                    Text(
                                        text = formatDuration(it),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val hasVideo = media.formats.any(MediaFormat::hasVideo)
            val audioFormats = media.formats
                .filter { it.hasAudio && !it.hasVideo }
                .ifEmpty { media.formats.filter(MediaFormat::hasAudio) }
            val hasAudio = audioFormats.isNotEmpty()
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = state.selectedKind == DownloadKind.VIDEO,
                        onClick = { onKindSelected(DownloadKind.VIDEO) },
                        enabled = hasVideo,
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = { Icon(Icons.Default.Movie, contentDescription = null) },
                    ) {
                        Text(stringResource(R.string.video))
                    }
                    SegmentedButton(
                        selected = state.selectedKind == DownloadKind.AUDIO,
                        onClick = { onKindSelected(DownloadKind.AUDIO) },
                        enabled = hasAudio,
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = { Icon(Icons.Default.Headphones, contentDescription = null) },
                    ) {
                        Text(stringResource(R.string.audio))
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.choose_quality),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            val shownFormats = if (state.selectedKind == DownloadKind.AUDIO) {
                audioFormats
            } else {
                media.formats.filter(MediaFormat::hasVideo)
            }
            val selectedId = if (state.selectedKind == DownloadKind.AUDIO) {
                state.selectedAudioFormatId
            } else {
                state.selectedVideoFormatId
            }
            items(shownFormats, key = MediaFormat::formatId) { format ->
                FormatRow(
                    format = format,
                    audioOnly = state.selectedKind == DownloadKind.AUDIO,
                    selected = selectedId == format.formatId,
                    onClick = { onFormatSelected(format.formatId) },
                )
            }

            item {
                Button(
                    onClick = onDownload,
                    enabled = selectedId != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.start_download))
                }
                Text(
                    text = stringResource(R.string.legal_download_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun FormatRow(
    format: MediaFormat,
    audioOnly: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val size = format.fileSize?.let(::formatFileSize) ?: stringResource(R.string.size_unknown)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (audioOnly) {
                        stringResource(R.string.audio_quality_value, format.label)
                    } else {
                        format.label
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        R.string.format_details,
                        format.extension.uppercase(Locale.ROOT),
                        size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainingSeconds = seconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, remainingSeconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, remainingSeconds)
    }
}

private fun formatFileSize(bytes: Long): String {
    return if (bytes >= 1_073_741_824L) {
        String.format(Locale.getDefault(), "%.1f GB", bytes / 1_073_741_824.0)
    } else if (bytes >= 1_048_576L) {
        String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
    } else {
        String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    }
}
