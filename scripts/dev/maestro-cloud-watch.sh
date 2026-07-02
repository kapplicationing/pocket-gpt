#!/usr/bin/env bash
# Poll a Maestro Cloud upload until it completes, or re-fire the flow after a
# timeout when status stays non-terminal (e.g. PENDING). Uses the same API as
# maestro-cloud-upload-status.sh.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

# shellcheck disable=SC1091
source scripts/dev/maestro-cloud-common.sh
pocketgpt_load_dotenv

UPLOAD_ID=""
ACCOUNT_ENV="MAESTRO_CLOUD_API_KEY"
FLOW=""
APK_PATH=""
TIMEOUT_SECONDS=1800
PROJECT_ID="${MAESTRO_PROJECT_ID:-}"
ANDROID_API_LEVEL=34

usage() {
  cat <<'USAGE'
Usage: bash scripts/dev/maestro-cloud-watch.sh [options]

Polls upload status; if still non-terminal after --timeout-seconds, runs
`maestro cloud` again for the same flow and APK, then tracks the new upload id.

Required:
  --upload-id <mupload_...>     Initial upload id (without label: prefix)
  --flow <path.yaml>          Maestro flow file relative to repo root
  --apk <path.apk>            App APK path

Options:
  --api-key-env MAESTRO_CLOUD_API_KEY|MAESTRO_CLOUD_API_KEY_2|MAESTRO_CLOUD_API_KEY_3   Default: MAESTRO_CLOUD_API_KEY
  --project-id <id>           Override MAESTRO_PROJECT_ID
  --timeout-seconds <n>       Seconds before re-fire. Default: 1800
  --android-api-level <n>     Default: 34
  --help                      Show help

Environment:
  MAESTRO_PROJECT_ID            Required unless --project-id is passed.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --upload-id) UPLOAD_ID="${2:?}"; shift 2 ;;
    --api-key-env) ACCOUNT_ENV="${2:?}"; shift 2 ;;
    --flow) FLOW="${2:?}"; shift 2 ;;
    --apk) APK_PATH="${2:?}"; shift 2 ;;
    --timeout-seconds) TIMEOUT_SECONDS="${2:?}"; shift 2 ;;
    --project-id) PROJECT_ID="${2:?}"; shift 2 ;;
    --android-api-level) ANDROID_API_LEVEL="${2:?}"; shift 2 ;;
    --help) usage; exit 0 ;;
    *) echo "unknown arg: $1" >&2; usage >&2; exit 64 ;;
  esac
done

if [[ -z "${UPLOAD_ID}" || -z "${FLOW}" || -z "${APK_PATH}" ]]; then
  usage >&2
  exit 64
fi

if [[ -z "${PROJECT_ID}" ]]; then
  echo "Set MAESTRO_PROJECT_ID or pass --project-id." >&2
  exit 1
fi

if [[ ! -f "${APK_PATH}" ]]; then
  echo "APK not found: ${APK_PATH}" >&2
  exit 1
fi

pocketgpt_require_maestro_cloud_api_key "${ACCOUNT_ENV}" >/dev/null
pocketgpt_print_maestro_cloud_account_banner "${ACCOUNT_ENV}"

MANIFEST_DIR="tmp/maestro-cloud-watch"
mkdir -p "${MANIFEST_DIR}"
MANIFEST="${MANIFEST_DIR}/${ACCOUNT_ENV}-$(date -u +%Y%m%dT%H%M%SZ).manifest.jsonl"
echo "{\"event\":\"start\",\"upload_id\":\"${UPLOAD_ID}\",\"flow\":\"${FLOW}\",\"apk\":\"${APK_PATH}\"}" >>"${MANIFEST}"

fetch_json() {
  local id="$1"
  curl -sS -H "Authorization: Bearer ${MAESTRO_CLOUD_API_KEY_SELECTED}" \
    "https://api.copilot.mobile.dev/v2/project/${PROJECT_ID}/upload/${id}"
}

top_status() {
  local id="$1"
  fetch_json "${id}" | jq -r '.status // "UNKNOWN"'
}

flow_status() {
  local id="$1"
  fetch_json "${id}" | jq -r '.flows[0].status // ""'
}

is_terminal() {
  local s="$1"
  case "${s}" in
    PASSED|FAILED|ERROR|CANCELED|ABORTED) return 0 ;;
    *) return 1 ;;
  esac
}

poll_completed() {
  local id="$1"
  fetch_json "${id}" | jq -e '.completed == true' >/dev/null 2>&1
}

start_ts=$(date +%s)
while :; do
  ts=$(date -u +%FT%TZ)
  st=$(top_status "${UPLOAD_ID}")
  fst=$(flow_status "${UPLOAD_ID}")
  echo "${ts} upload=${UPLOAD_ID} status=${st} flow_status=${fst}"
  echo "{\"event\":\"poll\",\"ts\":\"${ts}\",\"upload_id\":\"${UPLOAD_ID}\",\"status\":\"${st}\",\"flow_status\":\"${fst}\"}" >>"${MANIFEST}"

  # Maestro Cloud sometimes keeps top-level .status as RUNNING while .completed flips true.
  if poll_completed "${UPLOAD_ID}"; then
    echo "{\"event\":\"completed\",\"upload_id\":\"${UPLOAD_ID}\",\"top_status\":\"${st}\",\"flow_status\":\"${fst}\"}" >>"${MANIFEST}"
    echo "manifest=${MANIFEST}"
    if [[ "${fst}" == "PASSED" ]] || [[ "${st}" == "PASSED" ]]; then
      exit 0
    fi
    exit 1
  fi

  if is_terminal "${st}"; then
    echo "{\"event\":\"terminal\",\"upload_id\":\"${UPLOAD_ID}\",\"status\":\"${st}\"}" >>"${MANIFEST}"
    echo "manifest=${MANIFEST}"
    [[ "${st}" == "PASSED" ]] || exit 1
    exit 0
  fi

  if (( $(date +%s) - start_ts >= TIMEOUT_SECONDS )); then
    echo "$(date -u +%FT%TZ) timeout after ${TIMEOUT_SECONDS}s, re-firing maestro cloud" >&2
    refire_log="${MANIFEST_DIR}/refire-${UPLOAD_ID}.log"
    set +e
    maestro cloud \
      --api-key "${MAESTRO_CLOUD_API_KEY_SELECTED}" \
      --android-api-level "${ANDROID_API_LEVEL}" \
      --app-file "${APK_PATH}" \
      --flows "${FLOW}" \
      --format junit \
      --output "${MANIFEST_DIR}/refire-junit.xml" 2>&1 | tee -a "${refire_log}"
    rc=$?
    set -e
    new_id=""
    new_id=$(grep -oE 'mupload_[A-Za-z0-9]+' "${refire_log}" | tail -n1 || true)
    if [[ -z "${new_id}" ]]; then
      echo "could not extract new upload id from maestro cloud output (rc=${rc})" >&2
      exit 1
    fi
    echo "{\"event\":\"refire\",\"old_upload\":\"${UPLOAD_ID}\",\"new_upload\":\"${new_id}\"}" >>"${MANIFEST}"
    UPLOAD_ID="${new_id}"
    start_ts=$(date +%s)
  fi
  sleep 60
done
