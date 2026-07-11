#!/usr/bin/env bash
# Shared atomic performance-device lease helpers for perf-interaction scripts.

ANDROID_PERF_LOCK_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_PERF_LOCK_HELPER="$ANDROID_PERF_LOCK_SCRIPT_DIR/android_perf_lock.py"
PERF_LOCK_TOKEN=""
PERF_LOCK_DIR=""
PERF_LOCK_SERIAL=""
PERF_LOCK_PACKAGE=""
PERF_LOCK_OWNED=0

perf_lock_acquire() {
  local serial="$1"
  local package="$2"
  local owner_pid="$3"
  local owner_command="$4"
  local lease=""
  lease="$(python3 "$ANDROID_PERF_LOCK_HELPER" acquire \
    --serial "$serial" \
    --package "$package" \
    --owner-pid "$owner_pid" \
    --owner-command "$owner_command")" || return $?
  IFS=$'\t' read -r PERF_LOCK_TOKEN PERF_LOCK_DIR <<<"$lease"
  if [[ -z "$PERF_LOCK_TOKEN" || -z "$PERF_LOCK_DIR" ]]; then
    echo "[android-perf-lock] acquire returned an incomplete lease" >&2
    return 73
  fi
  PERF_LOCK_SERIAL="$serial"
  PERF_LOCK_PACKAGE="$package"
  PERF_LOCK_OWNED=1
}

perf_lock_validate() {
  local serial="$1"
  local package="$2"
  local token="$3"
  python3 "$ANDROID_PERF_LOCK_HELPER" validate \
    --serial "$serial" \
    --package "$package" \
    --token "$token"
}

perf_lock_release() {
  if [[ "$PERF_LOCK_OWNED" -ne 1 ]]; then
    return 0
  fi
  python3 "$ANDROID_PERF_LOCK_HELPER" release \
    --serial "$PERF_LOCK_SERIAL" \
    --package "$PERF_LOCK_PACKAGE" \
    --token "$PERF_LOCK_TOKEN" || return $?
  PERF_LOCK_OWNED=0
  PERF_LOCK_TOKEN=""
  PERF_LOCK_DIR=""
}
