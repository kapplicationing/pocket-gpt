# PocketAgent Play Console Metadata

Last updated: 2026-07-11
Owner: Product + Release Ops
Lifecycle: Repo package prepared; console enrollment, declarations, assets, and upload pending

Use this as the typed input sheet for the controlled-MVP internal-test submission. Re-check the live Play Console wording before answering policy questionnaires; those forms are external policy state, not repository truth.

## App And Release

| Field | Frozen Value |
|---|---|
| App name | `PocketAgent` |
| Application id | `com.pocketagent.android` |
| Default language | English (United States) |
| Category | Productivity |
| Version name | `0.1.0` |
| Version code | `20260711` |
| Initial track | Internal testing |
| Release scope | Controlled MVP |
| Ads | No |
| App access | No sign-in or restricted account required |
| Target audience | Adults, 18 and over |

## Public Contact And Policy

| Field | Value |
|---|---|
| Support email | `mohamad.kamar.msk@gmail.com` |
| Support URL | `https://github.com/kapplicationing/pocket-gpt/blob/main/SUPPORT.md` |
| Privacy policy URL | `https://github.com/kapplicationing/pocket-gpt/blob/main/PRIVACY.md` |

Verify both URLs after the release commit is pushed. The privacy URL must remain public, readable without GitHub authentication, and consistent with the submitted binary.

## Data Safety Working Answers

Repository-verified behavior:

1. AI inference runs on-device by default.
2. The app does not automatically transmit prompts, responses, selected images, microphone audio, or chat memory to a developer-operated service.
3. The app includes no ads, analytics SDK, or third-party crash-reporting SDK.
4. User-requested model catalog, Hugging Face search, and model-download requests use HTTPS and disclose normal network metadata to the selected host.
5. Diagnostics export is user initiated, redacts known sensitive keys, and is not uploaded automatically.
6. Chats, settings, models, and runtime state are stored locally. Android cloud backup is disabled, and Android 12+ transfer rules exclude every supported app-data domain; manufacturer device-to-device behavior can still vary.
7. Users can delete individual sessions and models or clear app storage through Android. There is no in-app delete-all or retention-window control in this release.

Form-entry rule: do not turn item 4 into a blanket `no data leaves the device` answer. Classify user-initiated model-host requests under the current Play Data safety definitions visible at submission time. Escalate any form wording that would require treating transport metadata, public model search terms, or manually emailed diagnostics as developer collection.

## Permissions And Feature Declarations

| Capability | Why It Exists | Listing Boundary |
|---|---|---|
| Internet / network state | User-requested model discovery, catalog refresh, and model download | Core inference remains local; downloads need network |
| Notifications | Visible long-running work and supported background status | No marketing notification claim |
| Foreground service / wake lock | Model download and limited-beta voice runtime continuity | Operational only |
| Microphone | Opt-in, limited-beta local voice | Excluded from public listing claims/assets |
| System file picker | User-selected GGUF model and single-image attachment | No broad device-storage access claim |

The app does not request location, contacts, SMS, camera, or advertising-ID access.

## Content Rating And Policy Notes

1. PocketAgent has no social feed, account system, public content sharing, in-app purchases, ads, gambling, health, finance, or government-service function.
2. User-selected local models can generate text based on user prompts. Answer the live content-rating questions for AI-generated content conservatively; do not infer a rating from this document.
3. The app is not directed to children, and the controlled-MVP target audience is 18 and over.
4. Model downloads can be large. Keep device/storage and network expectations in tester notes rather than making a universal performance claim.
5. Voice remains limited beta for controlled cohorts and is not a broad listing claim.
6. PocketAgent is a text-to-text conversational generative-AI app under Google Play's Generative AI Content policy. The current binary lacks the required in-app developer reporting/flagging flow and evidenced restricted-content prevention, so `PROD-14` blocks upload.
7. Implementing report intake creates a new developer data flow. Revisit this worksheet, `PRIVACY.md`, retention/deletion rules, and Data Safety answers after the real endpoint and payload are approved; an external support link or `mailto:` action is not sufficient.

## Asset Inputs

1. Listing copy: `docs/ux/play-store-listing-spec.md`
2. Screenshot manifest: `docs/operations/assets/mkt-04/controlled-mvp-asset-manifest.json`
3. Capture/approval guide: `docs/operations/assets/mkt-04/README.md`
4. Release notes and rollback: `docs/operations/release/controlled-mvp-0.1.0.md`
5. Launcher icon: app adaptive-icon resources under `apps/mobile-android/src/main/res/mipmap-anydpi-v26/`

Fresh screenshots remain an external capture output. Deterministic draft Play listing icon and feature graphic files exist under `docs/operations/assets/mkt-04/` with provenance, but still require Product and Marketing approval. Do not use the retained QA reference pack as listing art.

## Console-Only Completion

The following cannot be completed in Git:

1. enroll or verify the Play developer account and two-step verification;
2. accept current Play agreements and complete the live Data safety/content-rating questionnaires;
3. create the app record and tester cohort;
4. upload the signed AAB and Play assets;
5. record Play's artifact id, review result, and rollout timestamp.
