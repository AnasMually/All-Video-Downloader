package com.anas_mugally.videodownloader.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.anas_mugally.videodownloader.R
import com.anas_mugally.videodownloader.domain.DownloadKind
import com.anas_mugally.videodownloader.domain.MediaFormat
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedLinkSheet(
    sharedText: String,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var formatMenuExpanded by remember { mutableStateOf(false) }
    var enqueueAfterPermission by remember { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        if (enqueueAfterPermission) {
            enqueueAfterPermission = false
            viewModel.enqueueSelectedDownload()
        }
    }

    LaunchedEffect(sharedText) {
        viewModel.consumeSharedText(sharedText)
        viewModel.analyze()
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            Toast.makeText(context, context.getString(event.message), Toast.LENGTH_SHORT).show()
            if (
                event.message == R.string.download_added_to_queue ||
                event.message == R.string.download_already_queued
            ) {
                onDismiss()
            }
        }
    }

    val requestDownload = {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            enqueueAfterPermission = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.enqueueSelectedDownload()
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .navigationBarsPadding(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.share_sheet_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.share_sheet_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                }
            }

            HorizontalDivider()

            if (state.analyzing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.analyzing_link))
                    }
                }
            }

            state.error?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(Icons.Default.Error, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = viewModel::analyze) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
            }

            state.media?.let { media ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = media.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 112.dp, height = 72.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = media.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = state.url,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                val videoFormats = media.formats.filter(MediaFormat::hasVideo)
                val audioFormats = media.formats.filter { it.hasAudio && !it.hasVideo }
                    .ifEmpty { media.formats.filter(MediaFormat::hasAudio) }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = state.selectedKind == DownloadKind.VIDEO,
                        onClick = { viewModel.selectKind(DownloadKind.VIDEO) },
                        enabled = videoFormats.isNotEmpty(),
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = { Icon(Icons.Default.Movie, contentDescription = null) },
                    ) {
                        Text(stringResource(R.string.video_mp4))
                    }
                    SegmentedButton(
                        selected = state.selectedKind == DownloadKind.AUDIO,
                        onClick = { viewModel.selectKind(DownloadKind.AUDIO) },
                        enabled = audioFormats.isNotEmpty(),
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = { Icon(Icons.Default.Headphones, contentDescription = null) },
                    ) {
                        Text(stringResource(R.string.audio_m4a))
                    }
                }

                val formats = if (state.selectedKind == DownloadKind.VIDEO) videoFormats else audioFormats
                val selectedId = if (state.selectedKind == DownloadKind.VIDEO) {
                    state.selectedVideoFormatId
                } else {
                    state.selectedAudioFormatId
                }
                val selected = formats.firstOrNull { it.formatId == selectedId }
                ExposedDropdownMenuBox(
                    expanded = formatMenuExpanded,
                    onExpandedChange = { formatMenuExpanded = !formatMenuExpanded },
                ) {
                    OutlinedTextField(
                        value = selected?.displayName(state.selectedKind).orEmpty(),
                        onValueChange = {},
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        readOnly = true,
                        label = { Text(stringResource(R.string.choose_quality)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = formatMenuExpanded)
                        },
                        shape = RoundedCornerShape(18.dp),
                    )
                    ExposedDropdownMenu(
                        expanded = formatMenuExpanded,
                        onDismissRequest = { formatMenuExpanded = false },
                    ) {
                        formats.forEach { format ->
                            DropdownMenuItem(
                                text = { Text(format.displayName(state.selectedKind)) },
                                onClick = {
                                    viewModel.selectFormat(format.formatId)
                                    formatMenuExpanded = false
                                },
                            )
                        }
                    }
                }

                if (state.selectedKind == DownloadKind.AUDIO) {
                    Text(
                        text = stringResource(R.string.m4a_media3_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Button(
                    onClick = requestDownload,
                    enabled = selected != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.start_download))
                }
            }
        }
    }
}

private fun MediaFormat.displayName(kind: DownloadKind): String = when (kind) {
    DownloadKind.VIDEO -> "$label · MP4"
    DownloadKind.AUDIO -> if (audioBitrateKbps != null) {
        "M4A · $audioBitrateKbps kbps"
    } else {
        "M4A"
    }
}
