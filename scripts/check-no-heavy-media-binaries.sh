#!/usr/bin/env bash
set -euo pipefail

apk_path="${1:?Usage: check-no-heavy-media-binaries.sh <apk>}"
if [[ ! -f "$apk_path" ]]; then
  echo "APK not found: $apk_path" >&2
  exit 1
fi

archive_listing="$(unzip -Z1 "$apk_path")"
for forbidden_name in ffmpeg aria2 libavcodec libavdevice libavfilter libavformat libavutil libswresample libswscale; do
  if grep -Fqi "$forbidden_name" <<<"$archive_listing"; then
    echo "Forbidden heavy media binary found in APK: $forbidden_name" >&2
    grep -Fi "$forbidden_name" <<<"$archive_listing" >&2
    exit 1
  fi
done

echo "No FFmpeg, libav, or aria2 binaries are packaged."
du -h "$apk_path"
