package com.anas_mugally.videodownloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anas_mugally.videodownloader.R
import com.anas_mugally.videodownloader.domain.AudioTrack
import com.anas_mugally.videodownloader.domain.SubtitleTrack

@Composable
fun AudioTrackSelector(
    tracks: List<AudioTrack>,
    selectedId: String?,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val selected = tracks.firstOrNull { it.id == selectedId } ?: tracks.first()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.audio_track),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = audioTrackLabel(selected),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text("▾")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                tracks.forEach { track ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = audioTrackLabel(track),
                                fontWeight = if (track.id == selected.id) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelected(track.id)
                        },
                        leadingIcon = if (track.id == selected.id) {
                            { Icon(painterResource(R.drawable.ic_download_done), contentDescription = null) }
                        } else null,
                    )
                }
            }
        }
    }
}

@Composable
fun SubtitleSelector(
    tracks: List<SubtitleTrack>,
    selectedId: String?,
    onSelected: (String) -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val selected = tracks.firstOrNull { it.id == selectedId } ?: tracks.first()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.subtitles),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = subtitleLabel(selected),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("▾")
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    tracks.forEach { track ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = subtitleLabel(track),
                                    fontWeight = if (track.id == selected.id) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                expanded = false
                                onSelected(track.id)
                            },
                            leadingIcon = if (track.id == selected.id) {
                                { Icon(painterResource(R.drawable.ic_download_done), contentDescription = null) }
                            } else null,
                        )
                    }
                }
            }
            Button(onClick = onDownload) {
                Icon(painterResource(R.drawable.ic_download), contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.download_subtitle))
            }
        }
    }
}

@Composable
private fun audioTrackLabel(track: AudioTrack): String {
    val base = track.label.ifBlank { track.language ?: stringResource(R.string.audio) }
    return when {
        track.isOriginal && track.isDefault -> "$base · ${stringResource(R.string.audio_original)} · ${stringResource(R.string.audio_default)}"
        track.isOriginal -> "$base · ${stringResource(R.string.audio_original)}"
        track.isDefault -> "$base · ${stringResource(R.string.audio_default)}"
        else -> base
    }
}

@Composable
private fun subtitleLabel(track: SubtitleTrack): String {
    val base = track.label.ifBlank { track.language ?: stringResource(R.string.subtitles) }
    return if (track.automatic) "$base · ${stringResource(R.string.subtitle_auto)}" else base
}
