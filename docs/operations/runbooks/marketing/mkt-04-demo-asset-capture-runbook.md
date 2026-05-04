# MKT-04 Demo Asset Capture Runbook (Real Screenshots + Video)

Last updated: 2026-03-10
Owner: Marketing + QA support
Lifecycle: Ready to execute

## Purpose

Produce real app screenshots/video for launch assets using physical-device capture, while keeping copy claims evidence-safe.

## Preconditions

1. Connected Android device with adb authorization.
2. App build installed and runnable on the test device.
3. Claim-safe storyboard prepared from:
   - `docs/operations/tickets/mkt-10-claim-freeze-v1.md`
   - `docs/operations/tickets/prod-10-launch-gate-matrix.md`
   - `docs/ux/play-store-listing-spec.md`

## Capture Command

```bash
bash scripts/marketing/capture_mobile_demo_assets.sh \
  --output docs/operations/assets/mkt-04/2026-03-04 \
  --serial DEVICE_SERIAL_REDACTED \
  --record-seconds 30
```

If `--serial` is omitted, the script uses the first connected `adb` device.

## Required Asset Set

1. `screenshot-01.png`: useful on-device response state.
2. `screenshot-02.png`: second workflow state (for example image/tool/session continuity).
3. `demo.mp4`: 20-30 second interaction clip.

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
