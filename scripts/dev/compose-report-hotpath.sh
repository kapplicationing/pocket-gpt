#!/usr/bin/env bash
# Summarizes unstable Compose compiler report entries for Android hot paths.
#
# Usage:
#   scripts/dev/compose-report-hotpath.sh [--build] [--strict]
#
# Use --build to regenerate reports first. The script intentionally does not
# commit generated reports; it is a reviewer aid for hot-path UI/runtime PRs.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REPORT_DIR="$REPO_ROOT/apps/mobile-android/build/compose-reports"
DO_BUILD=0
STRICT=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --build)
      DO_BUILD=1
      shift
      ;;
    --strict)
      STRICT=1
      shift
      ;;
    -h|--help)
      sed -n '1,12p' "$0"
      exit 0
      ;;
    *)
      echo "unknown arg $1" >&2
      exit 64
      ;;
  esac
done

if [[ "$DO_BUILD" -eq 1 ]]; then
  (cd "$REPO_ROOT" && ./gradlew :apps:mobile-android:assembleDebug -q)
fi

if [[ ! -d "$REPORT_DIR" ]]; then
  echo "Compose reports not found at $REPORT_DIR. Run with --build first." >&2
  exit 66
fi

echo "Compose hot-path instability scan: $REPORT_DIR"
echo

matches="$(
  grep -RInE 'unstable|skippable=false|restartable scheme' "$REPORT_DIR" 2>/dev/null |
    grep -E 'ChatApp|ChatScreen|ChatComposer|MessageBubble|ModelSheet|ModelLibrary|SessionDrawer|ChatUiState|ModelProvisioningUiState|RuntimeUiState|ComposerUiState' || true
)"

if [[ -z "$matches" ]]; then
  echo "No hot-path unstable/skippable=false entries found."
  exit 0
fi

echo "$matches"
echo
echo "Review every entry above before merging hot-path UI/runtime changes." >&2
if [[ "$STRICT" -eq 1 ]]; then
  exit 1
fi
exit 0
