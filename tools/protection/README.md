# Protected QQ-SIP packaging

Build the optimized standalone release first, then encrypt its DEX payload and sign the final APK.
The loader sources and packaging script are included so this step remains reviewable and rebuildable.
Install Python `cryptography` in your build environment. Use JDK 17+ and Android SDK 37.0.

```sh
./gradlew :app:assembleStandaloneRelease
python tools/protection/pack.py \
  app/build/outputs/apk/standalone/release/app-standalone-release-unsigned.apk \
  QQ-SIP-unsigned.apk --sdk "$ANDROID_HOME" --build-tools 37.0.0
apksigner sign --ks /private/path/release.jks --ks-pass file:/private/path/password \
  --out QQ-SIP.apk QQ-SIP-unsigned.apk
apksigner verify --verbose --print-certs QQ-SIP.apk
zipalign -c -P 16 4 QQ-SIP.apk
```

The script encrypts all application DEX files with AES-256-GCM and verifies authentication,
including rejection of a modified payload. It preserves native libraries and resources byte-for-byte.
The runtime decrypts code into memory. Resources and native libraries are not encrypted; a controlled
device can still inspect running code. APK protection does not encrypt SIP/RTP traffic.

Signing keys and passwords must remain outside the repository. The generated payload key is part of
the loader, not a server credential or a replacement for signing. Keep the same signing identity for
future updates of `io.github.aetherkoda.qqsip`.
