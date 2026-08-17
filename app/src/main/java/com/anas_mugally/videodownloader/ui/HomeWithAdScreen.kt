package com.anas_mugally.videodownloader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.runtime.Composable
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
    onDownload: () -> Unit,
    onAdClick: (MyAppAd) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            HomeScreen(
                contentPadding = contentPadding,
                state = state,
                engineState = engineState,
                onUrlChange = onUrlChange,
                onAnalyze = onAnalyze,
                onKindSelected = onKindSelected,
                onFormatSelected = onFormatSelected,
                onDownload = onDownload,
            )
        }
        if (state.media != null && ad != null) {
            MyAppAdCard(
                ad = ad,
                onClick = onAdClick,
                modifier = Modifier.padding(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = contentPadding.calculateBottomPadding() + 8.dp,
                ),
            )
        }
    }
}
