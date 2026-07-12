# Controlled-MVP Listing Assets

Last updated: 2026-07-11
Owners: Marketing + Product
Lifecycle: Fresh capture required

The frozen seven-shot story, captions, claims, and QA rules live in `docs/ux/play-store-listing-spec.md`. The machine-readable status is `controlled-mvp-asset-manifest.json` in this directory.

## Current Asset Decision

The 15 retained QA screenshots under `tests/ui-screenshots/reference/sm-a515f-android13/` are rejected for listing use. They show the retired `Pocket GPT` name, synthetic fixture prompts and responses, overlapping unlock/tool surfaces, incomplete image states, and an older model-management design. Keep them as QA references only.

No fresh Android device is connected as of July 11, 2026, so no final release-candidate screenshots or video are present here yet. `MKT-08` remains open.

Deterministic draft brand assets now exist as `play-listing-icon.png` and `feature-graphic.png`, with dimensions and SHA-256 provenance in `store-brand-assets-provenance.json`. Rendering uses the repository-pinned Open Sans variable font and its OFL license under `scripts/marketing/assets/fonts/`, so host font selection cannot change the output. The drafts passed local visual/file QA but still require explicit Product and Marketing approval before upload.

## Capture Command

After the reviewed release candidate is installed on one explicitly pinned device:

```bash
bash scripts/marketing/capture_mobile_demo_assets.sh \
  --output docs/operations/assets/mkt-04/2026-07-11-pocketagent-0.1.0 \
  --serial ANDROID_SERIAL \
  --release-provenance dist/releases/controlled-mvp-0.1.0/signed-upload/release-provenance.json \
  --record-seconds 30
```

Use a new dated directory if capture happens later. The script requires signed-upload provenance, proves the installed APK checksum/version matches it, fails on an ambiguous device selection, requires PocketAgent to be foreground, captures all seven named states, and writes build/device metadata plus checksums.

## Approval Checklist

For each raw screenshot and derivative:

- [ ] Final app title says `PocketAgent`.
- [ ] Installed APK checksum, version name/code, and release Git SHA match the signed provenance packet.
- [ ] App state is real, complete, and reproducible; no fixture or synthetic `unlock step` copy remains.
- [ ] No keyboard, notification shade, account name, IP address, local path, private URL, raw diagnostics, blank image, loading placeholder, or overlapping modal is visible.
- [ ] Caption is one of the approved `CH-01` through `CH-07` blocks.
- [ ] Claim/gate mapping matches the manifest and `PROD-10`.
- [ ] Raw capture remains unchanged; edited derivative is traceable to it.
- [ ] Product approves claim truth.
- [ ] Marketing approves legibility and composition.
- [ ] Current Play Console format/dimension validation passes without distorting the app state.

The feature graphic and Play listing icon draft need the same Product + Marketing approval as screenshot derivatives. Regenerate them with `python3 scripts/marketing/render_store_brand_assets.py`; do not hand-edit an untracked variant.
