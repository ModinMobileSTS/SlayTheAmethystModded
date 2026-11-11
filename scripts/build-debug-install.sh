#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
GRADLE_BIN="${GRADLE_BIN:-$PROJECT_DIR/gradlew}"
ADB_BIN="${ADB_BIN:-adb}"
DEVICE_SERIAL="${1:-}"

case "$DEVICE_SERIAL" in
    ""|[aA][uU][tT][oO]|[dD][eE][fF][aA][uU][lL][tT]|[aA][lL][lL]|\*|-*|*[[:space:]]*)
        printf 'An explicit, user-approved device serial is required; automatic selection is forbidden.\n' >&2
        printf 'Usage: %s <device-serial>\n' "$0" >&2
        exit 1
        ;;
esac

if [[ ! -x "$GRADLE_BIN" ]]; then
    printf 'Gradle wrapper is not executable: %s\n' "$GRADLE_BIN" >&2
    printf 'Run: chmod +x %q\n' "$GRADLE_BIN" >&2
    exit 1
fi

if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
    printf 'adb was not found. Set ADB_BIN to its full path if needed.\n' >&2
    exit 1
fi

adb_args=(-s "$DEVICE_SERIAL")

printf 'Building debug APK...\n'
(
    cd "$PROJECT_DIR"
    "$GRADLE_BIN" :app:assembleDebug
)

shopt -s nullglob
apk_files=("$PROJECT_DIR"/app/build/outputs/apk/debug/*.apk)
shopt -u nullglob

if [[ "${#apk_files[@]}" -ne 1 ]]; then
    printf 'Expected exactly one debug APK, found %s.\n' "${#apk_files[@]}" >&2
    printf 'APK output directory: %s\n' "$PROJECT_DIR/app/build/outputs/apk/debug" >&2
    exit 1
fi

APK_PATH="${apk_files[0]}"
printf 'Installing %s...\n' "$APK_PATH"
"$ADB_BIN" "${adb_args[@]}" install --fastdeploy -r "$APK_PATH"
printf 'Debug APK installed successfully on %s.\n' "$DEVICE_SERIAL"
