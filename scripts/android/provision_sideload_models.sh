#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE' >&2
Usage:
  bash scripts/android/provision_sideload_models.sh \
    --device <serial> \
    --model-0-8b-local <host-path-to-gguf> \
    --model-1-7b-local <host-path-to-gguf> \
    [--device-dir <device-directory>]

Example:
  bash scripts/android/provision_sideload_models.sh \
    --device DEVICE_SERIAL_REDACTED \
    --model-0-8b-local /models/qwen3.5-0.8b-q4.gguf \
    --model-1-7b-local /models/qwen3-1.7b-q4_k_m.gguf \
    --device-dir /sdcard/Download/pocketgpt-models
USAGE
}

DEVICE=""
MODEL_0_8B_LOCAL=""
MODEL_1_7B_LOCAL=""
DEVICE_DIR="/sdcard/Download/pocketgpt-models"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      DEVICE="${2:-}"
      shift 2
      ;;
    --model-0-8b-local)
      MODEL_0_8B_LOCAL="${2:-}"
      shift 2
      ;;
    --model-1-7b-local)
      MODEL_1_7B_LOCAL="${2:-}"
      shift 2
      ;;
    --device-dir)
      DEVICE_DIR="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown flag: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "${DEVICE}" || -z "${MODEL_0_8B_LOCAL}" || -z "${MODEL_1_7B_LOCAL}" ]]; then
  echo "Missing required arguments." >&2
  usage
  exit 1
fi

if [[ ! -f "${MODEL_0_8B_LOCAL}" ]]; then
  echo "Missing local model file: ${MODEL_0_8B_LOCAL}" >&2
  exit 1
fi
if [[ ! -f "${MODEL_1_7B_LOCAL}" ]]; then
  echo "Missing local model file: ${MODEL_1_7B_LOCAL}" >&2
  exit 1
fi

MODEL_0_8B_DEVICE_PATH="${DEVICE_DIR}/qwen3.5-0.8b-q4.gguf"
MODEL_1_7B_DEVICE_PATH="${DEVICE_DIR}/qwen3-1.7b-q4_k_m.gguf"

CACHE_DIR="scripts/benchmarks/cache/${DEVICE}"
ENV_DIR="scripts/benchmarks/device-env"
mkdir -p "${CACHE_DIR}" "${ENV_DIR}"
CACHE_FILE="${CACHE_DIR}/model-provision-state.env"
ENV_FILE="${ENV_DIR}/${DEVICE}.env"

host_sha256() {
  local path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${path}" | awk '{print $1}'
  else
    shasum -a 256 "${path}" | awk '{print $1}'
  fi
}

device_sha256() {
  local path="$1"
  adb -s "${DEVICE}" shell "if command -v sha256sum >/dev/null 2>&1; then sha256sum '${path}'; elif command -v toybox >/dev/null 2>&1; then toybox sha256sum '${path}'; else echo ''; fi" \
    | tr -d '\r' | awk '{print $1}'
}

device_file_exists() {
  local path="$1"
  adb -s "${DEVICE}" shell "test -f '${path}'" >/dev/null 2>&1
}

push_if_changed() {
  local local_path="$1"
  local device_path="$2"
  local label="$3"

  local host_sha
  host_sha="$(host_sha256 "${local_path}")"
  local device_sha=""
  local skipped=0

  if device_file_exists "${device_path}"; then
    device_sha="$(device_sha256 "${device_path}" || true)"
  fi

  if [[ -n "${device_sha}" && "${host_sha}" == "${device_sha}" ]]; then
    echo "${label}: skip push (SHA unchanged)"
    skipped=1
  else
    echo "${label}: pushing to ${device_path}"
    adb -s "${DEVICE}" push "${local_path}" "${device_path}" >/dev/null
    device_sha="$(device_sha256 "${device_path}")"
    if [[ -z "${device_sha}" || "${device_sha}" != "${host_sha}" ]]; then
      echo "${label}: SHA mismatch after push" >&2
      exit 1
    fi
  fi

  if [[ "${label}" == "0.8B" ]]; then
    MODEL_0_8B_HOST_SHA="${host_sha}"
    MODEL_0_8B_DEVICE_SHA="${device_sha}"
    MODEL_0_8B_SKIPPED="${skipped}"
  else
    MODEL_1_7B_HOST_SHA="${host_sha}"
    MODEL_1_7B_DEVICE_SHA="${device_sha}"
    MODEL_1_7B_SKIPPED="${skipped}"
  fi
}

echo "Ensuring destination directory exists on device..."
adb -s "${DEVICE}" shell "mkdir -p '${DEVICE_DIR}'" >/dev/null

push_if_changed "${MODEL_0_8B_LOCAL}" "${MODEL_0_8B_DEVICE_PATH}" "0.8B"
push_if_changed "${MODEL_1_7B_LOCAL}" "${MODEL_1_7B_DEVICE_PATH}" "1.7B"

echo "Verifying files on device..."
adb -s "${DEVICE}" shell "test -f '${MODEL_0_8B_DEVICE_PATH}' && test -f '${MODEL_1_7B_DEVICE_PATH}'"

cat > "${CACHE_FILE}" <<STATE
DEVICE=${DEVICE}
DEVICE_DIR=${DEVICE_DIR}
MODEL_0_8B_DEVICE_PATH=${MODEL_0_8B_DEVICE_PATH}
MODEL_1_7B_DEVICE_PATH=${MODEL_1_7B_DEVICE_PATH}
MODEL_0_8B_HOST_SHA=${MODEL_0_8B_HOST_SHA}
MODEL_1_7B_HOST_SHA=${MODEL_1_7B_HOST_SHA}
MODEL_0_8B_DEVICE_SHA=${MODEL_0_8B_DEVICE_SHA}
MODEL_1_7B_DEVICE_SHA=${MODEL_1_7B_DEVICE_SHA}
MODEL_0_8B_SKIPPED=${MODEL_0_8B_SKIPPED}
MODEL_1_7B_SKIPPED=${MODEL_1_7B_SKIPPED}
UPDATED_AT=$(date +%FT%T)
STATE

MODEL_PROVISION_SKIPPED="0"
if [[ "${MODEL_0_8B_SKIPPED}" == "1" && "${MODEL_1_7B_SKIPPED}" == "1" ]]; then
  MODEL_PROVISION_SKIPPED="1"
fi

cat > "${ENV_FILE}" <<ENV
export POCKETGPT_QWEN_3_5_0_8B_Q4_SIDELOAD_PATH=${MODEL_0_8B_DEVICE_PATH}
export POCKETGPT_QWEN3_1_7B_Q4_K_M_SIDELOAD_PATH=${MODEL_1_7B_DEVICE_PATH}
export POCKETGPT_STAGE2_MODEL_PROVISION_SKIPPED=${MODEL_PROVISION_SKIPPED}
ENV

echo
echo "Model provisioning cache: ${CACHE_FILE}"
echo "Device env export file: ${ENV_FILE}"
echo
echo "Load env vars with:"
echo "source ${ENV_FILE}"
echo
echo "Then run:"
echo "bash scripts/dev/bench.sh stage2 --profile closure --device ${DEVICE} --date $(date +%F)"
