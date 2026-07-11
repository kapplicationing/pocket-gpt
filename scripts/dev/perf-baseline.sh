#!/usr/bin/env bash
# Captures a typing-frame baseline from dumpsys gfxinfo.
#
# IMPORTANT: This script enforces that PocketGPT is measured on the `benchmark`
# build variant (debuggable=false, no minify, signed), NEVER on `debug`. The
# `debug` build carries a 30-50% Compose recompose tax (no AOT, debug source
# instrumentation, MainThreadGuard + StrictMode overhead) that swamps any
# real-world signal. See docs/architecture/android-performance-contract.md
# for the rationale and the 2026-05-02 root-cause analysis.
#
# Pass --build to (re)build and install the benchmark APK before measuring.
# Pass --allow-debuggable only when intentionally measuring developer builds.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
HARNESS_HELPER="$SCRIPT_DIR/android_perf_harness.py"
source "$SCRIPT_DIR/android-benchmark-profile.sh"
source "$SCRIPT_DIR/android-perf-lock.sh"

ORIGINAL_COMMAND="$0"
for original_arg in "$@"; do
  ORIGINAL_COMMAND+=" $(printf '%q' "$original_arg")"
done

SERIAL="${ANDROID_SERIAL:-}"
PACKAGE="$POCKETGPT_BENCHMARK_PACKAGE"
# Thresholds calibrated against the benchmark variant on a Pixel/Galaxy class
# device after the 2026-05-02 jank investigation. Update only after a full
# perfetto-traced run shows sustained improvement on >=2 distinct runs.
THRESHOLD_JANKY_PCT=20
THRESHOLD_50P_MS=14
THRESHOLD_90P_MS=25
THRESHOLD_99P_MS=32
DO_BUILD=0
ALLOW_DEBUGGABLE=0
WAIT_TIMEOUT_SECONDS=45
REMOTE_UI_XML="/sdcard/pocketgpt-perf-window.xml"
LOCAL_UI_XML="$(mktemp -t pocketgpt-perf-window.XXXXXX.xml)"
OUT_DIR=""
BENCHMARK_INSTALLED_BY_THIS_RUN=0
PERF_LOCK_ACQUIRED=0

cleanup() {
  local exit_code=$?
  rm -f "$LOCAL_UI_XML"
  if [[ "$BENCHMARK_INSTALLED_BY_THIS_RUN" -eq 1 ]]; then
    if ! pocketgpt_uninstall_isolated_benchmark \
      "$SERIAL" "$PACKAGE" "$OUT_DIR/cleanup-uninstall.txt"; then
      if [[ "$exit_code" -eq 0 ]]; then
        exit_code=74
      fi
    fi
  fi
  if [[ "$PERF_LOCK_ACQUIRED" -eq 1 ]] && ! perf_lock_release; then
    if [[ "$exit_code" -eq 0 ]]; then
      exit_code=73
    fi
  fi
  trap - EXIT
  exit "$exit_code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

usage() {
  cat <<EOF
Usage: $0 [--serial SERIAL] [--package PACKAGE] [--build] [--allow-debuggable]
Measures PocketGPT typing-frame jank on the benchmark variant.

  --build              Clean-install/profile/measure the isolated benchmark APK
  --allow-debuggable   Permit measuring a debuggable build (NOT recommended)
EOF
}

adb_shell() {
  adb -s "$SERIAL" shell "$@"
}

wake_and_dismiss_keyguard() {
  adb_shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  adb_shell wm dismiss-keyguard >/dev/null 2>&1 || true
  # Samsung devices often need a menu key after dismiss-keyguard when the screen
  # was dozing. This is harmless when the device is already unlocked.
  adb_shell input keyevent 82 >/dev/null 2>&1 || true
}

dump_ui() {
  adb_shell uiautomator dump "$REMOTE_UI_XML" >/dev/null 2>&1 || return 1
  adb -s "$SERIAL" exec-out cat "$REMOTE_UI_XML" >"$LOCAL_UI_XML" 2>/dev/null || return 1
}

composer_center_from_dump() {
  python3 - "$LOCAL_UI_XML" <<'PY'
import re
import sys

path = sys.argv[1]
try:
    source = open(path, encoding="utf-8", errors="replace").read()
except OSError:
    sys.exit(1)

for node in re.findall(r"<node\b[^>]*>", source):
    if "composer_input" not in node:
        continue
    match = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if not match:
        continue
    left, top, right, bottom = map(int, match.groups())
    print(f"{(left + right) // 2} {(top + bottom) // 2}")
    sys.exit(0)
sys.exit(1)
PY
}

tag_center_from_dump() {
  local tag="$1"
  python3 "$HARNESS_HELPER" center --xml "$LOCAL_UI_XML" --resource-id "$tag"
}

prepare_fresh_install_state() {
  local deadline=$((SECONDS + WAIT_TIMEOUT_SECONDS))
  local coords=""
  local onboarding_dismissed=false

  wake_and_dismiss_keyguard
  adb_shell am force-stop "$PACKAGE" >/dev/null
  adb_shell am start -n "$PACKAGE/$POCKETGPT_MAIN_ACTIVITY_CLASS" >/dev/null
  while (( SECONDS < deadline )); do
    if dump_ui; then
      coords="$(tag_center_from_dump "session_drawer_button" 2>/dev/null || true)"
      if [[ -n "$coords" ]]; then
        printf '{"first_visible_activity_prepared":true,"onboarding_dismissed":%s}\n' \
          "$onboarding_dismissed" >"$OUT_DIR/first-visible-activity-setup.json"
        adb_shell am force-stop "$PACKAGE" >/dev/null
        return 0
      fi
      coords="$(tag_center_from_dump "onboarding_skip" 2>/dev/null || true)"
      if [[ -n "$coords" ]]; then
        read -r x y <<<"$coords"
        adb_shell input tap "$x" "$y" >/dev/null
        onboarding_dismissed=true
      fi
    fi
    sleep 1
  done
  echo "[perf-baseline] fresh benchmark install did not reach the app shell" >&2
  return 1
}

wait_for_composer() {
  local deadline=$((SECONDS + WAIT_TIMEOUT_SECONDS))
  local coords=""
  while (( SECONDS < deadline )); do
    wake_and_dismiss_keyguard
    if dump_ui && grep -q "composer_input" "$LOCAL_UI_XML"; then
      coords="$(composer_center_from_dump || true)"
      if [[ -n "$coords" ]]; then
        printf '%s\n' "$coords"
        return 0
      fi
    fi
    sleep 1
  done

  echo "[perf-baseline] composer_input did not become visible within ${WAIT_TIMEOUT_SECONDS}s." >&2
  echo "[perf-baseline] Current focused window:" >&2
  adb_shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' >&2 || true
  echo "[perf-baseline] Last UI dump excerpt:" >&2
  head -c 4000 "$LOCAL_UI_XML" >&2 || true
  echo >&2
  exit 69
}

clear_composer() {
  # Composer drafts persist across force-stop/start. Clear before resetting
  # gfxinfo so repeated benchmark runs compare the same text/layout cost.
  adb_shell input keycombination 113 29 >/dev/null 2>&1 || true # CTRL+A
  adb_shell input keyevent KEYCODE_DEL >/dev/null 2>&1 || true
}

gfxinfo_dump() {
  local attempt=""
  for attempt in 1 2 3; do
    if DUMP="$(adb_shell dumpsys gfxinfo "$PACKAGE" 2>&1)" && ! grep -q "Failure while dumping the app" <<<"$DUMP"; then
      printf '%s\n' "$DUMP"
      return 0
    fi
    sleep 1
  done
  printf '%s\n' "$DUMP"
  return 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      SERIAL="$2"
      shift 2
      ;;
    --package)
      PACKAGE="$2"
      shift 2
      ;;
    --build)
      DO_BUILD=1
      shift
      ;;
    --allow-debuggable)
      ALLOW_DEBUGGABLE=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "unknown arg $1" >&2
      usage >&2
      exit 64
      ;;
  esac
done

[[ -n "$SERIAL" ]] || {
  echo "ANDROID_SERIAL or --serial required" >&2
  exit 64
}
OUT_DIR="$REPO_ROOT/tmp/perf-baseline/$(date -u +%Y%m%dT%H%M%SZ)-$$"
mkdir -p "$OUT_DIR"
pocketgpt_require_isolated_benchmark_package "$PACKAGE"
perf_lock_acquire "$SERIAL" "$PACKAGE" "$$" "$ORIGINAL_COMMAND"
PERF_LOCK_ACQUIRED=1
echo "[perf-baseline] device lease=$PERF_LOCK_DIR"

if [[ "$DO_BUILD" -eq 1 ]]; then
  echo "[perf-baseline] building :apps:mobile-android:assembleBenchmark"
  (cd "$REPO_ROOT" && ./gradlew --no-daemon -Ppocketgpt.enableNativeBuild=true :apps:mobile-android:assembleBenchmark -q)
  APK="$REPO_ROOT/apps/mobile-android/build/outputs/apk/benchmark/mobile-android-benchmark.apk"
  [[ -f "$APK" ]] || {
    echo "[perf-baseline] benchmark APK not found at $APK" >&2
    exit 66
  }
  pocketgpt_verify_benchmark_apk "$REPO_ROOT" "$APK" "$OUT_DIR"
  BENCHMARK_INSTALLED_BY_THIS_RUN=1
  pocketgpt_activate_benchmark_profile \
    "$SERIAL" "$PACKAGE" "$APK" "$OUT_DIR" "$HARNESS_HELPER"
  prepare_fresh_install_state
fi

adb -s "$SERIAL" shell dumpsys package "$PACKAGE" >"$OUT_DIR/package-dump.txt" 2>&1 || true
python3 "$HARNESS_HELPER" assert-profile-compiled \
  --package-dump "$OUT_DIR/package-dump.txt" \
  --package "$PACKAGE" \
  --expected-status speed-profile \
  --expected-reason cmdline \
  >"$OUT_DIR/profile-compilation-sample-proof.json"

# Refuse to run on a debuggable build unless explicitly opted in. Debug builds
# inflate Compose recompose by ~40% and double-recompose per frame, producing
# misleading numbers that hide real regressions. See the 2026-05-02 RCA.
INSTALLED_FLAGS="$(adb -s "$SERIAL" shell pm dump "$PACKAGE" 2>/dev/null | awk '/pkgFlags=/ {print; exit}')"
if [[ -z "$INSTALLED_FLAGS" ]]; then
  echo "[perf-baseline] $PACKAGE is not installed on $SERIAL. Re-run with --build or install manually." >&2
  exit 67
fi
if echo "$INSTALLED_FLAGS" | grep -q DEBUGGABLE; then
  if [[ "$ALLOW_DEBUGGABLE" -eq 1 ]]; then
    echo "[perf-baseline] WARNING: measuring a DEBUGGABLE build (--allow-debuggable). Numbers will be 30-50% worse than benchmark variant." >&2
  else
    echo "[perf-baseline] refusing to measure DEBUGGABLE build of $PACKAGE." >&2
    echo "[perf-baseline] Install the benchmark variant: $0 --serial $SERIAL --build" >&2
    echo "[perf-baseline] Or pass --allow-debuggable to acknowledge the tax." >&2
    exit 68
  fi
fi

wake_and_dismiss_keyguard
adb_shell am force-stop "$PACKAGE" >/dev/null
adb_shell am start -n "$PACKAGE/$POCKETGPT_MAIN_ACTIVITY_CLASS" >/dev/null
read -r COMPOSER_X COMPOSER_Y < <(wait_for_composer)
adb_shell input tap "$COMPOSER_X" "$COMPOSER_Y" >/dev/null
sleep 1
clear_composer
sleep 1
adb_shell dumpsys gfxinfo "$PACKAGE" reset >/dev/null

for c in P o c k e t T y p i n g B e n c h m a r k T y p i n g T y p i n g; do
  adb_shell input text "$c" >/dev/null
done

sleep 2
DUMP="$(gfxinfo_dump)" || {
  echo "failed to dump gfxinfo frame stats" >&2
  printf '%s\n' "$DUMP" >&2
  exit 65
}
JANKY_PCT="$(
  printf '%s\n' "$DUMP" |
    awk '/Janky frames:/ && !/legacy/ {print $4; exit}' |
    tr -d '%()'
)"
P50="$(
  printf '%s\n' "$DUMP" |
    awk '/50th percentile:/ {print $3; exit}' |
    tr -d 'ms'
)"
P90="$(
  printf '%s\n' "$DUMP" |
    awk '/90th percentile:/ {print $3; exit}' |
    tr -d 'ms'
)"
P99="$(
  printf '%s\n' "$DUMP" |
    awk '/99th percentile:/ {print $3; exit}' |
    tr -d 'ms'
)"

[[ -n "$JANKY_PCT" && -n "$P50" && -n "$P90" && -n "$P99" ]] || {
  echo "failed to parse gfxinfo frame stats" >&2
  printf '%s\n' "$DUMP" >&2
  exit 65
}

if awk -v p="$P50" 'BEGIN { exit !(p + 0 >= 1000) }'; then
  echo "[perf-baseline] invalid harness state: p50=${P50}ms suggests gfxinfo captured a non-interactive/blocked window." >&2
  echo "[perf-baseline] Refusing to compare this run against app performance thresholds." >&2
  exit 70
fi

echo "janky=${JANKY_PCT}% p50=${P50}ms p90=${P90}ms p99=${P99}ms artifacts=${OUT_DIR}"

FAIL=0
fail_if_over() {
  local label="$1"; local value="$2"; local threshold="$3"
  if ! awk -v v="$value" -v t="$threshold" 'BEGIN { exit !(v + 0 <= t + 0) }'; then
    echo "[perf-baseline] FAIL: $label=${value} exceeds threshold ${threshold}" >&2
    FAIL=1
  fi
}
fail_if_over "janky%" "$JANKY_PCT" "$THRESHOLD_JANKY_PCT"
fail_if_over "p50ms" "$P50" "$THRESHOLD_50P_MS"
fail_if_over "p90ms" "$P90" "$THRESHOLD_90P_MS"
fail_if_over "p99ms" "$P99" "$THRESHOLD_99P_MS"

exit $FAIL
