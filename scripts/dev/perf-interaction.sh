#!/usr/bin/env bash
# Captures benchmark-variant frame stats for non-generation UI interactions.
#
# This is the focused companion to perf-baseline.sh. Use it for settings,
# model-library, and drawer smoothness checks without running a full E2E lane.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

SERIAL="${ANDROID_SERIAL:-}"
PACKAGE="com.pocketagent.android"
SCENARIO=""
DO_BUILD=0
ALLOW_DEBUGGABLE=0
OUT_DIR=""
BUILD_SOURCE="preinstalled-nondebuggable"
BUILD_VARIANT="unverified-nondebuggable"
NATIVE_RUNTIME_PACKAGED_JSON="null"
DEBUGGABLE=0
STARTED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
REMOTE_UI_XML="/sdcard/pocketgpt-perf-interaction.xml"
LOCAL_UI_XML="$(mktemp -t pocketgpt-perf-interaction.XXXXXX.xml)"
trap 'rm -f "$LOCAL_UI_XML"' EXIT

usage() {
  cat <<EOF
Usage: $0 --scenario settings-nav|model-sheet|drawer-search [--serial SERIAL] [--build] [--allow-debuggable] [--out-dir DIR]

Measures PocketGPT non-generation UI frame stats on the benchmark variant.
For acceptance evidence, use perf-interaction-gate.sh to capture and evaluate
three samples. This command captures one diagnostic sample.
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

if [[ -z "$OUT_DIR" ]]; then
  OUT_DIR="$REPO_ROOT/tmp/perf-interaction/$(date -u +%Y%m%dT%H%M%SZ)-${SCENARIO}"
fi
mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"

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
  local focus=""
  while (( SECONDS < deadline )); do
    focus="$(adb_shell dumpsys window 2>/dev/null | grep 'mCurrentFocus' || true)"
    if grep -q "$PACKAGE" <<<"$focus"; then
      return 0
    fi
    sleep 1
  done
  echo "[perf-interaction] $PACKAGE did not become foreground after launch." >&2
  printf '%s\n' "$focus" >&2
  exit 71
}

dump_ui() {
  adb_shell uiautomator dump "$REMOTE_UI_XML" >/dev/null 2>&1 || return 1
  adb -s "$SERIAL" exec-out cat "$REMOTE_UI_XML" >"$LOCAL_UI_XML" 2>/dev/null || return 1
}

tag_center_from_dump() {
  local tag="$1"
  python3 - "$LOCAL_UI_XML" "$tag" <<'PY'
import re
import sys

path, tag = sys.argv[1], sys.argv[2]
try:
    source = open(path, encoding="utf-8", errors="replace").read()
except OSError:
    sys.exit(1)

for node in re.findall(r"<node\b[^>]*>", source):
    if tag not in node:
        continue
    match = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if not match:
        continue
    left, top, right, bottom = map(int, match.groups())
    if right <= left or bottom <= top:
        continue
    print(f"{(left + right) // 2} {(top + bottom) // 2}")
    sys.exit(0)

if tag in {
    "composer_input",
    "completion_system_prompt_input",
    "model_search_input",
    "session_search_input",
}:
    for node in re.findall(r"<node\b[^>]*>", source):
        if 'class="android.widget.EditText"' not in node:
            continue
        match = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if not match:
            continue
        left, top, right, bottom = map(int, match.groups())
        if right <= left or bottom <= top:
            continue
        print(f"{(left + right) // 2} {(top + bottom) // 2}")
        sys.exit(0)
sys.exit(1)
PY
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
INSTALLED_FLAGS="$(awk '/pkgFlags=/ {print; exit}' <<<"$PACKAGE_DUMP")"
if [[ -z "$INSTALLED_FLAGS" ]]; then
  echo "[perf-interaction] $PACKAGE is not installed on $SERIAL. Re-run with --build or install manually." >&2
  exit 67
fi
if echo "$INSTALLED_FLAGS" | grep -q DEBUGGABLE; then
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
INSTALLED_APK_PATH="$(adb -s "$SERIAL" shell pm path "$PACKAGE" 2>/dev/null | sed -n 's/^package://p' | head -n 1 | tr -d '\r')"
if [[ -z "$VERSION_CODE" || -z "$VERSION_NAME" || -z "$LAST_UPDATE_TIME" || -z "$INSTALLED_APK_PATH" ]]; then
  echo "[perf-interaction] failed to read stable package identity for $PACKAGE on $SERIAL" >&2
  exit 67
fi

wake_and_dismiss_keyguard
adb_shell am force-stop "$PACKAGE" >/dev/null
adb_shell am start -n "$PACKAGE/.MainActivity" >/dev/null
wait_for_app_foreground
wait_tag "composer_input"
sleep 1
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
    tap_tag "completion_system_prompt_input"
    type_text_slowly "SmoothSettingsProbe"
    adb_shell input keyevent KEYCODE_BACK >/dev/null
    ;;
  model-sheet)
    tap_tag "open_model_library"
    tap_tag "model_search_input"
    type_text_slowly "qwen"
    adb_shell input swipe 500 1700 500 650 500 >/dev/null
    ;;
  drawer-search)
    tap_tag "session_drawer_button"
    tap_tag "session_search_input"
    type_text_slowly "chat"
    adb_shell input swipe 500 1700 500 650 500 >/dev/null
    ;;
esac

sleep 2
RAW_DUMP="$OUT_DIR/gfxinfo.txt"
gfxinfo_dump >"$RAW_DUMP" || {
  echo "[perf-interaction] failed to dump gfxinfo" >&2
  exit 65
}

JANKY="$(parse_metric janky "$RAW_DUMP")"
P50="$(parse_metric p50 "$RAW_DUMP")"
P90="$(parse_metric p90 "$RAW_DUMP")"
P99="$(parse_metric p99 "$RAW_DUMP")"
[[ -n "$JANKY" && -n "$P50" && -n "$P90" && -n "$P99" ]] || {
  echo "[perf-interaction] failed to parse gfxinfo metrics" >&2
  exit 65
}
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

python3 - \
  "$OUT_DIR/summary.json" \
  "$SCENARIO" \
  "$SERIAL" \
  "$PACKAGE" \
  "$BUILD_SOURCE" \
  "$BUILD_VARIANT" \
  "$NATIVE_RUNTIME_PACKAGED_JSON" \
  "$DEBUGGABLE_JSON" \
  "$VERSION_CODE" \
  "$VERSION_NAME" \
  "$LAST_UPDATE_TIME" \
  "$INSTALLED_APK_PATH" \
  "$STARTED_AT_UTC" \
  "$JANKY" \
  "$P50" \
  "$P90" \
  "$P99" \
  "$OUT_DIR" <<'PY'
import json
import sys

(
    output_path,
    scenario,
    serial,
    package,
    build_source,
    build_variant,
    native_runtime_packaged,
    debuggable,
    version_code,
    version_name,
    last_update_time,
    installed_apk_path,
    started_at_utc,
    janky_pct,
    p50_ms,
    p90_ms,
    p99_ms,
    artifact_dir,
) = sys.argv[1:]
payload = {
    "scenario": scenario,
    "serial": serial,
    "package": package,
    "build_source": build_source,
    "build_variant": build_variant,
    "native_runtime_packaged": True if native_runtime_packaged == "true" else None,
    "debuggable": debuggable == "true",
    "version_code": version_code,
    "version_name": version_name,
    "last_update_time": last_update_time,
    "installed_apk_path": installed_apk_path,
    "started_at_utc": started_at_utc,
    "janky_pct": float(janky_pct),
    "p50_ms": float(p50_ms),
    "p90_ms": float(p90_ms),
    "p99_ms": float(p99_ms),
    "artifact_dir": artifact_dir,
}
with open(output_path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, indent=2, sort_keys=True)
    handle.write("\n")
PY

echo "scenario=${SCENARIO} janky=${JANKY}% p50=${P50}ms p90=${P90}ms p99=${P99}ms artifacts=${OUT_DIR}"
