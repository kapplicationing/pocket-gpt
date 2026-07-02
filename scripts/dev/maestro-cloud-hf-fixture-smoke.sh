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
FLOW="tests/maestro-cloud/scenario-hf-download-installed-smoke.yaml"
RUN_ROOT=""
ALLOW_LOCAL_FIXTURE_URL=0
PREFLIGHT_ONLY=0

usage() {
  cat <<'USAGE'
Usage: bash scripts/dev/maestro-cloud-hf-fixture-smoke.sh --fixture-base-url <url> [options]

Options:
  --fixture-base-url <url>   Public URL for scripts/dev/hf-fixture-server.py.
  --api-level <level>        Android API level. Default: 34.
  --api-key-env <env>        Use MAESTRO_CLOUD_API_KEY, MAESTRO_CLOUD_API_KEY_2, or MAESTRO_CLOUD_API_KEY_3.
  --flow <path.yaml>         Maestro Cloud flow. Default: scenario-hf-download-installed-smoke.yaml.
  --run-root <path>          Write artifacts under this directory.
  --allow-local-fixture-url  Allow loopback/private fixture URLs for wrapper debugging only.
  --preflight-only           Validate fixture endpoint and exit before building or uploading.
  --help                     Show this help text.

Fixture exposure examples:
  tools/hf-fixture-devstack/README.md
  ngrok http 8765
  tailscale funnel 8765

Avoid accountless tunnels on restricted corporate networks unless preflight passes.
Cloudflared and localhost.run can be blocked by VPN/firewall policy before Maestro
Cloud ever reaches the fixture.
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
    --flow)
      FLOW="${2:?missing flow path}"
      shift 2
      ;;
    --run-root)
      RUN_ROOT="${2:?missing run root}"
      shift 2
      ;;
    --allow-local-fixture-url)
      ALLOW_LOCAL_FIXTURE_URL=1
      shift
      ;;
    --preflight-only)
      PREFLIGHT_ONLY=1
      shift
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

if python3 - "${FIXTURE_BASE_URL}" <<'PY'
import sys
from urllib.parse import urlparse

parsed = urlparse(sys.argv[1])
host = (parsed.hostname or "").lower()
path = parsed.path
if host == "app.maestro.dev" and "/upload/" in path:
    raise SystemExit(0)
raise SystemExit(1)
PY
then
  echo "The fixture base URL must be the public base URL for scripts/dev/hf-fixture-server.py." >&2
  echo "You passed a Maestro Cloud upload/report URL, which cannot serve HF fixture API paths." >&2
  echo "Start the fixture server, expose it with a public tunnel or hosted service, then pass that public URL." >&2
  exit 64
fi

if [[ "${ALLOW_LOCAL_FIXTURE_URL}" -ne 1 ]]; then
  if ! python3 - "${FIXTURE_BASE_URL}" <<'PY'
import ipaddress
import sys
from urllib.parse import urlparse

url = sys.argv[1]
host = urlparse(url).hostname or ""
normalized = host.strip("[]").lower()
blocked_names = {"localhost", "127.0.0.1", "::1"}
if normalized in blocked_names or normalized.endswith(".localhost") or normalized.endswith(".local"):
    print(f"Fixture URL host is local-only and Maestro Cloud cannot reach it: {host}", file=sys.stderr)
    raise SystemExit(1)
try:
    address = ipaddress.ip_address(normalized)
except ValueError:
    raise SystemExit(0)
if address.is_loopback or address.is_private or address.is_link_local or address.is_reserved or address.is_multicast:
    print(f"Fixture URL host is not public and Maestro Cloud cannot reach it: {host}", file=sys.stderr)
    raise SystemExit(1)
PY
  then
    echo "Expose the fixture with a public URL, for example Devstack, ngrok, Tailscale Funnel, or a short-lived hosted VM." >&2
    echo "Use --allow-local-fixture-url only to debug this wrapper without running a real cloud device." >&2
    exit 64
  fi
fi

if [[ -z "${RUN_ROOT}" ]]; then
  RUN_ROOT="tmp/maestro-cloud-hf-fixture/$(date -u +%Y%m%dT%H%M%SZ)"
fi
mkdir -p "${RUN_ROOT}"

if grep -Eq 'shared/(bootstrap-cloud-startup|open-model-library)\.yaml|model_library_hf_search_results|Check this file' "${FLOW}"; then
  echo "The cloud HF fixture flow still contains stale bootstrap/search steps: ${FLOW}" >&2
  echo "Update it to use shared/open-model-library-debug.yaml and the explicit Use URL -> Check file path before uploading." >&2
  exit 1
fi

SEARCH_JSON="${RUN_ROOT}/fixture-search.json"
TREE_JSON="${RUN_ROOT}/fixture-tree.json"
RANGE_BYTES="${RUN_ROOT}/fixture-range-byte.bin"
HEALTH_BODY="$(curl -fsS "${FIXTURE_BASE_URL%/}/health" || true)"
if [[ "${HEALTH_BODY}" != "ok" ]]; then
  echo "Fixture preflight failed: /health did not return ok." >&2
  echo "Fixture server did not pass the HF API/download preflight at ${FIXTURE_BASE_URL%/}." >&2
  echo "Use tools/hf-fixture-devstack for the preferred hosted fixture, or expose scripts/dev/hf-fixture-server.py through a public HTTPS endpoint that passes the printed preflight checks." >&2
  exit 1
fi
echo "Fixture preflight ok: /health" >&2

if ! curl -fsS "${FIXTURE_BASE_URL%/}/api/models?search=tiny" -o "${SEARCH_JSON}"; then
  echo "Fixture preflight failed: /api/models?search=tiny" >&2
  echo "Fixture server did not pass the HF API/download preflight at ${FIXTURE_BASE_URL%/}." >&2
  echo "Use tools/hf-fixture-devstack for the preferred hosted fixture, or expose scripts/dev/hf-fixture-server.py through a public HTTPS endpoint that passes the printed preflight checks." >&2
  exit 1
fi
echo "Fixture preflight ok: /api/models?search=tiny" >&2

if ! python3 - "${SEARCH_JSON}" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    search = json.load(handle)
if not any(item.get("id") == "fixture/tiny-gguf" for item in search):
    print("Fixture preflight failed: search did not include fixture/tiny-gguf", file=sys.stderr)
    raise SystemExit(1)
PY
then
  echo "Fixture server did not pass the HF API/download preflight at ${FIXTURE_BASE_URL%/}." >&2
  echo "Use tools/hf-fixture-devstack for the preferred hosted fixture, or expose scripts/dev/hf-fixture-server.py through a public HTTPS endpoint that passes the printed preflight checks." >&2
  exit 1
fi

if ! curl -fsS "${FIXTURE_BASE_URL%/}/api/models/fixture/tiny-gguf/tree/main" -o "${TREE_JSON}"; then
  echo "Fixture preflight failed: /api/models/fixture/tiny-gguf/tree/main" >&2
  echo "Fixture server did not pass the HF API/download preflight at ${FIXTURE_BASE_URL%/}." >&2
  echo "Use tools/hf-fixture-devstack for the preferred hosted fixture, or expose scripts/dev/hf-fixture-server.py through a public HTTPS endpoint that passes the printed preflight checks." >&2
  exit 1
fi
echo "Fixture preflight ok: /api/models/fixture/tiny-gguf/tree/main" >&2

if ! python3 - "${TREE_JSON}" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    tree = json.load(handle)
if not any(
    item.get("path") == "tiny.gguf"
    and item.get("lfs", {}).get("oid")
    and item.get("lfs", {}).get("size")
    for item in tree
):
    print("Fixture preflight failed: tree did not include tiny.gguf with LFS oid and size", file=sys.stderr)
    raise SystemExit(1)
PY
then
  echo "Fixture server did not pass the HF API/download preflight at ${FIXTURE_BASE_URL%/}." >&2
  echo "Use tools/hf-fixture-devstack for the preferred hosted fixture, or expose scripts/dev/hf-fixture-server.py through a public HTTPS endpoint that passes the printed preflight checks." >&2
  exit 1
fi

RANGE_STATUS="$(curl -fsS --range 0-0 -w "%{http_code}" -o "${RANGE_BYTES}" "${FIXTURE_BASE_URL%/}/fixture/tiny-gguf/resolve/main/tiny.gguf" || true)"
RANGE_SIZE="$(wc -c <"${RANGE_BYTES}" | tr -d ' ')"
if [[ "${RANGE_STATUS}" != "206" || "${RANGE_SIZE}" != "1" ]]; then
  echo "Fixture preflight failed: range download returned HTTP ${RANGE_STATUS:-missing} and ${RANGE_SIZE:-0} byte(s), expected HTTP 206 and 1 byte." >&2
  echo "Fixture server did not pass the HF API/download preflight at ${FIXTURE_BASE_URL%/}." >&2
  echo "Use tools/hf-fixture-devstack for the preferred hosted fixture, or expose scripts/dev/hf-fixture-server.py through a public HTTPS endpoint that passes the printed preflight checks." >&2
  exit 1
fi
echo "Fixture preflight ok: /fixture/tiny-gguf/resolve/main/tiny.gguf range 0-0" >&2

if [[ "${PREFLIGHT_ONLY}" -eq 1 ]]; then
  echo "ok: fixture preflight passed for ${FIXTURE_BASE_URL%/}"
  exit 0
fi

BUILD_LOG="${RUN_ROOT}/build.log"
GRADLE_USER_HOME=.gradle-home ./gradlew --no-daemon \
  -Ppocketgpt.enableNativeBuild=true \
  -Ppocketgpt.hfFixtureBaseUrl="${FIXTURE_BASE_URL}" \
  :apps:mobile-android:generateDebugBuildConfig \
  :apps:mobile-android:assembleDebug 2>&1 | tee "${BUILD_LOG}"

BUILD_CONFIG_PATH="apps/mobile-android/build/generated/source/buildConfig/debug/com/pocketagent/android/BuildConfig.java"
if [[ ! -f "${BUILD_CONFIG_PATH}" ]] ||
  ! grep -Fq "HF_FIXTURE_BASE_URL = \"${FIXTURE_BASE_URL}\"" "${BUILD_CONFIG_PATH}"; then
  echo "Debug APK was not built with the requested HF fixture base URL: ${FIXTURE_BASE_URL}" >&2
  echo "Inspect ${BUILD_CONFIG_PATH} and ${BUILD_LOG} before uploading to Maestro Cloud." >&2
  exit 1
fi

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
