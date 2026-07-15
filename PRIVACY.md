# PocketAgent Privacy Policy

Effective date: July 12, 2026
Developer: Kapplicationing
Contact: [mohamad.kamar.msk@gmail.com](mailto:mohamad.kamar.msk@gmail.com)

PocketAgent is a local-first Android assistant. Supported AI models run on your device, and the app does not require a cloud inference account.

## Data PocketAgent processes locally

PocketAgent may process and store these items on your Android device:

- prompts, generated responses, and chat-session history;
- images you explicitly select for a conversation;
- local memory and first-session progress;
- imported or downloaded model files and model-download state;
- runtime settings, readiness state, and performance measurements;
- microphone audio in memory while you use dictation, the Android assistant,
  or opt-in hands-free Offas;
- recognized voice text and short-lived confirmation or clarification state; and
- diagnostic information such as runtime state, timing, memory, and thermal measurements.

PocketAgent does not send prompts, responses, selected images, or microphone audio to a developer-operated inference service. Android cloud backup is disabled. On Android 12 and later, PocketAgent also configures exclusions for every supported app-data domain in device-to-device transfer rules; manufacturer transfer behavior can still vary.

When hands-free Offas is on, PocketAgent keeps the microphone active in a
foreground service so it can detect the local wake phrase. PocketAgent keeps an
ongoing private notification with **Talk now** and **Stop listening** controls;
Android also shows its system microphone privacy indicator on versions that
support it. Raw microphone audio is processed in memory and is not saved to a
file.

Recognized hands-free text is processed locally. Bounded phone commands use the
typed local action path. A general conversational request uses a temporary local
runtime session with ephemeral memory; PocketAgent deletes that session after
the turn completes or fails instead of adding it to normal chat history. A
dictated message remains an editable draft until you choose to send it.

## Network use

Core inference and voice processing run on-device. Network access is used only
for features that need it, such as checking a model catalog, searching public
Hugging Face model listings, or downloading a model file that you request.
Turning on hands-free Offas for the first time may download and verify a pinned
local voice pack of about 60 MB from Hugging Face and GitHub. These requests can
disclose ordinary connection information, including your IP address and request
metadata, to the network or model-hosting provider. Their privacy terms govern
information they receive.

PocketAgent does not include advertising, analytics, or third-party crash-reporting SDKs. It does not sell personal data.

## Local phone actions

PocketAgent can perform a small set of typed Android actions after the applicable
voice confirmation policy. Alarm and timer requests use Android's standard Clock
intents and pass the requested time or duration plus the spoken label to the
compatible Clock app installed on your phone. That app stores and manages the
resulting alarm or timer under its own behavior and privacy policy.

The CAMERA permission is used only to ask Android's camera service to turn an
available flashlight on or off. PocketAgent does not take, view, or store a photo
or video for this action. Images added to chats come only from the system picker
when you explicitly select them.

## Diagnostics and support

Diagnostics export is user initiated and redacts known sensitive keys before displaying the report. PocketAgent does not upload that report automatically. If you choose to share diagnostics with support, review the report first and remove anything you do not want to disclose.

Do not post prompts, images, microphone recordings, model files, or private diagnostics in a public support issue. Send privacy or security reports to [mohamad.kamar.msk@gmail.com](mailto:mohamad.kamar.msk@gmail.com).

## Permissions

PocketAgent may request:

- network access for user-requested model discovery, chat-model downloads, and
  the one-time local voice-pack setup;
- notification and foreground-service access for visible long-running work such
  as model downloads and opt-in hands-free listening;
- microphone access for dictation, assistant capture, and hands-free Offas;
- camera access only when you ask PocketAgent to change the flashlight;
- Android alarm capability to send requested alarms and timers to a compatible
  Clock app; and
- system file-picker access when you choose a model or image.

PocketAgent does not request location, contacts, SMS, or advertising-ID access.

## Retention and deletion

Local data remains on your device until you remove it, except for the temporary
hands-free conversational session described above. PocketAgent supports deleting
individual chat sessions and installed chat models. The local voice pack remains
in app storage until you clear PocketAgent's storage or uninstall the app.
Alarms and timers remain in the Clock app until you manage them there. You can
also clear PocketAgent's storage or uninstall it through Android settings. The
current release does not provide one in-app button that deletes every category
of local data at once.

Support email and GitHub issue content remain with the services through which you submit them and follow those services' retention policies. You can ask the developer to delete information you sent directly by emailing the contact above.

## Security

PocketAgent uses Android app isolation, disables cleartext network traffic,
validates supported model artifacts and every file in the pinned voice pack,
and redacts known sensitive diagnostic keys. No system can guarantee absolute
security; keep your device protected and install models only from sources you
trust.

## Children

PocketAgent is not directed to children. The controlled-MVP Play Store release targets adults age 18 and over.

## Changes

Material policy changes will update this file and its effective date before the related app behavior or external claim is published.
