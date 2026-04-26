# Maestro Android Companion CLI

Android-focused companion CLI for Maestro workflows in this repository. This is the canonical repo-local reference.

Run it with:

```bash
python3 tools/maestro_android/main.py doctor
python3 tools/maestro_android/main.py lane smoke
python3 tools/maestro_android/main.py scoped --flow tmp/maestro-repro.yaml
python3 tools/maestro_android/main.py report latest
```

Primary commands:

- `doctor`: delegates to the repo's current Android/Maestro doctor flow
- `devices`: lists connected adb devices
- `start-device`: launches an Android emulator AVD
- `test`: runs one or more Maestro flows with build/install bootstrap and structured artifacts under `.maestro-android/runs/`
- `lane`: runs configured repo lanes such as `smoke`, `journey`, `screenshot-pack`, and `lifecycle`
- `scoped`: wraps the repo's scoped repro workflow and enforces `tmp/` + title/description flow conventions
- `report`: finds the latest artifact bundle for `journey`, `screenshot-pack`, `smoke`, `raw`, `lifecycle`, or `latest`
- `trace`: prints the trace-capable bundle root and `trace.json` when present
- `merge-reports`: merges run manifests and JUnit outputs
- `clean`: removes companion CLI scratch artifacts
- `cloud`: passes through to `maestro cloud`

Configuration lives in `.maestro-android.yaml`.
