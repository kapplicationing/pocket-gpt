# Play Store Listing Spec (APP-STORE-01)

Last updated: 2026-07-12
Owner: Product + Marketing
Lifecycle: Copy frozen; fresh asset capture and approval pending

## Listing Identity

- App name: **PocketAgent**
- Category: **Productivity**
- Default language: **English (United States)**
- Initial release track: **Internal testing**

## Short Description

75 characters:

> Offline, local-first AI chat with prompt shortcuts, image help, and memory.

## Full Description

> PocketAgent is a local-first AI assistant for Android. Run supported AI models directly on your phone and keep chatting when you are offline after model setup.
>
> Ask questions and watch answers stream in. Continue conversations across local sessions, use prompt shortcuts for practical local workflows, or attach one image for contextual help in the same chat.
>
> You stay informed about the local runtime. PocketAgent shows whether the model is not ready, loading, ready, or in an error state, and offers clear recovery actions. You can import a compatible GGUF model or download a supported model when you choose. Model discovery and downloads require a network connection.
>
> PocketAgent keeps prompts, responses, selected images, and local memory on your device by default. Inference runs on-device, Android cloud backup is disabled, Android 12+ transfer exclusions cover supported app-data domains, and diagnostics export is user initiated and redacts known sensitive keys. Manufacturer device-to-device transfer behavior can vary. PocketAgent has no ads, analytics SDK, cloud chat sync, or required cloud inference account.
>
> Performance and model availability depend on your device and chosen model. This controlled MVP supports one-image contextual help, not broad document or multi-image analysis.

Production opt-in voice is implemented in the app, but the frozen listing above
does not make wake, battery, background-reliability, or supported-device claims
until the retained multi-OEM and 24-hour qualification gate is met.

## Frozen External Copy Blocks

| Copy ID | Approved Line | Claim / Gate Mapping |
|---|---|---|
| `CH-01` | Chat on-device, even offline after model setup. | `C-01`, `S-A` |
| `CH-02` | Start useful local workflows from prompt shortcuts. | `C-02`, `S-B` |
| `CH-03` | Ask about one image in the same conversation. | `C-03`, `S-C` |
| `CH-04` | See when your local model is ready and recover when it is not. | `C-04`, `S-D`; `C-09`, `S-G` |
| `CH-05` | Keep conversations and memory on your device by default. | `C-05`, `S-E`; `P-01`, `P-02` |
| `CH-06` | Continue across local chat sessions. | `C-03`, `S-C` |
| `CH-07` | Export redacted diagnostics when you choose. | `C-05`, `S-E`; `P-03`, `P-05` |

No other claim block is approved for the controlled-MVP listing without a new `MKT-10` review.

## Screenshot Shot List

| Order | Required State | Store Caption | Copy ID | Gate Row |
|---:|---|---|---|---|
| 1 | Completed real chat response with `Runtime: Ready` visible | Chat on-device, even offline | `CH-01` | `S-A` |
| 2 | Runtime loading/ready state plus recovery action | Know when your local model is ready | `CH-04` | `S-D`, `S-G` |
| 3 | Session drawer with two realistic, non-sensitive conversations | Continue across local chat sessions | `CH-06` | `S-C` |
| 4 | Prompt-shortcut dialog and the resulting prefilled prompt, without overlapping UI | Start useful workflows from a prompt | `CH-02` | `S-B` |
| 5 | One selected image and a completed contextual response | Ask about one image in your chat | `CH-03` | `S-C` |
| 6 | Model library showing active, downloaded, and available sections | Download, import, and recover models | `CH-04` | `S-D`, `S-G` |
| 7 | Privacy panel or redacted diagnostics action with readable copy | Local by default, transparent when you need help | `CH-05`, `CH-07` | `S-E` |

The retained March screenshot pack under `tests/ui-screenshots/reference/` is QA reference material only. It is not listing material: it shows the retired `Pocket GPT` name, synthetic `unlock step` prompts, overlapping surfaces, and incomplete image states. Capture all seven listing states again from the final PocketAgent release candidate.

## Asset QA Rules

1. Capture the final signed release candidate or a build proven source-equivalent to it; record version name/code, Git SHA, device, Android version, and capture time.
2. Use realistic but non-sensitive prompts and responses. Do not expose notifications, account names, IP addresses, local file paths, model-host credentials, or raw diagnostics.
3. Show one surface per image. Reject keyboard, system-overlay, clipped-sheet, blank-image, loading-placeholder, synthetic-fixture, or overlapping-modal captures.
4. Keep app screenshots truthful. Captions and framing may clarify the state but must not fabricate UI or outcomes.
5. Use only `CH-01` through `CH-07`. No broad voice, multi-image, document-analysis, cloud-sync, universal-performance, or delete-all/retention-control claim is allowed.
6. Product and Marketing must approve each derivative against its raw capture and the asset manifest before upload.
7. Confirm current Play Console file-format and dimension requirements during upload; do not stretch or crop away app state to force an outdated assumption.

## Store Metadata

- Privacy policy: `https://github.com/kapplicationing/pocket-gpt/blob/main/PRIVACY.md`
- Support page: `https://github.com/kapplicationing/pocket-gpt/blob/main/SUPPORT.md`
- Support email: `mohamad.kamar.msk@gmail.com`
- App access: no account or sign-in required
- Ads: none
- Target audience for this controlled MVP: adults age 18 and over
- Voice: production opt-in behavior; excluded from this frozen listing copy and screenshots until retained multi-OEM and 24-hour qualification supports broader claims

The operator worksheet for Play Console declarations is `docs/operations/play-console-metadata.md`.
