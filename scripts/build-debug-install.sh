#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
GRADLE_BIN="${GRADLE_BIN:-$PROJECT_DIR/gradlew}"
DEVICE_SERIAL="${1:-}"

if [[ "$#" -ne 1 ]]; then
    printf 'Exactly one explicit, user-approved device target is required.\n' >&2
    printf 'Usage: %s <device-host|host:port>\n' "$0" >&2
    exit 1
fi

case "$DEVICE_SERIAL" in
    ""|[aA][uU][tT][oO]|[dD][eE][fF][aA][uU][lL][tT]|[aA][lL][lL]|\*|-*|*[[:space:]]*)
        printf 'An explicit, user-approved device serial is required; automatic selection is forbidden.\n' >&2
        printf 'Usage: %s <device-host|host:port>\n' "$0" >&2
        exit 1
        ;;
esac

if [[ ! -x "$GRADLE_BIN" ]]; then
    printf 'Gradle wrapper is not executable: %s\n' "$GRADLE_BIN" >&2
    printf 'Run: chmod +x %q\n' "$GRADLE_BIN" >&2
    exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
    printf 'python3 is required for the project-local ADB connection.\n' >&2
    exit 1
fi

cd "$PROJECT_DIR"
# Resolve only the explicitly named device. A bare host may borrow its configured
# wireless port, but must never select a different host from local configuration.
DEVICE_SERIAL="$(python3 - "$DEVICE_SERIAL" <<'PY'
import sys
from scripts.tools.local_adb import config

try:
    endpoint = config()["STS_ADB_DEVICE_ENDPOINT"]
    host = endpoint.rpartition(":")[0]
    if sys.argv[1] not in (endpoint, host):
        raise RuntimeError("Requested device does not match STS_ADB_DEVICE_ENDPOINT in local configuration")
    print(endpoint)
except (RuntimeError, ValueError, OSError) as error:
    sys.exit(f"Local ADB configuration error: {error}")
PY
)"
# Pin the approved endpoint across build/install, even if .env.local changes.
export STS_ADB_DEVICE_ENDPOINT="$DEVICE_SERIAL"

printf 'Checking project-local ADB connection, then building debug APK...\n'
python3 -m scripts.tools.local_adb run -- "$GRADLE_BIN" :app:assembleDebug

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
python3 -m scripts.tools.local_adb adb -- install --fastdeploy -r "$APK_PATH"
printf 'Debug APK installed successfully on %s.\n' "$DEVICE_SERIAL"
