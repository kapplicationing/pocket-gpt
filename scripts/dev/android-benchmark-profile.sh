#!/usr/bin/env bash
# Shared fail-closed installation and Baseline Profile activation for Android perf runs.

POCKETGPT_BENCHMARK_PACKAGE="com.pocketagent.android.benchmark"
POCKETGPT_MAIN_ACTIVITY_CLASS="com.pocketagent.android.MainActivity"
POCKETGPT_PROFILE_RECEIVER="androidx.profileinstaller.ProfileInstallReceiver"

pocketgpt_require_isolated_benchmark_package() {
  local package="$1"
  if [[ "$package" != "$POCKETGPT_BENCHMARK_PACKAGE" ]]; then
    echo "Refusing destructive benchmark install for non-isolated package: $package" >&2
    echo "Expected exactly: $POCKETGPT_BENCHMARK_PACKAGE" >&2
    return 64
  fi
}

pocketgpt_require_success_line() {
  local output_file="$1"
  local operation="$2"
  if ! grep -Eq '^Success\r?$' "$output_file"; then
    echo "$operation did not report Success:" >&2
    cat "$output_file" >&2
    return 1
  fi
}

pocketgpt_verify_benchmark_apk() {
  local repo_root="$1"
  local apk="$2"
  local output_dir="$3"
  local generated_profile="$repo_root/apps/mobile-android/src/main/generated/baselineProfiles/baseline-prof.txt"
  local merged_profile="$repo_root/apps/mobile-android/build/intermediates/combined_art_profile/benchmark/compileBenchmarkArtProfile/baseline-prof.txt"
  local application_metadata="$repo_root/apps/mobile-android/build/intermediates/packaged_manifests/benchmark/processBenchmarkManifestForPackage/output-metadata.json"
  local harness="$repo_root/scripts/dev/android_perf_harness.py"

  mkdir -p "$output_dir"
  python3 "$harness" assert-application-id \
    --metadata "$application_metadata" \
    --expected "$POCKETGPT_BENCHMARK_PACKAGE" \
    >"$output_dir/application-id-proof.json"
  unzip -Z1 "$apk" >"$output_dir/apk-entries.txt"
  if ! grep -Fxq 'lib/arm64-v8a/libpocket_llama.so' "$output_dir/apk-entries.txt"; then
    echo "Benchmark APK is missing lib/arm64-v8a/libpocket_llama.so: $apk" >&2
    return 1
  fi
  python3 "$repo_root/scripts/benchmarks/verify_android_baseline_profile.py" \
    --apk "$apk" \
    --generated-profile "$generated_profile" \
    --merged-profile "$merged_profile" \
    --json-output "$output_dir/baseline-profile-packaging.json"
}

pocketgpt_activate_benchmark_profile() {
  local serial="$1"
  local package="$2"
  local apk="$3"
  local output_dir="$4"
  local harness="$5"
  local receiver_component="$package/$POCKETGPT_PROFILE_RECEIVER"

  pocketgpt_require_isolated_benchmark_package "$package"
  mkdir -p "$output_dir"

  if ! adb -s "$serial" shell pm path "$package" \
    >"$output_dir/package-paths-before-install.txt" 2>&1; then
    cat "$output_dir/package-paths-before-install.txt" >&2
    return 1
  fi
  if grep -q '^package:' "$output_dir/package-paths-before-install.txt"; then
    if ! adb -s "$serial" uninstall "$package" >"$output_dir/uninstall-before-install.txt" 2>&1; then
      cat "$output_dir/uninstall-before-install.txt" >&2
      return 1
    fi
    pocketgpt_require_success_line "$output_dir/uninstall-before-install.txt" "Benchmark uninstall"
  else
    printf 'not-installed\n' >"$output_dir/uninstall-before-install.txt"
  fi

  if ! adb -s "$serial" install "$apk" >"$output_dir/install.txt" 2>&1; then
    cat "$output_dir/install.txt" >&2
    return 1
  fi
  pocketgpt_require_success_line "$output_dir/install.txt" "Benchmark install"

  if ! adb -s "$serial" shell am broadcast \
    -a androidx.profileinstaller.action.SKIP_FILE \
    -e EXTRA_SKIP_FILE_OPERATION WRITE_SKIP_FILE \
    -n "$receiver_component" \
    >"$output_dir/profile-skip-broadcast.txt" 2>&1; then
    cat "$output_dir/profile-skip-broadcast.txt" >&2
    return 1
  fi
  python3 "$harness" assert-broadcast-result \
    --output "$output_dir/profile-skip-broadcast.txt" \
    --expected 10 \
    >"$output_dir/profile-skip-proof.json"
  adb -s "$serial" shell am force-stop "$package" >/dev/null

  if ! adb -s "$serial" shell am broadcast \
    -a androidx.profileinstaller.action.INSTALL_PROFILE \
    -n "$receiver_component" \
    >"$output_dir/profile-install-broadcast.txt" 2>&1; then
    cat "$output_dir/profile-install-broadcast.txt" >&2
    return 1
  fi
  python3 "$harness" assert-broadcast-result \
    --output "$output_dir/profile-install-broadcast.txt" \
    --expected 1 \
    >"$output_dir/profile-install-proof.json"
  adb -s "$serial" shell am force-stop "$package" >/dev/null

  if ! adb -s "$serial" shell cmd package compile \
    -f -m speed-profile "$package" \
    >"$output_dir/profile-compile.txt" 2>&1; then
    cat "$output_dir/profile-compile.txt" >&2
    return 1
  fi
  python3 "$harness" assert-compile-result \
    --output "$output_dir/profile-compile.txt" \
    >"$output_dir/profile-compile-proof.json"

  adb -s "$serial" shell dumpsys package "$package" \
    >"$output_dir/package-dump-profiled.txt" 2>&1
  python3 "$harness" assert-profile-compiled \
    --package-dump "$output_dir/package-dump-profiled.txt" \
    --package "$package" \
    --expected-status speed-profile \
    --expected-reason cmdline \
    >"$output_dir/profile-compilation-proof.json"
}

pocketgpt_uninstall_isolated_benchmark() {
  local serial="$1"
  local package="$2"
  local output_file="$3"
  local paths_file="${output_file%.txt}-paths.txt"

  pocketgpt_require_isolated_benchmark_package "$package"
  if ! adb -s "$serial" shell pm path "$package" >"$paths_file" 2>&1; then
    cat "$paths_file" >&2
    return 1
  fi
  if ! grep -q '^package:' "$paths_file"; then
    printf 'not-installed\n' >"$output_file"
    return 0
  fi
  if ! adb -s "$serial" uninstall "$package" >"$output_file" 2>&1; then
    cat "$output_file" >&2
    return 1
  fi
  pocketgpt_require_success_line "$output_file" "Benchmark cleanup uninstall"
}
