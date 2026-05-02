#!/usr/bin/env bash
# Forces a clean Maestro local-agent bootstrap on the chosen wired/wireless
# Android serial. Idempotent. Run before any local `maestro test` that has
# previously failed at the gRPC layer.
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      SERIAL="${2:?missing serial}"
      shift 2
      ;;
    *)
      echo "unknown arg: $1" >&2
      exit 64
      ;;
  esac
done

if [[ -z "${SERIAL}" ]]; then
  echo "ANDROID_SERIAL or --serial <id> required" >&2
  exit 64
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"
mkdir -p tmp

echo "1) reverse port-forward Maestro agent to host"
adb -s "${SERIAL}" reverse --remove-all || true
adb -s "${SERIAL}" reverse tcp:7001 tcp:7001

echo "2) ensure no stale maestro processes"
pkill -f 'maestro test' 2>/dev/null || true
pkill -f 'maestro studio' 2>/dev/null || true

echo "3) prime Maestro driver with a no-op flow"
cat >tmp/maestro-bootstrap-noop.yaml <<'EOF'
appId: com.pocketagent.android
---
- launchApp
EOF
if ! maestro --device "${SERIAL}" test tmp/maestro-bootstrap-noop.yaml \
  --format junit --output tmp/maestro-bootstrap-noop.xml; then
  echo "bootstrap probe failed; check that the device is unlocked and connected" >&2
  exit 1
fi
echo "ok: local Maestro bootstrap is healthy on ${SERIAL}"
