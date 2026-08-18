from pathlib import Path
import runpy

runpy.run_path('tools/apply-facebook-progress-fixes.py', run_name='__main__')

path = Path('app/src/main/java/com/anas_mugally/videodownloader/download/DownloadService.kt')
text = path.read_text(encoding='utf-8')
old = '                        aggregateProgressOffsetBytes = video.length()'
new = '                        aggregateProgressOffsetBytes = requireNotNull(video).length()'
if old not in text:
    raise SystemExit('Expected nullable video progress line was not generated')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
