# SIP Communications Source Review

This review identifies the original Android source, the changes carried by SIP Communications, and the scope of the published Android snapshot.

## Upstream revisions

| Reference | Scope |
| --- | --- |
| [Voice SIP Agent baseline](https://github.com/sparkcyf/android-voice-sip-agent/tree/2e2c46ccaeda1aa2e2c129d42b4084b5a0a45a13) | Recorded source base for the Android fork |
| [Later upstream revision](https://github.com/sparkcyf/android-voice-sip-agent/tree/298d64b89b13e7737708972947390d2d84450200) | Reviewed on November 7, 2026. Upstream configuration-file import and export, including `ConfigFile.kt` and `ConfigFileTest.kt`, were not merged. |
| [Public Android snapshot](https://github.com/AetherKoda/sip-android-gateway/tree/234baea708072b159e26ec4204c255ca18ef2328) | `0.4.0-yx.12`, Android version code `111` |

## Source comparison

The table compares the public snapshot's tracked module files with the recorded upstream baseline. Text line endings were normalized and the Gradle wrapper JAR was compared byte for byte. Documentation, license files, and root-level build files are outside these module counts.

| Module | Unchanged | Modified | Added |
| --- | ---: | ---: | ---: |
| Android app (`app`) | 17 | 14 | 15 |
| Call bridge (`gateway`) | 8 | 1 | 0 |
| Gradle wrapper and versions (`gradle`) | 3 | 0 | 0 |
| Privileged audio access (`privileged`) | 24 | 3 | 0 |
| Native SIP bindings (`sip-pjsua2`) | 19 | 1 | 0 |
| JVM SIP stack (`sip`) | 19 | 0 | 0 |
| Android telephony (`telephony`) | 12 | 4 | 2 |
| Voice interfaces (`voice-api`) | 10 | 0 | 0 |

Every upstream file path within these modules is present in the public snapshot.

## Changes in the Android fork

- **Provisioning and session state.** The app obtains SIP settings from the management platform. Session renewal checks that the same authenticated session is still active before applying a delayed response.
- **Handset setup and recovery.** The localized UI guides users through Android permissions and required default roles. Readiness indicators, reconnection, and state reporting support operation with a managed handset.
- **Call audio and diagnostics.** Changes cover compatibility settings, handset output handling, and per-direction audio diagnostics. The cellular and SIP call legs still use the upstream gateway design.
- **SMS handling.** The outbox waits for a matching server acknowledgement before removing the corresponding local entry. MMS attachments remain outside the implemented flow.
- **Routing boundary.** The Android app handles the cellular leg. The backend and PBX coordinate shared lines, group ringing, queueing, and transfers.

## Android modules

| Module | Responsibility |
| --- | --- |
| `app` | Provisioning, permission guidance, connection state, device reporting, and SMS acknowledgement |
| `gateway` | Call lifecycle and two-way audio bridge |
| `telephony` | Android Telecom integration, SIM selection, and cellular call handling |
| `privileged` | Shizuku connection, call-audio access, and local output handling |
| `sip` | Kotlin SIP stack, UDP transport, G.711, and RTP |
| `sip-pjsua2` | PJSUA2 bindings, SIP transports, and media codecs |
| `voice-api` | Shared call-endpoint, call-leg, and audio interfaces |

The comparison describes source provenance and documented behavior. See [Testing](TESTING.md) for runtime evidence and [Third-party notices](../THIRD_PARTY_NOTICES.md) for attribution and component licenses.
