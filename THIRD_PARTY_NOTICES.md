# Third-party notices

Voice SIP Agent is licensed under the GNU General Public License v2 or later with an Apache-2.0 linking
exception.
SIP Communications is a modified Android project based on [sparkcyf/android-voice-sip-agent](https://github.com/sparkcyf/android-voice-sip-agent), copyright (C) 2026 sparkcyf. Changes include managed platform login, Chinese UI and integration with central call routing. This public source snapshot is dated 2026-11-23.

Android builds from this source use the following third-party software.

## Native code (statically linked into `libpjsua2.so`)

| Component | Version | License | Source |
|---|---|---|---|
| pjproject (pjsip, pjmedia, pjlib, pjsua2 SWIG binding) | 2.17 | GPL-2.0-or-later | https://github.com/pjsip/pjproject |
| Third-party code bundled with pjproject (e.g. libsrtp) | as shipped with pjproject 2.17 | see each component's license in pjproject's `third_party/` directory (libsrtp: BSD-3-Clause) | https://github.com/pjsip/pjproject/tree/2.17/third_party |
| OpenSSL | 3.5.9 | Apache-2.0 | https://github.com/openssl/openssl |
| libopus | 1.6.1 | BSD-3-Clause (see upstream COPYING for patent notices) | https://opus-codec.org/ |
| LLVM libc++ (`libc++_shared.so`, from the Android NDK) | NDK r28c | Apache-2.0 WITH LLVM-exception | https://github.com/llvm/llvm-project |

pjproject is built by [`sip-pjsua2/native/build-pjsua2.sh`](sip-pjsua2/native/build-pjsua2.sh) from the
SHA-256-pinned upstream release tarballs, with the modifications in [`sip-pjsua2/native/`](sip-pjsua2/native/)
(`config_site.h`, `pjsua2-java-ext.i`, `pjsip-tls-wildcard.patch`). The [pjsip third-party license notice](https://docs.pjsip.org/en/latest/overview/license_3rd_party.html)
gives explicit permission to link pjproject with OpenSSL.

## Java / Kotlin libraries

| Component | License | Source |
|---|---|---|
| Shizuku API / provider (`dev.rikka.shizuku:api` and `dev.rikka.shizuku:provider`, 13.1.5) | MIT | https://github.com/RikkaApps/Shizuku-API · [license and copyright notice](LICENSES/Shizuku-API-MIT.txt) |
| Kotlin standard library, kotlinx.coroutines | Apache-2.0 | https://github.com/JetBrains/kotlin, https://github.com/Kotlin/kotlinx.coroutines |
| AndroidX (core, activity, Compose UI, Material 3) | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support |

The Shizuku manager and service are supplied separately by [Shizuku](https://github.com/RikkaApps/Shizuku) under its [Apache-2.0 license](https://github.com/RikkaApps/Shizuku/blob/master/LICENSE). The package metadata for [API 13.1.5](https://central.sonatype.com/artifact/dev.rikka.shizuku/api/13.1.5) and [Provider 13.1.5](https://central.sonatype.com/artifact/dev.rikka.shizuku/provider/13.1.5) identifies both libraries as MIT.

Full license texts: [GNU GPL](LICENSE), [Apache 2.0](LICENSES/Apache-2.0.txt), and [Shizuku API MIT](LICENSES/Shizuku-API-MIT.txt).

## Corresponding source

The corresponding source for an APK includes the source commit used to build it, the build files, and
the pinned upstream tarballs (see `build-pjsua2.sh` for versions and SHA-256 sums).
