#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

# shellcheck disable=SC1091
source scripts/dev/maestro-cloud-common.sh
pocketgpt_load_dotenv

API_KEY_ENV="$(pocketgpt_default_maestro_cloud_key_env)"
PROJECT_ID="${MAESTRO_PROJECT_ID:-}"
WATCH=0
INTERVAL_SEC=60
UPLOADS=()

usage() {
  cat <<'USAGE'
Usage: bash scripts/dev/maestro-cloud-upload-status.sh [options] <label:upload-id>...

Options:
  --project-id <id>           Override MAESTRO_PROJECT_ID.
  --api-key-env <env>         Use MAESTRO_CLOUD_API_KEY, MAESTRO_CLOUD_API_KEY_2, or MAESTRO_CLOUD_API_KEY_3.
  --watch                     Poll until every upload is completed.
  --interval <sec>            Poll interval for --watch mode. Default: 60.
  --help                      Show this help text.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project-id)
      PROJECT_ID="${2:?missing project id}"
      shift 2
      ;;
    --api-key-env)
      API_KEY_ENV="${2:?missing api key env}"
      shift 2
      ;;
    --watch)
      WATCH=1
      shift
      ;;
    --interval)
      INTERVAL_SEC="${2:?missing interval seconds}"
      shift 2
      ;;
    --help)
      usage
      echo
      echo "Configured accounts: $(pocketgpt_maestro_cloud_describe_available_accounts)"
      exit 0
      ;;
    *)
      UPLOADS+=("$1")
      shift
      ;;
  esac
done

pocketgpt_require_maestro_cloud_api_key "${API_KEY_ENV}" >/dev/null
pocketgpt_print_maestro_cloud_account_banner "${API_KEY_ENV}"

if [[ -z "${PROJECT_ID}" ]]; then
  echo "Set --project-id or MAESTRO_PROJECT_ID." >&2
  exit 1
fi

if [[ ${#UPLOADS[@]} -eq 0 ]]; then
  echo "Provide at least one <label:upload-id> entry." >&2
  usage >&2
  exit 1
fi

for entry in "${UPLOADS[@]}"; do
  if [[ "${entry}" != *:* ]]; then
    echo "Invalid upload entry: ${entry}" >&2
    echo "Expected <label:upload-id>." >&2
    exit 1
  fi
done

poll_once() {
  printf '%-18s %-28s %-10s %-10s %-8s %-12s %s\n' 'label' 'upload_id' 'upload' 'flow' 'done' 'launched' 'errors'
  for entry in "${UPLOADS[@]}"; do
    label="${entry%%:*}"
    upload_id="${entry##*:}"
    json="$(curl -sS -H "Authorization: Bearer ${MAESTRO_CLOUD_API_KEY_SELECTED}" "https://api.copilot.mobile.dev/v2/project/${PROJECT_ID}/upload/${upload_id}")"
    printf '%-18s %-28s %-10s %-10s %-8s %-12s %s\n' \
      "${label}" \
      "${upload_id}" \
      "$(jq -r '.status' <<<"${json}")" \
      "$(jq -r '.flows[0].status // ""' <<<"${json}")" \
      "$(jq -r '.completed' <<<"${json}")" \
      "$(jq -r '.wasAppLaunched' <<<"${json}")" \
      "$(jq -r '(.flows[0].errors // []) | join(" | ")' <<<"${json}")"
  done
}

if [[ ${WATCH} -eq 0 ]]; then
  poll_once
  exit 0
fi

while true; do
  date '+%Y-%m-%d %H:%M:%S'
  poll_once
  if printf '%s\n' "${UPLOADS[@]}" | while read -r entry; do
    upload_id="${entry##*:}"
    curl -sS -H "Authorization: Bearer ${MAESTRO_CLOUD_API_KEY_SELECTED}" "https://api.copilot.mobile.dev/v2/project/${PROJECT_ID}/upload/${upload_id}" | jq -e '.completed == true' >/dev/null || exit 1
  done; then
    break
  fi
  echo
  sleep "${INTERVAL_SEC}"
done
