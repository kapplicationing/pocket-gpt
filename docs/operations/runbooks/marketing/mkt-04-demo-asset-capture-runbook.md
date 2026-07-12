# MKT-04 Demo Asset Capture Runbook (Real Screenshots + Video)

Last updated: 2026-07-11
Owner: Marketing + QA support
Lifecycle: Ready to execute

## Purpose

Produce real app screenshots/video for launch assets using physical-device capture, while keeping copy claims evidence-safe.

## Preconditions

1. Connected Android device with adb authorization.
2. Signed release APK installed and runnable on the explicitly pinned test device.
3. `release-provenance.json` from the same signed release build is available; the capture script verifies the installed APK checksum against it.
4. Claim-safe storyboard prepared from:
   - `docs/operations/tickets/mkt-10-claim-freeze-v1.md`
   - `docs/operations/tickets/prod-10-launch-gate-matrix.md`
   - `docs/ux/play-store-listing-spec.md`

## Capture Command

```bash
bash scripts/marketing/capture_mobile_demo_assets.sh \
  --output docs/operations/assets/mkt-04/2026-07-11-pocketagent-0.1.0 \
  --serial ANDROID_SERIAL \
  --release-provenance dist/releases/controlled-mvp-0.1.0/signed-upload/release-provenance.json \
  --record-seconds 30
```

Do not capture from an unsigned or unprovenanced build. The script accepts an omitted serial only when exactly one authorized device is attached.

## Required Asset Set

1. `01-chat-ready.png`
2. `02-runtime-recovery.png`
3. `03-session-continuity.png`
4. `04-prompt-shortcuts.png`
5. `05-single-image-help.png`
6. `06-model-library.png`
7. `07-privacy-diagnostics.png`
8. `08-demo.mp4`: 20-30 second interaction clip unless `--skip-video` is deliberate and recorded

## Storyboard Guidance (Claim-Safe)

1. Show offline indicator + user prompt + assistant response state.
2. Show one practical workflow from locked launch set.
3. Avoid on-screen text that implies excluded/provisional claims.
4. Keep narration/subtitles constrained to validated claims only.

## Claim QA Checklist

1. No broad voice-now or cross-platform-now claims; any voice capture must stay clearly labeled as limited beta and out of the public Play Store asset set.
2. No universal device-performance guarantees.
3. No unsourced competitor comparisons.
4. Every caption line maps to a `PROD-10` row and allowed claim set in `MKT-10`.

## Output Handling

1. Store raw captures under dated folder in `docs/operations/assets/mkt-04/`.
2. Keep originals unedited for auditability.
3. Create edited derivatives only after raw-asset approval.
