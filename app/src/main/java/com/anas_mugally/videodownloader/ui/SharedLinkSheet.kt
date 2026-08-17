package com.anas_mugally.videodownloader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
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
    val currentAd by viewModel.currentAd.collectAsStateWithLifecycle()
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
            if (event.message == R.string.download_added_to_queue || event.message == R.string.download_already_queued) {
                onDismiss()
            }
        }
    }

    val requestDownload = {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            enqueueAfterPermission = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.enqueueSelectedDownload()
        }
    }

    val media = state.media
    val formats = media?.let {
        val videoFormats = it.formats.filter(MediaFormat::hasVideo)
        val audioFormats = it.formats.filter { format -> format.hasAudio && !format.hasVideo }
            .ifEmpty { it.formats.filter(MediaFormat::hasAudio) }
        if (state.selectedKind == DownloadKind.VIDEO) videoFormats else audioFormats
    }.orEmpty()
    val selectedId = if (state.selectedKind == DownloadKind.VIDEO) state.selectedVideoFormatId else state.selectedAudioFormatId
    val selectedFormat = formats.firstOrNull { it.formatId == selectedId }

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
                .heightIn(min = 220.dp, max = 680.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
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
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close))
                    }
                }

                HorizontalDivider()

                if (state.analyzing) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(150.dp),
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
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(18.dp)) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                            Icon(painterResource(R.drawable.ic_error), contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                                TextButton(onClick = viewModel::analyze) { Text(stringResource(R.string.retry)) }
                            }
                        }
                    }
                }

                media?.let { currentMedia ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = currentMedia.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(width = 112.dp, height = 72.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = currentMedia.title,
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

                    currentAd?.let { ad ->
                        MyAppAdCard(
                            ad = ad,
                            onClick = {
                                viewModel.recordAdClick(it)
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.urlApp)))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    val videoFormats = currentMedia.formats.filter(MediaFormat::hasVideo)
                    val audioFormats = currentMedia.formats.filter { it.hasAudio && !it.hasVideo }
                        .ifEmpty { currentMedia.formats.filter(MediaFormat::hasAudio) }
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = state.selectedKind == DownloadKind.VIDEO,
                            onClick = { viewModel.selectKind(DownloadKind.VIDEO) },
                            enabled = videoFormats.isNotEmpty(),
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            icon = { Icon(painterResource(R.drawable.ic_movie), contentDescription = null) },
                        ) { Text(stringResource(R.string.video_mp4)) }
                        SegmentedButton(
                            selected = state.selectedKind == DownloadKind.AUDIO,
                            onClick = { viewModel.selectKind(DownloadKind.AUDIO) },
                            enabled = audioFormats.isNotEmpty(),
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            icon = { Icon(painterResource(R.drawable.ic_headphones), contentDescription = null) },
                        ) { Text(stringResource(R.string.audio_m4a)) }
                    }

                    Text(
                        text = stringResource(R.string.choose_quality),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        formats.forEach { format ->
                            FormatChoiceCard(
                                format = format,
                                kind = state.selectedKind,
                                selected = format.formatId == selectedId,
                                onClick = { viewModel.selectFormat(format.formatId) },
                            )
                        }
                    }

                    val shouldChooseAudioTrack = currentMedia.audioTracks.isNotEmpty() &&
                        (state.selectedKind == DownloadKind.AUDIO || selectedFormat?.requiresMerge == true)
                    if (shouldChooseAudioTrack) {
                        AudioTrackSelector(
                            tracks = currentMedia.audioTracks,
                            selectedId = state.selectedAudioTrackId,
                            onSelected = viewModel::selectAudioTrack,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (currentMedia.subtitles.isNotEmpty()) {
                        SubtitleSelector(
                            tracks = currentMedia.subtitles,
                            selectedId = state.selectedSubtitleId,
                            onSelected = viewModel::selectSubtitle,
                            onDownload = viewModel::downloadSelectedSubtitle,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (state.selectedKind == DownloadKind.AUDIO) {
                        Text(
                            text = stringResource(R.string.m4a_media3_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Fixed action area: quality/audio/subtitle lists scroll above it, but the
            // primary download action remains reachable at the bottom of the share window.
            if (media != null) {
                HorizontalDivider()
                Button(
                    onClick = requestDownload,
                    enabled = selectedFormat != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_download), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.start_download))
                }
            }
        }
    }
}
