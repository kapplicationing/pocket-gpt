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
NON_TRANSIENT_LOG="${TMP_DIR}/non-transient.log"
printf 'io.grpc.StatusRuntimeException: UNAVAILABLE: io exception\n' > "${TRANSIENT_LOG}"
printf 'Element not found: session_drawer_button\n' > "${NON_TRANSIENT_LOG}"

assert_true maestro_android_log_has_transient_failure "${TRANSIENT_LOG}"
assert_false maestro_android_log_has_transient_failure "${NON_TRANSIENT_LOG}"

echo "test_scoped_repro_lib.sh: PASS"
