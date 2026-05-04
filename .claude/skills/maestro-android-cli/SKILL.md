---
name: maestro-android-cli
description: Use when running, debugging, or triaging Android Maestro work across emulator, connected devices, and Maestro Cloud. Prefer over raw adb or long ad-hoc Gradle commands when the standalone maestro-android CLI is available.
---

# Maestro Android CLI

Use this skill when the issue is Android test execution, device selection, local Maestro bootstrap, selector drift, or hosted reruns.

## Default matrix

Start from:

1. emulator for fast harness/bootstrap proof
2. one connected device for real transport and OEM proof
3. cloud for hosted confirmation

## Fast path

```bash
maestro-android doctor
maestro-android devices --json
maestro-android lane smoke --device <serial>
maestro-android scoped --flow tmp/repro.yaml
maestro-android device probe --device <serial>
maestro-android cloud probe --flow <path>
```

## Rules

1. If more than one transport is attached, do not guess. Run `devices --json` and pin `--device <serial>`.
2. If the same path fails twice, stop and pivot.
3. If local Maestro bootstrap is in doubt, run `device probe --device <serial>`.
4. Use `cloud probe` for one hosted question before `cloud smoke`.
