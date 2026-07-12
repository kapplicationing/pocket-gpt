# PocketAgent Support

PocketAgent's public support path is the [GitHub issue tracker](https://github.com/kapplicationing/pocket-gpt/issues). Email support is available at [mohamad.kamar.msk@gmail.com](mailto:mohamad.kamar.msk@gmail.com).

## Before opening an issue

1. Confirm the device runs Android 8.0 or newer on 64-bit ARM hardware.
2. Open `Model library` and confirm a supported model is installed, active, and passes runtime checks.
3. Retry the failed action once. If the runtime is not ready, use `Refresh runtime checks` or `Fix model library`.
4. If the issue remains, export diagnostics from `Advanced` and review the output before sharing it.

## What to include

- PocketAgent version name and version code;
- device model and Android version;
- whether the issue affects setup, model download/import, chat, image help, local tools, sessions, or limited-beta voice;
- exact steps to reproduce;
- the visible runtime state and error code; and
- a redacted diagnostics excerpt when it helps reproduce the issue.

Never put prompts, responses, personal images, microphone recordings, model files, credentials, or unreviewed diagnostics in a public issue.

## Private security and privacy reports

Do not open a public issue for a suspected vulnerability, data exposure, or privacy incident. Email [mohamad.kamar.msk@gmail.com](mailto:mohamad.kamar.msk@gmail.com) with the subject `PocketAgent private report`. Include the minimum detail needed to reproduce the problem and wait for a private follow-up before sending sensitive artifacts.

## Controlled-pilot response targets

These are operating targets, not contractual service guarantees:

- `S0` — suspected data loss, security issue, or privacy breach: acknowledge within 1 hour;
- `S1` — setup, chat, or another core workflow is blocked: acknowledge within 4 hours and provide a workaround or update within 24 hours; and
- `S2` — degraded but usable behavior: acknowledge within 1 business day.

The release owner pauses an affected claim or rollout while an open `S0` or `S1` incident lacks a safe mitigation.
