#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

MODE="${1:-generate}"
GENERATED_PROFILE="apps/mobile-android/src/main/generated/baselineProfiles/baseline-prof.txt"
NATIVE_BUILD="${POCKETGPT_BASELINE_PROFILE_NATIVE_BUILD:-false}"
RUN_STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
ARTIFACT_ROOT="${POCKETGPT_BASELINE_PROFILE_ARTIFACT_ROOT:-tmp/baseline-profile/${RUN_STAMP}}"
GENERATOR_CLASS="com.pocketagent.android.baselineprofile.BaselineProfileGenerator"

usage() {
  cat >&2 <<'EOF'
Usage: ANDROID_SERIAL=<api-33+-device> scripts/dev/baseline-profile.sh generate
       scripts/dev/baseline-profile.sh verify

WARNING: generate targets com.pocketagent.android through nonMinifiedRelease.
AndroidX may uninstall/reinstall or clear that package while collecting profiles.
Use only a disposable/profile-generation device with no PocketGPT data to preserve.

Environment:
  POCKETGPT_BASELINE_PROFILE_NATIVE_BUILD=true|false
  POCKETGPT_BASELINE_PROFILE_ARTIFACT_ROOT=<artifact-directory>
EOF
}

require_file() {
  local path="$1"
  if [[ ! -s "${path}" ]]; then
    echo "Required non-empty file is missing: ${path}" >&2
    exit 1
  fi
}

resolve_single_apk() {
  local output_dir="$1"
  local -a apks=()
  while IFS= read -r apk; do
    apks+=("${apk}")
  done < <(find "${output_dir}" -maxdepth 1 -type f -name '*.apk' | sort)
  if [[ "${#apks[@]}" -ne 1 ]]; then
    echo "Expected exactly one APK under ${output_dir}; found ${#apks[@]}." >&2
    exit 1
  fi
  printf '%s\n' "${apks[0]}"
}

capture_device_state() {
  local label="$1"
  local output="${ARTIFACT_ROOT}/device-${label}.txt"
  {
    echo "serial=${ANDROID_SERIAL}"
    echo "timestamp_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    adb -s "${ANDROID_SERIAL}" get-state
    adb -s "${ANDROID_SERIAL}" shell getprop ro.build.version.sdk
    adb -s "${ANDROID_SERIAL}" shell dumpsys package com.pocketagent.android \
      | grep -E 'versionCode=|versionName=|flags=|pkgFlags=' \
      | head -n 20 || true
    adb -s "${ANDROID_SERIAL}" shell dumpsys activity \
      | grep -E -i 'instrumentation|topResumedActivity|mCurrentFocus' \
      | head -n 40 || true
    adb -s "${ANDROID_SERIAL}" shell ps -A \
      | grep -E 'pocketagent|androidx\.test|uiautomator' || true
  } > "${output}" 2>&1
  echo "Captured device state: ${output}"
}

verify_variant() {
  local variant="$1"
  local apk_dir="$2"
  local profile_path="$3"
  local apk
  apk="$(resolve_single_apk "${apk_dir}")"
  python3 scripts/benchmarks/verify_android_baseline_profile.py \
    --apk "${apk}" \
    --generated-profile "${GENERATED_PROFILE}" \
    --merged-profile "${profile_path}" \
    --json-output "${ARTIFACT_ROOT}/${variant}-verification.json"
}

generate_profile() {
  if [[ -z "${ANDROID_SERIAL:-}" ]]; then
    echo "ANDROID_SERIAL must pin one API 33+ device for Baseline Profile generation." >&2
    exit 1
  fi
  if ! command -v adb >/dev/null 2>&1; then
    echo "adb is required for Baseline Profile generation." >&2
    exit 1
  fi
  echo "Baseline Profile generation requires a disposable device; the base app may be reinstalled." >&2
  mkdir -p "${ARTIFACT_ROOT}"
  capture_device_state before

  ANDROID_SERIAL="${ANDROID_SERIAL}" ./gradlew --no-daemon \
    "-Ppocketgpt.enableNativeBuild=${NATIVE_BUILD}" \
    "-Pandroid.testInstrumentationRunnerArguments.class=${GENERATOR_CLASS}" \
    :apps:mobile-android:generateBaselineProfile \
    2>&1 | tee "${ARTIFACT_ROOT}/generation.log"

  require_file "${GENERATED_PROFILE}"
  capture_device_state after
}

verify_packaging() {
  mkdir -p "${ARTIFACT_ROOT}"
  require_file "${GENERATED_PROFILE}"

  ./gradlew --no-daemon \
    "-Ppocketgpt.enableNativeBuild=${NATIVE_BUILD}" \
    :apps:mobile-android:assembleBenchmark \
    :apps:mobile-android:assembleRelease \
    2>&1 | tee "${ARTIFACT_ROOT}/packaging-build.log"

  verify_variant \
    benchmark \
    apps/mobile-android/build/outputs/apk/benchmark \
    apps/mobile-android/build/intermediates/combined_art_profile/benchmark/compileBenchmarkArtProfile/baseline-prof.txt
  verify_variant \
    release \
    apps/mobile-android/build/outputs/apk/release \
    apps/mobile-android/build/intermediates/combined_art_profile/release/compileReleaseArtProfile/baseline-prof.txt

  echo "Baseline Profile evidence: ${ARTIFACT_ROOT}"
}

case "${MODE}" in
  generate)
    if [[ "$#" -ne 1 ]]; then
      usage
      exit 1
    fi
    generate_profile
    verify_packaging
    ;;
  verify)
    if [[ "$#" -ne 1 ]]; then
      usage
      exit 1
    fi
    verify_packaging
    ;;
  -h|--help|help)
    usage
    ;;
  *)
    usage
    exit 1
    ;;
esac
