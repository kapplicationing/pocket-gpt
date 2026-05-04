#!/usr/bin/env bash

maestro_android_sanitize_token() {
  printf '%s' "${1:-}" | tr -c 'A-Za-z0-9._-' '_'
}

maestro_android_is_tcpip_serial() {
  local serial="${1:-}"
  [[ "${serial}" == *:* && "${serial}" != emulator-* ]] || [[ "${serial}" == *"._adb-tls-connect._tcp" ]]
}

maestro_android_transient_failure_regex() {
  printf '%s' "Unable to launch app|TimeoutException|timed out while waiting for FUNCTIONFS_BIND|TcpForwarder|UNAVAILABLE: io exception|Connection refused|DEADLINE_EXCEEDED: deadline exceeded|waiting_for_connection"
}

maestro_android_log_has_transient_failure() {
  local log_path="${1:-}"
  if [[ -z "${log_path}" || ! -f "${log_path}" ]]; then
    return 1
  fi
  rg -n "$(maestro_android_transient_failure_regex)" "${log_path}" >/dev/null 2>&1
}

maestro_android_kill_conflicting_local_processes() {
  local serial="${1:-}"
  if [[ -z "${serial}" ]]; then
    return 0
  fi
  local current_pid="$$"
  local parent_pid="${PPID:-0}"
  local patterns=(
    "maestro.cli.AppKt --device ${serial}"
    "adb -s ${serial} shell .*am instrument"
  )
  local pattern=""
  local line=""
  local pid=""
  for pattern in "${patterns[@]}"; do
    while IFS= read -r line; do
      [[ -n "${line}" ]] || continue
      pid="${line%% *}"
      [[ "${pid}" =~ ^[0-9]+$ ]] || continue
      if [[ "${pid}" == "${current_pid}" || "${pid}" == "${parent_pid}" ]]; then
        continue
      fi
      kill "${pid}" >/dev/null 2>&1 || true
    done < <(pgrep -af "${pattern}" || true)
  done
}

maestro_android_device_lock_path() {
  local repo_root="${1:-}"
  local serial="${2:-}"
  if [[ -z "${repo_root}" || -z "${serial}" ]]; then
    echo "Missing repo_root or serial for device lock path." >&2
    return 1
  fi
  printf '%s/scripts/benchmarks/device-env/locks/%s.lock\n' \
    "${repo_root}" \
    "$(maestro_android_sanitize_token "${serial}")"
}

maestro_android_active_device_lock_pid() {
  local repo_root="${1:-}"
  local serial="${2:-}"
  local lock_path
  lock_path="$(maestro_android_device_lock_path "${repo_root}" "${serial}")" || return 1
  if [[ ! -f "${lock_path}" ]]; then
    return 1
  fi
  local lock_pid
  lock_pid="$(awk -F= '/^pid=/{print $2; exit}' "${lock_path}")"
  if [[ ! "${lock_pid}" =~ ^[0-9]+$ ]]; then
    return 1
  fi
  if ! kill -0 "${lock_pid}" >/dev/null 2>&1; then
    return 1
  fi
  printf '%s\n' "${lock_pid}"
}

maestro_android_flow_uses_clear_state() {
  local flow_path="${1:-}"
  if [[ -z "${flow_path}" || ! -f "${flow_path}" ]]; then
    return 1
  fi
  python3 - "${flow_path}" <<'PY'
from pathlib import Path
import sys

text = Path(sys.argv[1]).read_text(encoding="utf-8")
previous_launch = False
for raw_line in text.splitlines():
    line = raw_line.strip()
    if line.startswith("- launchApp:"):
        if "clearState: true" in line:
            raise SystemExit(0)
        if "clearState: false" in line:
            raise SystemExit(1)
        previous_launch = True
        continue
    if previous_launch and line.startswith("clearState:"):
        value = line.split(":", 1)[1].strip().lower()
        if value == "true":
            raise SystemExit(0)
        if value == "false":
            raise SystemExit(1)
    if line.startswith("- ") and not line.startswith("- launchApp:"):
        previous_launch = False
raise SystemExit(1)
PY
}

maestro_android_prepare_flow_without_clear_state() {
  local source_path="${1:-}"
  local output_path="${2:-}"
  if [[ -z "${source_path}" || ! -f "${source_path}" ]]; then
    echo "Missing source flow for clear-state rewrite." >&2
    return 1
  fi
  if [[ -z "${output_path}" ]]; then
    echo "Missing output path for clear-state rewrite." >&2
    return 1
  fi
  python3 - "${source_path}" "${output_path}" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1])
target = Path(sys.argv[2])
text = source.read_text(encoding="utf-8")
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(text.replace("clearState: true", "clearState: false"), encoding="utf-8")
PY
}

maestro_android_flow_starts_with_launch_app() {
  local flow_path="${1:-}"
  if [[ -z "${flow_path}" || ! -f "${flow_path}" ]]; then
    return 1
  fi
  python3 - "${flow_path}" <<'PY'
from pathlib import Path
import sys

text = Path(sys.argv[1]).read_text(encoding="utf-8")
body_started = False
for raw_line in text.splitlines():
    line = raw_line.rstrip()
    stripped = line.strip()
    if not body_started:
      if stripped == "---":
        body_started = True
      continue
    if not stripped:
      continue
    if stripped.startswith("- launchApp:"):
      raise SystemExit(0)
    if stripped.startswith("- "):
      raise SystemExit(1)
raise SystemExit(1)
PY
}

maestro_android_prepare_flow_without_initial_launch_app() {
  local source_path="${1:-}"
  local output_path="${2:-}"
  if [[ -z "${source_path}" || ! -f "${source_path}" ]]; then
    echo "Missing source flow for launch-app rewrite." >&2
    return 1
  fi
  if [[ -z "${output_path}" ]]; then
    echo "Missing output path for launch-app rewrite." >&2
    return 1
  fi
  python3 - "${source_path}" "${output_path}" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1])
target = Path(sys.argv[2])
lines = source.read_text(encoding="utf-8").splitlines()
output: list[str] = []
body_started = False
skipping_launch = False
launch_removed = False

for line in lines:
    stripped = line.strip()
    if not body_started:
        output.append(line)
        if stripped == "---":
            body_started = True
        continue
    if skipping_launch:
        if line.startswith("- "):
            skipping_launch = False
            launch_removed = True
            output.append(line)
        elif stripped == "":
            continue
        else:
            continue
        continue
    if launch_removed:
        output.append(line)
        continue
    if stripped.startswith("- launchApp:"):
        if stripped != "- launchApp:":
            launch_removed = True
            continue
        skipping_launch = True
        continue
    launch_removed = True
    output.append(line)

target.parent.mkdir(parents=True, exist_ok=True)
target.write_text("\n".join(output) + "\n", encoding="utf-8")
PY
}

maestro_android_prepared_flow_path() {
  local source_path="${1:-}"
  local output_dir="${2:-}"
  local stamp="${3:-}"
  local serial="${4:-}"
  local suffix="${5:-prepared-flow}"
  if [[ -z "${source_path}" || -z "${output_dir}" || -z "${stamp}" || -z "${serial}" ]]; then
    echo "Missing prepared-flow path inputs." >&2
    return 1
  fi
  local flow_basename
  flow_basename="$(basename "${source_path}" .yaml)"
  local serial_token
  serial_token="$(maestro_android_sanitize_token "${serial}")"
  printf '%s/.%s-%s-%s-%s.yaml\n' "${output_dir}" "${flow_basename}" "${stamp}" "${serial_token}" "${suffix}"
}
