#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

# shellcheck disable=SC1091
source scripts/dev/maestro-cloud-common.sh
pocketgpt_load_dotenv

BUILD_APK=1
API_LEVELS=(34)
API_LEVELS_SET=0
RUN_ROOT=""
API_KEY_ENV="$(pocketgpt_default_maestro_cloud_key_env)"

usage() {
  cat <<'USAGE'
Usage: bash scripts/dev/maestro-cloud-smoke.sh [options]

Options:
  --no-build                  Reuse the existing debug APK.
  --api-level <level[,level]> Add one or more Android API levels to run.
  --run-root <path>           Write artifacts under the provided root.
  --api-key-env <env>         Use MAESTRO_CLOUD_API_KEY or MAESTRO_CLOUD_API_KEY_2.
  --help                      Show this help text.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-build)
      BUILD_APK=0
      shift
      ;;
    --api-level)
      if [[ ${API_LEVELS_SET} -eq 0 ]]; then
        API_LEVELS=()
        API_LEVELS_SET=1
      fi
      pocketgpt_append_maestro_cloud_api_levels API_LEVELS "${2:?missing api level}"
      shift 2
      ;;
    --run-root)
      RUN_ROOT="${2:?missing run root}"
      shift 2
      ;;
    --api-key-env)
      API_KEY_ENV="${2:?missing api key env}"
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

if [[ ${#API_LEVELS[@]} -eq 0 ]]; then
  echo "Provide at least one Android API level." >&2
  exit 1
fi

pocketgpt_require_maestro_cloud_api_key "${API_KEY_ENV}" >/dev/null
pocketgpt_print_maestro_cloud_account_banner "${API_KEY_ENV}"

if [[ ${BUILD_APK} -eq 1 ]]; then
  GRADLE_USER_HOME=.gradle-home ./gradlew --no-daemon -Ppocketgpt.enableNativeBuild=true :apps:mobile-android:assembleDebug
fi

APK_PATH="$(find apps/mobile-android/build/outputs/apk/debug -type f -name '*.apk' | sort | head -n 1)"
if [[ -z "${APK_PATH}" || ! -f "${APK_PATH}" ]]; then
  echo "Debug APK not found." >&2
  exit 1
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

if [[ -z "${RUN_ROOT}" ]]; then
  RUN_ROOT="tmp/maestro-cloud-smoke/$(date -u +%Y%m%dT%H%M%SZ)"
fi

mkdir -p "${RUN_ROOT}"

GIT_COMMIT="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
API_LEVELS_VALUE="$(IFS=,; echo "${API_LEVELS[*]}")"
STARTED_AT_VALUE="$(timestamp_utc)"
ROOT_MANIFEST="$(APK_PATH_VALUE="${APK_PATH}" API_KEY_ENV_VALUE="${API_KEY_ENV}" BUILD_APK_VALUE="${BUILD_APK}" GIT_COMMIT_VALUE="${GIT_COMMIT}" API_LEVELS_VALUE="${API_LEVELS_VALUE}" RUN_ROOT_VALUE="${RUN_ROOT}" STARTED_AT_VALUE="${STARTED_AT_VALUE}" python3 - <<'PY'
import json
import os

api_levels = os.environ["API_LEVELS_VALUE"].split(",")
print(json.dumps({
    "apk_path": os.environ["APK_PATH_VALUE"],
    "api_key_env": os.environ["API_KEY_ENV_VALUE"],
    "api_levels": api_levels,
    "build_apk": os.environ["BUILD_APK_VALUE"] == "1",
    "command": "bash scripts/dev/maestro-cloud-smoke.sh",
    "flows_root": "tests/maestro-cloud/",
    "git_commit": os.environ["GIT_COMMIT_VALUE"],
    "include_tags": ["cloud-smoke"],
    "run_root": os.environ["RUN_ROOT_VALUE"],
    "started_at_utc": os.environ["STARTED_AT_VALUE"],
    "status": "running",
}, sort_keys=True))
PY
)"
write_json_file "${RUN_ROOT}/run-manifest.json" "${ROOT_MANIFEST}"

API_STATUS_FILES=()

for api_level in "${API_LEVELS[@]}"; do
  api_dir="${RUN_ROOT}/api-${api_level}"
  mkdir -p "${api_dir}"
  api_started_at="$(timestamp_utc)"
  api_manifest="$(API_LEVEL_VALUE="${api_level}" API_KEY_ENV_VALUE="${API_KEY_ENV}" APK_PATH_VALUE="${APK_PATH}" JUNIT_PATH_VALUE="${api_dir}/junit.xml" API_DIR_VALUE="${api_dir}" STARTED_AT_VALUE="${api_started_at}" python3 - <<'PY'
import json
import os

print(json.dumps({
    "android_api_level": os.environ["API_LEVEL_VALUE"],
    "apk_path": os.environ["APK_PATH_VALUE"],
    "api_key_env": os.environ["API_KEY_ENV_VALUE"],
    "command": [
        "maestro", "cloud",
        "--api-key", "<redacted>",
        "--android-api-level", os.environ["API_LEVEL_VALUE"],
        "--device-locale", "en_US",
        "--app-file", os.environ["APK_PATH_VALUE"],
        "--flows", "tests/maestro-cloud/",
        "--include-tags", "cloud-smoke",
        "--format", "junit",
        "--output", os.environ["JUNIT_PATH_VALUE"],
    ],
    "flows_root": "tests/maestro-cloud/",
    "include_tags": ["cloud-smoke"],
    "junit_path": os.environ["JUNIT_PATH_VALUE"],
    "run_dir": os.environ["API_DIR_VALUE"],
    "started_at_utc": os.environ["STARTED_AT_VALUE"],
    "status": "running",
}, sort_keys=True))
PY
)"
  write_json_file "${api_dir}/run-manifest.json" "${api_manifest}"

  echo "Running Maestro Cloud smoke suite on Android API ${api_level} using ${API_KEY_ENV}"
  set +e
  maestro cloud \
    --api-key "${MAESTRO_CLOUD_API_KEY_SELECTED}" \
    --android-api-level "${api_level}" \
    --device-locale en_US \
    --app-file "${APK_PATH}" \
    --flows tests/maestro-cloud/ \
    --include-tags cloud-smoke \
    --format junit \
    --output "${api_dir}/junit.xml" 2>&1 | tee "${api_dir}/cli-output.log"
  command_exit_code=${PIPESTATUS[0]}
  set -e

  api_status_payload="$(python3 -m tools.devctl.cloud_artifacts status \
    --android-api-level "${api_level}" \
    --cli-exit-code "${command_exit_code}" \
    --cli-output "${api_dir}/cli-output.log" \
    --completed-at "$(timestamp_utc)" \
    --junit "${api_dir}/junit.xml")"
  write_json_file "${api_dir}/status.json" "${api_status_payload}"
  updated_api_manifest="$(API_LEVEL_VALUE="${api_level}" API_KEY_ENV_VALUE="${API_KEY_ENV}" APK_PATH_VALUE="${APK_PATH}" JUNIT_PATH_VALUE="${api_dir}/junit.xml" API_DIR_VALUE="${api_dir}" STARTED_AT_VALUE="${api_started_at}" STATUS_PAYLOAD_VALUE="${api_status_payload}" python3 - <<'PY'
import json
import os

status_payload = json.loads(os.environ["STATUS_PAYLOAD_VALUE"])
print(json.dumps({
    "android_api_level": os.environ["API_LEVEL_VALUE"],
    "apk_path": os.environ["APK_PATH_VALUE"],
    "api_key_env": os.environ["API_KEY_ENV_VALUE"],
    "app_binary_id": status_payload.get("app_binary_id"),
    "app_id": status_payload.get("app_id"),
    "blocker_key": status_payload.get("blocker_key"),
    "cli_exit_code": status_payload.get("cli_exit_code"),
    "command": [
        "maestro", "cloud",
        "--api-key", "<redacted>",
        "--android-api-level", os.environ["API_LEVEL_VALUE"],
        "--device-locale", "en_US",
        "--app-file", os.environ["APK_PATH_VALUE"],
        "--flows", "tests/maestro-cloud/",
        "--include-tags", "cloud-smoke",
        "--format", "junit",
        "--output", os.environ["JUNIT_PATH_VALUE"],
    ],
    "completed_at_utc": status_payload.get("completed_at_utc"),
    "flows_root": "tests/maestro-cloud/",
    "include_tags": ["cloud-smoke"],
    "junit_path": os.environ["JUNIT_PATH_VALUE"],
    "run_dir": os.environ["API_DIR_VALUE"],
    "started_at_utc": os.environ["STARTED_AT_VALUE"],
    "status": status_payload.get("status"),
    "status_path": f"{os.environ['API_DIR_VALUE']}/status.json",
    "upload_id": status_payload.get("upload_id"),
    "upload_url": status_payload.get("upload_url"),
}, sort_keys=True))
PY
)"
  write_json_file "${api_dir}/run-manifest.json" "${updated_api_manifest}"
  API_STATUS_FILES+=("${api_dir}/status.json")
done

ROOT_STATUS_PAYLOAD="$(python3 -m tools.devctl.cloud_artifacts aggregate \
  --completed-at "$(timestamp_utc)" \
  --run-root "${RUN_ROOT}" \
  "${API_STATUS_FILES[@]}")"
write_json_file "${RUN_ROOT}/status.json" "${ROOT_STATUS_PAYLOAD}"

OVERALL_STATUS="$(STATUS_PATH="${RUN_ROOT}/status.json" python3 - <<'PY'
import json
import os
from pathlib import Path

payload = json.loads(Path(os.environ["STATUS_PATH"]).read_text(encoding="utf-8"))
print(payload.get("status", "failed"))
PY
)"

case "${OVERALL_STATUS}" in
  passed)
    EXIT_CODE=0
    ;;
  blocked_external_account_setup)
    EXIT_CODE=2
    ;;
  *)
    EXIT_CODE=1
    ;;
esac

UPDATED_ROOT_MANIFEST="$(APK_PATH_VALUE="${APK_PATH}" API_KEY_ENV_VALUE="${API_KEY_ENV}" BUILD_APK_VALUE="${BUILD_APK}" GIT_COMMIT_VALUE="${GIT_COMMIT}" API_LEVELS_VALUE="${API_LEVELS_VALUE}" RUN_ROOT_VALUE="${RUN_ROOT}" STARTED_AT_VALUE="${STARTED_AT_VALUE}" OVERALL_STATUS_VALUE="${OVERALL_STATUS}" python3 - <<'PY'
import json
import os

api_levels = os.environ["API_LEVELS_VALUE"].split(",")
print(json.dumps({
    "apk_path": os.environ["APK_PATH_VALUE"],
    "api_key_env": os.environ["API_KEY_ENV_VALUE"],
    "api_levels": api_levels,
    "build_apk": os.environ["BUILD_APK_VALUE"] == "1",
    "command": "bash scripts/dev/maestro-cloud-smoke.sh",
    "flows_root": "tests/maestro-cloud/",
    "git_commit": os.environ["GIT_COMMIT_VALUE"],
    "include_tags": ["cloud-smoke"],
    "run_root": os.environ["RUN_ROOT_VALUE"],
    "started_at_utc": os.environ["STARTED_AT_VALUE"],
    "status": os.environ["OVERALL_STATUS_VALUE"],
}, sort_keys=True))
PY
)"
write_json_file "${RUN_ROOT}/run-manifest.json" "${UPDATED_ROOT_MANIFEST}"

echo "Maestro Cloud smoke artifacts: ${RUN_ROOT}"

exit "${EXIT_CODE}"
