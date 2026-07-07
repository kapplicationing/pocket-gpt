#!/usr/bin/env bash
set -euo pipefail

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "${ANDROID_SDK_ROOT}" ]]; then
  echo "ANDROID_SDK_ROOT or ANDROID_HOME must be set." >&2
  exit 2
fi

SDKMANAGER_BIN="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "${SDKMANAGER_BIN}" ]]; then
  SDKMANAGER_BIN="$(command -v sdkmanager || true)"
fi
if [[ -z "${SDKMANAGER_BIN}" || ! -x "${SDKMANAGER_BIN}" ]]; then
  echo "sdkmanager not found under ${ANDROID_SDK_ROOT} or PATH." >&2
  exit 2
fi

COMPONENTS=("$@")
if [[ ${#COMPONENTS[@]} -eq 0 ]]; then
  COMPONENTS=("cmake;3.22.1")
fi

install_component() {
  local component="$1"
  local attempt
  for attempt in 1 2 3; do
    echo "Installing Android SDK component '${component}' (attempt ${attempt}/3)."
    set +o pipefail
    yes | "${SDKMANAGER_BIN}" --sdk_root="${ANDROID_SDK_ROOT}" --install "${component}"
    local status="${PIPESTATUS[1]}"
    set -o pipefail
    if [[ "${status}" -eq 0 ]]; then
      echo "Installed Android SDK component '${component}'."
      return 0
    fi

    echo "::warning::sdkmanager failed for '${component}' on attempt ${attempt} (exit ${status})."
    rm -rf "${ANDROID_SDK_ROOT}/.temp" "${ANDROID_SDK_ROOT}/temp" || true
    sleep "$((attempt * 5))"
  done

  echo "Failed to install Android SDK component '${component}' after 3 attempts." >&2
  return 1
}

for component in "${COMPONENTS[@]}"; do
  install_component "${component}"
done

if [[ " ${COMPONENTS[*]} " == *" cmake;3.22.1 "* ]]; then
  CMAKE_BIN="${ANDROID_SDK_ROOT}/cmake/3.22.1/bin/cmake"
  if [[ ! -x "${CMAKE_BIN}" ]]; then
    echo "Expected CMake binary not found at ${CMAKE_BIN}." >&2
    exit 1
  fi
  "${CMAKE_BIN}" --version | head -n 1
fi
