#!/usr/bin/env bash
set -euo pipefail

apk_path="${1:?Usage: check-python-runtime-abi.sh <apk> <abi> <ELF32|ELF64> <machine-pattern>}"
abi="${2:?Missing ABI}"
expected_class="${3:?Missing ELF class}"
expected_machine="${4:?Missing machine pattern}"

if [[ ! -f "$apk_path" ]]; then
  echo "APK not found: $apk_path" >&2
  exit 1
fi

work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT

unzip -q "$apk_path" "lib/$abi/libpython.zip.so" -d "$work_dir"
python_archive="$work_dir/lib/$abi/libpython.zip.so"
unzip -q "$python_archive" "usr/lib/libcrypto.so.3" -d "$work_dir/python"
crypto_library="$work_dir/python/usr/lib/libcrypto.so.3"

header="$(readelf -h "$crypto_library")"
if ! grep -Eq "Class:[[:space:]]+$expected_class" <<<"$header"; then
  echo "Python runtime in $apk_path has the wrong ELF class; expected $expected_class" >&2
  exit 1
fi
if ! grep -Eq "Machine:[[:space:]]+$expected_machine" <<<"$header"; then
  echo "Python runtime in $apk_path has the wrong machine; expected $expected_machine" >&2
  exit 1
fi

echo "Verified $abi Python runtime: $expected_class / $expected_machine"
