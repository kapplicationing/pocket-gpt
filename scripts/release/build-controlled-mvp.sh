#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MANIFEST_PATH="${REPO_ROOT}/config/release/controlled-mvp.json"
DEVICE=""
UNSIGNED="false"

usage() {
  cat <<'USAGE'
Usage:
  bash scripts/release/build-controlled-mvp.sh [--unsigned] [--device <adb-serial>]

Modes:
  default      Build a signed upload AAB and signed release APK. Requires the four
               POCKETAGENT_RELEASE_* signing variables. With --device, install and
               start/version-check the APK on that exact canary.
  --unsigned   Build explicitly non-publishable local inspection artifacts. Signing
               variables are ignored and --device is not allowed.

Signing variables:
  POCKETAGENT_RELEASE_KEYSTORE
  POCKETAGENT_RELEASE_STORE_PASSWORD
  POCKETAGENT_RELEASE_KEY_ALIAS
  POCKETAGENT_RELEASE_KEY_PASSWORD

Release version, track, support links, and claims come from:
  config/release/controlled-mvp.json
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      [[ $# -ge 2 ]] || { echo "--device requires an adb serial" >&2; exit 2; }
      DEVICE="$2"
      shift 2
      ;;
    --unsigned)
      UNSIGNED="true"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

command -v python3 >/dev/null 2>&1 || { echo "python3 is required" >&2; exit 1; }
command -v git >/dev/null 2>&1 || { echo "git is required" >&2; exit 1; }
[[ -f "$MANIFEST_PATH" ]] || { echo "Missing release config: $MANIFEST_PATH" >&2; exit 1; }

manifest_value() {
  python3 - "$MANIFEST_PATH" "$1" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
value = payload[sys.argv[2]]
if isinstance(value, (dict, list)):
    print(json.dumps(value, separators=(",", ":")))
else:
    print(value)
PY
}

VERSION_CODE="$(manifest_value version_code)"
VERSION_NAME="$(manifest_value version_name)"
PLAY_TRACK="$(manifest_value play_track)"
APPLICATION_ID="$(manifest_value application_id)"
RELEASE_ID="$(manifest_value release_id)"

[[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || { echo "Invalid version_code in release config" >&2; exit 1; }
(( VERSION_CODE <= 2100000000 )) || { echo "version_code exceeds Google Play's Android limit" >&2; exit 1; }
[[ -n "$VERSION_NAME" ]] || { echo "version_name must not be blank" >&2; exit 1; }
[[ "$PLAY_TRACK" == "internal" ]] || {
  echo "Controlled-MVP build must start on the internal Play track; found: $PLAY_TRACK" >&2
  exit 1
}

cd "$REPO_ROOT"

if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
  echo "Release build refused: the worktree is not clean." >&2
  echo "Commit or isolate all intended changes, then build from the reviewed clean tree." >&2
  exit 1
fi

HEAD_SHA="$(git rev-parse HEAD)"
ORIGIN_MAIN_SHA="$(git rev-parse origin/main 2>/dev/null || true)"
if [[ -z "$ORIGIN_MAIN_SHA" || "$HEAD_SHA" != "$ORIGIN_MAIN_SHA" ]]; then
  echo "Release build refused: HEAD must match the local origin/main ref." >&2
  echo "Fetch/review origin/main before building; this script does not mutate Git state." >&2
  exit 1
fi

bash scripts/dev/launch-readiness.sh
python3 - build/devctl/launch-readiness/launch-readiness-report.json <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    report = json.load(handle)
if report.get("gate_readiness") != "promoted":
    raise SystemExit("Release build refused: controlled-MVP gate is not promoted.")
matrix = report.get("matrix", {})
if matrix.get("required_pass") != matrix.get("required_total"):
    raise SystemExit("Release build refused: not all required PROD-10 rows pass.")
PY

if [[ "$UNSIGNED" == "true" ]]; then
  [[ -z "$DEVICE" ]] || { echo "--device is not allowed for an unsigned artifact" >&2; exit 2; }
  unset POCKETAGENT_RELEASE_KEYSTORE
  unset POCKETAGENT_RELEASE_STORE_PASSWORD
  unset POCKETAGENT_RELEASE_KEY_ALIAS
  unset POCKETAGENT_RELEASE_KEY_PASSWORD
  ARTIFACT_KIND="unsigned-local-inspection"
else
  missing=()
  for name in \
    POCKETAGENT_RELEASE_KEYSTORE \
    POCKETAGENT_RELEASE_STORE_PASSWORD \
    POCKETAGENT_RELEASE_KEY_ALIAS \
    POCKETAGENT_RELEASE_KEY_PASSWORD; do
    [[ -n "${!name:-}" ]] || missing+=("$name")
  done
  if (( ${#missing[@]} > 0 )); then
    echo "Signed upload build requires: ${missing[*]}" >&2
    echo "Do not substitute the debug keystore and do not commit signing values." >&2
    exit 1
  fi
  [[ -f "$POCKETAGENT_RELEASE_KEYSTORE" ]] || { echo "Configured release keystore file does not exist" >&2; exit 1; }
  ARTIFACT_KIND="signed-upload"
fi

export POCKETAGENT_RELEASE_VERSION_CODE="$VERSION_CODE"
export POCKETAGENT_RELEASE_VERSION_NAME="$VERSION_NAME"

rm -rf \
  apps/mobile-android/build/outputs/bundle/release \
  apps/mobile-android/build/outputs/apk/release

./gradlew --no-daemon \
  :apps:mobile-android:bundleRelease \
  :apps:mobile-android:assembleRelease

AAB_SOURCE="$(find apps/mobile-android/build/outputs/bundle/release -maxdepth 1 -type f -name '*.aab' -print | head -n 1)"
APK_SOURCE="$(find apps/mobile-android/build/outputs/apk/release -maxdepth 1 -type f -name '*.apk' -print | head -n 1)"
[[ -n "$AAB_SOURCE" && -f "$AAB_SOURCE" ]] || { echo "Release AAB was not produced" >&2; exit 1; }
[[ -n "$APK_SOURCE" && -f "$APK_SOURCE" ]] || { echo "Release APK was not produced" >&2; exit 1; }

OUTPUT_DIR="dist/releases/${RELEASE_ID}/${ARTIFACT_KIND}"
mkdir -p "$OUTPUT_DIR"
AAB_OUTPUT="${OUTPUT_DIR}/pocketagent-${VERSION_NAME}-${VERSION_CODE}-${ARTIFACT_KIND}.aab"
APK_OUTPUT="${OUTPUT_DIR}/pocketagent-${VERSION_NAME}-${VERSION_CODE}-${ARTIFACT_KIND}.apk"
cp "$AAB_SOURCE" "$AAB_OUTPUT"
cp "$APK_SOURCE" "$APK_OUTPUT"

if [[ "$UNSIGNED" == "false" ]]; then
  command -v jarsigner >/dev/null 2>&1 || { echo "jarsigner is required to verify the signed AAB" >&2; exit 1; }
  command -v unzip >/dev/null 2>&1 || { echo "unzip is required to inspect the signed AAB" >&2; exit 1; }
  if ! unzip -Z1 "$AAB_OUTPUT" | grep -E '^META-INF/[^/]+\.(RSA|DSA|EC)$' >/dev/null; then
    echo "Signed upload AAB has no JAR signature block; refusing an unsigned archive." >&2
    exit 1
  fi
  JARSIGNER_OUTPUT="$(LC_ALL=C jarsigner -verify "$AAB_OUTPUT" 2>&1)" || {
    echo "$JARSIGNER_OUTPUT" >&2
    echo "Signed upload AAB failed jarsigner verification." >&2
    exit 1
  }
  if ! grep -qx 'jar verified\.' <<<"$JARSIGNER_OUTPUT"; then
    echo "$JARSIGNER_OUTPUT" >&2
    echo "Signed upload AAB did not produce an explicit jarsigner verified result." >&2
    exit 1
  fi

  SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  if [[ -z "$SDK_ROOT" && -f local.properties ]]; then
    SDK_ROOT="$(sed -n 's/^sdk\.dir=//p' local.properties | tail -n 1 | sed 's/\\:/:/g; s/\\\\/\\/g')"
  fi
  APKSIGNER=""
  if [[ -n "$SDK_ROOT" && -d "$SDK_ROOT/build-tools" ]]; then
    APKSIGNER="$(python3 - "$SDK_ROOT/build-tools" <<'PY'
import re
import sys
from pathlib import Path

candidates = list(Path(sys.argv[1]).glob("*/apksigner"))
if candidates:
    def version_key(path: Path) -> tuple[int, ...]:
        return tuple(int(part) for part in re.findall(r"\d+", path.parent.name))
    print(max(candidates, key=version_key))
PY
)"
  fi
  [[ -n "$APKSIGNER" ]] || { echo "Android SDK apksigner was not found" >&2; exit 1; }
  "$APKSIGNER" verify "$APK_OUTPUT"
fi

INSTALL_STATUS="not-run"
if [[ -n "$DEVICE" ]]; then
  command -v maestro-android >/dev/null 2>&1 || { echo "maestro-android is required for device preflight" >&2; exit 1; }
  command -v adb >/dev/null 2>&1 || { echo "adb is required for explicit release APK installation" >&2; exit 1; }
  ANDROID_SERIAL="$DEVICE" maestro-android device info
  adb -s "$DEVICE" install -r "$APK_OUTPUT"
  START_OUTPUT="$(adb -s "$DEVICE" shell am start -W -n "${APPLICATION_ID}/.MainActivity")"
  grep -q '^Status: ok' <<<"$START_OUTPUT" || {
    echo "$START_OUTPUT" >&2
    echo "Release APK did not reach a successful MainActivity start." >&2
    exit 1
  }
  PACKAGE_DUMP="$(adb -s "$DEVICE" shell dumpsys package "$APPLICATION_ID")"
  grep -q "versionCode=${VERSION_CODE}" <<<"$PACKAGE_DUMP" || {
    echo "Installed package version code does not match $VERSION_CODE" >&2
    exit 1
  }
  grep -q "versionName=${VERSION_NAME}" <<<"$PACKAGE_DUMP" || {
    echo "Installed package version name does not match $VERSION_NAME" >&2
    exit 1
  }
  INSTALL_STATUS="install_start_and_version_passed:${DEVICE}"
fi

AAB_SHA256="$(shasum -a 256 "$AAB_OUTPUT" | awk '{print $1}')"
APK_SHA256="$(shasum -a 256 "$APK_OUTPUT" | awk '{print $1}')"
export RELEASE_METADATA_OUTPUT="${OUTPUT_DIR}/release-provenance.json"
export RELEASE_METADATA_ID="$RELEASE_ID"
export RELEASE_METADATA_KIND="$ARTIFACT_KIND"
export RELEASE_METADATA_TRACK="$PLAY_TRACK"
export RELEASE_METADATA_APPLICATION_ID="$APPLICATION_ID"
export RELEASE_METADATA_VERSION_CODE="$VERSION_CODE"
export RELEASE_METADATA_VERSION_NAME="$VERSION_NAME"
export RELEASE_METADATA_HEAD_SHA="$HEAD_SHA"
export RELEASE_METADATA_AAB="$AAB_OUTPUT"
export RELEASE_METADATA_AAB_SHA256="$AAB_SHA256"
export RELEASE_METADATA_APK="$APK_OUTPUT"
export RELEASE_METADATA_APK_SHA256="$APK_SHA256"
export RELEASE_METADATA_INSTALL_STATUS="$INSTALL_STATUS"
python3 <<'PY'
import datetime
import json
import os

payload = {
    "schema": "pocketagent-release-provenance-v1",
    "generated_at_utc": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "release_id": os.environ["RELEASE_METADATA_ID"],
    "artifact_kind": os.environ["RELEASE_METADATA_KIND"],
    "play_track": os.environ["RELEASE_METADATA_TRACK"],
    "application_id": os.environ["RELEASE_METADATA_APPLICATION_ID"],
    "version_code": int(os.environ["RELEASE_METADATA_VERSION_CODE"]),
    "version_name": os.environ["RELEASE_METADATA_VERSION_NAME"],
    "git_sha": os.environ["RELEASE_METADATA_HEAD_SHA"],
    "aab": {
        "path": os.environ["RELEASE_METADATA_AAB"],
        "sha256": os.environ["RELEASE_METADATA_AAB_SHA256"],
    },
    "apk": {
        "path": os.environ["RELEASE_METADATA_APK"],
        "sha256": os.environ["RELEASE_METADATA_APK_SHA256"],
    },
    "install_validation": os.environ["RELEASE_METADATA_INSTALL_STATUS"],
}
with open(os.environ["RELEASE_METADATA_OUTPUT"], "w", encoding="utf-8") as handle:
    json.dump(payload, handle, indent=2)
    handle.write("\n")
PY

echo "Release artifacts prepared under: $OUTPUT_DIR"
echo "Artifact kind: $ARTIFACT_KIND"
echo "AAB SHA-256: $AAB_SHA256"
echo "APK SHA-256: $APK_SHA256"
echo "Install/start/version validation: $INSTALL_STATUS"
if [[ "$UNSIGNED" == "true" ]]; then
  echo "NON-PUBLISHABLE: unsigned local inspection artifacts must never be uploaded to Play."
elif [[ -z "$DEVICE" ]]; then
  echo "INCOMPLETE: signed artifacts exist, but physical-canary install validation was not run."
fi
