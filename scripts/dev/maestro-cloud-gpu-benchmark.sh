#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

# shellcheck disable=SC1091
source scripts/dev/maestro-cloud-common.sh
pocketgpt_load_dotenv

BUILD_APK=1
API_LEVELS=(34 33)
API_LEVELS_SET=0
API_KEY_ENV="$(pocketgpt_default_maestro_cloud_key_env)"
RUN_ROOT=""

usage() {
  cat <<'USAGE'
Usage: bash scripts/dev/maestro-cloud-gpu-benchmark.sh [options]

Options:
  --no-build                  Reuse the existing debug APK.
  --api-level <level[,level]> Add one or more Android API levels to run.
  --api-key-env <env>         Use MAESTRO_CLOUD_API_KEY or MAESTRO_CLOUD_API_KEY_2.
  --run-root <path>           Write artifacts under the provided root.
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
    --api-key-env)
      API_KEY_ENV="${2:?missing api key env}"
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

if [[ -z "${RUN_ROOT}" ]]; then
  RUN_ROOT="tmp/maestro-cloud-gpu-benchmark/$(date -u +%Y%m%dT%H%M%SZ)-$(pocketgpt_maestro_cloud_account_label "${API_KEY_ENV}")"
fi

mkdir -p "${RUN_ROOT}"

EXIT_CODE=0
for api_level in "${API_LEVELS[@]}"; do
  run_dir="${RUN_ROOT}/api-${api_level}"
  mkdir -p "${run_dir}"
  echo "Running Maestro Cloud GPU benchmark on Android API ${api_level} using ${API_KEY_ENV}"
  if ! maestro cloud \
    --api-key "${MAESTRO_CLOUD_API_KEY_SELECTED}" \
    --android-api-level "${api_level}" \
    --device-locale en_US \
    --app-file "${APK_PATH}" \
    --flows tests/maestro-cloud/scenario-gpu-cpu-benchmark.yaml \
    --format junit \
    --output "${run_dir}/junit.xml" | tee "${run_dir}/run.log"; then
    EXIT_CODE=1
  fi
done

echo "Maestro Cloud GPU benchmark artifacts: ${RUN_ROOT}"
exit "${EXIT_CODE}"
