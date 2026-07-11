#!/usr/bin/env bash
# Captures benchmark-variant frame stats for non-generation UI interactions.
#
# This is the focused companion to perf-baseline.sh. Use it for settings,
# model-library, and drawer smoothness checks without running a full E2E lane.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
HARNESS_HELPER="$SCRIPT_DIR/android_perf_harness.py"
source "$SCRIPT_DIR/android-perf-lock.sh"

ORIGINAL_COMMAND="$0"
for original_arg in "$@"; do
  ORIGINAL_COMMAND+=" $(printf '%q' "$original_arg")"
done

SERIAL="${ANDROID_SERIAL:-}"
PACKAGE="com.pocketagent.android"
SCENARIO=""
DO_BUILD=0
ALLOW_DEBUGGABLE=0
OUT_DIR=""
DECLARED_RUNTIME_CONDITION=""
DECLARED_DOWNLOAD_CONDITION=""
DECLARED_VOICE_CONDITION=""
PARENT_LOCK_TOKEN=""
BUILD_SOURCE="preinstalled-nondebuggable"
BUILD_VARIANT="unverified-nondebuggable"
NATIVE_RUNTIME_PACKAGED_JSON="null"
DEBUGGABLE=0
STARTED_AT_UTC=""
REMOTE_UI_XML="/sdcard/pocketgpt-perf-interaction.xml"
LOCAL_UI_XML="$(mktemp -t pocketgpt-perf-interaction.XXXXXX.xml)"

cleanup() {
  local exit_code=$?
  rm -f "$LOCAL_UI_XML"
  if ! perf_lock_release; then
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
Usage: $0 --scenario settings-nav|model-sheet|drawer-search --runtime-state unloaded|loading|loaded-idle --download-state idle|active --voice-state inactive|active [--serial SERIAL] [--build] [--allow-debuggable] [--out-dir DIR]

Measures PocketGPT non-generation UI frame stats on the benchmark variant.
For acceptance evidence, use perf-interaction-gate.sh to capture and evaluate
three samples. This command captures one diagnostic sample. The runtime,
download, and voice flags are operator declarations checked for consistency;
they are not observed application state.
EOF
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
    --scenario)
      SCENARIO="$2"
      shift 2
      ;;
    --runtime-state)
      DECLARED_RUNTIME_CONDITION="$2"
      shift 2
      ;;
    --download-state)
      DECLARED_DOWNLOAD_CONDITION="$2"
      shift 2
      ;;
    --voice-state)
      DECLARED_VOICE_CONDITION="$2"
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
    --out-dir)
      OUT_DIR="$2"
      shift 2
      ;;
    --lock-token)
      PARENT_LOCK_TOKEN="$2"
      shift 2
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
case "$SCENARIO" in
  settings-nav|model-sheet|drawer-search) ;;
  *)
    echo "--scenario must be settings-nav, model-sheet, or drawer-search" >&2
    usage >&2
    exit 64
    ;;
esac
case "$DECLARED_RUNTIME_CONDITION" in
  unloaded|loading|loaded-idle) ;;
  *)
    echo "--runtime-state must be unloaded, loading, or loaded-idle" >&2
    exit 64
    ;;
esac
case "$DECLARED_DOWNLOAD_CONDITION" in
  idle|active) ;;
  *)
    echo "--download-state must be idle or active" >&2
    exit 64
    ;;
esac
case "$DECLARED_VOICE_CONDITION" in
  inactive|active) ;;
  *)
    echo "--voice-state must be inactive or active" >&2
    exit 64
    ;;
esac

if [[ -z "$OUT_DIR" ]]; then
  RUN_ID="$(python3 "$HARNESS_HELPER" run-id --pid "$$")"
  OUT_DIR="$REPO_ROOT/tmp/perf-interaction/${RUN_ID}-${SCENARIO}"
fi
mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"

if [[ -n "$PARENT_LOCK_TOKEN" ]]; then
  perf_lock_validate "$SERIAL" "$PACKAGE" "$PARENT_LOCK_TOKEN"
else
  perf_lock_acquire "$SERIAL" "$PACKAGE" "$$" "$ORIGINAL_COMMAND"
  echo "[perf-interaction] device lease=$PERF_LOCK_DIR"
fi

adb_shell() {
  adb -s "$SERIAL" shell "$@"
}

wake_and_dismiss_keyguard() {
  adb_shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  adb_shell wm dismiss-keyguard >/dev/null 2>&1 || true
  adb_shell input keyevent 82 >/dev/null 2>&1 || true
  adb_shell cmd statusbar collapse >/dev/null 2>&1 || true
}

wait_for_app_foreground() {
  local deadline=$((SECONDS + 30))
  local focus_file="$OUT_DIR/window-focus-launch.txt"
  while (( SECONDS < deadline )); do
    adb_shell dumpsys window >"$focus_file" 2>&1 || true
    if python3 "$HARNESS_HELPER" assert-foreground \
      --window "$focus_file" \
      --package "$PACKAGE" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "[perf-interaction] $PACKAGE did not become foreground after launch." >&2
  cat "$focus_file" >&2 2>/dev/null || true
  exit 71
}

dump_ui() {
  adb_shell uiautomator dump "$REMOTE_UI_XML" >/dev/null 2>&1 || return 1
  adb -s "$SERIAL" exec-out cat "$REMOTE_UI_XML" >"$LOCAL_UI_XML" 2>/dev/null || return 1
}

tag_center_from_dump() {
  local tag="$1"
  python3 "$HARNESS_HELPER" center \
    --xml "$LOCAL_UI_XML" \
    --resource-id "$tag"
}

wait_tag() {
  local tag="$1"
  local deadline=$((SECONDS + 30))
  local coords=""
  while (( SECONDS < deadline )); do
    if dump_ui; then
      coords="$(tag_center_from_dump "$tag" || true)"
      if [[ -n "$coords" ]]; then
        return 0
      fi
    fi
    sleep 1
  done
  echo "[perf-interaction] tag not found: $tag" >&2
  cp "$LOCAL_UI_XML" "$OUT_DIR/last-ui.xml" 2>/dev/null || true
  exit 69
}

tap_tag() {
  local tag="$1"
  local deadline=$((SECONDS + 30))
  local coords=""
  while (( SECONDS < deadline )); do
    if dump_ui; then
      coords="$(tag_center_from_dump "$tag" || true)"
      if [[ -n "$coords" ]]; then
        read -r x y <<<"$coords"
        adb_shell input tap "$x" "$y" >/dev/null
        return 0
      fi
    fi
    sleep 1
  done
  echo "[perf-interaction] tag not found: $tag" >&2
  cp "$LOCAL_UI_XML" "$OUT_DIR/last-ui.xml" 2>/dev/null || true
  exit 69
}

gfxinfo_dump() {
  local dump=""
  for _ in 1 2 3; do
    if dump="$(adb_shell dumpsys gfxinfo "$PACKAGE" 2>&1)" && ! grep -q "Failure while dumping the app" <<<"$dump"; then
      printf '%s\n' "$dump"
      return 0
    fi
    sleep 1
  done
  printf '%s\n' "$dump"
  return 1
}

parse_metric() {
  local label="$1"
  local dump_file="$2"
  case "$label" in
    janky)
      awk '/Janky frames:/ && !/legacy/ {print $4; exit}' "$dump_file" | tr -d '%()'
      ;;
    p50)
      awk '/50th percentile:/ {print $3; exit}' "$dump_file" | tr -d 'ms'
      ;;
    p90)
      awk '/90th percentile:/ {print $3; exit}' "$dump_file" | tr -d 'ms'
      ;;
    p99)
      awk '/99th percentile:/ {print $3; exit}' "$dump_file" | tr -d 'ms'
      ;;
  esac
}

type_text_slowly() {
  local text="$1"
  local i=""
  local char=""
  for (( i = 0; i < ${#text}; i++ )); do
    char="${text:i:1}"
    adb_shell input text "$char" >/dev/null
  done
}

clear_text_field() {
  local tag="$1"
  local text_length=""
  local cleared_length=""
  local i=0
  local keycodes=()

  dump_ui || {
    echo "[perf-interaction] failed to inspect text field before clearing: $tag" >&2
    exit 70
  }
  text_length="$(python3 "$HARNESS_HELPER" text-length \
    --xml "$LOCAL_UI_XML" \
    --resource-id "$tag")"
  if ! [[ "$text_length" =~ ^[0-9]+$ ]] || (( text_length > 4096 )); then
    echo "[perf-interaction] invalid text length for $tag: $text_length" >&2
    exit 70
  fi
  if (( text_length == 0 )); then
    return 0
  fi

  adb_shell input keyevent KEYCODE_MOVE_END >/dev/null
  for (( i = 0; i < text_length; i++ )); do
    keycodes+=(KEYCODE_DEL)
    if (( ${#keycodes[@]} == 64 || i + 1 == text_length )); then
      adb_shell input keyevent "${keycodes[@]}" >/dev/null
      keycodes=()
    fi
  done
  sleep 1
  dump_ui || {
    echo "[perf-interaction] failed to inspect text field after clearing: $tag" >&2
    exit 70
  }
  cleared_length="$(python3 "$HARNESS_HELPER" text-length \
    --xml "$LOCAL_UI_XML" \
    --resource-id "$tag")"
  if [[ "$cleared_length" != "0" ]]; then
    echo "[perf-interaction] $tag did not clear exactly; remaining length=$cleared_length" >&2
    exit 70
  fi
}

prepare_scenario_input() {
  case "$SCENARIO" in
    settings-nav)
      tap_tag "completion_settings_button"
      ;;
    model-sheet)
      tap_tag "open_model_library"
      ;;
    drawer-search)
      tap_tag "session_drawer_button"
      ;;
  esac
  tap_tag "$FINAL_INPUT_TAG"
  clear_text_field "$FINAL_INPUT_TAG"
  adb_shell input keyevent KEYCODE_BACK >/dev/null
  sleep 1
  adb_shell input keyevent KEYCODE_BACK >/dev/null
  sleep 1

  # Relaunch after preconditioning so every measured journey starts from the
  # same chat surface with an empty target, including persisted system prompts.
  adb_shell am force-stop "$PACKAGE" >/dev/null
  adb_shell am start -n "$PACKAGE/.MainActivity" >/dev/null
  wait_for_app_foreground
  wait_tag "composer_input"
  sleep 1
}

capture_device_state_snapshot() {
  local phase="$1"
  adb_shell dumpsys display >"$OUT_DIR/display-${phase}.txt" 2>&1 || true
  {
    adb_shell cmd thermalservice get-current-status 2>/dev/null || true
    adb_shell dumpsys thermalservice 2>/dev/null || true
  } >"$OUT_DIR/thermal-${phase}.txt"
  adb_shell dumpsys battery >"$OUT_DIR/battery-${phase}.txt" 2>&1 || true
  {
    printf 'peak_refresh_rate='
    adb_shell settings get system peak_refresh_rate 2>/dev/null || true
    printf 'min_refresh_rate='
    adb_shell settings get system min_refresh_rate 2>/dev/null || true
    printf 'user_refresh_rate='
    adb_shell settings get system user_refresh_rate 2>/dev/null || true
  } >"$OUT_DIR/refresh-settings-${phase}.txt"
}

if [[ "$DO_BUILD" -eq 1 ]]; then
  echo "[perf-interaction] building native-enabled :apps:mobile-android:assembleBenchmark"
  (cd "$REPO_ROOT" && ./gradlew --no-daemon -Ppocketgpt.enableNativeBuild=true :apps:mobile-android:assembleBenchmark -q)
  APK="$REPO_ROOT/apps/mobile-android/build/outputs/apk/benchmark/mobile-android-benchmark.apk"
  [[ -f "$APK" ]] || {
    echo "[perf-interaction] benchmark APK not found at $APK" >&2
    exit 66
  }
  unzip -Z1 "$APK" >"$OUT_DIR/apk-entries.txt"
  grep -Fxq 'lib/arm64-v8a/libpocket_llama.so' "$OUT_DIR/apk-entries.txt" || {
    echo "[perf-interaction] benchmark APK is missing lib/arm64-v8a/libpocket_llama.so" >&2
    exit 66
  }
  adb -s "$SERIAL" install -r "$APK" >/dev/null
  BUILD_SOURCE="assembled-native-benchmark"
  BUILD_VARIANT="benchmark"
  NATIVE_RUNTIME_PACKAGED_JSON="true"
fi

PACKAGE_DUMP="$(adb -s "$SERIAL" shell dumpsys package "$PACKAGE" 2>/dev/null || true)"
printf '%s\n' "$PACKAGE_DUMP" >"$OUT_DIR/package-dump.txt"
adb -s "$SERIAL" shell pm path "$PACKAGE" >"$OUT_DIR/package-paths.txt" 2>&1 || true
INSTALLED_FLAGS="$(awk '/pkgFlags=/ {print; exit}' <<<"$PACKAGE_DUMP")"
if [[ -z "$INSTALLED_FLAGS" ]]; then
  echo "[perf-interaction] $PACKAGE is not installed on $SERIAL. Re-run with --build or install manually." >&2
  exit 67
fi
if echo "$INSTALLED_FLAGS" | grep -Eq '(^|[^[:alnum:]_])DEBUGGABLE([^[:alnum:]_]|$)'; then
  DEBUGGABLE=1
  if [[ "$ALLOW_DEBUGGABLE" -eq 1 ]]; then
    BUILD_SOURCE="debuggable-allowed"
    echo "[perf-interaction] WARNING: measuring DEBUGGABLE build." >&2
  else
    echo "[perf-interaction] refusing to measure DEBUGGABLE build. Use --build for benchmark variant." >&2
    exit 68
  fi
fi
VERSION_CODE="$(sed -n 's/.*versionCode=\([^ ]*\).*/\1/p' <<<"$PACKAGE_DUMP" | head -n 1 | tr -d '\r')"
VERSION_NAME="$(sed -n 's/^[[:space:]]*versionName=//p' <<<"$PACKAGE_DUMP" | head -n 1 | tr -d '\r')"
LAST_UPDATE_TIME="$(sed -n 's/^[[:space:]]*lastUpdateTime=//p' <<<"$PACKAGE_DUMP" | head -n 1 | tr -d '\r')"
INSTALLED_APK_PATH="$(sed -n 's/^package://p' "$OUT_DIR/package-paths.txt" | head -n 1 | tr -d '\r')"
if [[ -z "$VERSION_CODE" || -z "$VERSION_NAME" || -z "$LAST_UPDATE_TIME" || -z "$INSTALLED_APK_PATH" ]]; then
  echo "[perf-interaction] failed to read stable package identity for $PACKAGE on $SERIAL" >&2
  exit 67
fi
python3 "$HARNESS_HELPER" assert-package-stable \
  --before-dump "$OUT_DIR/package-dump.txt" \
  --before-paths "$OUT_DIR/package-paths.txt" \
  --after-dump "$OUT_DIR/package-dump.txt" \
  --after-paths "$OUT_DIR/package-paths.txt" \
  >"$OUT_DIR/package-identity-before.json"

adb_shell getprop >"$OUT_DIR/device-properties.txt" 2>&1 || true
printf 'declared_runtime_condition=%s\ndeclared_download_condition=%s\ndeclared_voice_condition=%s\n' \
  "$DECLARED_RUNTIME_CONDITION" "$DECLARED_DOWNLOAD_CONDITION" "$DECLARED_VOICE_CONDITION" \
  >"$OUT_DIR/declared-conditions.txt"
IFS=$'\t' read -r FINAL_INPUT_TAG FINAL_INPUT_TEXT < <(
  python3 "$HARNESS_HELPER" scenario-state --scenario "$SCENARIO"
)
wake_and_dismiss_keyguard
adb_shell am force-stop "$PACKAGE" >/dev/null
adb_shell am start -n "$PACKAGE/.MainActivity" >/dev/null
wait_for_app_foreground
wait_tag "composer_input"
sleep 1
prepare_scenario_input
STARTED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
capture_device_state_snapshot before
adb_shell dumpsys gfxinfo "$PACKAGE" reset >/dev/null

case "$SCENARIO" in
  settings-nav)
    tap_tag "advanced_sheet_button"
    sleep 1
    adb_shell input swipe 500 1700 500 650 500 >/dev/null
    sleep 1
    adb_shell input keyevent KEYCODE_BACK >/dev/null
    sleep 1
    tap_tag "completion_settings_button"
    tap_tag "$FINAL_INPUT_TAG"
    clear_text_field "$FINAL_INPUT_TAG"
    type_text_slowly "$FINAL_INPUT_TEXT"
    adb_shell input keyevent KEYCODE_BACK >/dev/null
    ;;
  model-sheet)
    tap_tag "open_model_library"
    tap_tag "$FINAL_INPUT_TAG"
    clear_text_field "$FINAL_INPUT_TAG"
    type_text_slowly "$FINAL_INPUT_TEXT"
    adb_shell input swipe 500 1700 500 650 500 >/dev/null
    ;;
  drawer-search)
    tap_tag "session_drawer_button"
    tap_tag "$FINAL_INPUT_TAG"
    clear_text_field "$FINAL_INPUT_TAG"
    type_text_slowly "$FINAL_INPUT_TEXT"
    adb_shell input swipe 500 1700 500 650 500 >/dev/null
    ;;
esac

sleep 2
capture_device_state_snapshot after
dump_ui || {
  echo "[perf-interaction] failed to capture final UI hierarchy" >&2
  exit 70
}
cp "$LOCAL_UI_XML" "$OUT_DIR/final-ui.xml"
python3 "$HARNESS_HELPER" assert-scenario-final \
  --xml "$OUT_DIR/final-ui.xml" \
  --scenario "$SCENARIO"

adb -s "$SERIAL" shell dumpsys package "$PACKAGE" >"$OUT_DIR/package-dump-after.txt" 2>&1 || true
adb -s "$SERIAL" shell pm path "$PACKAGE" >"$OUT_DIR/package-paths-after.txt" 2>&1 || true
python3 "$HARNESS_HELPER" assert-package-stable \
  --before-dump "$OUT_DIR/package-dump.txt" \
  --before-paths "$OUT_DIR/package-paths.txt" \
  --after-dump "$OUT_DIR/package-dump-after.txt" \
  --after-paths "$OUT_DIR/package-paths-after.txt" \
  >"$OUT_DIR/package-identity-after.json"

adb_shell dumpsys window >"$OUT_DIR/window-focus-after.txt" 2>&1 || true
python3 "$HARNESS_HELPER" assert-foreground \
  --window "$OUT_DIR/window-focus-after.txt" \
  --package "$PACKAGE" \
  >"$OUT_DIR/window-focus-after.json"

RAW_DUMP="$OUT_DIR/gfxinfo.txt"
gfxinfo_dump >"$RAW_DUMP" || {
  echo "[perf-interaction] failed to dump gfxinfo" >&2
  exit 65
}

# Revalidate again after gfxinfo so an external launcher steal or reinstall
# during the dump cannot make cached frame data look like an accepted journey.
adb -s "$SERIAL" shell dumpsys package "$PACKAGE" >"$OUT_DIR/package-dump-post-gfxinfo.txt" 2>&1 || true
adb -s "$SERIAL" shell pm path "$PACKAGE" >"$OUT_DIR/package-paths-post-gfxinfo.txt" 2>&1 || true
python3 "$HARNESS_HELPER" assert-package-stable \
  --before-dump "$OUT_DIR/package-dump.txt" \
  --before-paths "$OUT_DIR/package-paths.txt" \
  --after-dump "$OUT_DIR/package-dump-post-gfxinfo.txt" \
  --after-paths "$OUT_DIR/package-paths-post-gfxinfo.txt" \
  >"$OUT_DIR/package-identity-post-gfxinfo.json"

adb_shell dumpsys window >"$OUT_DIR/window-focus-post-gfxinfo.txt" 2>&1 || true
python3 "$HARNESS_HELPER" assert-foreground \
  --window "$OUT_DIR/window-focus-post-gfxinfo.txt" \
  --package "$PACKAGE" \
  >"$OUT_DIR/window-focus-post-gfxinfo.json"

JANKY="$(parse_metric janky "$RAW_DUMP")"
P50="$(parse_metric p50 "$RAW_DUMP")"
P90="$(parse_metric p90 "$RAW_DUMP")"
P99="$(parse_metric p99 "$RAW_DUMP")"
TOTAL_FRAMES="$(awk '/Total frames rendered:/ {print $4; exit}' "$RAW_DUMP" | tr -d '[:space:]')"
[[ -n "$JANKY" && -n "$P50" && -n "$P90" && -n "$P99" && -n "$TOTAL_FRAMES" ]] || {
  echo "[perf-interaction] failed to parse gfxinfo metrics" >&2
  exit 65
}
if ! [[ "$TOTAL_FRAMES" =~ ^[1-9][0-9]*$ ]]; then
  echo "[perf-interaction] invalid or empty gfxinfo frame window: total_frames=$TOTAL_FRAMES" >&2
  exit 70
fi
if awk -v p="$P50" 'BEGIN { exit !(p + 0 >= 1000) }'; then
  echo "[perf-interaction] invalid harness state: p50=${P50}ms suggests gfxinfo captured a blocked or non-interactive window." >&2
  cp "$LOCAL_UI_XML" "$OUT_DIR/last-ui.xml" 2>/dev/null || true
  exit 70
fi
if [[ "$DEBUGGABLE" -eq 1 ]]; then
  DEBUGGABLE_JSON=true
else
  DEBUGGABLE_JSON=false
fi
COMPLETED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

python3 "$SCRIPT_DIR/build_android_perf_summary.py" \
  --output "$OUT_DIR/summary.json" \
  --artifact-dir "$OUT_DIR" \
  --scenario "$SCENARIO" \
  --serial "$SERIAL" \
  --package "$PACKAGE" \
  --build-source "$BUILD_SOURCE" \
  --build-variant "$BUILD_VARIANT" \
  --native-runtime-packaged "$NATIVE_RUNTIME_PACKAGED_JSON" \
  --debuggable "$DEBUGGABLE_JSON" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --last-update-time "$LAST_UPDATE_TIME" \
  --installed-apk-path "$INSTALLED_APK_PATH" \
  --started-at-utc "$STARTED_AT_UTC" \
  --completed-at-utc "$COMPLETED_AT_UTC" \
  --runtime-condition "$DECLARED_RUNTIME_CONDITION" \
  --download-condition "$DECLARED_DOWNLOAD_CONDITION" \
  --voice-condition "$DECLARED_VOICE_CONDITION" \
  --total-frames "$TOTAL_FRAMES" \
  --janky-pct "$JANKY" \
  --p50-ms "$P50" \
  --p90-ms "$P90" \
  --p99-ms "$P99"

echo "scenario=${SCENARIO} janky=${JANKY}% p50=${P50}ms p90=${P90}ms p99=${P99}ms artifacts=${OUT_DIR}"
