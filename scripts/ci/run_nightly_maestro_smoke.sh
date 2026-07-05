#!/usr/bin/env bash
set -euo pipefail

RUN_LABEL="${1:-manual}"
FLOW_PATH="${NIGHTLY_MAESTRO_FLOW_PATH:-tests/maestro/scenario-onboarding.yaml}"
OUT_DIR="${NIGHTLY_MAESTRO_OUT_DIR:-tmp/nightly-maestro-smoke/${RUN_LABEL}}"
APP_ID="com.pocketagent.android"
REQUESTED_SERIAL="${ADB_SERIAL:-${ANDROID_SERIAL:-${DEVICE_SERIAL:-}}}"
ADB_TIMEOUT_SEC="${NIGHTLY_MAESTRO_ADB_TIMEOUT_SEC:-120}"
TIMEOUT_KILL_AFTER="${NIGHTLY_MAESTRO_KILL_AFTER_SEC:-30}"
TIMEOUT_BIN="$(command -v timeout || command -v gtimeout || true)"

with_timeout() {
  local duration="$1"
  shift
  if [[ -n "${TIMEOUT_BIN}" ]]; then
    "${TIMEOUT_BIN}" --kill-after="${TIMEOUT_KILL_AFTER}" "${duration}" "$@"
    return
  fi
  "$@"
}

select_device_serial() {
  if [[ -n "${REQUESTED_SERIAL}" ]]; then
    adb -s "${REQUESTED_SERIAL}" get-state >/dev/null
    printf '%s\n' "${REQUESTED_SERIAL}"
    return
  fi

  local devices
  devices="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
  local count
  count="$(printf '%s\n' "${devices}" | sed '/^$/d' | wc -l | tr -d ' ')"
  if [[ "${count}" == "1" ]]; then
    printf '%s\n' "${devices}"
    return
  fi
  if [[ "${count}" == "0" ]]; then
    echo "No connected emulator/device for Nightly Maestro smoke." >&2
  else
    echo "Multiple adb devices detected; set ADB_SERIAL/ANDROID_SERIAL/DEVICE_SERIAL." >&2
    printf '%s\n' "${devices}" >&2
  fi
  return 1
}

capture_failure_state() {
  local out_dir="$1"
  local screen_file="${out_dir}/failure-screen.png"
  local hierarchy_device_path="/sdcard/pocketgpt-nightly-window.xml"
  local hierarchy_file="${out_dir}/failure-window.xml"

  with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" exec-out screencap -p > "${screen_file}" 2>/dev/null || true
  with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" shell uiautomator dump "${hierarchy_device_path}" >/dev/null 2>&1 || true
  with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" pull "${hierarchy_device_path}" "${hierarchy_file}" >/dev/null 2>&1 || true
  with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" shell rm "${hierarchy_device_path}" >/dev/null 2>&1 || true
}

mkdir -p "${OUT_DIR}"
if [[ -z "${TIMEOUT_BIN}" ]]; then
  echo "::warning::No timeout binary found (timeout/gtimeout); failure-state capture will run without hard time bounds."
fi

./gradlew --no-daemon \
  -Ppocketgpt.enableNativeBuild=false \
  :apps:mobile-android:assembleDebug

APK_PATH="$(find apps/mobile-android/build/outputs/apk/debug -type f -name '*.apk' | sort | head -n 1)"
if [[ -z "${APK_PATH}" ]]; then
  echo "No debug APK found under apps/mobile-android/build/outputs/apk/debug" >&2
  exit 1
fi

DEVICE_SERIAL="$(select_device_serial)"

adb -s "${DEVICE_SERIAL}" install -r "${APK_PATH}" >/dev/null
adb -s "${DEVICE_SERIAL}" shell pm clear "${APP_ID}" >/dev/null || true
adb -s "${DEVICE_SERIAL}" logcat -c >/dev/null || true

set +e
maestro --device "${DEVICE_SERIAL}" test "${FLOW_PATH}" \
  --format junit \
  --debug-output "${OUT_DIR}/debug" \
  > "${OUT_DIR}/junit.xml" \
  2> "${OUT_DIR}/maestro-stderr.log"
RC=$?
set -e

if [[ "${RC}" != "0" ]]; then
  capture_failure_state "${OUT_DIR}"
fi
adb -s "${DEVICE_SERIAL}" logcat -d > "${OUT_DIR}/logcat.txt" 2>/dev/null || true
exit "${RC}"
