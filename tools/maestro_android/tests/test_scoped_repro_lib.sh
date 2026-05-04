#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "${REPO_ROOT}/tools/maestro_android/scoped_repro_lib.sh"

assert_eq() {
  local expected="$1"
  local actual="$2"
  if [[ "${expected}" != "${actual}" ]]; then
    echo "expected '${expected}', got '${actual}'" >&2
    exit 1
  fi
}

assert_true() {
  if ! "$@"; then
    echo "expected command to succeed: $*" >&2
    exit 1
  fi
}

assert_false() {
  if "$@"; then
    echo "expected command to fail: $*" >&2
    exit 1
  fi
}

assert_eq "wifi_5555_alpha" "$(maestro_android_sanitize_token 'wifi:5555/alpha')"
assert_true maestro_android_is_tcpip_serial "192.168.1.3:5555"
assert_true maestro_android_is_tcpip_serial "adb-RFCT2178PDV-nZWer7._adb-tls-connect._tcp"
assert_false maestro_android_is_tcpip_serial "emulator-5554"
assert_false maestro_android_is_tcpip_serial "SER123"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

TRANSIENT_LOG="${TMP_DIR}/transient.log"
TRANSIENT_DEADLINE_LOG="${TMP_DIR}/transient-deadline.log"
NON_TRANSIENT_LOG="${TMP_DIR}/non-transient.log"
printf 'io.grpc.StatusRuntimeException: UNAVAILABLE: io exception\n' > "${TRANSIENT_LOG}"
printf 'deviceInfo call failed on android with Status{code=DEADLINE_EXCEEDED, description=deadline exceeded after 119.97s. [open=[[waiting_for_connection]]]}\n' > "${TRANSIENT_DEADLINE_LOG}"
printf 'Element not found: session_drawer_button\n' > "${NON_TRANSIENT_LOG}"

assert_true maestro_android_log_has_transient_failure "${TRANSIENT_LOG}"
assert_true maestro_android_log_has_transient_failure "${TRANSIENT_DEADLINE_LOG}"
assert_false maestro_android_log_has_transient_failure "${NON_TRANSIENT_LOG}"

CLEAR_STATE_FLOW="${TMP_DIR}/clear-state-flow.yaml"
NO_CLEAR_STATE_FLOW="${TMP_DIR}/no-clear-state-flow.yaml"
REWRITTEN_FLOW="${TMP_DIR}/rewritten-flow.yaml"
NO_LAUNCH_FLOW="${TMP_DIR}/no-launch-flow.yaml"
PREPARED_FLOW_DIR="${TMP_DIR}/prepared"

cat > "${CLEAR_STATE_FLOW}" <<'EOF'
appId: com.pocketagent.android
---
- launchApp:
    clearState: true
- assertVisible: "Ready"
EOF

cat > "${NO_CLEAR_STATE_FLOW}" <<'EOF'
appId: com.pocketagent.android
---
- launchApp:
    clearState: false
- assertVisible: "Ready"
EOF

assert_true maestro_android_flow_uses_clear_state "${CLEAR_STATE_FLOW}"
assert_false maestro_android_flow_uses_clear_state "${NO_CLEAR_STATE_FLOW}"

maestro_android_prepare_flow_without_clear_state "${CLEAR_STATE_FLOW}" "${REWRITTEN_FLOW}"
assert_false maestro_android_flow_uses_clear_state "${REWRITTEN_FLOW}"
assert_true rg -n "clearState: false" "${REWRITTEN_FLOW}"

assert_true maestro_android_flow_starts_with_launch_app "${CLEAR_STATE_FLOW}"
maestro_android_prepare_flow_without_initial_launch_app "${CLEAR_STATE_FLOW}" "${NO_LAUNCH_FLOW}"
assert_false maestro_android_flow_starts_with_launch_app "${NO_LAUNCH_FLOW}"
assert_false rg -n "launchApp" "${NO_LAUNCH_FLOW}"
assert_false rg -n "clearState:" "${NO_LAUNCH_FLOW}"
assert_true rg -n 'assertVisible: "Ready"' "${NO_LAUNCH_FLOW}"

assert_eq \
  "${PREPARED_FLOW_DIR}/.clear-state-flow-20260503-120000-wifi_5555_alpha-prepared-flow.yaml" \
  "$(maestro_android_prepared_flow_path "${CLEAR_STATE_FLOW}" "${PREPARED_FLOW_DIR}" "20260503-120000" 'wifi:5555/alpha' 'prepared-flow')"

assert_eq \
  "${REPO_ROOT}/scripts/benchmarks/device-env/locks/wifi_5555_alpha.lock" \
  "$(maestro_android_device_lock_path "${REPO_ROOT}" 'wifi:5555/alpha')"

LOCK_PATH="${TMP_DIR}/device.lock"
printf 'pid=%s\nowner=devctl lane maestro\n' "$$" > "${LOCK_PATH}"
mkdir -p "${TMP_DIR}/scripts/benchmarks/device-env/locks"
cp "${LOCK_PATH}" "${TMP_DIR}/scripts/benchmarks/device-env/locks/wifi_5555_alpha.lock"
assert_eq \
  "$$" \
  "$(maestro_android_active_device_lock_pid "${TMP_DIR}" 'wifi:5555/alpha')"

echo "test_scoped_repro_lib.sh: PASS"
