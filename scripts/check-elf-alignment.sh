#!/usr/bin/env bash
set -euo pipefail

apk_path="${1:-}"
if [[ -z "$apk_path" || ! -f "$apk_path" ]]; then
  echo "Usage: $0 <apk-path>" >&2
  exit 2
fi

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
unzip -qq "$apk_path" 'lib/*/*.so' -d "$work_dir"

checked=0
while IFS= read -r -d '' library; do
  checked=$((checked + 1))
  while IFS= read -r alignment; do
    if (( alignment < 0x4000 )); then
      echo "Native library is not 16 KB aligned: ${library#"$work_dir"/}" >&2
      exit 1
    fi
  done < <(readelf -lW "$library" | awk '$1 == "LOAD" { print $NF }')
done < <(
  find "$work_dir/lib" -type f -name '*.so' \
    \( -path '*/arm64-v8a/*' -o -path '*/x86_64/*' \) -print0
)

if (( checked == 0 )); then
  echo "No 64-bit native libraries were found in $apk_path" >&2
  exit 1
fi

echo "Verified 16 KB ELF alignment for $checked 64-bit native libraries."
