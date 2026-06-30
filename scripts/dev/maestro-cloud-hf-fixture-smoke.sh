#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

# shellcheck disable=SC1091
source scripts/dev/maestro-cloud-common.sh
pocketgpt_load_dotenv

API_LEVEL=34
API_KEY_ENV="$(pocketgpt_default_maestro_cloud_key_env)"
FIXTURE_BASE_URL="${POCKETGPT_HF_FIXTURE_BASE_URL:-}"
FLOW="tests/maestro-cloud/scenario-hf-fixture-download-smoke.yaml"
RUN_ROOT=""

usage() {
  cat <<'USAGE'
Usage: bash scripts/dev/maestro-cloud-hf-fixture-smoke.sh --fixture-base-url <url> [options]

Options:
  --fixture-base-url <url>   Public URL for scripts/dev/hf-fixture-server.py.
  --api-level <level>        Android API level. Default: 34.
  --api-key-env <env>        Use MAESTRO_CLOUD_API_KEY or MAESTRO_CLOUD_API_KEY_2.
  --run-root <path>          Write artifacts under this directory.
  --help                     Show this help text.

Fixture exposure examples:
  cloudflared tunnel --url http://127.0.0.1:8765
  ngrok http 8765
  ssh -R 80:127.0.0.1:8765 nokey@localhost.run
  tailscale funnel 8765
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --fixture-base-url)
      FIXTURE_BASE_URL="${2:?missing fixture base URL}"
      shift 2
      ;;
    --api-level)
      API_LEVEL="${2:?missing api level}"
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
      exit 64
      ;;
  esac
done

if [[ -z "${FIXTURE_BASE_URL}" ]]; then
  echo "Provide --fixture-base-url or POCKETGPT_HF_FIXTURE_BASE_URL." >&2
  usage >&2
  exit 64
fi

case "${FIXTURE_BASE_URL}" in
  http://*|https://*) ;;
  *)
    echo "Fixture base URL must start with http:// or https://: ${FIXTURE_BASE_URL}" >&2
    exit 64
    ;;
esac

if [[ -z "${RUN_ROOT}" ]]; then
  RUN_ROOT="tmp/maestro-cloud-hf-fixture/$(date -u +%Y%m%dT%H%M%SZ)"
fi
mkdir -p "${RUN_ROOT}"

FIXTURE_HEALTH_URL="${FIXTURE_BASE_URL%/}/health"
if ! python3 - "${FIXTURE_HEALTH_URL}" <<'PY' >/dev/null 2>&1
import sys
import urllib.request

url = sys.argv[1]
with urllib.request.urlopen(url, timeout=5) as response:
    raise SystemExit(0 if response.status == 200 else 1)
PY
then
  echo "Fixture server is not healthy at ${FIXTURE_HEALTH_URL}." >&2
  echo "Start scripts/dev/hf-fixture-server.py and expose it with a public tunnel before running cloud smoke." >&2
  exit 1
fi

BUILD_LOG="${RUN_ROOT}/build.log"
GRADLE_USER_HOME=.gradle-home ./gradlew --no-daemon \
  -Ppocketgpt.enableNativeBuild=true \
  -Ppocketgpt.hfFixtureBaseUrl="${FIXTURE_BASE_URL}" \
  :apps:mobile-android:assembleDebug 2>&1 | tee "${BUILD_LOG}"

APK_PATH="$(find apps/mobile-android/build/outputs/apk/debug -type f -name '*.apk' | sort | head -n 1)"
if [[ -z "${APK_PATH}" || ! -f "${APK_PATH}" ]]; then
  echo "Debug APK not found. See ${BUILD_LOG}" >&2
  exit 1
fi

cat >"${RUN_ROOT}/run-manifest.json" <<EOF
{
  "android_api_level": "${API_LEVEL}",
  "api_key_env": "${API_KEY_ENV}",
  "app_file": "${APK_PATH}",
  "build_log": "${BUILD_LOG}",
  "fixture_base_url": "${FIXTURE_BASE_URL}",
  "flow": "${FLOW}",
  "run_root": "${RUN_ROOT}"
}
EOF

bash scripts/dev/maestro-cloud-flow.sh \
  --flow "${FLOW}" \
  --no-build \
  --app-file "${APK_PATH}" \
  --api-level "${API_LEVEL}" \
  --api-key-env "${API_KEY_ENV}" \
  --run-root "${RUN_ROOT}/cloud"
