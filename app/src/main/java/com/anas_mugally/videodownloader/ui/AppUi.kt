package com.anas_mugally.videodownloader.ui

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anas_mugally.videodownloader.MainActivity
import com.anas_mugally.videodownloader.R
import com.anas_mugally.videodownloader.domain.DownloadTask
import kotlinx.coroutines.flow.collectLatest

data class AppLaunchData(
    val screen: String? = null,
    val taskId: String? = null,
    val play: Boolean = false,
    val sequence: Long = 0L,
)

private enum class AppDestination(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
) {
    HOME(R.string.home, R.drawable.ic_home),
    DOWNLOADS(R.string.downloads, R.drawable.ic_download),
    SETTINGS(R.string.settings, R.drawable.ic_settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllVideoDownloaderApp(
    launchData: AppLaunchData,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var destination by remember { mutableStateOf(AppDestination.HOME) }
    var playingTaskId by remember { mutableStateOf<String?>(null) }
    var enqueueAfterPermission by remember { mutableStateOf(false) }
    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsGranted = granted
        if (enqueueAfterPermission) {
            enqueueAfterPermission = false
            viewModel.enqueueSelectedDownload()
            destination = AppDestination.DOWNLOADS
        }
    }
    val cookiesDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(viewModel::importCookies)
    }

    LaunchedEffect(launchData.sequence) {
        if (launchData.screen == MainActivity.SCREEN_DOWNLOADS) {
            destination = AppDestination.DOWNLOADS
        }
        if (launchData.play) playingTaskId = launchData.taskId
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            snackbarHostState.showSnackbar(context.getString(event.message))
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
            destination = AppDestination.DOWNLOADS
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        if (destination == AppDestination.HOME) {
                            context.getString(R.string.app_name)
                        } else {
                            context.getString(destination.label)
                        },
                    )
                },
                actions = {
                    if (destination == AppDestination.HOME) {
                        IconButton(onClick = { destination = AppDestination.SETTINGS }) {
                            Icon(
                                painterResource(R.drawable.ic_settings),
                                contentDescription = context.getString(R.string.settings),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                AppDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = { Icon(painterResource(item.icon), contentDescription = null) },
                        label = { Text(context.getString(item.label)) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (destination) {
            AppDestination.HOME -> HomeScreen(
                contentPadding = padding,
                state = state,
                engineState = engineState,
                onUrlChange = viewModel::setUrl,
                onAnalyze = viewModel::analyze,
                onKindSelected = viewModel::selectKind,
                onFormatSelected = viewModel::selectFormat,
                onDownload = requestDownload,
            )

            AppDestination.DOWNLOADS -> DownloadsScreen(
                contentPadding = padding,
                tasks = tasks,
                onPause = viewModel::pause,
                onResume = viewModel::resume,
                onRetry = viewModel::retry,
                onCancel = viewModel::cancel,
                onDelete = viewModel::delete,
                onClearFinished = viewModel::clearFinished,
                onPlay = { task -> playingTaskId = task.id },
                onShare = { task -> shareDownloadedMedia(context, task) },
            )

            AppDestination.SETTINGS -> SettingsScreen(
                contentPadding = padding,
                settings = settings,
                engineState = engineState,
                cookiesImported = state.cookiesImported,
                notificationsGranted = notificationsGranted,
                onWifiOnlyChanged = viewModel::setWifiOnly,
                onDynamicColorChanged = viewModel::setDynamicColor,
                onThemeModeChanged = viewModel::setThemeMode,
                onOutputFolderSaved = viewModel::setOutputFolder,
                onFileNameModeChanged = viewModel::setFileNameMode,
                onImportCookies = { cookiesDocument.launch(arrayOf("text/plain", "text/*")) },
                onClearCookies = viewModel::clearCookies,
                onRequestNotifications = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
        }
    }

    val playerTask = playingTaskId?.let { id -> tasks.firstOrNull { it.id == id } }
    if (playerTask?.outputUri != null) {
        PlayerDialog(task = playerTask, onDismiss = { playingTaskId = null })
    }
}

private fun shareDownloadedMedia(context: Context, task: DownloadTask) {
    val uri = task.outputUri?.let(Uri::parse) ?: return
    val share = Intent(Intent.ACTION_SEND).apply {
        type = task.outputMimeType ?: "*/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, task.outputName ?: task.title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(share, context.getString(R.string.share_download)),
    )
}
