# Phone Setup Guide

Instructions for building and running PocketAgent on a physical Android phone.

## Prerequisites

### On Your Computer

1. **Android SDK & Gradle** (via Android Studio or command line)
   - Verify with: `./gradlew --version`
   - See `scripts/dev/README.md#first-10-minutes-new-joiner` for full setup

2. **ADB (Android Debug Bridge)**
   - Verify with: `adb version`
   - Usually installed with Android SDK; can also install standalone

### On Your Phone

1. **Enable Developer Mode**
   - Go to **Settings > About phone**
   - Tap **Build number** 7 times
   - Go back to **Settings > System > Developer options** (or similar, varies by phone)
   - Enable **USB Debugging**

2. **Connect via USB**
   - Use a USB cable (preferably the one that came with the phone)
   - When prompted on the phone, tap **Allow** to grant USB debugging permissions
   - Verify connection: `adb devices` should list your phone

## Build Steps

### 1) Verify Doctor & Environment

```bash
python3 tools/devctl/main.py doctor
```

Fix any issues before proceeding.

### 2) Build Debug APK

```bash
./gradlew --no-daemon -Ppocketgpt.enableNativeBuild=true :apps:mobile-android:assembleDebug
```

The debug APK is written to the module's Gradle output directory; use the `find` command below instead of hard-coding a build path.

### 3) Install on Phone

```bash
APK_PATH="$(find apps/mobile-android/build/outputs/apk/debug -type f -name '*.apk' | sort | head -n 1)"
adb install -r "${APK_PATH}"
```

The `-r` flag reinstalls if the app is already present.

## Running the App

### Quick Iteration Loop (Recommended for Development)

Use the dev loop script to automatically build, install, and launch with live logcat streaming:

```bash
bash scripts/android/dev_loop.sh
```

Options:

```bash
bash scripts/android/dev_loop.sh --serial your-device-id          # Target specific device
bash scripts/android/dev_loop.sh --no-install                  # Skip rebuild (just relaunch + logs)
bash scripts/android/dev_loop.sh --full-logcat                 # Stream all logcat (unfiltered)
bash scripts/android/dev_loop.sh --filter "ERROR|CRASH"        # Custom log filter
```

This script handles:
1. Building the debug APK
2. Installing on device (or reinstalling)
3. Launching the app
4. Streaming filtered logcat output in real-time

Press **Ctrl+C** to stop the logcat stream.

### First Time on Device

1. Unlock your phone
2. Open **PocketAgent** from your app drawer
3. Complete the onboarding flow:
   - Grant permissions (storage, microphone if prompted)
   - Download language model (2-5 minutes on typical home WiFi)
   - Start chatting

### Subsequent Runs

Simply tap the app icon, or use `bash scripts/android/dev_loop.sh --no-install` for fast iteration without rebuilding.

## Troubleshooting

### "adb devices" shows nothing or "unauthorized"

1. Disconnect USB cable
2. On phone: go to **Developer options** and toggle **USB Debugging off/on**
3. Reconnect USB
4. Tap **Allow** on phone permission prompt
5. Verify with `adb devices`

### Installation fails with "cmd: Can't find service: package"

This is a transient Android issue:

```bash
adb kill-server
adb start-server
adb install -r "${APK_PATH}"
```

### App crashes on launch

1. Check logcat output:
   ```bash
   adb logcat | grep -i "pocketagent\|crash\|error"
   ```

2. If crashes mention native bridge or GPU:
   - Your device may not support the GPU offload optimization
   - Check `docs/testing/runbooks.md` for fallback modes

3. For detailed runtime diagnostics:
   - `bash scripts/dev/scoped-repro.sh --no-build --no-install`

### "Not enough storage" during model download

1. Check available space: `adb shell df /sdcard`
2. Free up space on phone or use a smaller model variant
3. Models are cached at `/sdcard/Android/media/com.pocketagent.android/`

### USB debugging permission keeps getting revoked

1. Go to **Developer options > Select USB Configuration** and choose **File Transfer (MTP)**
2. Try a different USB port or cable
3. Some USB hubs or low-powered ports can cause issues

## Running Tests on Device

### Quick Smoke Test

```bash
bash scripts/dev/device-test.sh 1 smoke-basic
```

### Full E2E Flow

```bash
bash scripts/dev/journey.sh
```

See `scripts/dev/README.md#physical-device-lane` for full options.

## Next Steps

- **Fast iteration**: Use `bash scripts/android/dev_loop.sh` for quick build-install-launch-log cycles
- **For testing changes**: Use `bash scripts/dev/device-test.sh` to run automated flows
- **For performance profiling**: See `scripts/benchmarks/README.md`
- **For production builds**: Contact the release team; debug builds are for development only

## Reference

- Dev command reference: `scripts/dev/README.md`
- Testing strategy: `docs/testing/test-strategy.md`
- Android troubleshooting: `docs/testing/runbooks.md`
