#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
Usage: scripts/ci/run_weekly_send_capture.sh --device <adb-serial> [options]

Options:
  --device <adb-serial>              Required-tier Android device.
  --reply-timeout-seconds <seconds>  Strict completion SLA. Default: 90.
  --run-date <YYYY-MM-DD>            Packet date. Default: current UTC date.
  --run-root <path>                  Override packet output directory.
  --previous-report <path>           Prior qa-13-weekly-report.json for delta classification.
EOF
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

DEVICE="${ADB_SERIAL:-${ANDROID_SERIAL:-}}"
REPLY_TIMEOUT_SECONDS=90
RUN_DATE="$(date -u +%F)"
RUN_ROOT=""
PREVIOUS_REPORT=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      DEVICE="${2:?missing value for --device}"
      shift 2
      ;;
    --reply-timeout-seconds)
      REPLY_TIMEOUT_SECONDS="${2:?missing value for --reply-timeout-seconds}"
      shift 2
      ;;
    --run-date)
      RUN_DATE="${2:?missing value for --run-date}"
      shift 2
      ;;
    --run-root)
      RUN_ROOT="${2:?missing value for --run-root}"
      shift 2
      ;;
    --previous-report)
      PREVIOUS_REPORT="${2:?missing value for --previous-report}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 2
      ;;
  esac
done

if [[ -z "${DEVICE}" ]]; then
  echo "--device or ADB_SERIAL/ANDROID_SERIAL is required." >&2
  exit 2
fi
if ! [[ "${REPLY_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "--reply-timeout-seconds must be a positive integer." >&2
  exit 2
fi
if ! [[ "${RUN_DATE}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
  echo "--run-date must use YYYY-MM-DD." >&2
  exit 2
fi

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
if [[ -z "${RUN_ROOT}" ]]; then
  RUN_ROOT="tmp/weekly-send-capture/${RUN_DATE}/${DEVICE}/${STAMP}"
fi
mkdir -p "${RUN_ROOT}"
JOURNEY_LOG="${RUN_ROOT}/journey.log"

export ADB_SERIAL="${DEVICE}"
export ANDROID_SERIAL="${DEVICE}"

set +e
python3 tools/devctl/main.py lane journey \
  --repeats 1 \
  --mode strict \
  --steps instrumentation,send-capture \
  --reply-timeout-seconds "${REPLY_TIMEOUT_SECONDS}" \
  2>&1 | tee "${JOURNEY_LOG}"
LANE_EXIT_CODE=${PIPESTATUS[0]}
set -e

packet_args=(
  --journey-log "${JOURNEY_LOG}"
  --output-dir "${RUN_ROOT}"
  --lane-exit-code "${LANE_EXIT_CODE}"
)
if [[ -n "${PREVIOUS_REPORT}" && -f "${PREVIOUS_REPORT}" ]]; then
  packet_args+=(--previous-report "${PREVIOUS_REPORT}")
fi

set +e
python3 -m tools.devctl.weekly_send_capture "${packet_args[@]}"
PACKET_EXIT_CODE=$?
set -e

echo "QA-13 packet root: ${RUN_ROOT}"
if [[ ${LANE_EXIT_CODE} -ne 0 || ${PACKET_EXIT_CODE} -ne 0 ]]; then
  exit 1
fi
