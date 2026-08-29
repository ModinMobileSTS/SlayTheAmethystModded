#!/usr/bin/env bash
# perf-loop.sh — one-command perf bench with Arthas flush-spike analysis
#
# Usage:
#   ./scripts/perf-loop.sh [options] [DEVICE_SERIAL]
#
# Options:
#   --skip-build          Skip gradlew assembleDebug + mod build
#   --skip-install        Skip APK install (implies --skip-build)
#   --update-baseline     Overwrite perf_bench_baseline.json with this run
#   --timeout <sec>       Game run timeout in seconds (default: 360)
#   --connector-port <p>  Connector daemon port (default: 19876)
#   --character <id>      Autoplay character (default: IRONCLAD)
#   -h / --help           Print this help
#
# Environment variables (all optional):
#   STS_TEST_DEVICE       ADB device serial (overridden by positional arg)
#   STS_CONNECTOR_PORT    Connector port (overridden by --connector-port)
#   GRADLE_BIN            Path to gradlew (default: ./gradlew)
#
# Examples:
#   ./scripts/perf-loop.sh
#   ./scripts/perf-loop.sh localhost:15555
#   ./scripts/perf-loop.sh --skip-install --update-baseline localhost:15555
#   ./scripts/perf-loop.sh --timeout 480 --character DEFECT
#
# Output:
#   Artifacts written to agent-tmp/perf-bench-<timestamp>/
#   Terminal output: perf summary table + flush-spike caller table

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# ── Defaults ────────────────────────────────────────────────────────────────
GRADLE_BIN="${GRADLE_BIN:-$PROJECT_DIR/gradlew}"
SKIP_BUILD=0
SKIP_INSTALL=0
UPDATE_BASELINE=0
TIMEOUT_SEC=360
CONNECTOR_PORT="${STS_CONNECTOR_PORT:-19876}"
DEVICE_SERIAL="${STS_TEST_DEVICE:-}"
CHARACTER="IRONCLAD"

# ── Argument parsing ─────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-build)      SKIP_BUILD=1;        shift ;;
        --skip-install)    SKIP_INSTALL=1; SKIP_BUILD=1; shift ;;
        --update-baseline) UPDATE_BASELINE=1;   shift ;;
        --timeout)         TIMEOUT_SEC="$2";    shift 2 ;;
        --connector-port)  CONNECTOR_PORT="$2"; shift 2 ;;
        --character)       CHARACTER="$2";      shift 2 ;;
        -h|--help)
            sed -n '/^# perf-loop/,/^[^#]/p' "$0" | grep '^#' | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        -*)
            printf 'Unknown option: %s\n' "$1" >&2; exit 1 ;;
        *)
            DEVICE_SERIAL="$1"; shift ;;
    esac
done

export STS_CONNECTOR_PORT="$CONNECTOR_PORT"
if [[ -n "$DEVICE_SERIAL" ]]; then
    export STS_TEST_DEVICE="$DEVICE_SERIAL"
fi

# ── Preflight ────────────────────────────────────────────────────────────────
if [[ ! -x "$GRADLE_BIN" ]]; then
    printf '[perf-loop] gradlew not executable: %s\n' "$GRADLE_BIN" >&2
    printf '            Run: chmod +x %q\n' "$GRADLE_BIN" >&2
    exit 1
fi

cd "$PROJECT_DIR"

# ── Step 1: Build (mod JAR + APK) ───────────────────────────────────────────
if [[ "$SKIP_BUILD" -eq 0 ]]; then
    printf '\n[perf-loop] Building mod JAR and debug APK...\n'
    "$GRADLE_BIN" \
        :mods:amethyst-runtime-compat:jar \
        :app:assembleDebug
fi

# ── Step 2: Connector daemon ─────────────────────────────────────────────────
printf '\n[perf-loop] Ensuring connector daemon on port %s...\n' "$CONNECTOR_PORT"
python3 -m scripts.tools.connector start --port "$CONNECTOR_PORT" 2>&1 || true

# ── Step 3: Assemble harness arguments ───────────────────────────────────────
HARNESS_ARGS=(
    sts-harness
    -Command perf-bench
    -ConnectorPort "$CONNECTOR_PORT"
    -TimeoutSeconds "$TIMEOUT_SEC"
    -SingleRoomCharacter "$CHARACTER"
    -LaunchMode mts_basemod
)

if [[ "$SKIP_INSTALL" -eq 1 ]]; then
    HARNESS_ARGS+=(-SkipInstall)
fi

if [[ "$UPDATE_BASELINE" -eq 1 ]]; then
    HARNESS_ARGS+=(-UpdateBaseline)
fi

if [[ -n "$DEVICE_SERIAL" ]]; then
    HARNESS_ARGS+=(-DeviceSerial "$DEVICE_SERIAL")
fi

# ── Step 4: Run perf-bench (includes Arthas trace automatically) ─────────────
printf '\n[perf-loop] Running perf-bench (timeout %ss, character %s)...\n' \
    "$TIMEOUT_SEC" "$CHARACTER"
printf '[perf-loop] Args: %s\n\n' "${HARNESS_ARGS[*]}"

python3 scripts/tools/main.py "${HARNESS_ARGS[@]}"

printf '\n[perf-loop] Done.\n'
