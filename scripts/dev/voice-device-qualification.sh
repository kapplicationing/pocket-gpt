#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF' >&2
Usage:
  bash scripts/dev/voice-device-qualification.sh \
    --device <serial> \
    --fixture-dir <sherpa-asr-model-dir> \
    [--kws-fixture-dir <sherpa-kws-model-dir>] \
    [--test-method <method>] \
    [--no-build] [--no-install]

Runs the canonical physical voice qualification through maestro-android. The
ASR fixture directory must be the official
sherpa-onnx-streaming-zipformer-en-20M-2023-02-17 model and contain test_wavs/0.wav.
Pass --kws-fixture-dir to add the exact production int8 dedicated wake-model
positive/negative wiring probe.

The script verifies every selected fixture, transfers only the required runtime
files plus one ASR test wave, and removes transport fixtures on exit. The
connected instrumentation runner may uninstall the debug app during teardown.
EOF
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

DEVICE=""
FIXTURE_DIR=""
KWS_FIXTURE_DIR=""
TEST_METHOD=""
MAESTRO_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      DEVICE="${2:-}"
      shift 2
      ;;
    --fixture-dir)
      FIXTURE_DIR="${2:-}"
      shift 2
      ;;
    --kws-fixture-dir)
      KWS_FIXTURE_DIR="${2:-}"
      shift 2
      ;;
    --test-method)
      TEST_METHOD="${2:-}"
      shift 2
      ;;
    --no-build|--no-install)
      MAESTRO_ARGS+=("$1")
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 2
      ;;
  esac
done

if [[ -z "${DEVICE}" || -z "${FIXTURE_DIR}" ]]; then
  usage
  exit 2
fi

for command in adb maestro-android shasum; do
  if ! command -v "${command}" >/dev/null 2>&1; then
    echo "Required command is unavailable: ${command}" >&2
    exit 1
  fi
done

ASR_DIR="sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
KWS_DIR="sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
DEVICE_STAGE="/data/local/tmp/pocketgpt-voice-fixtures"
APP_MODEL_DIR="files/offas-voice-models/${ASR_DIR}"
TEST_CLASS="com.pocketagent.android.voice.VoiceDeviceQualificationTest"
TEST_SELECTOR="${TEST_CLASS}${TEST_METHOD:+#${TEST_METHOD}}"
FILES=(
  "encoder-epoch-99-avg-1.int8.onnx"
  "decoder-epoch-99-avg-1.onnx"
  "joiner-epoch-99-avg-1.int8.onnx"
  "tokens.txt"
  "test_wavs/0.wav"
)
HASHES=(
  "3810755ce7c3ab26b42a8bcf39d191308fa27fb0f53358823ba46141d03b7eb3"
  "45a7f940ecfb53d89fa270ad11b88b961e53a317203eb24b1c8e95ed208b0f30"
  "e085d73b593cf9b0707f370dbd656d58327d3fe36d80d849202ef81df02cb01e"
  "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb"
  "6bc58a4efdf20daac252b6b1502632601a71efe0308f6757dc1eda34891a7e4f"
)

if [[ "$(basename "${FIXTURE_DIR}")" != "${ASR_DIR}" ]]; then
  echo "Expected fixture directory named ${ASR_DIR}: ${FIXTURE_DIR}" >&2
  exit 1
fi

for index in "${!FILES[@]}"; do
  path="${FIXTURE_DIR}/${FILES[${index}]}"
  if [[ ! -f "${path}" ]]; then
    echo "Voice fixture is missing ${FILES[${index}]}" >&2
    exit 1
  fi
  actual_hash="$(shasum -a 256 "${path}" | awk '{print $1}')"
  if [[ "${actual_hash}" != "${HASHES[${index}]}" ]]; then
    echo "Voice fixture hash mismatch: ${FILES[${index}]}" >&2
    exit 1
  fi
done

if [[ "$(adb -s "${DEVICE}" get-state 2>/dev/null || true)" != "device" ]]; then
  echo "Device is not available through adb: ${DEVICE}" >&2
  exit 1
fi

HOST_STAGE="$(mktemp -d "${TMPDIR:-/tmp}/pocketgpt-voice-qualification.XXXXXX")"

cleanup() {
  status=$?
  rm -rf "${HOST_STAGE}"
  if [[ "$(adb -s "${DEVICE}" get-state 2>/dev/null || true)" == "device" ]]; then
    adb -s "${DEVICE}" shell rm -rf "${DEVICE_STAGE}" >/dev/null 2>&1 || true
    adb -s "${DEVICE}" shell run-as com.pocketagent.android \
      rm -rf "${APP_MODEL_DIR}/test_wavs" >/dev/null 2>&1 || true
  else
    echo "Warning: adb disconnected before device fixture cleanup: ${DEVICE_STAGE}" >&2
  fi
  return "${status}"
}
trap cleanup EXIT

mkdir -p "${HOST_STAGE}/${ASR_DIR}/test_wavs"
for file in "${FILES[@]}"; do
  cp "${FIXTURE_DIR}/${file}" "${HOST_STAGE}/${ASR_DIR}/${file}"
done

if [[ -n "${KWS_FIXTURE_DIR}" ]]; then
  if [[ "$(basename "${KWS_FIXTURE_DIR}")" != "${KWS_DIR}" ]]; then
    echo "Expected KWS fixture directory named ${KWS_DIR}: ${KWS_FIXTURE_DIR}" >&2
    exit 1
  fi
  KWS_FILES=(
    "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    "tokens.txt"
  )
  KWS_HASHES=(
    "1e721676515bcd42a186979733981213c66c80db680e1cc582dfedf3be76e678"
    "e40ff43297abe815e8898494c17e71bba2152d9d40fa3eb803f75d0f7533329a"
    "eae9da0c7e1e6c6a3f4cc42d167899c388f6c6701b94cb96320e4f55df79624c"
    "fd2ded4050a55d2b1578870ba8697d02371980217806b7558bd0a5cc60f3ba53"
  )
  mkdir -p "${HOST_STAGE}/${KWS_DIR}"
  for index in "${!KWS_FILES[@]}"; do
    file="${KWS_FILES[${index}]}"
    path="${KWS_FIXTURE_DIR}/${file}"
    [[ -f "${path}" ]] || { echo "Wake fixture is missing ${file}" >&2; exit 1; }
    actual_hash="$(shasum -a 256 "${path}" | awk '{print $1}')"
    if [[ "${actual_hash}" != "${KWS_HASHES[${index}]}" ]]; then
      echo "Wake fixture hash mismatch: ${file}" >&2
      exit 1
    fi
    cp "${path}" "${HOST_STAGE}/${KWS_DIR}/${file}"
  done
  cp "${REPO_ROOT}/config/voice/offas-keywords.txt" "${HOST_STAGE}/${KWS_DIR}/keywords.txt"
fi

adb -s "${DEVICE}" shell rm -rf "${DEVICE_STAGE}"
adb -s "${DEVICE}" shell mkdir -p "${DEVICE_STAGE}"
adb -s "${DEVICE}" push "${HOST_STAGE}/${ASR_DIR}" "${DEVICE_STAGE}/"
if [[ -n "${KWS_FIXTURE_DIR}" ]]; then
  adb -s "${DEVICE}" push "${HOST_STAGE}/${KWS_DIR}" "${DEVICE_STAGE}/"
fi

RUNNER_ARGS=(
  --runner-arg "voice_fixture_dir=${DEVICE_STAGE}"
  --runner-arg "voice_acoustic_source=device_fixture"
)
if [[ -n "${KWS_FIXTURE_DIR}" ]]; then
  RUNNER_ARGS+=(--runner-arg "voice_kws_fixture_dir=${DEVICE_STAGE}")
fi

run_maestro() {
  maestro-android scoped \
    --type instrumented \
    --test-class "${TEST_SELECTOR}" \
    "${RUNNER_ARGS[@]}" \
    --device "${DEVICE}" \
    "$@"
}

if [[ ${#MAESTRO_ARGS[@]} -gt 0 ]]; then
  run_maestro "${MAESTRO_ARGS[@]}"
else
  run_maestro
fi
