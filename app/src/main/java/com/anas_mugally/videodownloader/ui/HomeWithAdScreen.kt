package com.anas_mugally.videodownloader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
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
    val layoutDirection = LocalLayoutDirection.current
    val showAd = state.media != null && ad != null
    val homePadding = if (showAd) {
        PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            top = contentPadding.calculateTopPadding(),
            end = contentPadding.calculateEndPadding(layoutDirection),
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
