#!/usr/bin/env bash

maestro_android_sanitize_token() {
  printf '%s' "${1:-}" | tr -c 'A-Za-z0-9._-' '_'
}

maestro_android_is_tcpip_serial() {
  local serial="${1:-}"
  [[ "${serial}" == *:* && "${serial}" != emulator-* ]] || [[ "${serial}" == *"._adb-tls-connect._tcp" ]]
}

maestro_android_transient_failure_regex() {
  printf '%s' "Unable to launch app|TimeoutException|timed out while waiting for FUNCTIONFS_BIND|TcpForwarder|UNAVAILABLE: io exception|Connection refused"
}

maestro_android_log_has_transient_failure() {
  local log_path="${1:-}"
  if [[ -z "${log_path}" || ! -f "${log_path}" ]]; then
    return 1
  fi
  rg -n "$(maestro_android_transient_failure_regex)" "${log_path}" >/dev/null 2>&1
}
