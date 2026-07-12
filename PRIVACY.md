# PocketAgent Privacy Policy

Effective date: July 11, 2026
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
- microphone audio while you explicitly use or enable the limited-beta local voice feature; and
- diagnostic information such as runtime state, timing, memory, and thermal measurements.

PocketAgent does not send prompts, responses, selected images, or microphone audio to a developer-operated inference service. Android cloud backup is disabled. On Android 12 and later, PocketAgent also configures exclusions for every supported app-data domain in device-to-device transfer rules; manufacturer transfer behavior can still vary.

## Network use

Core inference runs on-device. Network access is used only for features that need it, such as checking a model catalog, searching public Hugging Face model listings, or downloading a model file that you request. Those requests can disclose ordinary connection information, including your IP address and request metadata, to the network or model-hosting provider. Their privacy terms govern information they receive.

PocketAgent does not include advertising, analytics, or third-party crash-reporting SDKs. It does not sell personal data.

## Diagnostics and support

Diagnostics export is user initiated and redacts known sensitive keys before displaying the report. PocketAgent does not upload that report automatically. If you choose to share diagnostics with support, review the report first and remove anything you do not want to disclose.

Do not post prompts, images, microphone recordings, model files, or private diagnostics in a public support issue. Send privacy or security reports to [mohamad.kamar.msk@gmail.com](mailto:mohamad.kamar.msk@gmail.com).

## Permissions

PocketAgent may request:

- network access for user-requested model discovery and downloads;
- notification and foreground-service access for visible long-running work such as model downloads;
- microphone access for the opt-in limited-beta local voice feature; and
- system file-picker access when you choose a model or image.

PocketAgent does not request location, contacts, SMS, or advertising-ID access.

## Retention and deletion

Local data remains on your device until you remove it. PocketAgent supports deleting individual chat sessions and installed models. You can also clear the app's storage or uninstall it through Android settings. The current controlled-MVP release does not provide one in-app button that deletes every category of local data at once.

Support email and GitHub issue content remain with the services through which you submit them and follow those services' retention policies. You can ask the developer to delete information you sent directly by emailing the contact above.

## Security

PocketAgent uses Android app isolation, disables cleartext network traffic, validates supported model artifacts, and redacts known sensitive diagnostic keys. No system can guarantee absolute security; keep your device protected and install models only from sources you trust.

## Children

PocketAgent is not directed to children. The controlled-MVP Play Store release targets adults age 18 and over.

## Changes

Material policy changes will update this file and its effective date before the related app behavior or external claim is published.
