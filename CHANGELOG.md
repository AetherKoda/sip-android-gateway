# QQ-SIP / SIP通訊 Changelog

This history covers the Android fork. Build records, user-reported calls, and automated checks are identified where evidence is available.

## 0.4.0-standalone.2 — QQ-SIP / SIP通訊

- Renamed the public app to QQ-SIP (English) and SIP通訊 (Chinese). The new package is `io.github.aetherkoda.qqsip`; it installs separately from standalone.1 and the managed app. It uses an existing signing identity, not standalone.1's different signer. Keep standalone.1 installed until migration is complete.
- Retained direct SIP domain, username and password login with no bundled PBX address or management API origin. QR scanning and pasted `qqsip://login` / credential-bearing SIP URIs import settings through the same validation and connection flow. QR content is never fetched as a website.
- Integrated local wireless-debugging pairing and shell-UID call-service startup for Android 12+ primary users. Android 11 and secondary users retain the existing Shizuku route. The standalone app still leaves SMS roles and messages with the existing SMS app.
- Ported the relevant private integration candidate fixes as one public revision: typed discovery keys and generations, retry/timeout handling, late-result rejection, separate screen ownership, lock-safe status notifications, nonce/UID/expiry/replay protection, transport cleanup and shell loader support. No intermediate public APK releases are implied or fabricated.
- Reject incoming SIP sources when the configured server address cannot be resolved.
- Added optimized AES-256-GCM DEX packaging and the corresponding loader/build sources. Native libraries and resources remain unencrypted; this is not protection against inspection of a running app or SIP/RTP encryption.
- Reused the verified ARM64 PJSUA2/native payload shared with standalone.1. This release does not claim a fresh NDK rebuild or a new physical two-way call acceptance. See [testing](docs/TESTING.md) and [QR setup](docs/SIP_QR.md).
- Updated the reported two-call trial with participant/device labels and a flowchart: call 1 remains on SIM-A; call 2 remains on SIM-B. SIM-C selection and full rotation were not demonstrated. Multi-VPS mode is listed as the next research step, not as a completed feature.
- Keep QQ-SIP source, documentation and APK releases in this single repository, with the original author attribution preserved.

## 0.4.0-standalone.1

- Added a public Android APK with manually configured SIP credentials and PBX transport.
- Kept the separate Shizuku startup flow. The standalone package can be installed alongside the platform-managed app.
- Focused the standalone build on calling. It leaves the default SMS role and messages with the existing SMS app.

## 0.4.0-yx.12

- Published the Android source and build files with the GPL and third-party notices.
- Carried forward the session, SMS outbox, and device-reporting changes made after the `.11` source snapshot. This revision did not introduce new call-audio processing.
- A historical release build and signing check were recorded for version code `111`. The public source verification later passed 42 JVM tests. [Testing notes](docs/TESTING.md)

## 0.4.0-yx.11

- Simplified the handset interface by removing the general SMS screen and standalone output mute control. The call-compatibility settings remained available.
- Kept inbound SMS in the local outbox until the server confirmed receipt.
- Added handset readiness and call-event reporting for the management platform.

## 0.4.0-yx.10

- Added a platform-provisioned UDP compatibility profile alongside TLS settings.
- A later Bria call was reported successful in both directions with the compatibility settings. The report did not establish a single cause for earlier one-way audio.

## 0.4.0-yx.9

- Added a compatibility preset combining direct telephony audio with handset output enabled.
- The preset makes handset audio audible during a call. Test handset muting separately.

## 0.4.0-yx.8

- Added per-direction call-audio diagnostics.
- Preserved rejection reasons and adjusted when the app applies and restores handset output handling around a call.

## 0.4.0-yx.7

- Added periodic renewal for active platform sessions.
- Guarded against expired or revoked sessions being restored by a delayed response.

## 0.4.0-yx.6

- Separated Shizuku service readiness from platform session status.
- Kept the prompt for Shizuku authorization available after setup.

## 0.4.0-yx.5

- Added reconnection from the home screen and refined the audio mode and SIM selection controls.

## 0.4.0-yx.4

- Displayed configuration synchronization errors separately.
- Cleared a previous synchronization error after a successful retry.

## 0.4.0-yx.3

- Allowed the app to request notification permission again after a refusal.
- Recorded early DTMF and two-way call testing. Device results apply to the setups tested at the time.

## 0.4.0-yx.1 to 0.4.0-yx.2

- Established the Android fork from Voice SIP Agent.
- Added a localized interface, a distinct Android package, managed SIP provisioning, and in-app permission guidance.

## Later development candidate

The single-app startup candidate used a separate build and underwent a limited handset trial. Its evidence is recorded in [Testing](docs/TESTING.md#single-app-startup-candidate).
