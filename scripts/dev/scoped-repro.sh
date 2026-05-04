#!/usr/bin/env bash
# DEPRECATED: Use `maestro-android scoped` instead. This script is kept for
# backwards compatibility but all new features land in the CLI.
# See: maestro-android scoped --help
set -euo pipefail

usage() {
  cat <<'EOF' >&2
Usage:
  bash scripts/dev/scoped-repro.sh --flow <path> [options] [-- <extra maestro args>]

Required:
  --flow <path>                 Maestro flow path (usually under tmp/ for scoped repro)

Options:
  --serial <id>                 Device serial (default: ADB_SERIAL/ANDROID_SERIAL/first attached device)
  --apk <path>                  Explicit APK path (default: latest debug APK)
  --no-build                    Skip Gradle assemble step
  --no-install                  Skip adb install step
  --allow-parallel              Disable the default host-local Maestro execution lock
  --native-build <true|false>   Value for -Ppocketgpt.enableNativeBuild (default: true)
  --log-dir <path>              Output directory for logs (default: tmp)
  --pattern <regex>             Crash/runtime signature regex scan for logcat
  --app-context <regex>         Additional regex that must appear in match context
  --adb-timeout-sec <seconds>   adb timeout (default: 120)
  --maestro-timeout-sec <sec>   maestro timeout (default: 1200)
  --help                        Show help

Examples:
  bash scripts/dev/scoped-repro.sh --flow tmp/maestro-repro.yaml
  bash scripts/dev/scoped-repro.sh --flow tests/maestro/scenario-first-run-gpu-chat.yaml --no-build
EOF
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"
source "${REPO_ROOT}/tools/maestro_android/scoped_repro_lib.sh"

FLOW_PATH=""
DEVICE_SERIAL="${ADB_SERIAL:-${ANDROID_SERIAL:-}}"
APK_PATH=""
BUILD_APK=1
INSTALL_APK=1
ALLOW_PARALLEL=0
NATIVE_BUILD="true"
LOG_DIR="tmp"
ADB_TIMEOUT_SEC=120
MAESTRO_TIMEOUT_SEC=1200
TIMEOUT_KILL_AFTER_SEC=30
CRASH_SIGNATURE_REGEX="FATAL EXCEPTION|Fatal signal|SIGSEGV|Abort message|ANR in|OutOfMemoryError|UnsatisfiedLinkError|UI-RUNTIME-001"
APP_CONTEXT_REGEX="com\\.pocketagent\\.android|Cmdline: com\\.pocketagent\\.android|Process: com\\.pocketagent\\.android|UI-RUNTIME-001"
TIMEOUT_BIN="$(command -v timeout || command -v gtimeout || true)"
MAESTRO_EXTRA_ARGS=()
LOCK_ROOT="${TMPDIR:-/tmp}/pocketgpt-maestro-scoped-repro.lock"
LOCK_ACQUIRED=0
PREPARED_FLOW_PATHS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --flow)
      FLOW_PATH="${2:-}"
      shift 2
      ;;
    --serial)
      DEVICE_SERIAL="${2:-}"
      shift 2
      ;;
    --apk)
      APK_PATH="${2:-}"
      shift 2
      ;;
    --no-build)
      BUILD_APK=0
      shift
      ;;
    --no-install)
      INSTALL_APK=0
      shift
      ;;
    --allow-parallel)
      ALLOW_PARALLEL=1
      shift
      ;;
    --native-build)
      NATIVE_BUILD="${2:-}"
      shift 2
      ;;
    --log-dir)
      LOG_DIR="${2:-}"
      shift 2
      ;;
    --pattern)
      CRASH_SIGNATURE_REGEX="${2:-}"
      shift 2
      ;;
    --app-context)
      APP_CONTEXT_REGEX="${2:-}"
      shift 2
      ;;
    --adb-timeout-sec)
      ADB_TIMEOUT_SEC="${2:-}"
      shift 2
      ;;
    --maestro-timeout-sec)
      MAESTRO_TIMEOUT_SEC="${2:-}"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    --)
      shift
      MAESTRO_EXTRA_ARGS=("$@")
      break
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "${FLOW_PATH}" ]]; then
  echo "Missing required --flow argument." >&2
  usage
  exit 1
fi

if [[ ! -f "${FLOW_PATH}" ]]; then
  echo "Flow does not exist: ${FLOW_PATH}" >&2
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not installed or not on PATH." >&2
  exit 1
fi

if ! command -v maestro >/dev/null 2>&1; then
  echo "maestro is not installed or not on PATH." >&2
  exit 1
fi

if ! command -v rg >/dev/null 2>&1; then
  echo "rg is not installed or not on PATH." >&2
  exit 1
fi

with_timeout() {
  local duration="$1"
  shift
  if [[ -n "${TIMEOUT_BIN}" ]]; then
    "${TIMEOUT_BIN}" --kill-after="${TIMEOUT_KILL_AFTER_SEC}" "${duration}" "$@"
    return
  fi
  "$@"
}

release_lock() {
  if [[ ${LOCK_ACQUIRED} -eq 1 ]]; then
    rmdir "${LOCK_ROOT}" >/dev/null 2>&1 || true
    LOCK_ACQUIRED=0
  fi
}

cleanup_prepared_flows() {
  if [[ ${#PREPARED_FLOW_PATHS[@]} -eq 0 ]]; then
    return
  fi
  rm -f -- "${PREPARED_FLOW_PATHS[@]}" >/dev/null 2>&1 || true
}

trap 'cleanup_prepared_flows; release_lock' EXIT

abort_if_device_lock_held_by_other() {
  local serial="${1:-}"
  local current_pid="$$"
  local parent_pid="${PPID:-0}"
  local lock_path
  lock_path="$(maestro_android_device_lock_path "${REPO_ROOT}" "${serial}")" || return 1
  local lock_pid
  lock_pid="$(maestro_android_active_device_lock_pid "${REPO_ROOT}" "${serial}" || true)"
  if [[ -z "${lock_pid}" || "${lock_pid}" == "${current_pid}" || "${lock_pid}" == "${parent_pid}" ]]; then
    return 0
  fi
  local owner
  owner="$(awk -F= '/^owner=/{print $2; exit}' "${lock_path}")"
  echo "[scoped-repro] device ${serial} is already owned by active lane pid ${lock_pid}${owner:+ (${owner})}; refusing to kill or overlap it." >&2
  exit 73
}

acquire_lock() {
  local waited_sec=0
  if [[ ${ALLOW_PARALLEL} -eq 1 ]]; then
    return
  fi
  until mkdir "${LOCK_ROOT}" >/dev/null 2>&1; do
    if [[ ${waited_sec} -eq 0 ]]; then
      echo "[scoped-repro] waiting for host-local Maestro lock at ${LOCK_ROOT}" >&2
    fi
    sleep 2
    waited_sec=$((waited_sec + 2))
  done
  LOCK_ACQUIRED=1
}

acquire_lock

if [[ ${BUILD_APK} -eq 1 ]]; then
  ./gradlew --no-daemon "-Ppocketgpt.enableNativeBuild=${NATIVE_BUILD}" :apps:mobile-android:assembleDebug
fi

if [[ -z "${APK_PATH}" ]]; then
  APK_PATH="$(find apps/mobile-android/build/outputs/apk/debug -type f -name '*.apk' | sort | head -n 1)"
fi

if [[ -z "${APK_PATH}" || ! -f "${APK_PATH}" ]]; then
  echo "No debug APK found. Build first or pass --apk <path>." >&2
  exit 1
fi

if [[ -z "${DEVICE_SERIAL}" ]]; then
  DEVICE_SERIAL="$(with_timeout "${ADB_TIMEOUT_SEC}" adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi

if [[ -z "${DEVICE_SERIAL}" ]]; then
  echo "No connected device detected. Attach a device or pass --serial <id>." >&2
  exit 1
fi

abort_if_device_lock_held_by_other "${DEVICE_SERIAL}"

if [[ ${INSTALL_APK} -eq 1 ]]; then
  with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" install -r "${APK_PATH}" >/dev/null
fi

mkdir -p "${LOG_DIR}"

STAMP="$(date +%Y%m%d-%H%M%S)"
FLOW_BASENAME="$(basename "${FLOW_PATH}" .yaml)"
SERIAL_TOKEN="$(maestro_android_sanitize_token "${DEVICE_SERIAL}")"
LOG_PATH="${LOG_DIR}/${FLOW_BASENAME}-${STAMP}-${SERIAL_TOKEN}-logcat.txt"
MAESTRO_LOG_PATH="${LOG_DIR}/${FLOW_BASENAME}-${STAMP}-${SERIAL_TOKEN}-maestro.log"
MATCH_PATH="${LOG_DIR}/${FLOW_BASENAME}-${STAMP}-${SERIAL_TOKEN}-logcat-matches.txt"
CANDIDATE_PATH="${MATCH_PATH}.candidate"
FLOW_RUN_PATH="${FLOW_PATH}"
FLOW_RUN_NOTE=""
USE_ADB_CLEAR_STATE=0
USE_ADB_LAUNCH_APP=0
PREPARED_FLOW_DIR="$(dirname "${FLOW_PATH}")"

if maestro_android_flow_uses_clear_state "${FLOW_PATH}"; then
  FLOW_RUN_PATH="$(maestro_android_prepared_flow_path "${FLOW_PATH}" "${PREPARED_FLOW_DIR}" "${STAMP}" "${DEVICE_SERIAL}" "prepared-flow")"
  maestro_android_prepare_flow_without_clear_state "${FLOW_PATH}" "${FLOW_RUN_PATH}"
  PREPARED_FLOW_PATHS+=("${FLOW_RUN_PATH}")
  FLOW_RUN_NOTE="adb-pm-clear + rewritten flow (${FLOW_RUN_PATH})"
  USE_ADB_CLEAR_STATE=1
fi

if maestro_android_flow_starts_with_launch_app "${FLOW_RUN_PATH}"; then
  local_flow_source="${FLOW_RUN_PATH}"
  FLOW_RUN_PATH="$(maestro_android_prepared_flow_path "${FLOW_PATH}" "${PREPARED_FLOW_DIR}" "${STAMP}" "${DEVICE_SERIAL}" "prepared-no-launch")"
  maestro_android_prepare_flow_without_initial_launch_app "${local_flow_source}" "${FLOW_RUN_PATH}"
  PREPARED_FLOW_PATHS+=("${FLOW_RUN_PATH}")
  FLOW_RUN_NOTE="${FLOW_RUN_NOTE:+${FLOW_RUN_NOTE}; }adb-monkey-launch + launchApp-stripped flow (${FLOW_RUN_PATH})"
  USE_ADB_LAUNCH_APP=1
fi

maestro_android_kill_conflicting_local_processes "${DEVICE_SERIAL}"

recover_transport() {
  with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" forward --remove-all >/dev/null 2>&1 || true
  if maestro_android_is_tcpip_serial "${DEVICE_SERIAL}"; then
    with_timeout "${ADB_TIMEOUT_SEC}" adb disconnect "${DEVICE_SERIAL}" >/dev/null 2>&1 || true
    with_timeout "${ADB_TIMEOUT_SEC}" adb connect "${DEVICE_SERIAL}" >/dev/null 2>&1 || true
  fi
  with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" wait-for-device >/dev/null 2>&1 || true
  with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" shell am force-stop com.pocketagent.android >/dev/null 2>&1 || true
}

run_maestro_attempt() {
  local attempt="$1"
  local attempt_log="${MAESTRO_LOG_PATH}.attempt${attempt}"
  local exit_code=0
  maestro_android_kill_conflicting_local_processes "${DEVICE_SERIAL}"
  with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" forward --remove-all >/dev/null 2>&1 || true
  if [[ ${USE_ADB_CLEAR_STATE} -eq 1 ]]; then
    with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" shell pm clear com.pocketagent.android >/dev/null 2>&1 || true
  fi
  if [[ ${USE_ADB_LAUNCH_APP} -eq 1 ]]; then
    with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" shell monkey -p com.pocketagent.android -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  fi
  if [[ ${#MAESTRO_EXTRA_ARGS[@]} -gt 0 ]]; then
    with_timeout "${MAESTRO_TIMEOUT_SEC}" maestro --device "${DEVICE_SERIAL}" test "${FLOW_RUN_PATH}" "${MAESTRO_EXTRA_ARGS[@]}" >"${attempt_log}" 2>&1 || exit_code=$?
  else
    with_timeout "${MAESTRO_TIMEOUT_SEC}" maestro --device "${DEVICE_SERIAL}" test "${FLOW_RUN_PATH}" >"${attempt_log}" 2>&1 || exit_code=$?
  fi
  {
    echo "=== attempt ${attempt} ==="
    cat "${attempt_log}"
    echo
  } >>"${MAESTRO_LOG_PATH}"
  rm -f "${attempt_log}"
  return "${exit_code}"
}

with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" logcat -c >/dev/null || true

: > "${MAESTRO_LOG_PATH}"
set +e
run_maestro_attempt 1
MAESTRO_EXIT=$?
if [[ ${MAESTRO_EXIT} -ne 0 ]] && maestro_android_log_has_transient_failure "${MAESTRO_LOG_PATH}"; then
  echo "[scoped-repro] transient Maestro/bootstrap failure detected; retrying once" >>"${MAESTRO_LOG_PATH}"
  recover_transport
  run_maestro_attempt 2
  MAESTRO_EXIT=$?
fi
set -e

with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" logcat -d > "${LOG_PATH}" || true

FAILURE_CLASSIFICATION="passed"
if [[ ${MAESTRO_EXIT} -ne 0 ]]; then
  if with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" get-state >/dev/null 2>&1 && \
    with_timeout "${ADB_TIMEOUT_SEC}" adb -s "${DEVICE_SERIAL}" shell echo maestro-android-ok >/dev/null 2>&1; then
    if maestro_android_log_has_transient_failure "${MAESTRO_LOG_PATH}"; then
      FAILURE_CLASSIFICATION="maestro_server_bootstrap"
    else
      FAILURE_CLASSIFICATION="product_ui_or_flow"
    fi
  else
    FAILURE_CLASSIFICATION="adb_transport"
  fi
fi

CRASH_MATCH=0
if rg -n -C 25 "${CRASH_SIGNATURE_REGEX}" "${LOG_PATH}" > "${CANDIDATE_PATH}" 2>/dev/null; then
  if rg -n "${APP_CONTEXT_REGEX}" "${CANDIDATE_PATH}" >/dev/null 2>&1; then
    mv "${CANDIDATE_PATH}" "${MATCH_PATH}"
    CRASH_MATCH=1
  else
    rm -f "${CANDIDATE_PATH}"
  fi
fi

echo "Scoped repro summary:"
echo "  Flow: ${FLOW_PATH}"
if [[ -n "${FLOW_RUN_NOTE}" ]]; then
  echo "  Flow execution mode: ${FLOW_RUN_NOTE}"
fi
echo "  Device: ${DEVICE_SERIAL}"
echo "  APK: ${APK_PATH}"
echo "  Maestro log: ${MAESTRO_LOG_PATH}"
echo "  Logcat: ${LOG_PATH}"
echo "  Maestro exit code: ${MAESTRO_EXIT}"
echo "  Failure classification: ${FAILURE_CLASSIFICATION}"

if [[ ${CRASH_MATCH} -eq 1 ]]; then
  echo "  Crash/runtime signatures: FOUND (${MATCH_PATH})"
  echo "  First matches:"
  sed -n '1,60p' "${MATCH_PATH}"
else
  echo "  Crash/runtime signatures: none"
fi

if [[ ${MAESTRO_EXIT} -ne 0 ]]; then
  exit "${MAESTRO_EXIT}"
fi

if [[ ${CRASH_MATCH} -eq 1 ]]; then
  exit 86
fi
