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
API_KEY_ENVS=()

usage() {
  cat <<'USAGE'
Usage: bash scripts/dev/maestro-cloud-smoke-parallel.sh [options]

Options:
  --no-build                  Reuse the existing debug APK.
  --api-level <level[,level]> Add one or more Android API levels to each child run.
  --run-root <path>           Write all per-account artifacts under the provided root.
  --api-key-env <env>         Restrict execution to one or more explicit accounts.
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
      API_KEY_ENVS+=("${2:?missing api key env}")
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

if [[ ${#API_KEY_ENVS[@]} -eq 0 ]]; then
  while IFS= read -r api_key_env; do
    [[ -n "${api_key_env}" ]] || continue
    API_KEY_ENVS+=("${api_key_env}")
  done < <(pocketgpt_available_maestro_cloud_key_envs)
fi

if [[ ${#API_KEY_ENVS[@]} -eq 0 ]]; then
  echo "Set at least one Maestro Cloud API key in .env." >&2
  exit 1
fi

deduped_envs=()
for api_key_env in "${API_KEY_ENVS[@]}"; do
  skip=0
  for existing_env in "${deduped_envs[@]:-}"; do
    if [[ "${existing_env}" == "${api_key_env}" ]]; then
      skip=1
      break
    fi
  done
  if [[ ${skip} -eq 0 ]]; then
    deduped_envs+=("${api_key_env}")
  fi
done
API_KEY_ENVS=("${deduped_envs[@]}")

if [[ -z "${RUN_ROOT}" ]]; then
  RUN_ROOT="tmp/maestro-cloud-smoke/$(date -u +%Y%m%dT%H%M%SZ)-parallel"
fi
mkdir -p "${RUN_ROOT}"

if [[ ${BUILD_APK} -eq 1 ]]; then
  GRADLE_USER_HOME=.gradle-home ./gradlew --no-daemon -Ppocketgpt.enableNativeBuild=true :apps:mobile-android:assembleDebug
fi

pids=()
labels=()
run_logs=()

for api_key_env in "${API_KEY_ENVS[@]}"; do
  pocketgpt_require_maestro_cloud_api_key "${api_key_env}" >/dev/null
  label="$(pocketgpt_maestro_cloud_account_label "${api_key_env}")"
  labels+=("${label}")
  run_logs+=("${RUN_ROOT}/${label}.log")
  echo "Starting Maestro Cloud smoke for ${label} (${api_key_env})"
  cmd=(
    bash scripts/dev/maestro-cloud-smoke.sh
    --no-build
    --api-key-env "${api_key_env}"
    --run-root "${RUN_ROOT}/${label}"
  )
  for api_level in "${API_LEVELS[@]}"; do
    cmd+=(--api-level "${api_level}")
  done
  "${cmd[@]}" >"${RUN_ROOT}/${label}.log" 2>&1 &
  pids+=("$!")
done

exit_code=0
for index in "${!pids[@]}"; do
  pid="${pids[$index]}"
  label="${labels[$index]}"
  log_path="${run_logs[$index]}"
  if wait "${pid}"; then
    echo "Cloud smoke passed for ${label} (log: ${log_path})"
  else
    rc=$?
    echo "Cloud smoke failed for ${label} (log: ${log_path})" >&2
    if [[ ${rc} -eq 2 && ${exit_code} -eq 0 ]]; then
      exit_code=2
    else
      exit_code=1
    fi
  fi
done

echo "Parallel Maestro Cloud smoke artifacts: ${RUN_ROOT}"
exit "${exit_code}"
