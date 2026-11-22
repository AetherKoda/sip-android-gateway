# SIP Communications Changelog

This history covers the Android fork. Build records, user-reported calls, and automated checks are identified where evidence is available.

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
