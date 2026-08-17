package com.anas_mugally.videodownloader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anas_mugally.videodownloader.data.EngineState
import com.anas_mugally.videodownloader.data.MyAppAd
import com.anas_mugally.videodownloader.domain.DownloadKind

@Composable
fun HomeWithAdScreen(
    contentPadding: PaddingValues,
    state: MainUiState,
    engineState: EngineState,
    ad: MyAppAd?,
    onUrlChange: (String) -> Unit,
    onAnalyze: () -> Unit,
    onKindSelected: (DownloadKind) -> Unit,
    onFormatSelected: (String) -> Unit,
    onAudioTrackSelected: (String) -> Unit,
    onSubtitleSelected: (String) -> Unit,
    onSubtitleDownload: () -> Unit,
    onDownload: () -> Unit,
    onAdClick: (MyAppAd) -> Unit,
) {
    val showAd = state.media != null && ad != null
    val homePadding = if (showAd) {
        PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 132.dp,
        )
    } else {
        contentPadding
    }

    Box(Modifier.fillMaxSize()) {
        HomeScreen(
            contentPadding = homePadding,
            state = state,
            engineState = engineState,
            onUrlChange = onUrlChange,
            onAnalyze = onAnalyze,
            onKindSelected = onKindSelected,
            onFormatSelected = onFormatSelected,
            onAudioTrackSelected = onAudioTrackSelected,
            onSubtitleSelected = onSubtitleSelected,
            onSubtitleDownload = onSubtitleDownload,
            onDownload = onDownload,
        )

        if (showAd) {
            MyAppAdCard(
                ad = requireNotNull(ad),
                onClick = onAdClick,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 20.dp,
                        end = 20.dp,
                        bottom = contentPadding.calculateBottomPadding() + 8.dp,
                    ),
            )
        }
    }
}
