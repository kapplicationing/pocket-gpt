#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

SERIAL="${ANDROID_SERIAL:-}"
PORT="${POCKETGPT_HF_FIXTURE_PORT:-8765}"
SIZE_BYTES="${POCKETGPT_HF_FIXTURE_SIZE_BYTES:-25165824}"
CHUNK_SIZE="${POCKETGPT_HF_FIXTURE_CHUNK_SIZE:-16384}"
CHUNK_DELAY_MS="${POCKETGPT_HF_FIXTURE_CHUNK_DELAY_MS:-20}"
BUILD_APK=1
RUN_PROBE=1
ENABLE_NATIVE_BUILD="${POCKETGPT_ENABLE_NATIVE_BUILD:-true}"
FLOW="tests/maestro/scenario-hf-fixture-download-smoke.yaml"
RUN_ROOT=""
TRANSPORT="${POCKETGPT_HF_FIXTURE_TRANSPORT:-reverse}"
HOST_IP="${POCKETGPT_HF_FIXTURE_HOST_IP:-}"

usage() {
  cat <<'USAGE'
Usage: bash scripts/dev/maestro-hf-fixture-smoke.sh [options]

Options:
  --serial <adb-serial>  Pin the connected device. Defaults to ANDROID_SERIAL.
  --flow <path>          Maestro flow to run. Default: tests/maestro/scenario-hf-fixture-download-smoke.yaml.
  --port <port>          Local fixture server port. Default: 8765.
  --run-root <path>      Artifact directory. Default: tmp/hf-fixture-smoke/<timestamp>.
  --transport <mode>     Fixture transport: reverse or lan. Default: reverse.
  --host-ip <ip>         Host IP for --transport lan. Defaults to macOS route detection.
  --no-build             Reuse the currently installed debug APK.
  --disable-native-build Build without native bridge libraries. Use only for compile/debug triage.
  --skip-probe           Skip the maestro-android bootstrap probe before the flow.
  --help                 Show this help text.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      SERIAL="${2:?missing serial}"
      shift 2
      ;;
    --flow)
      FLOW="${2:?missing flow path}"
      shift 2
      ;;
    --port)
      PORT="${2:?missing port}"
      shift 2
      ;;
    --run-root)
      RUN_ROOT="${2:?missing run root}"
      shift 2
      ;;
    --transport)
      TRANSPORT="${2:?missing transport}"
      shift 2
      ;;
    --host-ip)
      HOST_IP="${2:?missing host ip}"
      shift 2
      ;;
    --no-build)
      BUILD_APK=0
      shift
      ;;
    --disable-native-build)
      ENABLE_NATIVE_BUILD=false
      shift
      ;;
    --skip-probe)
      RUN_PROBE=0
      shift
      ;;
    --help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 64
      ;;
  esac
done

if [[ -z "${SERIAL}" ]]; then
  echo "ANDROID_SERIAL or --serial <id> required" >&2
  exit 64
fi

if [[ ! -f "${FLOW}" ]]; then
  echo "Flow not found: ${FLOW}" >&2
  exit 64
fi

if [[ "${TRANSPORT}" != "reverse" && "${TRANSPORT}" != "lan" ]]; then
  echo "Unsupported fixture transport: ${TRANSPORT}. Use reverse or lan." >&2
  exit 64
fi

if [[ -z "${RUN_ROOT}" ]]; then
  RUN_ROOT="tmp/hf-fixture-smoke/$(date -u +%Y%m%dT%H%M%SZ)"
fi
mkdir -p "${RUN_ROOT}"

DEVICE_STATE="$(adb -s "${SERIAL}" get-state 2>/dev/null || true)"
if [[ "${DEVICE_STATE}" != "device" ]]; then
  echo "Device ${SERIAL} is not online for adb; state=${DEVICE_STATE:-missing}." >&2
  echo "Re-pair wireless debugging or connect over USB, then rerun this wrapper." >&2
  exit 1
fi

SERVER_LOG="${RUN_ROOT}/hf-fixture-server.log"
SERVER_MANIFEST="${RUN_ROOT}/hf-fixture-manifest.json"
JUNIT_PATH="${RUN_ROOT}/maestro-junit.xml"
MAESTRO_LOG="${RUN_ROOT}/maestro-output.log"
MAESTRO_DEBUG_DIR="${RUN_ROOT}/maestro-debug"
LOGCAT_PATH="${RUN_ROOT}/logcat.txt"
SERVER_HOST="127.0.0.1"
FIXTURE_BASE_URL="http://127.0.0.1:${PORT}/"

detect_host_ip() {
  local detected
  detected="$(ipconfig getifaddr en0 2>/dev/null || true)"
  if [[ -z "${detected}" ]]; then
    detected="$(ipconfig getifaddr en1 2>/dev/null || true)"
  fi
  if [[ -z "${detected}" ]]; then
    detected="$(python3 - <<'PY'
import socket

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
try:
    sock.connect(("8.8.8.8", 80))
    print(sock.getsockname()[0])
finally:
    sock.close()
PY
)"
  fi
  printf '%s' "${detected}"
}

if [[ "${TRANSPORT}" == "lan" ]]; then
  SERVER_HOST="0.0.0.0"
  if [[ -z "${HOST_IP}" ]]; then
    HOST_IP="$(detect_host_ip)"
  fi
  if [[ -z "${HOST_IP}" ]]; then
    echo "Could not detect a host LAN IP. Rerun with --host-ip <ip>." >&2
    exit 1
  fi
  FIXTURE_BASE_URL="http://${HOST_IP}:${PORT}/"
fi

cleanup() {
  if [[ "${TRANSPORT}" == "reverse" ]]; then
    adb -s "${SERIAL}" reverse --remove "tcp:${PORT}" >/dev/null 2>&1 || true
  fi
  if [[ -n "${LOGCAT_PID:-}" ]]; then
    kill "${LOGCAT_PID}" 2>/dev/null || true
    wait "${LOGCAT_PID}" 2>/dev/null || true
  fi
  if [[ -n "${SERVER_PID:-}" ]]; then
    kill "${SERVER_PID}" 2>/dev/null || true
    wait "${SERVER_PID}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

if python3 - "${PORT}" <<'PY' >/dev/null 2>&1
import socket
import sys

with socket.create_connection(("127.0.0.1", int(sys.argv[1])), timeout=0.2):
    raise SystemExit(0)
PY
then
  echo "Fixture port ${PORT} is already in use on the host." >&2
  echo "Stop the existing fixture server or rerun with --port <free-port>." >&2
  exit 1
fi

python3 scripts/dev/hf-fixture-server.py \
  --host "${SERVER_HOST}" \
  --port "${PORT}" \
  --size-bytes "${SIZE_BYTES}" \
  --chunk-size "${CHUNK_SIZE}" \
  --chunk-delay-ms "${CHUNK_DELAY_MS}" \
  --write-manifest "${SERVER_MANIFEST}" \
  >"${SERVER_LOG}" 2>&1 &
SERVER_PID=$!

SERVER_READY=0
for _ in {1..50}; do
  if python3 - "${PORT}" <<'PY' >/dev/null 2>&1
import sys
import urllib.request

port = sys.argv[1]
with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=0.5) as response:
    raise SystemExit(0 if response.status == 200 else 1)
PY
  then
    SERVER_READY=1
    break
  fi
  sleep 0.1
done

if ! kill -0 "${SERVER_PID}" 2>/dev/null || [[ "${SERVER_READY}" -ne 1 ]]; then
  echo "Fixture server failed to become healthy. See ${SERVER_LOG}" >&2
  exit 1
fi

if [[ "${TRANSPORT}" == "reverse" ]]; then
  adb -s "${SERIAL}" reverse "tcp:${PORT}" "tcp:${PORT}" >/dev/null
fi

if [[ ${BUILD_APK} -eq 1 ]]; then
  GRADLE_USER_HOME=.gradle-home ./gradlew --no-daemon \
    --rerun-tasks \
    -Ppocketgpt.enableNativeBuild="${ENABLE_NATIVE_BUILD}" \
    -Ppocketgpt.hfFixtureBaseUrl="${FIXTURE_BASE_URL}" \
    :apps:mobile-android:assembleDebug

  BUILD_CONFIG_PATH="apps/mobile-android/build/generated/source/buildConfig/debug/com/pocketagent/android/BuildConfig.java"
  if [[ ! -f "${BUILD_CONFIG_PATH}" ]] ||
    ! grep -Fq "HF_FIXTURE_BASE_URL = \"${FIXTURE_BASE_URL}\"" "${BUILD_CONFIG_PATH}"; then
    echo "Debug APK was not built with the requested HF fixture base URL: ${FIXTURE_BASE_URL}" >&2
    echo "Inspect ${BUILD_CONFIG_PATH} before running Maestro." >&2
    exit 1
  fi
  grep -F "HF_FIXTURE_BASE_URL" "${BUILD_CONFIG_PATH}" >"${RUN_ROOT}/build-config-fixture-url.txt"

  APK_PATH="$(find apps/mobile-android/build/outputs/apk/debug -type f -name '*.apk' | sort | head -n 1)"
  if [[ -z "${APK_PATH}" || ! -f "${APK_PATH}" ]]; then
    echo "Debug APK not found." >&2
    exit 1
  fi
  adb -s "${SERIAL}" install -r "${APK_PATH}" >/dev/null
fi

if [[ ${RUN_PROBE} -eq 1 ]]; then
  PROBE_LOG="${RUN_ROOT}/maestro-bootstrap-probe.log"
  set +e
  maestro-android device --device "${SERIAL}" probe 2>&1 | tee "${PROBE_LOG}"
  PROBE_EXIT=${PIPESTATUS[0]}
  set -e
  if [[ ${PROBE_EXIT} -ne 0 ]]; then
    echo "Maestro bootstrap probe failed; skipping HF fixture flow. See ${PROBE_LOG}" >&2
    exit "${PROBE_EXIT}"
  fi
fi

cat >"${RUN_ROOT}/run-manifest.json" <<EOF
{
  "adb_serial": "${SERIAL}",
  "chunk_delay_ms": ${CHUNK_DELAY_MS},
  "chunk_size": ${CHUNK_SIZE},
  "fixture_base_url": "${FIXTURE_BASE_URL}",
  "fixture_transport": "${TRANSPORT}",
  "fixture_server_host": "${SERVER_HOST}",
  "fixture_host_ip": "${HOST_IP}",
  "flow": "${FLOW}",
  "enable_native_build": ${ENABLE_NATIVE_BUILD},
  "build_config_fixture_url": "${RUN_ROOT}/build-config-fixture-url.txt",
  "maestro_debug_dir": "${MAESTRO_DEBUG_DIR}",
  "junit_path": "${JUNIT_PATH}",
  "logcat_path": "${LOGCAT_PATH}",
  "maestro_log": "${MAESTRO_LOG}",
  "run_probe": ${RUN_PROBE},
  "server_manifest": "${SERVER_MANIFEST}",
  "size_bytes": ${SIZE_BYTES}
}
EOF

adb -s "${SERIAL}" logcat -c >/dev/null 2>&1 || true
adb -s "${SERIAL}" logcat -v time >"${LOGCAT_PATH}" 2>&1 &
LOGCAT_PID=$!

set +e
maestro --device "${SERIAL}" test "${FLOW}" \
  --debug-output "${MAESTRO_DEBUG_DIR}" \
  --format junit \
  --output "${JUNIT_PATH}" 2>&1 | tee "${MAESTRO_LOG}"
MAESTRO_EXIT=${PIPESTATUS[0]}
set -e

if [[ ${MAESTRO_EXIT} -ne 0 ]]; then
  echo "Maestro fixture smoke failed. Artifacts: ${RUN_ROOT}" >&2
  echo "Read ${LOGCAT_PATH}, ${SERVER_LOG}, and the screenshot before classifying product vs local adb-reverse transport." >&2
  exit "${MAESTRO_EXIT}"
fi

echo "ok: HF fixture smoke passed. Artifacts: ${RUN_ROOT}"
