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
RUN_ROOT=""

usage() {
  cat <<'USAGE'
Usage: bash scripts/dev/maestro-hf-fixture-smoke.sh [options]

Options:
  --serial <adb-serial>  Pin the connected device. Defaults to ANDROID_SERIAL.
  --port <port>          Local fixture server port. Default: 8765.
  --run-root <path>      Artifact directory. Default: tmp/hf-fixture-smoke/<timestamp>.
  --no-build             Reuse the currently installed debug APK.
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
    --port)
      PORT="${2:?missing port}"
      shift 2
      ;;
    --run-root)
      RUN_ROOT="${2:?missing run root}"
      shift 2
      ;;
    --no-build)
      BUILD_APK=0
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

if [[ -z "${RUN_ROOT}" ]]; then
  RUN_ROOT="tmp/hf-fixture-smoke/$(date -u +%Y%m%dT%H%M%SZ)"
fi
mkdir -p "${RUN_ROOT}"

SERVER_LOG="${RUN_ROOT}/hf-fixture-server.log"
SERVER_MANIFEST="${RUN_ROOT}/hf-fixture-manifest.json"
JUNIT_PATH="${RUN_ROOT}/maestro-junit.xml"
MAESTRO_LOG="${RUN_ROOT}/maestro-output.log"
MAESTRO_DEBUG_DIR="${RUN_ROOT}/maestro-debug"
FIXTURE_BASE_URL="http://127.0.0.1:${PORT}/"

cleanup() {
  adb -s "${SERIAL}" reverse --remove "tcp:${PORT}" >/dev/null 2>&1 || true
  if [[ -n "${SERVER_PID:-}" ]]; then
    kill "${SERVER_PID}" 2>/dev/null || true
    wait "${SERVER_PID}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

python3 scripts/dev/hf-fixture-server.py \
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

adb -s "${SERIAL}" reverse "tcp:${PORT}" "tcp:${PORT}" >/dev/null

if [[ ${BUILD_APK} -eq 1 ]]; then
  GRADLE_USER_HOME=.gradle-home ./gradlew --no-daemon \
    -Ppocketgpt.enableNativeBuild=false \
    -Ppocketgpt.hfFixtureBaseUrl="${FIXTURE_BASE_URL}" \
    :apps:mobile-android:assembleDebug

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
  "flow": "tests/maestro/scenario-hf-fixture-download-smoke.yaml",
  "maestro_debug_dir": "${MAESTRO_DEBUG_DIR}",
  "junit_path": "${JUNIT_PATH}",
  "maestro_log": "${MAESTRO_LOG}",
  "run_probe": ${RUN_PROBE},
  "server_manifest": "${SERVER_MANIFEST}",
  "size_bytes": ${SIZE_BYTES}
}
EOF

set +e
maestro --device "${SERIAL}" test tests/maestro/scenario-hf-fixture-download-smoke.yaml \
  --debug-output "${MAESTRO_DEBUG_DIR}" \
  --format junit \
  --output "${JUNIT_PATH}" 2>&1 | tee "${MAESTRO_LOG}"
MAESTRO_EXIT=${PIPESTATUS[0]}
set -e

if [[ ${MAESTRO_EXIT} -ne 0 ]]; then
  echo "Maestro fixture smoke failed. Artifacts: ${RUN_ROOT}" >&2
  exit "${MAESTRO_EXIT}"
fi

echo "ok: HF fixture smoke passed. Artifacts: ${RUN_ROOT}"
