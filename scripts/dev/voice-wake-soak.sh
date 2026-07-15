#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF' >&2
Usage:
  bash scripts/dev/voice-wake-soak.sh \
    --device <serial> \
    [--duration-minutes <minutes>] \
    [--sample-seconds <seconds>] \
    [--expected-wakes <count>] \
    [--mode always-on|baseline] \
    [--baseline-metrics <metrics.env>] \
    [--output-dir <directory>] \
    [--package <application-id>] \
    [--allow-charging]

Measures PocketAgent without rebuilding, installing, changing battery stats, or
waking the screen. The default is a 24-hour, screen-off-friendly always-on soak
with no expected wake phrases. Capture a matching --mode baseline run with the
listener off, then pass its metrics.env to --baseline-metrics so the report can
calculate incremental drain instead of mislabeling whole-phone drain.

This lane measures survival, battery/thermal movement, process PSS/CPU snapshots,
and unexplained KWS detections. It does not by itself qualify a production device
tier: recall/noise, audio routing, calls, Doze, and multiple retained runs remain
separate gates.
EOF
}

DEVICE=""
DURATION_MINUTES=1440
SAMPLE_SECONDS=60
EXPECTED_WAKES=0
MODE="always-on"
BASELINE_METRICS=""
OUTPUT_DIR=""
ALLOW_CHARGING=false
PACKAGE="com.pocketagent.android"
SERVICE="OffasListenerService"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device) DEVICE="${2:-}"; shift 2 ;;
    --duration-minutes) DURATION_MINUTES="${2:-}"; shift 2 ;;
    --sample-seconds) SAMPLE_SECONDS="${2:-}"; shift 2 ;;
    --expected-wakes) EXPECTED_WAKES="${2:-}"; shift 2 ;;
    --mode) MODE="${2:-}"; shift 2 ;;
    --baseline-metrics) BASELINE_METRICS="${2:-}"; shift 2 ;;
    --output-dir) OUTPUT_DIR="${2:-}"; shift 2 ;;
    --package) PACKAGE="${2:-}"; shift 2 ;;
    --allow-charging) ALLOW_CHARGING=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage; exit 2 ;;
  esac
done

is_positive_integer() {
  [[ "$1" =~ ^[1-9][0-9]*$ ]]
}

is_non_negative_integer() {
  [[ "$1" =~ ^[0-9]+$ ]]
}

if [[ -z "${DEVICE}" ]] || ! is_positive_integer "${DURATION_MINUTES}" || \
   ! is_positive_integer "${SAMPLE_SECONDS}" || ! is_non_negative_integer "${EXPECTED_WAKES}" || \
   [[ ! "${PACKAGE}" =~ ^[A-Za-z0-9._]+$ ]] || \
   [[ "${MODE}" != "always-on" && "${MODE}" != "baseline" ]]; then
  usage
  exit 2
fi

for command in adb awk sed grep date; do
  command -v "${command}" >/dev/null 2>&1 || {
    echo "Required command is unavailable: ${command}" >&2
    exit 1
  }
done

if [[ "$(adb -s "${DEVICE}" get-state 2>/dev/null || true)" != "device" ]]; then
  echo "Device is not available through adb: ${DEVICE}" >&2
  exit 1
fi
if ! adb -s "${DEVICE}" shell pm path "${PACKAGE}" | grep -q '^package:'; then
  echo "PocketAgent is not installed on ${DEVICE}." >&2
  exit 1
fi
listener_running=0
if adb -s "${DEVICE}" shell dumpsys activity services "${PACKAGE}" | grep -q "${SERVICE}"; then
  listener_running=1
fi
if [[ "${MODE}" == "always-on" && "${listener_running}" != "1" ]]; then
  echo "PocketAgent always-on listening is not running." >&2
  echo "Turn on Hands-free Offas in Voice access before starting an always-on soak." >&2
  exit 1
fi
if [[ "${MODE}" == "baseline" && "${listener_running}" != "0" ]]; then
  echo "Stop PocketAgent always-on listening before capturing a baseline." >&2
  exit 1
fi

timestamp="$(date -u '+%Y%m%dT%H%M%SZ')"
if [[ -z "${OUTPUT_DIR}" ]]; then
  OUTPUT_DIR="tmp/voice-wake-soak/${timestamp}-${DEVICE//[^A-Za-z0-9._-]/_}"
fi
mkdir -p "${OUTPUT_DIR}"
SAMPLES="${OUTPUT_DIR}/samples.csv"
LOGCAT="${OUTPUT_DIR}/voice-logcat.txt"
SUMMARY="${OUTPUT_DIR}/summary.md"
METADATA="${OUTPUT_DIR}/device.txt"
METRICS="${OUTPUT_DIR}/metrics.env"

battery_dump() {
  adb -s "${DEVICE}" shell dumpsys battery 2>/dev/null | tr -d '\r'
}

battery_value() {
  local label="$1"
  battery_dump | awk -F: -v label="${label}" '$1 ~ "^[[:space:]]*" label "$" {gsub(/[[:space:]]/, "", $2); print $2; exit}'
}

record_audio_active() {
  adb -s "${DEVICE}" shell appops get "${PACKAGE}" RECORD_AUDIO 2>/dev/null | \
    grep -Eq 'RECORD_AUDIO:.*\(running\)|RECORD_AUDIO:.*running=true'
}

if [[ "${MODE}" == "always-on" ]] && ! record_audio_active; then
  echo "PocketAgent's listener exists, but microphone capture is not active." >&2
  echo "Wait for the listening notification and Android microphone indicator, then retry." >&2
  exit 1
fi
if [[ "${MODE}" == "baseline" ]] && record_audio_active; then
  echo "PocketAgent still owns active microphone capture; stop listening before the baseline." >&2
  exit 1
fi

is_powered() {
  battery_dump | awk -F: '
    /^[[:space:]]*(AC|USB|Wireless) powered:/ {
      gsub(/[[:space:]]/, "", $2)
      if ($2 == "true") powered=1
    }
    END { print powered + 0 }
  '
}

start_powered="$(is_powered)"
if [[ "${start_powered}" == "1" && "${ALLOW_CHARGING}" != true ]]; then
  echo "Disconnect charging before a battery qualification soak." >&2
  echo "Use --allow-charging only for a survival diagnostic; drain results will be invalid." >&2
  exit 1
fi

{
  echo "run_started_utc=${timestamp}"
  echo "device=${DEVICE}"
  echo "package=${PACKAGE}"
  echo "duration_minutes=${DURATION_MINUTES}"
  echo "sample_seconds=${SAMPLE_SECONDS}"
  echo "expected_wakes=${EXPECTED_WAKES}"
  echo "mode=${MODE}"
  echo "baseline_metrics=${BASELINE_METRICS}"
  echo "allow_charging=${ALLOW_CHARGING}"
  echo "model=$(adb -s "${DEVICE}" shell getprop ro.product.model | tr -d '\r')"
  echo "manufacturer=$(adb -s "${DEVICE}" shell getprop ro.product.manufacturer | tr -d '\r')"
  echo "android=$(adb -s "${DEVICE}" shell getprop ro.build.version.release | tr -d '\r')"
  echo "sdk=$(adb -s "${DEVICE}" shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "fingerprint=$(adb -s "${DEVICE}" shell getprop ro.build.fingerprint | tr -d '\r')"
  echo "app=$(adb -s "${DEVICE}" shell dumpsys package "${PACKAGE}" | awk '/versionName=/{print $1; exit}' | tr -d '\r')"
} > "${METADATA}"

LOGCAT_PID=""
cleanup() {
  status=$?
  if [[ -n "${LOGCAT_PID}" ]]; then
    kill "${LOGCAT_PID}" >/dev/null 2>&1 || true
    wait "${LOGCAT_PID}" >/dev/null 2>&1 || true
  fi
  exit "${status}"
}
trap cleanup EXIT INT TERM

start_epoch="$(date +%s)"
adb -s "${DEVICE}" logcat -v epoch -T 1 \
  -s PocketAgentVoice:D PocketAgentVoiceAction:D '*:S' > "${LOGCAT}" 2>&1 &
LOGCAT_PID=$!

printf '%s\n' 'epoch,level,charge_counter_uah,voltage_mv,temperature_tenths_c,powered,service_running,mic_active,pid,pss_kb,cpu_percent,screen_on' > "${SAMPLES}"

sample() {
  local now battery level charge voltage temperature powered services service_running mic_active pid pss cpu screen_on
  now="$(date +%s)"
  battery="$(battery_dump)"
  level="$(printf '%s\n' "${battery}" | awk -F: '/^[[:space:]]*level:/{gsub(/[[:space:]]/, "", $2); print $2; exit}')"
  charge="$(printf '%s\n' "${battery}" | awk -F: '/^[[:space:]]*Charge counter:/{gsub(/[[:space:]]/, "", $2); print $2; exit}')"
  voltage="$(printf '%s\n' "${battery}" | awk -F: '/^[[:space:]]*voltage:/{gsub(/[[:space:]]/, "", $2); print $2; exit}')"
  temperature="$(printf '%s\n' "${battery}" | awk -F: '/^[[:space:]]*temperature:/{gsub(/[[:space:]]/, "", $2); print $2; exit}')"
  powered="$(printf '%s\n' "${battery}" | awk -F: '/^[[:space:]]*(AC|USB|Wireless) powered:/{gsub(/[[:space:]]/, "", $2); if ($2 == "true") p=1} END{print p+0}')"
  services="$(adb -s "${DEVICE}" shell dumpsys activity services "${PACKAGE}" 2>/dev/null | tr -d '\r')"
  service_running=0
  printf '%s\n' "${services}" | grep -q "${SERVICE}" && service_running=1
  mic_active=0
  record_audio_active && mic_active=1
  pid="$(adb -s "${DEVICE}" shell pidof "${PACKAGE}" 2>/dev/null || true)"
  pid="$(printf '%s' "${pid}" | tr -d '\r' | awk '{print $1}')"
  pss=""
  cpu=""
  if [[ -n "${pid}" ]]; then
    pss="$(adb -s "${DEVICE}" shell dumpsys meminfo "${PACKAGE}" 2>/dev/null | awk '/TOTAL PSS:/{gsub(/,/, "", $3); print $3; exit}')"
    cpu="$(adb -s "${DEVICE}" shell dumpsys cpuinfo 2>/dev/null | awk -v package="${PACKAGE}" '$0 ~ package {gsub(/%/, "", $1); print $1; exit}')"
  fi
  screen_on=0
  adb -s "${DEVICE}" shell dumpsys power 2>/dev/null | grep -q 'Display Power: state=ON' && screen_on=1
  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "${now}" "${level}" "${charge}" "${voltage}" "${temperature}" "${powered}" \
    "${service_running}" "${mic_active}" "${pid}" "${pss}" "${cpu}" "${screen_on}" >> "${SAMPLES}"
}

end_target=$((start_epoch + DURATION_MINUTES * 60))
echo "Voice ${MODE} soak started: ${OUTPUT_DIR}"
echo "Leave the phone unplugged and the screen off. Expected wake phrases: ${EXPECTED_WAKES}."
while true; do
  sample
  now="$(date +%s)"
  if (( now >= end_target )); then
    break
  fi
  remaining=$((end_target - now))
  delay="${SAMPLE_SECONDS}"
  if (( remaining < delay )); then
    delay="${remaining}"
  fi
  sleep "${delay}"
done

kill "${LOGCAT_PID}" >/dev/null 2>&1 || true
wait "${LOGCAT_PID}" >/dev/null 2>&1 || true
LOGCAT_PID=""
end_epoch="$(date +%s)"

first="$(sed -n '2p' "${SAMPLES}")"
last="$(tail -n 1 "${SAMPLES}")"
first_level="$(printf '%s' "${first}" | awk -F, '{print $2}')"
last_level="$(printf '%s' "${last}" | awk -F, '{print $2}')"
first_charge="$(printf '%s' "${first}" | awk -F, '{print $3}')"
last_charge="$(printf '%s' "${last}" | awk -F, '{print $3}')"
first_voltage="$(printf '%s' "${first}" | awk -F, '{print $4}')"
last_voltage="$(printf '%s' "${last}" | awk -F, '{print $4}')"
first_temperature="$(printf '%s' "${first}" | awk -F, '{print $5}')"
max_temperature="$(awk -F, 'NR>1 && $5 != "" {if (!seen || $5 > max) max=$5; seen=1} END{if (seen) print max}' "${SAMPLES}")"
listener_missing_samples="$(awk -F, 'NR>1 && $7 != 1 {count++} END{print count+0}' "${SAMPLES}")"
listener_present_samples="$(awk -F, 'NR>1 && $7 == 1 {count++} END{print count+0}' "${SAMPLES}")"
mic_inactive_samples="$(awk -F, 'NR>1 && $8 != 1 {count++} END{print count+0}' "${SAMPLES}")"
mic_active_samples="$(awk -F, 'NR>1 && $8 == 1 {count++} END{print count+0}' "${SAMPLES}")"
powered_samples="$(awk -F, 'NR>1 && $6 == 1 {count++} END{print count+0}' "${SAMPLES}")"
wake_detections="$(awk -v start="${start_epoch}" '$1 + 0 >= start && /KWS_WAKE_DETECTED/ {count++} END{print count+0}' "${LOGCAT}")"
unexplained_wakes=$((wake_detections > EXPECTED_WAKES ? wake_detections - EXPECTED_WAKES : 0))
duration_seconds=$((end_epoch - start_epoch))
duration_hours="$(awk -v seconds="${duration_seconds}" 'BEGIN{printf "%.4f", seconds / 3600}')"
level_per_hour="unknown"
whole_device_mw="unknown"
incremental_level_per_hour="unknown"
incremental_mw="unknown"
temperature_rise_c="unknown"

if [[ "${first_level}" =~ ^[0-9]+$ && "${last_level}" =~ ^[0-9]+$ ]]; then
  level_per_hour="$(awk -v first="${first_level}" -v last="${last_level}" -v hours="${duration_hours}" 'BEGIN{printf "%.3f", (first-last)/hours}')"
fi
if [[ "${first_charge}" =~ ^-?[0-9]+$ && "${last_charge}" =~ ^-?[0-9]+$ && \
      "${first_voltage}" =~ ^[0-9]+$ && "${last_voltage}" =~ ^[0-9]+$ ]]; then
  whole_device_mw="$(awk -v first="${first_charge}" -v last="${last_charge}" \
    -v v1="${first_voltage}" -v v2="${last_voltage}" -v hours="${duration_hours}" \
    'BEGIN{delta=first-last; if(delta<0)delta=-delta; printf "%.2f", (delta/hours)*((v1+v2)/2)/1000000}')"
fi
if [[ "${first_temperature}" =~ ^-?[0-9]+$ && "${max_temperature}" =~ ^-?[0-9]+$ ]]; then
  temperature_rise_c="$(awk -v first="${first_temperature}" -v max="${max_temperature}" 'BEGIN{printf "%.1f", (max-first)/10}')"
fi

verdict="INCOMPLETE"
reason="A retained result requires at least 24 hours and complete unplugged charge data."

metric_from_file() {
  local key="$1"
  local file="$2"
  awk -F= -v key="${key}" '$1 == key {print substr($0, index($0, "=") + 1); exit}' "${file}"
}

baseline_valid=false
if [[ "${MODE}" == "always-on" && -n "${BASELINE_METRICS}" && -f "${BASELINE_METRICS}" ]]; then
  baseline_mode="$(metric_from_file mode "${BASELINE_METRICS}")"
  baseline_duration="$(metric_from_file duration_seconds "${BASELINE_METRICS}")"
  baseline_powered="$(metric_from_file powered_samples "${BASELINE_METRICS}")"
  baseline_listener_present="$(metric_from_file listener_present_samples "${BASELINE_METRICS}")"
  baseline_mic_active="$(metric_from_file mic_active_samples "${BASELINE_METRICS}")"
  baseline_level_per_hour="$(metric_from_file level_per_hour "${BASELINE_METRICS}")"
  baseline_whole_device_mw="$(metric_from_file whole_device_mw "${BASELINE_METRICS}")"
  if [[ "${baseline_mode}" == "baseline" && "${baseline_duration}" =~ ^[0-9]+$ && \
        "${baseline_powered}" == "0" && "${baseline_listener_present}" == "0" && \
        "${baseline_mic_active}" == "0" && \
        "${baseline_level_per_hour}" =~ ^-?[0-9]+([.][0-9]+)?$ && \
        "${baseline_whole_device_mw}" =~ ^-?[0-9]+([.][0-9]+)?$ ]] && (( baseline_duration >= 86400 )); then
    baseline_valid=true
    incremental_level_per_hour="$(awk -v active="${level_per_hour}" -v baseline="${baseline_level_per_hour}" 'BEGIN{printf "%.3f", active-baseline}')"
    incremental_mw="$(awk -v active="${whole_device_mw}" -v baseline="${baseline_whole_device_mw}" 'BEGIN{printf "%.2f", active-baseline}')"
  fi
fi

if [[ "${MODE}" == "baseline" ]]; then
  if (( duration_seconds >= 86400 )) && [[ "${powered_samples}" == "0" && "${listener_present_samples}" == "0" && \
       "${mic_active_samples}" == "0" && \
       "${level_per_hour}" != "unknown" && "${whole_device_mw}" != "unknown" ]]; then
    verdict="BASELINE_CAPTURED"
    reason="This unplugged listener-off baseline can be paired with a matching always-on run."
  elif [[ "${powered_samples}" != "0" || "${listener_present_samples}" != "0" || \
          "${mic_active_samples}" != "0" ]]; then
    verdict="FAIL"
    reason="The phone was powered, the listener ran, or PocketAgent held the microphone during the baseline window."
  fi
elif (( duration_seconds >= 86400 )) && [[ "${powered_samples}" == "0" && "${listener_missing_samples}" == "0" && \
     "${mic_inactive_samples}" == "0" && \
     "${level_per_hour}" != "unknown" && "${whole_device_mw}" != "unknown" && \
     "${temperature_rise_c}" != "unknown" && "${baseline_valid}" == true ]]; then
  if awk -v pph="${incremental_level_per_hour}" -v mw="${incremental_mw}" -v temp="${temperature_rise_c}" \
       -v false_wakes="${unexplained_wakes}" -v hours="${duration_hours}" \
       'BEGIN{exit !((pph <= 0.5) && (mw <= 75) && (temp < 3) && ((false_wakes/hours)*100 <= 1))}'; then
    verdict="PASS"
    reason="This paired soak passed the incremental battery, thermal, survival, and unexplained-wake budgets. Other device-tier gates still apply."
  else
    verdict="FAIL"
    reason="At least one battery, thermal, survival, or unexplained-wake budget failed."
  fi
elif [[ "${listener_missing_samples}" != "0" || "${mic_inactive_samples}" != "0" || \
        "${powered_samples}" != "0" ]]; then
  verdict="FAIL"
  reason="The listener or microphone capture stopped, or the phone was powered during the qualification window."
elif (( duration_seconds >= 86400 )) && [[ "${baseline_valid}" != true ]]; then
  reason="Pair this run with a valid 24-hour listener-off --baseline-metrics file to calculate incremental drain."
fi

cat > "${METRICS}" <<EOF
mode=${MODE}
duration_seconds=${duration_seconds}
level_per_hour=${level_per_hour}
whole_device_mw=${whole_device_mw}
temperature_rise_c=${temperature_rise_c}
powered_samples=${powered_samples}
listener_missing_samples=${listener_missing_samples}
listener_present_samples=${listener_present_samples}
mic_inactive_samples=${mic_inactive_samples}
mic_active_samples=${mic_active_samples}
wake_detections=${wake_detections}
expected_wakes=${EXPECTED_WAKES}
unexplained_wakes=${unexplained_wakes}
EOF

cat > "${SUMMARY}" <<EOF
# Voice wake soak

- Verdict: **${verdict}**
- Reason: ${reason}
- Device: ${DEVICE}
- Mode: ${MODE}
- Duration: ${duration_hours} hours
- Whole-device battery-level drain: ${level_per_hour} percentage points/hour
- Whole-device power estimate from charge counter: ${whole_device_mw} mW
- Paired incremental battery-level drain: ${incremental_level_per_hour} percentage points/hour
- Paired incremental power: ${incremental_mw} mW
- Maximum battery-temperature rise: ${temperature_rise_c} C
- Listener-missing samples: ${listener_missing_samples}
- Listener-present samples: ${listener_present_samples}
- Microphone-inactive samples: ${mic_inactive_samples}
- Microphone-active samples: ${mic_active_samples}
- Powered samples: ${powered_samples}
- KWS detections: ${wake_detections}
- Expected wake phrases: ${EXPECTED_WAKES}
- Unexplained detections: ${unexplained_wakes}

This report qualifies only the measured soak. It does not restrict feature
availability; retained recall/noise, wake-latency, route, call, Doze,
permission, reboot, and multi-run evidence still determine support confidence.
EOF

echo "Voice wake soak complete: ${SUMMARY}"
