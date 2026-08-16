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

apk_size_bytes="$(stat -c '%s' "$apk_path")"
max_apk_size_bytes=$((75 * 1024 * 1024))
if (( apk_size_bytes > max_apk_size_bytes )); then
  echo "APK exceeds the 75 MiB per-ABI size budget: $apk_size_bytes bytes" >&2
  exit 1
fi

echo "No FFmpeg, libav, or aria2 binaries are packaged."
du -h "$apk_path"
