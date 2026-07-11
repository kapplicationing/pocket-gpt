#!/usr/bin/env bash
# Generates and validates benchmark-specific Compose compiler evidence for Android hot paths.
#
# Usage:
#   scripts/dev/compose-report-hotpath.sh [--build] [--strict]
#
# --build forces compileBenchmarkKotlin to regenerate reports. Without it, the
# script accepts the last bundle only when it is complete and newer than source/config inputs.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VARIANT="benchmark"
REPORT_DIR="$REPO_ROOT/apps/mobile-android/build/compose-reports/$VARIANT"
METRICS_DIR="$REPO_ROOT/apps/mobile-android/build/compose-metrics/$VARIANT"
BUILD_EPOCH_FILE="$REPORT_DIR/.pocketgpt-build-start-epoch"
VALIDATION_JSON="$REPORT_DIR/validation.json"
DO_BUILD=0
STRICT=0
EXPECTED_COMPOSABLES=(
  PocketAgentApp
  ChatComposerDock
  ChatScreenBody
  ComposerInputRow
  MessageBubble
  ModelLibrarySheetHost
  ModelSheet
  SessionDrawer
  CompletionSettingsSheet
)
EXPECTED_SKIPPABLE_COMPOSABLES=(
  GeneralTabContent
  PerformanceSettingsSection
  DownloadSettingsSection
  KeepAliveSettingsSection
  VoiceSettingsSection
  ReasoningSettingsSection
  CompletionSettingsSheet
  CompletionCommonSettingsSection
  CompletionThinkingSection
  CompletionAdvancedSettingsSection
)

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
      sed -n '1,9p' "$0"
      exit 0
      ;;
    *)
      echo "unknown arg $1" >&2
      exit 64
      ;;
  esac
done

if [[ "$DO_BUILD" -eq 1 ]]; then
  mkdir -p "$REPORT_DIR" "$METRICS_DIR"
  find "$REPORT_DIR" -mindepth 1 -delete
  find "$METRICS_DIR" -mindepth 1 -delete
  BUILD_STARTED_EPOCH="$(date +%s)"
  echo "[compose-report] regenerating $VARIANT compiler reports"
  (
    cd "$REPO_ROOT"
    ./gradlew --no-daemon --rerun-tasks \
      -Ppocketgpt.composeReportVariant="$VARIANT" \
      :apps:mobile-android:compileBenchmarkKotlin -q
  )
  printf '%s\n' "$BUILD_STARTED_EPOCH" >"$BUILD_EPOCH_FILE"
elif [[ -f "$BUILD_EPOCH_FILE" ]]; then
  BUILD_STARTED_EPOCH="$(tr -d '[:space:]' <"$BUILD_EPOCH_FILE")"
else
  echo "Fresh benchmark Compose reports are unavailable. Run with --build first." >&2
  exit 66
fi

if ! [[ "$BUILD_STARTED_EPOCH" =~ ^[0-9]+$ ]]; then
  echo "Invalid Compose report freshness marker: $BUILD_EPOCH_FILE" >&2
  exit 65
fi

validator_args=(
  python3 "$REPO_ROOT/scripts/dev/validate_compose_hotpath_reports.py"
  --report-dir "$REPORT_DIR"
  --metrics-dir "$METRICS_DIR"
  --variant "$VARIANT"
  --not-before-epoch "$BUILD_STARTED_EPOCH"
  --freshness-input "$REPO_ROOT/apps/mobile-android/src/main/kotlin"
  --freshness-input "$REPO_ROOT/apps/mobile-android/build.gradle.kts"
  --freshness-input "$REPO_ROOT/apps/mobile-android/compose-stability.conf"
  --freshness-input "$REPO_ROOT/build.gradle.kts"
  --freshness-input "$REPO_ROOT/settings.gradle.kts"
  --freshness-input "$REPO_ROOT/gradle.properties"
  --output "$VALIDATION_JSON"
)
for composable in "${EXPECTED_COMPOSABLES[@]}"; do
  validator_args+=(--expected-composable "$composable")
done
for composable in "${EXPECTED_SKIPPABLE_COMPOSABLES[@]}"; do
  validator_args+=(--expected-skippable-composable "$composable")
done
"${validator_args[@]}"

echo "Compose hot-path instability scan: $REPORT_DIR"
echo
matches="$(
  grep -RInE 'unstable|skippable=false|restartable scheme' "$REPORT_DIR" 2>/dev/null |
    grep -E 'ChatApp|ChatScreen|ChatComposer|MessageBubble|ModelSheet|ModelLibrary|SessionDrawer|CompletionSettings|CompletionCommon|CompletionThinking|CompletionAdvanced|GeneralTab|PerformanceSettings|DownloadSettings|KeepAliveSettings|VoiceSettings|ReasoningSettings|ChatUiState|ModelProvisioningUiState|RuntimeUiState|ComposerUiState' || true
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
