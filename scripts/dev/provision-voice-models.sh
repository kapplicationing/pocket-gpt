#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF' >&2
Usage:
  bash scripts/dev/provision-voice-models.sh \
    --device <serial> \
    --asr-dir <official-asr-model-dir> \
    --kws-dir <official-kws-model-dir>

Installs integrity-verified Sherpa ASR and/or dedicated Offas wake-word files
into an already-installed debuggable PocketAgent build. Existing complete model
directories are replaced atomically. The KWS bundle's stock multi-keyword file
is intentionally replaced with config/voice/offas-keywords.txt. The production
compatibility marker is written only after both complete int8 model sets verify.
EOF
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEVICE=""
ASR_DIR=""
KWS_DIR=""
PACKAGE="com.pocketagent.android"
DEVICE_STAGE="/data/local/tmp/pocketgpt-voice-provision"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device) DEVICE="${2:-}"; shift 2 ;;
    --asr-dir) ASR_DIR="${2:-}"; shift 2 ;;
    --kws-dir) KWS_DIR="${2:-}"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ -z "${DEVICE}" || -z "${ASR_DIR}" || -z "${KWS_DIR}" ]]; then
  usage
  exit 2
fi

for command in adb shasum; do
  command -v "${command}" >/dev/null 2>&1 || {
    echo "Required command is unavailable: ${command}" >&2
    exit 1
  }
done

if [[ "$(adb -s "${DEVICE}" get-state 2>/dev/null || true)" != "device" ]]; then
  echo "Device is not available through adb: ${DEVICE}" >&2
  exit 1
fi
if ! adb -s "${DEVICE}" shell pm path "${PACKAGE}" | grep -q '^package:'; then
  echo "Install a debuggable PocketAgent build before provisioning voice models." >&2
  exit 1
fi
if ! adb -s "${DEVICE}" shell run-as "${PACKAGE}" pwd >/dev/null 2>&1; then
  echo "The installed PocketAgent build is not debuggable; run-as provisioning is unavailable." >&2
  exit 1
fi

HOST_STAGE="$(mktemp -d "${TMPDIR:-/tmp}/pocketgpt-voice-provision.XXXXXX")"
cleanup() {
  rm -rf "${HOST_STAGE}"
  adb -s "${DEVICE}" shell rm -rf "${DEVICE_STAGE}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

verify_and_copy() {
  local source_dir="$1"
  local target_name="$2"
  local target_dir="${HOST_STAGE}/${target_name}"
  local files=()
  local hashes=()

  if [[ "${target_name}" == "${ASR_NAME}" ]]; then
    files=("${ASR_FILES[@]}")
    hashes=("${ASR_HASHES[@]}")
  else
    files=("${KWS_FILES[@]}")
    hashes=("${KWS_HASHES[@]}")
  fi

  if [[ ! -d "${source_dir}" || "$(basename "${source_dir}")" != "${target_name}" ]]; then
    echo "Expected model directory named ${target_name}: ${source_dir}" >&2
    exit 1
  fi
  mkdir -p "${target_dir}"
  for index in "${!files[@]}"; do
    local file="${files[${index}]}"
    local source="${source_dir}/${file}"
    [[ -f "${source}" ]] || { echo "Missing model file: ${source}" >&2; exit 1; }
    local actual_hash
    actual_hash="$(shasum -a 256 "${source}" | awk '{print $1}')"
    if [[ "${actual_hash}" != "${hashes[${index}]}" ]]; then
      echo "Model hash mismatch: ${source}" >&2
      exit 1
    fi
    cp "${source}" "${target_dir}/${file}"
  done
}

ASR_NAME="sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
ASR_FILES=(
  "encoder-epoch-99-avg-1.int8.onnx"
  "decoder-epoch-99-avg-1.onnx"
  "joiner-epoch-99-avg-1.int8.onnx"
  "tokens.txt"
)
ASR_HASHES=(
  "3810755ce7c3ab26b42a8bcf39d191308fa27fb0f53358823ba46141d03b7eb3"
  "45a7f940ecfb53d89fa270ad11b88b961e53a317203eb24b1c8e95ed208b0f30"
  "e085d73b593cf9b0707f370dbd656d58327d3fe36d80d849202ef81df02cb01e"
  "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb"
)

KWS_NAME="sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
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

MODELS=()
if [[ -n "${ASR_DIR}" ]]; then
  verify_and_copy "${ASR_DIR}" "${ASR_NAME}"
  MODELS+=("${ASR_NAME}")
fi
if [[ -n "${KWS_DIR}" ]]; then
  verify_and_copy "${KWS_DIR}" "${KWS_NAME}"
  cp "${REPO_ROOT}/config/voice/offas-keywords.txt" "${HOST_STAGE}/${KWS_NAME}/keywords.txt"
  MODELS+=("${KWS_NAME}")
fi

adb -s "${DEVICE}" shell am force-stop "${PACKAGE}"
adb -s "${DEVICE}" shell rm -rf "${DEVICE_STAGE}"
adb -s "${DEVICE}" shell mkdir -p "${DEVICE_STAGE}"
for model in "${MODELS[@]}"; do
  adb -s "${DEVICE}" push "${HOST_STAGE}/${model}" "${DEVICE_STAGE}/" >/dev/null
  destination="files/offas-voice-models/${model}"
  staging="${destination}.staging"
  adb -s "${DEVICE}" shell run-as "${PACKAGE}" rm -rf "${staging}"
  adb -s "${DEVICE}" shell run-as "${PACKAGE}" mkdir -p "${staging}"
  while IFS= read -r file; do
    adb -s "${DEVICE}" shell run-as "${PACKAGE}" cp \
      "${DEVICE_STAGE}/${model}/${file}" "${staging}/${file}" </dev/null
  done < <(find "${HOST_STAGE}/${model}" -maxdepth 1 -type f -exec basename {} \; | sort)
  adb -s "${DEVICE}" shell run-as "${PACKAGE}" rm -rf "${destination}"
  adb -s "${DEVICE}" shell run-as "${PACKAGE}" mv "${staging}" "${destination}"

  while IFS= read -r file; do
    host_hash="$(shasum -a 256 "${HOST_STAGE}/${model}/${file}" | awk '{print $1}')"
    device_hash="$(adb -s "${DEVICE}" exec-out run-as "${PACKAGE}" sha256sum "${destination}/${file}" </dev/null | awk '{print $1}' | tr -d '\r')"
    if [[ "${host_hash}" != "${device_hash}" ]]; then
      echo "Device verification failed: ${model}/${file}" >&2
      exit 1
    fi
  done < <(find "${HOST_STAGE}/${model}" -maxdepth 1 -type f -exec basename {} \; | sort)
  echo "Provisioned and verified ${model}"
done

MARKER_NAME="install-manifest.properties"
MARKER_CONTENT=$'profile=offas-int8-sherpa-8.5.4-v2\nruntime=8.5.4\n'
printf '%s' "${MARKER_CONTENT}" > "${HOST_STAGE}/${MARKER_NAME}"
adb -s "${DEVICE}" push "${HOST_STAGE}/${MARKER_NAME}" "${DEVICE_STAGE}/${MARKER_NAME}" >/dev/null
adb -s "${DEVICE}" shell run-as "${PACKAGE}" mkdir -p files/offas-voice-models
adb -s "${DEVICE}" shell run-as "${PACKAGE}" cp \
  "${DEVICE_STAGE}/${MARKER_NAME}" "files/offas-voice-models/${MARKER_NAME}"
DEVICE_MARKER="$(adb -s "${DEVICE}" exec-out run-as "${PACKAGE}" \
  cat "files/offas-voice-models/${MARKER_NAME}" | tr -d '\r')"
if [[ "${DEVICE_MARKER}" != "${MARKER_CONTENT%$'\n'}" ]]; then
  echo "Device verification failed: ${MARKER_NAME}" >&2
  exit 1
fi
echo "Published production voice profile marker"

echo "Voice model files are ready. Launch PocketAgent and re-open Voice access to refresh status."
echo "Production always-on qualification is a separate retained device-tier gate."
