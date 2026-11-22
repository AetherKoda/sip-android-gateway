# SIP Communications

Use a SIM-equipped Android phone as a cellular gateway for a SIP phone system. A SIP client such as Linphone or Bria sends an outbound call through the PBX, and the Android phone places it on the mobile network. The same bridge can carry an incoming mobile call back to the SIP system when the routing policy allows it.

SIP Communications builds on sparkcyf's [Voice SIP Agent](https://github.com/sparkcyf/android-voice-sip-agent). It adds platform-managed SIP settings and device reporting for setups with multiple SIM phones. A compatible backend and PBX coordinate line selection, group ringing, and transfers. Each Android handset handles one cellular call at a time.

[Development history](#development-history) · [Source review](docs/SOURCE_REVIEW.md) · [Testing](docs/TESTING.md) · [Changelog](CHANGELOG.md) · [Third-party notices](THIRD_PARTY_NOTICES.md)

## Development history

1. **Starting with the call bridge.** The first versions adapted Voice SIP Agent for centrally managed handsets. The Android gateway gained a localized setup flow and received its SIP settings from the platform, so each SIM phone could be prepared without entering its SIP configuration by hand.
2. **Getting handsets ready.** Early changes made it possible to retry Android permission requests, reconnect from the home screen, and see Shizuku readiness separately from the platform session. Session renewal was later added for handsets that stay online.
3. **Working through call and audio issues.** Early device calls confirmed two-way audio, hang-up, and in-call keypad tones in the setups tested. Reports of one-way audio led to separate counters for each direction and a direct-telephony audio preset. The preset leaves the handset speaker or earpiece audible, so local muting is a separate test.
4. **Operating multiple SIM phones.** The Android client added readiness and call-event reporting, and retained received SMS locally until the server acknowledged it. The platform and PBX handle fixed and shared lines, group ringing, and transfers. The public source snapshot also passed 42 SIP and gateway tests. [Changelog](CHANGELOG.md) · [Device and automated test notes](docs/TESTING.md)

### Preparing for Shizuku integration

The public version uses Shizuku API / Provider 13.1.5 to connect to the separately installed Shizuku service. To explore a simpler setup, a separate prototype tried starting the call service inside the gateway, using LibADB Android, Conscrypt, and Bouncy Castle while retaining the Shizuku connection path. It completed one two-way call on a REDMI 15C.

The next step is to plan the single-app flow for pairing, permission checks, and call-service startup, then validate it across more handsets and after a reboot. [Candidate test notes](docs/TESTING.md#single-app-startup-candidate)

## Requirements

- An ARM64 Android phone running Android 11 or later, with a SIM that supports voice calls.
- [Shizuku](https://github.com/RikkaApps/Shizuku) installed, running, and authorized. After a reboot, start it again before using the gateway.
- A compatible provisioning backend, a SIP PBX, and a SIP client for the calling or answering side.

The public source baseline is `0.4.0-yx.12` (Android version code `111`). It includes the Android client and its build files. The PBX and provisioning service are separate components.

## Set up a handset

1. Install and start Shizuku, then authorize SIP Communications. Install the gateway APK built and signed for your deployment.
2. Use the gateway account provided by your operator. The separate SIP client uses its own SIP account.
3. Follow the app prompts for notifications, phone access, and any required default phone or SMS roles.
4. Enable the gateway and check Shizuku, call-audio access, background operation, and SIP registration. Place a call and check that both people can hear each other and either end can hang up.

Updates that replace an existing installation need the same Android package identifier and signing key. After a reboot, start Shizuku again and confirm that SIP registration has recovered.

## Routing and configuration

```mermaid
flowchart LR
    Client["SIP client"] <-->|"SIP and media"| PBX["PBX"]
    PBX <-->|"SIP and media"| Gateway["SIP Communications"]
    Gateway <-->|"Cellular call"| Remote["Other party"]
    PBX <-->|"Group transfer"| Group["Answering group"]
```

- **Fixed line.** The PBX assigns a specific SIM handset. That handset stays busy through a transfer until the cellular call ends.
- **Shared lines.** With the corresponding PBX policy, another idle SIM handset can carry a new call after a transfer is answered. The first handset continues to carry the original call.
- **Group ringing.** The configured PBX controls who rings, how waiting calls queue, and when the remaining phones stop ringing after an answer.
- **Incoming calls.** The handset follows the provisioned policy to reject the call or send it to the permitted SIP destination.

| Setting | What it does |
| --- | --- |
| SIP provisioning | The backend supplies the SIP server settings, credentials, and transport. |
| SIP transport and media | The PBX must agree with the app's transport and SRTP policy. A UDP compatibility profile can still carry unencrypted media. |
| Outbound SIM | Use the default or select a SIM slot. The gateway handles one active cellular call per handset. |
| Audio compatibility | The compatibility preset uses direct telephony audio and leaves handset output audible. Test handset muting separately. |
| Reconnect | Request a reconnect, then wait for SIP registration to confirm recovery. |

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| Shizuku is unavailable | Check that Shizuku is running and authorized. Review pairing and Android background restrictions if it still cannot connect. |
| Calls connect but audio is one-way | Check microphone permission and mute on the SIP client. Try the appropriate audio mode and compare per-direction audio counters while both people speak. |
| SIP certificate name mismatch | Match the SIP server name to the certificate and keep certificate verification enabled. |
| SIP 488 | Check the codecs and media-encryption policy on both ends. |
| Transfer fails | Check that the target group is configured on the PBX and that its SIP clients are registered. |

The readiness indicators cover setup and registration. Check actual two-way audio during a connected call. An audio peak in a single diagnostic sample can reflect sound from earlier in the call. Compare counters over an interval while both parties speak.

Received SMS stays in the outbox until the expected acknowledgement arrives. MMS attachments are outside the supported flow. Keep account details and raw device logs out of public issue reports.

## Device testing

Physical call tests were reported successful on **OPPO Reno10**, **OPPO Reno15**, and **vivo V60**. Results depend on the Android build, carrier, and audio settings used. Check both audio directions on the intended handset and network. The [testing notes](docs/TESTING.md) record the test scope and the scenarios that still need coverage.

## Build from source

Use JDK 17 or newer, Android SDK 37, the Android NDK required by the native script, SWIG, and the native build tools. Generate the ARM64 PJSUA2 bindings before assembling the app. This project uses the upstream build flow and targets ARM64 in its Android configuration. [Upstream build instructions](https://github.com/sparkcyf/android-voice-sip-agent/blob/2e2c46ccaeda1aa2e2c129d42b4084b5a0a45a13/README.md#building-from-source)

```bash
ABIS=arm64-v8a APP_PLATFORM=android-30 ./sip-pjsua2/native/build-pjsua2.sh
./gradlew :app:assembleDebug
```

The build produces an Android client. End-to-end use requires compatible provisioning and PBX routing. For the JVM SIP and bridge tests, run `./gradlew :sip:test :gateway:test`.

## Open-source components

| Component | Role | License |
| --- | --- | --- |
| [Voice SIP Agent](https://github.com/sparkcyf/android-voice-sip-agent) | Android gateway and call bridge | GPL-2.0-or-later with an Apache 2.0 linking exception |
| [Shizuku](https://github.com/RikkaApps/Shizuku) | Separately installed service for privileged call integration and audio access | Apache-2.0 |
| [Shizuku API / Provider 13.1.5](https://github.com/RikkaApps/Shizuku-API) | Client interface to Shizuku | [MIT](LICENSES/Shizuku-API-MIT.txt) |
| [PJSIP / PJSUA2 2.17](https://github.com/pjsip/pjproject) | SIP signaling and call media | GPL-2.0-or-later |
| [libsrtp](https://github.com/cisco/libsrtp) | Secure RTP, supplied with pjproject | BSD-3-Clause |
| [OpenSSL 3.5.9](https://github.com/openssl/openssl) | TLS and cryptography | Apache-2.0 |
| [libopus 1.6.1](https://opus-codec.org/) | Opus audio codec | BSD-3-Clause |
| [AndroidX / Jetpack Compose](https://android.googlesource.com/platform/frameworks/support), [Kotlin](https://github.com/JetBrains/kotlin), and [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Android UI and background tasks | Apache-2.0 |
| [LLVM libc++](https://github.com/llvm/llvm-project) | C++ runtime from the Android NDK | Apache-2.0 WITH LLVM-exception |

[Third-party notices and build sources](THIRD_PARTY_NOTICES.md) · [Dependency versions](gradle/libs.versions.toml)

## Support development

If SIP Communications is useful to you, you can make an optional PayPal contribution to support continued development and testing on real devices.

[Contribute via PayPal](https://paypal.me/MINFANGWU)

## License

Copyright (C) 2026 sparkcyf.

SIP Communications is free software under the GNU General Public License, version 2 or any later version. Upstream also grants an Apache 2.0 linking exception, provided the other code complies with the GPL. Each distributed APK must be accompanied by the corresponding source, or made available as permitted by the applicable GPL terms. [GPL license](LICENSE) · [Upstream license and linking exception](https://github.com/sparkcyf/android-voice-sip-agent#license)

## Disclaimer

**Operator responsibility.** If you use, configure, or operate SIP Communications, you are responsible for your actions, any call charges, and the consequences of your use.

**Misuse.** SIP Communications is intended for lawful, authorized use. Fraud, impersonation, harassment, unauthorized calls, and recordings made without legally required consent are examples of misuse.

**Limitation of liability.** To the extent permitted by applicable law, the authors, maintainers, and contributors assume no liability for losses or claims arising from another person's use or misuse of the software.

**Availability and emergency calls.** The software is provided as is, without warranty to the extent permitted by applicable law. Calls can drop, be misrouted, or connect without audio. Use a regular phone to reach emergency services. The gateway rejects recognized emergency numbers.

**Deployment.** Protect SIP credentials and PBX access, and restrict outbound dialing to the destinations your setup requires. Your carrier may charge for calls placed through the SIM. Follow local laws, carrier terms, and applicable consent requirements for call forwarding or recording. Shizuku grants shell-level privileges, so install it and the gateway from sources you trust.

These notices do not alter the rights and obligations in the GPL. SIP Communications is not affiliated with or endorsed by Google, Motorola, Shizuku, PJSIP, Asterisk, Flexisip, or any carrier.
