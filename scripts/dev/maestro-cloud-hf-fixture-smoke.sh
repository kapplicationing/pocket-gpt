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
ALLOW_LOCAL_FIXTURE_URL=0

usage() {
  cat <<'USAGE'
Usage: bash scripts/dev/maestro-cloud-hf-fixture-smoke.sh --fixture-base-url <url> [options]

Options:
  --fixture-base-url <url>   Public URL for scripts/dev/hf-fixture-server.py.
  --api-level <level>        Android API level. Default: 34.
  --api-key-env <env>        Use MAESTRO_CLOUD_API_KEY or MAESTRO_CLOUD_API_KEY_2.
  --run-root <path>          Write artifacts under this directory.
  --allow-local-fixture-url  Allow loopback/private fixture URLs for wrapper debugging only.
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
    --allow-local-fixture-url)
      ALLOW_LOCAL_FIXTURE_URL=1
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
    echo "Expose the fixture with a public URL, for example cloudflared, ngrok, localhost.run, Tailscale Funnel, or a short-lived hosted VM." >&2
    echo "Use --allow-local-fixture-url only to debug this wrapper without running a real cloud device." >&2
    exit 64
  fi
fi

if [[ -z "${RUN_ROOT}" ]]; then
  RUN_ROOT="tmp/maestro-cloud-hf-fixture/$(date -u +%Y%m%dT%H%M%SZ)"
fi
mkdir -p "${RUN_ROOT}"

if ! python3 - "${FIXTURE_BASE_URL%/}" <<'PY' >/dev/null 2>&1
import json
import sys
import urllib.request

base_url = sys.argv[1]

def request(path, headers=None):
    req = urllib.request.Request(base_url + path, headers=headers or {})
    with urllib.request.urlopen(req, timeout=5) as response:
        body = response.read()
        return response.status, body

status, body = request("/health")
if status != 200 or body.strip() != b"ok":
    raise SystemExit(1)

status, body = request("/api/models?search=tiny")
search = json.loads(body.decode("utf-8"))
if status != 200 or not any(item.get("id") == "fixture/tiny-gguf" for item in search):
    raise SystemExit(1)

status, body = request("/api/models/fixture/tiny-gguf/tree/main")
tree = json.loads(body.decode("utf-8"))
if status != 200 or not any(
    item.get("path") == "tiny.gguf"
    and item.get("lfs", {}).get("oid")
    and item.get("lfs", {}).get("size")
    for item in tree
):
    raise SystemExit(1)

status, body = request(
    "/fixture/tiny-gguf/resolve/main/tiny.gguf",
    headers={"Range": "bytes=0-0"},
)
if status != 206 or len(body) != 1:
    raise SystemExit(1)
PY
then
  echo "Fixture server did not pass the HF API/download preflight at ${FIXTURE_BASE_URL%/}." >&2
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
