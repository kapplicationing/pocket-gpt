#!/usr/bin/env bash
# Runs one non-generation interaction three times on one native benchmark APK,
# then validates provenance and enforces median frame thresholds.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
HARNESS_HELPER="$SCRIPT_DIR/android_perf_harness.py"
source "$SCRIPT_DIR/android-perf-lock.sh"

ORIGINAL_COMMAND="$0"
for original_arg in "$@"; do
  ORIGINAL_COMMAND+=" $(printf '%q' "$original_arg")"
done

SERIAL="${ANDROID_SERIAL:-}"
PACKAGE="com.pocketagent.android"
SCENARIO=""
OUT_DIR=""
DECLARED_RUNTIME_CONDITION=""
DECLARED_DOWNLOAD_CONDITION=""
DECLARED_VOICE_CONDITION=""

cleanup() {
  local exit_code=$?
  if ! perf_lock_release; then
    if [[ "$exit_code" -eq 0 ]]; then
      exit_code=73
    fi
  fi
  trap - EXIT
  exit "$exit_code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

usage() {
  cat <<EOF
Usage: $0 --scenario settings-nav|model-sheet|drawer-search --runtime-state unloaded|loading|loaded-idle --download-state idle|active --voice-state inactive|active [--serial SERIAL] [--package PACKAGE] [--out-dir DIR]

Builds and installs the native-enabled benchmark APK for sample 1, captures
three samples on the same installed package, then enforces median thresholds.
The runtime, download, and voice flags are operator declarations checked for
consistency; they are not observed application state.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      SERIAL="$2"
      shift 2
      ;;
    --package)
      PACKAGE="$2"
      shift 2
      ;;
    --scenario)
      SCENARIO="$2"
      shift 2
      ;;
    --runtime-state)
      DECLARED_RUNTIME_CONDITION="$2"
      shift 2
      ;;
    --download-state)
      DECLARED_DOWNLOAD_CONDITION="$2"
      shift 2
      ;;
    --voice-state)
      DECLARED_VOICE_CONDITION="$2"
      shift 2
      ;;
    --out-dir)
      OUT_DIR="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "unknown arg $1" >&2
      usage >&2
      exit 64
      ;;
  esac
done

[[ -n "$SERIAL" ]] || {
  echo "ANDROID_SERIAL or --serial required" >&2
  exit 64
}
case "$SCENARIO" in
  settings-nav|model-sheet|drawer-search) ;;
  *)
    echo "--scenario must be settings-nav, model-sheet, or drawer-search" >&2
    usage >&2
    exit 64
    ;;
esac
case "$DECLARED_RUNTIME_CONDITION" in
  unloaded|loading|loaded-idle) ;;
  *) echo "--runtime-state must be unloaded, loading, or loaded-idle" >&2; exit 64 ;;
esac
case "$DECLARED_DOWNLOAD_CONDITION" in
  idle|active) ;;
  *) echo "--download-state must be idle or active" >&2; exit 64 ;;
esac
case "$DECLARED_VOICE_CONDITION" in
  inactive|active) ;;
  *) echo "--voice-state must be inactive or active" >&2; exit 64 ;;
esac

if [[ -z "$OUT_DIR" ]]; then
  RUN_ID="$(python3 "$HARNESS_HELPER" run-id --pid "$$")"
  OUT_DIR="$REPO_ROOT/tmp/perf-interaction/${RUN_ID}-${SCENARIO}-gate"
fi
mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"

perf_lock_acquire "$SERIAL" "$PACKAGE" "$$" "$ORIGINAL_COMMAND"
echo "[perf-interaction-gate] device lease=$PERF_LOCK_DIR"

summary_paths=()
for sample_number in 1 2 3; do
  sample_dir="$OUT_DIR/sample-$sample_number"
  command=(
    bash "$SCRIPT_DIR/perf-interaction.sh"
    --serial "$SERIAL"
    --package "$PACKAGE"
    --scenario "$SCENARIO"
    --runtime-state "$DECLARED_RUNTIME_CONDITION"
    --download-state "$DECLARED_DOWNLOAD_CONDITION"
    --voice-state "$DECLARED_VOICE_CONDITION"
    --lock-token "$PERF_LOCK_TOKEN"
    --out-dir "$sample_dir"
  )
  if [[ "$sample_number" -eq 1 ]]; then
    command+=(--build)
  fi
  echo "[perf-interaction-gate] sample $sample_number/3"
  "${command[@]}"
  summary_paths+=("$sample_dir/summary.json")
done

evaluation_path="$OUT_DIR/evaluation.json"
python3 "$REPO_ROOT/scripts/benchmarks/evaluate_android_frame_thresholds.py" \
  --scenario "$SCENARIO" \
  --package "$PACKAGE" \
  --output "$evaluation_path" \
  "${summary_paths[@]}"
echo "[perf-interaction-gate] evaluation=$evaluation_path"
