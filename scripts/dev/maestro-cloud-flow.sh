#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

# shellcheck disable=SC1091
source scripts/dev/maestro-cloud-common.sh
pocketgpt_load_dotenv

BUILD_APK=1
API_LEVEL=34
API_KEY_ENV="$(pocketgpt_default_maestro_cloud_key_env)"
APP_BINARY_ID="${MAESTRO_APP_BINARY_ID:-}"
APP_FILE=""
DEVICE_LOCALE="en_US"
FLOW=""
PROJECT_ID="${MAESTRO_PROJECT_ID:-}"
RUN_ROOT=""

usage() {
  cat <<'USAGE'
Usage: bash scripts/dev/maestro-cloud-flow.sh --flow <path.yaml> [options]

Options:
  --flow <path.yaml>          Flow file under tests/maestro-cloud/.
  --no-build                  Reuse the existing debug APK when needed.
  --api-level <level>         Android API level. Default: 34.
  --api-key-env <env>         Use MAESTRO_CLOUD_API_KEY, MAESTRO_CLOUD_API_KEY_2, or MAESTRO_CLOUD_API_KEY_3.
  --app-binary-id <id>        Reuse an uploaded Maestro Cloud binary.
  --app-file <path.apk>       Use a specific APK instead of building/finding one.
  --device-locale <locale>    Device locale. Default: en_US.
  --project-id <id>           Override MAESTRO_PROJECT_ID.
  --run-root <path>           Write artifacts under this directory.
  --help                      Show this help text.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --flow)
      FLOW="${2:?missing flow path}"
      shift 2
      ;;
    --no-build)
      BUILD_APK=0
      shift
      ;;
    --api-level)
      API_LEVEL="${2:?missing api level}"
      shift 2
      ;;
    --api-key-env)
      API_KEY_ENV="${2:?missing api key env}"
      shift 2
      ;;
    --app-binary-id)
      APP_BINARY_ID="${2:?missing app binary id}"
      shift 2
      ;;
    --app-file)
      APP_FILE="${2:?missing app file}"
      shift 2
      ;;
    --device-locale)
      DEVICE_LOCALE="${2:?missing locale}"
      shift 2
      ;;
    --project-id)
      PROJECT_ID="${2:?missing project id}"
      shift 2
      ;;
    --run-root)
      RUN_ROOT="${2:?missing run root}"
      shift 2
      ;;
    --help)
      usage
      echo
      echo "Configured accounts: $(pocketgpt_maestro_cloud_describe_available_accounts)"
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ -z "${FLOW}" ]]; then
  echo "Provide --flow." >&2
  usage >&2
  exit 1
fi

if [[ ! -f "${FLOW}" ]]; then
  echo "Flow not found: ${FLOW}" >&2
  exit 1
fi

pocketgpt_require_maestro_cloud_api_key "${API_KEY_ENV}" >/dev/null
pocketgpt_print_maestro_cloud_account_banner "${API_KEY_ENV}"

if [[ -z "${APP_BINARY_ID}" ]]; then
  if [[ -n "${APP_FILE}" && ! -f "${APP_FILE}" ]]; then
    echo "APK not found: ${APP_FILE}" >&2
    exit 1
  fi

  if [[ -z "${APP_FILE}" && ${BUILD_APK} -eq 1 ]]; then
    GRADLE_USER_HOME=.gradle-home ./gradlew --no-daemon -Ppocketgpt.enableNativeBuild=true :apps:mobile-android:assembleDebug
  fi

  if [[ -z "${APP_FILE}" ]]; then
    APP_FILE="$(find apps/mobile-android/build/outputs/apk/debug -type f -name '*.apk' | sort | head -n 1)"
  fi

  if [[ -z "${APP_FILE}" || ! -f "${APP_FILE}" ]]; then
    echo "Debug APK not found." >&2
    exit 1
  fi
fi

timestamp_utc() {
  date -u +"%Y-%m-%dT%H:%M:%SZ"
}

write_json_file() {
  local output_path="$1"
  local payload="$2"
  PAYLOAD="${payload}" python3 - "$output_path" <<'PY'
import json
import os
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps(json.loads(os.environ["PAYLOAD"]), indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
}

flow_slug="$(basename "${FLOW}" .yaml)"
if [[ -z "${RUN_ROOT}" ]]; then
  RUN_ROOT="tmp/maestro-cloud-targeted/$(date -u +%Y%m%dT%H%M%SZ)-${flow_slug}-$(pocketgpt_maestro_cloud_account_label "${API_KEY_ENV}")"
fi
mkdir -p "${RUN_ROOT}"

started_at="$(timestamp_utc)"
git_commit="$(git rev-parse HEAD 2>/dev/null || echo unknown)"

manifest_payload="$(FLOW_VALUE="${FLOW}" API_LEVEL_VALUE="${API_LEVEL}" API_KEY_ENV_VALUE="${API_KEY_ENV}" APP_BINARY_ID_VALUE="${APP_BINARY_ID}" APP_FILE_VALUE="${APP_FILE}" DEVICE_LOCALE_VALUE="${DEVICE_LOCALE}" PROJECT_ID_VALUE="${PROJECT_ID}" RUN_ROOT_VALUE="${RUN_ROOT}" GIT_COMMIT_VALUE="${git_commit}" STARTED_AT_VALUE="${started_at}" python3 - <<'PY'
import json
import os

print(json.dumps({
    "android_api_level": os.environ["API_LEVEL_VALUE"],
    "api_key_env": os.environ["API_KEY_ENV_VALUE"],
    "app_binary_id": os.environ["APP_BINARY_ID_VALUE"] or None,
    "app_file": os.environ["APP_FILE_VALUE"] or None,
    "build_apk": not bool(os.environ["APP_BINARY_ID_VALUE"]),
    "command": "bash scripts/dev/maestro-cloud-flow.sh",
    "device_locale": os.environ["DEVICE_LOCALE_VALUE"],
    "flow": os.environ["FLOW_VALUE"],
    "git_commit": os.environ["GIT_COMMIT_VALUE"],
    "project_id": os.environ["PROJECT_ID_VALUE"] or None,
    "run_root": os.environ["RUN_ROOT_VALUE"],
    "started_at_utc": os.environ["STARTED_AT_VALUE"],
    "status": "running",
}, sort_keys=True))
PY
)"
write_json_file "${RUN_ROOT}/run-manifest.json" "${manifest_payload}"

cmd=(
  maestro cloud
  --api-key "${MAESTRO_CLOUD_API_KEY_SELECTED}"
  --android-api-level "${API_LEVEL}"
  --device-locale "${DEVICE_LOCALE}"
)

if [[ -n "${PROJECT_ID}" ]]; then
  cmd+=(--project-id "${PROJECT_ID}")
fi

if [[ -n "${APP_BINARY_ID}" ]]; then
  cmd+=(--app-binary-id "${APP_BINARY_ID}")
else
  cmd+=(--app-file "${APP_FILE}")
fi

cmd+=(
  --flows "${FLOW}"
  --format junit
  --output "${RUN_ROOT}/junit.xml"
)

echo "Running Maestro Cloud flow ${FLOW} on Android API ${API_LEVEL} using ${API_KEY_ENV}"
set +e
"${cmd[@]}" 2>&1 | tee "${RUN_ROOT}/cli-output.log"
command_exit_code=${PIPESTATUS[0]}
set -e

status_payload="$(python3 -m tools.devctl.cloud_artifacts status \
  --android-api-level "${API_LEVEL}" \
  --cli-exit-code "${command_exit_code}" \
  --cli-output "${RUN_ROOT}/cli-output.log" \
  --completed-at "$(timestamp_utc)" \
  --junit "${RUN_ROOT}/junit.xml")"
write_json_file "${RUN_ROOT}/status.json" "${status_payload}"

updated_manifest="$(FLOW_VALUE="${FLOW}" API_LEVEL_VALUE="${API_LEVEL}" API_KEY_ENV_VALUE="${API_KEY_ENV}" APP_BINARY_ID_VALUE="${APP_BINARY_ID}" APP_FILE_VALUE="${APP_FILE}" DEVICE_LOCALE_VALUE="${DEVICE_LOCALE}" PROJECT_ID_VALUE="${PROJECT_ID}" RUN_ROOT_VALUE="${RUN_ROOT}" GIT_COMMIT_VALUE="${git_commit}" STARTED_AT_VALUE="${started_at}" STATUS_PAYLOAD_VALUE="${status_payload}" python3 - <<'PY'
import json
import os

status_payload = json.loads(os.environ["STATUS_PAYLOAD_VALUE"])
print(json.dumps({
    "android_api_level": os.environ["API_LEVEL_VALUE"],
    "api_key_env": os.environ["API_KEY_ENV_VALUE"],
    "app_binary_id": status_payload.get("app_binary_id") or os.environ["APP_BINARY_ID_VALUE"] or None,
    "app_file": os.environ["APP_FILE_VALUE"] or None,
    "cli_exit_code": status_payload.get("cli_exit_code"),
    "command": "bash scripts/dev/maestro-cloud-flow.sh",
    "completed_at_utc": status_payload.get("completed_at_utc"),
    "device_locale": os.environ["DEVICE_LOCALE_VALUE"],
    "flow": os.environ["FLOW_VALUE"],
    "git_commit": os.environ["GIT_COMMIT_VALUE"],
    "project_id": status_payload.get("project_id") or os.environ["PROJECT_ID_VALUE"] or None,
    "run_root": os.environ["RUN_ROOT_VALUE"],
    "started_at_utc": os.environ["STARTED_AT_VALUE"],
    "status": status_payload.get("status"),
    "status_path": f"{os.environ['RUN_ROOT_VALUE']}/status.json",
    "upload_id": status_payload.get("upload_id"),
    "upload_url": status_payload.get("upload_url"),
}, sort_keys=True))
PY
)"
write_json_file "${RUN_ROOT}/run-manifest.json" "${updated_manifest}"

echo "Maestro Cloud flow artifacts: ${RUN_ROOT}"

overall_status="$(STATUS_PATH="${RUN_ROOT}/status.json" python3 - <<'PY'
import json
import os
from pathlib import Path

payload = json.loads(Path(os.environ["STATUS_PATH"]).read_text(encoding="utf-8"))
print(payload.get("status", "failed"))
PY
)"

case "${overall_status}" in
  passed)
    exit 0
    ;;
  blocked_external_account_setup)
    exit 2
    ;;
  *)
    exit 1
    ;;
esac
