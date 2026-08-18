package com.anas_mugally.videodownloader.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.anas_mugally.videodownloader.R
import com.anas_mugally.videodownloader.domain.DownloadKind
import com.anas_mugally.videodownloader.domain.DownloadStatus
import com.anas_mugally.videodownloader.domain.DownloadTask
import com.anas_mugally.videodownloader.download.DownloadProgressText
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(
    contentPadding: PaddingValues,
    tasks: List<DownloadTask>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearFinished: () -> Unit,
    onPlay: (DownloadTask) -> Unit,
    onShare: (DownloadTask) -> Unit,
) {
    val sortedTasks = tasks.sortedWith(
        compareBy<DownloadTask> { statusPriority(it.status) }
            .thenByDescending(DownloadTask::createdAt),
    )
    val hasFinished = tasks.any {
        it.status == DownloadStatus.COMPLETED ||
            it.status == DownloadStatus.FAILED ||
            it.status == DownloadStatus.CANCELLED
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.download_queue),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.download_queue_subtitle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (hasFinished) {
                    TextButton(onClick = onClearFinished) {
                        Icon(painterResource(R.drawable.ic_history), contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.clear_finished))
                    }
                }
            }
        }

        if (sortedTasks.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_download),
                            contentDescription = null,
                            modifier = Modifier.size(52.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.no_downloads),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.no_downloads_description),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        items(sortedTasks, key = DownloadTask::id) { task ->
            DownloadTaskCard(
                modifier = Modifier.animateItemPlacement(),
                task = task,
                onPause = { onPause(task.id) },
                onResume = { onResume(task.id) },
                onRetry = { onRetry(task.id) },
                onCancel = { onCancel(task.id) },
                onDelete = { onDelete(task.id) },
                onPlay = { onPlay(task) },
                onShare = { onShare(task) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    modifier: Modifier = Modifier,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onPlay: () -> Unit,
    onShare: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (task.status == DownloadStatus.FAILED) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = task.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(82.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.task_format,
                            if (task.kind == DownloadKind.AUDIO) {
                                stringResource(R.string.audio)
                            } else {
                                stringResource(R.string.video)
                            },
                            task.formatLabel,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(task.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AssistChip(
                    onClick = {},
                    label = { Text(statusText(task.status)) },
                )
            }

            when (task.status) {
                DownloadStatus.DOWNLOADING -> {
                    val context = LocalContext.current
                    LinearProgressIndicator(
                        progress = task.progress / 100f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = DownloadProgressText.primary(context, task),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    DownloadProgressText.secondary(context, task)?.let { details ->
                        Text(
                            text = details,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                DownloadStatus.QUEUED, DownloadStatus.WAITING_FOR_WIFI -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (task.status == DownloadStatus.WAITING_FOR_WIFI) {
                        Text(
                            text = stringResource(R.string.waiting_for_wifi),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }

                DownloadStatus.FAILED -> {
                    Text(
                        text = stringResource(R.string.download_failed_user),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }

                else -> Unit
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (task.status) {
                    DownloadStatus.DOWNLOADING,
                    DownloadStatus.QUEUED,
                    DownloadStatus.WAITING_FOR_WIFI -> {
                        FilledTonalButton(onClick = onPause) {
                            Icon(painterResource(R.drawable.ic_pause), contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.pause))
                        }
                        OutlinedButton(onClick = onCancel) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.cancel))
                        }
                    }

                    DownloadStatus.PAUSED -> {
                        Button(onClick = onResume) {
                            Icon(painterResource(R.drawable.ic_play), contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.resume))
                        }
                        OutlinedButton(onClick = onCancel) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.cancel))
                        }
                    }

                    DownloadStatus.COMPLETED -> {
                        Button(onClick = onPlay) {
                            Icon(painterResource(R.drawable.ic_play), contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.play))
                        }
                        FilledTonalButton(onClick = onShare) {
                            Icon(painterResource(R.drawable.ic_share), contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.share))
                        }
                        TextButton(onClick = onDelete) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.delete))
                        }
                    }

                    DownloadStatus.FAILED,
                    DownloadStatus.CANCELLED -> {
                        Button(onClick = onRetry) {
                            Icon(painterResource(R.drawable.ic_retry), contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.retry))
                        }
                        TextButton(onClick = onDelete) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.delete))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusText(status: DownloadStatus): String = when (status) {
    DownloadStatus.QUEUED -> stringResource(R.string.status_queued)
    DownloadStatus.WAITING_FOR_WIFI -> stringResource(R.string.status_waiting)
    DownloadStatus.DOWNLOADING -> stringResource(R.string.status_downloading)
    DownloadStatus.PAUSED -> stringResource(R.string.status_paused)
    DownloadStatus.COMPLETED -> stringResource(R.string.status_completed)
    DownloadStatus.FAILED -> stringResource(R.string.status_failed)
    DownloadStatus.CANCELLED -> stringResource(R.string.status_cancelled)
}

private fun statusPriority(status: DownloadStatus): Int = when (status) {
    DownloadStatus.DOWNLOADING -> 0
    DownloadStatus.QUEUED -> 1
    DownloadStatus.WAITING_FOR_WIFI -> 2
    DownloadStatus.PAUSED -> 3
    DownloadStatus.FAILED -> 4
    DownloadStatus.COMPLETED -> 5
    DownloadStatus.CANCELLED -> 6
}
