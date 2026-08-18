from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'Patch point not found in {path}: {old[:120]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')

replace_once(
    'app/src/main/java/com/anas_mugally/videodownloader/ui/DownloadsScreen.kt',
    '''                DownloadStatus.FAILED -> task.error?.let { error ->
                    Text(
                        text = error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
''',
    '''                DownloadStatus.FAILED -> {
                    Text(
                        text = stringResource(R.string.download_failed_user),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
''',
)

# Once both sources are on disk, display the actual combined transferred size
# while the final processing phase runs instead of retaining an estimate.
replace_once(
    'app/src/main/java/com/anas_mugally/videodownloader/download/DownloadService.kt',
    '''                        transferredBytes,
                        expectedTotal ?: transferredBytes,
                        0L,
                        0L,
''',
    '''                        transferredBytes,
                        transferredBytes,
                        0L,
                        0L,
''',
)

replace_once(
    'app/build.gradle.kts',
    'versionCode = 12\n        versionName = "1.6.3"',
    'versionCode = 13\n        versionName = "1.6.4"',
)
