#!/usr/bin/env bash
set -euo pipefail

OUTPUT_DIR=""
SERIAL=""
RECORD_SECONDS="20"
SKIP_VIDEO="false"
PACKAGE_ID="com.pocketagent.android"
RELEASE_PROVENANCE=""

SHOT_FILES=(
  "01-chat-ready.png"
  "02-runtime-recovery.png"
  "03-session-continuity.png"
  "04-prompt-shortcuts.png"
  "05-single-image-help.png"
  "06-model-library.png"
  "07-privacy-diagnostics.png"
)

SHOT_INSTRUCTIONS=(
  "Show a completed, realistic chat response with Runtime: Ready visible."
  "Show a truthful runtime loading/not-ready state and the matching recovery action."
  "Open Sessions with at least two realistic, non-sensitive conversation names."
  "Open prompt shortcuts with one clean surface and no unlock cue or overlapping modal."
  "Show one selected image and its completed contextual response; reject blank image states."
  "Open Model library with active/downloaded/available state visible and no live private URL."
  "Show the Privacy panel or redacted diagnostics action with readable, current PocketAgent copy."
)

usage() {
  cat <<'USAGE'
Usage:
  bash scripts/marketing/capture_mobile_demo_assets.sh \
    --output <dir> --serial <adb-serial> --release-provenance <json> \
    [--record-seconds <n>] [--skip-video]

The script captures the seven frozen Play-listing states interactively, records device/build
metadata, and optionally records one short raw demo. It never adds marketing overlays.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output)
      [[ $# -ge 2 ]] || { echo "--output requires a directory" >&2; exit 2; }
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --serial)
      [[ $# -ge 2 ]] || { echo "--serial requires an adb serial" >&2; exit 2; }
      SERIAL="$2"
      shift 2
      ;;
    --record-seconds)
      [[ $# -ge 2 ]] || { echo "--record-seconds requires an integer" >&2; exit 2; }
      RECORD_SECONDS="$2"
      shift 2
      ;;
    --release-provenance)
      [[ $# -ge 2 ]] || { echo "--release-provenance requires a JSON path" >&2; exit 2; }
      RELEASE_PROVENANCE="$2"
      shift 2
      ;;
    --skip-video)
      SKIP_VIDEO="true"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

[[ -n "$OUTPUT_DIR" ]] || { echo "Missing required --output" >&2; usage >&2; exit 2; }
[[ -n "$RELEASE_PROVENANCE" && -f "$RELEASE_PROVENANCE" ]] || {
  echo "A real --release-provenance JSON file is required." >&2
  exit 2
}
[[ "$RECORD_SECONDS" =~ ^[1-9][0-9]*$ ]] || { echo "--record-seconds must be a positive integer" >&2; exit 2; }
(( RECORD_SECONDS <= 180 )) || { echo "--record-seconds must be 180 or less" >&2; exit 2; }
command -v adb >/dev/null 2>&1 || { echo "adb is not installed or not in PATH" >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "python3 is required" >&2; exit 1; }

provenance_value() {
  python3 - "$RELEASE_PROVENANCE" "$1" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    value = json.load(handle)
for key in sys.argv[2].split("."):
    value = value[key]
print(value)
PY
}

EXPECTED_KIND="$(provenance_value artifact_kind)"
EXPECTED_RELEASE_ID="$(provenance_value release_id)"
EXPECTED_APPLICATION_ID="$(provenance_value application_id)"
EXPECTED_VERSION_NAME="$(provenance_value version_name)"
EXPECTED_VERSION_CODE="$(provenance_value version_code)"
EXPECTED_GIT_SHA="$(provenance_value git_sha)"
EXPECTED_APK_SHA256="$(provenance_value apk.sha256)"
PROVENANCE_SHA256="$(shasum -a 256 "$RELEASE_PROVENANCE" | awk '{print $1}')"
[[ "$EXPECTED_KIND" == "signed-upload" ]] || {
  echo "Final listing capture requires signed-upload provenance; found: $EXPECTED_KIND" >&2
  exit 1
}
[[ "$EXPECTED_APPLICATION_ID" == "$PACKAGE_ID" ]] || {
  echo "Provenance application id does not match $PACKAGE_ID" >&2
  exit 1
}

if [[ -z "$SERIAL" ]]; then
  DEVICES=()
  while IFS= read -r device; do
    [[ -n "$device" ]] && DEVICES+=("$device")
  done < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  if (( ${#DEVICES[@]} != 1 )); then
    echo "Expected exactly one connected adb device; found ${#DEVICES[@]}. Pass --serial explicitly." >&2
    exit 1
  fi
  SERIAL="${DEVICES[0]}"
fi

ADB=(adb -s "$SERIAL")
[[ "$("${ADB[@]}" get-state 2>/dev/null)" == "device" ]] || { echo "Device is not ready: $SERIAL" >&2; exit 1; }
"${ADB[@]}" shell pm path "$PACKAGE_ID" >/dev/null 2>&1 || {
  echo "PocketAgent package $PACKAGE_ID is not installed on $SERIAL" >&2
  exit 1
}

mkdir -p "$OUTPUT_DIR"
if find "$OUTPUT_DIR" -maxdepth 1 -type f | grep -q .; then
  echo "Output directory must be empty so raw-capture provenance is unambiguous: $OUTPUT_DIR" >&2
  exit 1
fi

assert_app_foreground() {
  local foreground
  foreground="$("${ADB[@]}" shell dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | tail -n 1 || true)"
  if [[ "$foreground" != *"$PACKAGE_ID"* ]]; then
    echo "PocketAgent is not foreground on $SERIAL. Current focus: ${foreground:-unknown}" >&2
    return 1
  fi
}

CAPTURED_AT_UTC="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
CHECKOUT_GIT_SHA="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
PACKAGE_DUMP="$("${ADB[@]}" shell dumpsys package "$PACKAGE_ID")"
VERSION_CODE="$(sed -n 's/.*versionCode=\([^ ]*\).*/\1/p' <<<"$PACKAGE_DUMP" | head -n 1 | tr -d '\r')"
VERSION_NAME="$(sed -n 's/^[[:space:]]*versionName=//p' <<<"$PACKAGE_DUMP" | head -n 1 | tr -d '\r')"
DEVICE_APK_PATH="$("${ADB[@]}" shell pm path "$PACKAGE_ID" | sed -n 's/^package://p' | head -n 1 | tr -d '\r')"
[[ -n "$DEVICE_APK_PATH" ]] || { echo "Could not resolve installed base APK path" >&2; exit 1; }
INSTALLED_APK_SHA256="$("${ADB[@]}" exec-out cat "$DEVICE_APK_PATH" | shasum -a 256 | awk '{print $1}')"
[[ "$VERSION_CODE" == "$EXPECTED_VERSION_CODE" ]] || {
  echo "Installed version code $VERSION_CODE does not match provenance $EXPECTED_VERSION_CODE" >&2
  exit 1
}
[[ "$VERSION_NAME" == "$EXPECTED_VERSION_NAME" ]] || {
  echo "Installed version name $VERSION_NAME does not match provenance $EXPECTED_VERSION_NAME" >&2
  exit 1
}
[[ "$INSTALLED_APK_SHA256" == "$EXPECTED_APK_SHA256" ]] || {
  echo "Installed APK checksum does not match release provenance" >&2
  exit 1
}
DEVICE_MODEL="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"
ANDROID_RELEASE="$("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r')"
ANDROID_SDK="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
SERIAL_SHA256="$(printf '%s' "$SERIAL" | shasum -a 256 | awk '{print $1}')"

{
  echo "schema=pocketagent-raw-capture-v1"
  echo "captured_at_utc=$CAPTURED_AT_UTC"
  echo "release_id=$EXPECTED_RELEASE_ID"
  echo "release_git_sha=$EXPECTED_GIT_SHA"
  echo "capture_checkout_git_sha=$CHECKOUT_GIT_SHA"
  echo "application_id=$PACKAGE_ID"
  echo "version_name=$VERSION_NAME"
  echo "version_code=$VERSION_CODE"
  echo "installed_apk_sha256=$INSTALLED_APK_SHA256"
  echo "release_provenance_sha256=$PROVENANCE_SHA256"
  echo "device_serial_sha256=$SERIAL_SHA256"
  echo "device_model=$DEVICE_MODEL"
  echo "android_release=$ANDROID_RELEASE"
  echo "android_sdk=$ANDROID_SDK"
} > "$OUTPUT_DIR/capture-metadata.txt"

echo "Using device: $SERIAL ($DEVICE_MODEL, Android $ANDROID_RELEASE)"
echo "Installed PocketAgent: $VERSION_NAME ($VERSION_CODE)"
echo "Installed APK matches signed provenance: $EXPECTED_APK_SHA256"
echo "Raw output: $OUTPUT_DIR"
echo "Before every capture, dismiss the keyboard, notifications, system overlays, and any unrelated modal."

for index in "${!SHOT_FILES[@]}"; do
  number=$(( index + 1 ))
  file_name="${SHOT_FILES[$index]}"
  instruction="${SHOT_INSTRUCTIONS[$index]}"
  echo
  echo "Screenshot ${number}/${#SHOT_FILES[@]}: $file_name"
  echo "$instruction"
  echo "Press Enter only when the final PocketAgent state is clean and truthful."
  read -r
  assert_app_foreground
  "${ADB[@]}" exec-out screencap -p > "$OUTPUT_DIR/$file_name"
  [[ -s "$OUTPUT_DIR/$file_name" ]] || { echo "Empty screenshot: $file_name" >&2; exit 1; }
done

if [[ "$SKIP_VIDEO" == "false" ]]; then
  TMP_REMOTE="/sdcard/pocketagent-controlled-mvp-demo.mp4"
  echo
  echo "Demo video: show one short chat or setup recovery path using only frozen listing claims."
  echo "Press Enter to start the ${RECORD_SECONDS}s raw recording."
  read -r
  assert_app_foreground
  "${ADB[@]}" shell rm -f "$TMP_REMOTE" >/dev/null 2>&1 || true
  "${ADB[@]}" shell screenrecord --time-limit "$RECORD_SECONDS" "$TMP_REMOTE"
  "${ADB[@]}" pull "$TMP_REMOTE" "$OUTPUT_DIR/08-demo.mp4" >/dev/null
  "${ADB[@]}" shell rm -f "$TMP_REMOTE" >/dev/null 2>&1 || true
fi

(
  cd "$OUTPUT_DIR"
  shasum -a 256 ./* > SHA256SUMS
)

echo
echo "Raw capture complete. Do not upload before Product + Marketing approve every file against the asset manifest."
ls -lh "$OUTPUT_DIR" | sed -n '1,30p'
