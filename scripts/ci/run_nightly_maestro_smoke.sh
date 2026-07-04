#!/usr/bin/env bash
set -euo pipefail

RUN_LABEL="${1:-manual}"
FLOW_PATH="${NIGHTLY_MAESTRO_FLOW_PATH:-tests/maestro/scenario-onboarding.yaml}"
OUT_DIR="${NIGHTLY_MAESTRO_OUT_DIR:-tmp/nightly-maestro-smoke/${RUN_LABEL}}"
APP_ID="com.pocketagent.android"
REQUESTED_SERIAL="${ADB_SERIAL:-${ANDROID_SERIAL:-${DEVICE_SERIAL:-}}}"

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

mkdir -p "${OUT_DIR}"

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

adb -s "${DEVICE_SERIAL}" logcat -d > "${OUT_DIR}/logcat.txt" 2>/dev/null || true
exit "${RC}"
