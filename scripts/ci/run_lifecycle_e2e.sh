#!/usr/bin/env bash
set -euo pipefail

FLOW_PATH="tests/maestro/scenario-first-run-download-chat.yaml"
OUT_DIR="${LIFECYCLE_E2E_OUT_DIR:-tmp/lifecycle-e2e-first-run}"
APP_ID="com.pocketagent.android"
APP_TEST_ID="com.pocketagent.android.test"
RISK_REASON="unknown"
REQUESTED_SERIAL="${ADB_SERIAL:-${ANDROID_SERIAL:-${DEVICE_SERIAL:-}}}"
ATTEMPT_TIMEOUT_SEC="${LIFECYCLE_E2E_ATTEMPT_TIMEOUT_SEC:-1200}"
ADB_TIMEOUT_SEC="${LIFECYCLE_E2E_ADB_TIMEOUT_SEC:-120}"
TIMEOUT_KILL_AFTER="${LIFECYCLE_E2E_KILL_AFTER_SEC:-30}"
CRASH_SIGNATURE_REGEX="${LIFECYCLE_E2E_CRASH_SIGNATURE_REGEX:-SIGSEGV|Abort message|nativeLoadModel failed|UI-RUNTIME-001|FATAL EXCEPTION}"
TIMEOUT_BIN="$(command -v timeout || command -v gtimeout || true)"

usage() {
  cat <<'EOF'
Usage: bash scripts/ci/run_lifecycle_e2e.sh [--device <serial>] [reason]

Runs the first-run lifecycle Maestro flow with crash-signature capture and one clean-state retry.
If --device is omitted, ADB_SERIAL, ANDROID_SERIAL, or DEVICE_SERIAL is used. Without an
explicit serial, exactly one authorized adb device must be attached.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device|--serial)
      REQUESTED_SERIAL="${2:?missing serial for $1}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
    *)
      RISK_REASON="$1"
      shift
      ;;
  esac
done

if [[ $# -gt 0 ]]; then
  echo "Unexpected trailing arguments: $*" >&2
  usage >&2
  exit 2
fi

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
    if ! with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${REQUESTED_SERIAL}" get-state >/dev/null; then
      echo "Requested device ${REQUESTED_SERIAL} is not connected and authorized." >&2
      return 1
    fi
    printf '%s\n' "${REQUESTED_SERIAL}"
    return 0
  fi

  local devices
  devices="$(with_timeout "${ADB_TIMEOUT_SEC}" adb devices | awk 'NR>1 && $2=="device" {print $1}')"
  local count
  count="$(printf '%s\n' "${devices}" | sed '/^$/d' | wc -l | tr -d ' ')"
  if [[ "${count}" == "1" ]]; then
    printf '%s\n' "${devices}"
    return 0
  fi
  if [[ "${count}" == "0" ]]; then
    echo "No connected emulator/device for Maestro run." >&2
  else
    echo "Multiple adb devices detected; pass --device or set ADB_SERIAL/ANDROID_SERIAL." >&2
    printf '%s\n' "${devices}" >&2
  fi
  return 1
}

capture_logcat() {
  local out_file="$1"
  if ! with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" logcat -d > "${out_file}" 2>/dev/null; then
    echo "::warning::Failed to capture logcat to ${out_file} within ${ADB_TIMEOUT_SEC}s."
    return 1
  fi
}

capture_failure_state() {
  local out_dir="$1"
  local screen_file="${out_dir}/failure-screen.png"
  local hierarchy_device_path="/sdcard/pocketgpt-lifecycle-window.xml"
  local hierarchy_file="${out_dir}/failure-window.xml"

  with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" exec-out screencap -p > "${screen_file}" 2>/dev/null || true
  with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" shell uiautomator dump "${hierarchy_device_path}" >/dev/null 2>&1 || true
  with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" pull "${hierarchy_device_path}" "${hierarchy_file}" >/dev/null 2>&1 || true
  with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" shell rm "${hierarchy_device_path}" >/dev/null 2>&1 || true
}

detect_app_crash_signatures() {
  local log_file="$1"
  local matches_file="$2"
  local candidate_file="${matches_file}.candidate"
  : > "${matches_file}"

  if ! rg -n "${CRASH_SIGNATURE_REGEX}" "${log_file}" > "${candidate_file}" 2>/dev/null; then
    return 1
  fi

  if ! awk '
    {
      lines[NR] = $0
    }
    END {
      found = 0
      for (i = 1; i <= NR; i++) {
        line = lines[i]
        if (line !~ /(SIGSEGV|Abort message|nativeLoadModel failed|UI-RUNTIME-001|FATAL EXCEPTION)/) {
          continue
        }
        start = i - 25
        if (start < 1) {
          start = 1
        }
        stop = i + 25
        if (stop > NR) {
          stop = NR
        }
        app_related = 0
        for (j = start; j <= stop; j++) {
          if (lines[j] ~ /(com\.pocketagent\.android|PocketLlamaJNI|libpocket_llama|Cmdline: com\.pocketagent\.android|Process: com\.pocketagent\.android)/) {
            app_related = 1
            break
          }
        }
        if (!app_related) {
          continue
        }
        found = 1
        printf("---- crash-context line %d ----\n", i)
        for (j = start; j <= stop; j++) {
          print lines[j]
        }
      }
      if (!found) {
        exit 1
      }
    }
  ' "${log_file}" > "${matches_file}"; then
    return 1
  fi
  return 0
}

mkdir -p "${OUT_DIR}"
if [[ -z "${TIMEOUT_BIN}" ]]; then
  echo "::warning::No timeout binary found (timeout/gtimeout); lifecycle script will run without hard time bounds."
fi

APK_PATH="$(find apps/mobile-android/build/outputs/apk/debug -type f -name '*.apk' | sort | head -n 1)"
if [[ -z "${APK_PATH}" ]]; then
  echo "No debug APK found under apps/mobile-android/build/outputs/apk/debug" >&2
  exit 1
fi

DEVICE_SERIAL="$(select_device_serial)"

if ! with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" install -r "${APK_PATH}" >/dev/null; then
  echo "Failed to install APK on ${DEVICE_SERIAL} within ${ADB_TIMEOUT_SEC}s." >&2
  exit 1
fi

run_attempt() {
  local attempt="$1"
  local attempt_dir="${OUT_DIR}/attempt-${attempt}"
  local logcat_file="${attempt_dir}/logcat.txt"
  local crash_file="${attempt_dir}/crash-signatures.txt"
  mkdir -p "${attempt_dir}"
  with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" logcat -c >/dev/null || true
  set +e
  with_timeout "${ATTEMPT_TIMEOUT_SEC}" maestro --device "${DEVICE_SERIAL}" test "${FLOW_PATH}" --format junit --debug-output "${attempt_dir}/debug" \
    > "${attempt_dir}/junit.xml" \
    2> "${attempt_dir}/maestro-stderr.log"
  local rc=$?
  set -e
  local failure_state_captured=false
  if [[ ${rc} -ne 0 ]]; then
    capture_failure_state "${attempt_dir}"
    failure_state_captured=true
  fi
  capture_logcat "${logcat_file}" || true
  if [[ -f "${logcat_file}" ]] && detect_app_crash_signatures "${logcat_file}" "${crash_file}"; then
    echo "::error::Lifecycle E2E attempt ${attempt} detected app crash signatures in logcat."
    if [[ "${failure_state_captured}" != "true" ]]; then
      capture_failure_state "${attempt_dir}"
    fi
    rc=86
  fi
  if [[ ${rc} -eq 124 ]]; then
    echo "::warning::Lifecycle E2E attempt ${attempt} timed out after ${ATTEMPT_TIMEOUT_SEC}s."
  elif [[ ${rc} -eq 86 ]]; then
    echo "::warning::Lifecycle E2E attempt ${attempt} failed due to crash signature detection."
  fi
  return ${rc}
}

if run_attempt 1; then
  printf "decision=%s\nreason=%s\nfirst_attempt_failed=%s\nfinal_attempt=%s\n" \
    "run" "${RISK_REASON}" "false" "1" > "${OUT_DIR}/retry-summary.txt"
  exit 0
fi

echo "::warning::First lifecycle E2E attempt failed; retrying once after clean-state reset."
printf "decision=%s\nreason=%s\nfirst_attempt_failed=%s\n" \
  "run" "${RISK_REASON}" "true" > "${OUT_DIR}/retry-summary.txt"

with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" shell pm clear "${APP_ID}" >/dev/null || true
with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" uninstall "${APP_ID}" >/dev/null || true
with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" uninstall "${APP_TEST_ID}" >/dev/null || true
if ! with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" install -r "${APK_PATH}" >/dev/null; then
  echo "Failed to reinstall APK on ${DEVICE_SERIAL} within ${ADB_TIMEOUT_SEC}s." >&2
  exit 1
fi

if run_attempt 2; then
  echo "final_attempt=2" >> "${OUT_DIR}/retry-summary.txt"
  exit 0
fi

echo "final_attempt=none" >> "${OUT_DIR}/retry-summary.txt"
exit 1
