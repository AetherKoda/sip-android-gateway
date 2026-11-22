# SIP通訊

我開發 SIP通訊，是想讓多支插有 SIM 卡的手機一起使用，讓不同的人可以接聽和轉接。有人接起轉來的電話後，原本的 SIM 手機會繼續維持這通電話。如果還有一支空閒的 SIM 手機，負責撥號的人就能接著打下一通。

我把原本由使用者逐項填寫的 SIP 主機、連接埠和帳密，改成由平台統一派送。SIM 手機登入平台帳號後，伺服器負責分配線路、讓群組成員一起響鈴、把通話交給接起的人。手機端的通話橋接沿用 sparkcyf 的開源成果，感謝原作者分享程式碼。[原作者的 android-voice-sip-agent](https://github.com/sparkcyf/android-voice-sip-agent)

## 通話怎麼走

撥打或接聽的一端使用 Bria、Linphone 等 SIP 軟體。這一端透過網路和總機連到插有 SIM 的 Android 手機，由 Android 手機透過電信網路撥給對方。對方用一般電話接聽即可，通話聲音在兩個方向都經過 Android 手機橋接。

這份公開版提供 Android 手機端原始碼、建置檔與授權文件。登入、線路分配和群組轉接需要搭配相容的後端與 SIP 總機。程式版本為 `0.4.0-yx.12`，Android 最低需求為 Android 11，建置預設為 ARM64。

## 實機測試

以下三款手機已通過實機通話測試：

- OPPO Reno10
- OPPO Reno15
- vivo V60

手機實測採用當時的裝置、系統和網路設定。SIM 手機在 Wi-Fi、熱點或行動網路下的表現，仍須在使用地點確認。用同一支 SIM 同時通話和上網時，建議在接通後確認網路仍然可用。

## 使用的開源專案

| 專案 | 在 SIP通訊的用途 | 授權 |
| --- | --- | --- |
| [Voice SIP Agent](https://github.com/sparkcyf/android-voice-sip-agent) | Android 通話橋接的程式基礎 | GPL-2.0-or-later，附 Apache 2.0 連結例外 |
| [Shizuku](https://github.com/RikkaApps/Shizuku) | 公開版搭配獨立安裝的 Shizuku 啟動通話服務 | [Apache-2.0](https://github.com/RikkaApps/Shizuku/blob/master/LICENSE) |
| [Shizuku API／Provider 13.1.5](https://github.com/RikkaApps/Shizuku-API) | App 引用的介面及連線元件 | [MIT](LICENSES/Shizuku-API-MIT.txt) |
| [PJSIP／PJSUA2 2.17](https://github.com/pjsip/pjproject) | SIP 連線與通話媒體 | GPL-2.0-or-later |
| [libsrtp](https://github.com/cisco/libsrtp) | 語音封包加密，由 pjproject 隨附 | BSD-3-Clause |
| [OpenSSL 3.5.9](https://github.com/openssl/openssl) | TLS 與加密 | Apache-2.0 |
| [libopus 1.6.1](https://opus-codec.org/) | Opus 語音編解碼 | BSD-3-Clause |
| [AndroidX／Jetpack Compose](https://android.googlesource.com/platform/frameworks/support)、[Kotlin](https://github.com/JetBrains/kotlin) 與 [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Android 介面與背景工作 | Apache-2.0 |
| [LLVM libc++](https://github.com/llvm/llvm-project) | Android NDK 提供的 C++ 執行函式庫 | Apache-2.0 WITH LLVM-exception |

各元件的版本及建置來源見授權清單。[第三方授權聲明](THIRD_PARTY_NOTICES.md) · [直接依賴版本](gradle/libs.versions.toml)

## 下一步是整合 Shizuku 的啟動流程

目前公開的 Android 版搭配獨立安裝的 Shizuku。我希望把首次配對、權限檢查和通話服務啟動整合到 SIP通訊，讓使用者在同一個 App 看清楚需要做什麼。接下來會驗證不同手機、重開機後恢復、來電與外撥，以及雙向語音。

先前我做的單一 App 候選版，用自己的啟動流程配合 LibADB Android 3.1.1（Apache-2.0 或 GPL-3.0-or-later）、Conscrypt 2.5.3（Apache-2.0）和 Bouncy Castle 1.81（MIT 類授權），並保留原有的 Shizuku 連線方式。它曾在 REDMI 15C 完成一次雙向通話。這筆紀錄適用於當時的候選版與機型。[LibADB Android 授權](https://github.com/MuntashirAkon/libadb-android/blob/3.1.1/COPYING) · [Conscrypt 授權](https://github.com/google/conscrypt/blob/2.5.3/LICENSE) · [Bouncy Castle 授權](https://www.bouncycastle.org/licence.html)

正式整合時，如果採用 Shizuku 主程式的原始碼，會保留適用的版權和授權文字、標示修改，並依 APK 實際採用的元件提供對應來源。[Shizuku 授權](https://github.com/RikkaApps/Shizuku/blob/master/LICENSE)

## 從原始碼建置

準備 JDK 17 以上、Android SDK 37、相容的 Android NDK、SWIG 及原生建置工具。原生程式庫使用下列腳本建置，執行環境與 NDK 的要求寫在腳本中。[原生建置腳本](sip-pjsua2/native/build-pjsua2.sh)

```bash
ABIS=arm64-v8a APP_PLATFORM=android-30 sip-pjsua2/native/build-pjsua2.sh
./gradlew -Pyx.apiBaseUrl=https://platform.example.org :app:assembleDebug
```

把 `https://platform.example.org` 換成自己的相容平台 HTTPS 網址。沒有設定平台網址時，App 無法登入。手機端需安裝、啟動並授權 Shizuku，再按 App 提示完成電話及相關權限。手機重新開機後，須重新啟動 Shizuku 才能恢復橋接。

## 支持開發

如果 SIP通訊對你有幫助，歡迎透過 PayPal 自願贊助，支持我繼續開發與實機測試。

[透過 PayPal 贊助](https://paypal.me/MINFANGWU)

## 執照

Copyright (C) 2026 sparkcyf

本程式是自由軟體，可依自由軟體基金會發布的 GNU 通用公共授權第 2 版，或自行選擇後續版本，重新散佈及修改。原作者允許連結採 Apache License 2.0 授權的程式庫，前提是其餘程式碼均符合 GPL。[授權全文](LICENSE) · [原作者的授權與連結例外](https://github.com/sparkcyf/android-voice-sip-agent#license)

每個 APK 的對應原始碼以實際建置該 APK 的提交、固定版本的上游來源及所需建置檔為準。[第三方授權聲明](THIRD_PARTY_NOTICES.md)

## 免責聲明

**使用與濫用責任。** SIP通訊供合法、經授權的通話與測試使用。專案作者、維護者及貢獻者不授權以本程式進行詐騙、冒用身分、騷擾、未經授權的外撥或未依法取得必要同意的錄音。使用、部署或操作本程式的人，須對自己的行為、通話費用及相關後果負責。在適用法律允許的範圍內，作者、維護者及貢獻者不對他人操作或濫用本程式所造成的損失或索賠負責。

**軟體以現況提供。** 在適用法律允許的範圍內，本程式不提供明示或默示擔保。通話可能中斷、轉錯或無聲。需要聯絡緊急服務時，請用一般電話直接撥打。本閘道會拒絕辨識到的緊急號碼。

**部署與使用。** 請保護帳密與總機權限，並限制外撥範圍。透過 SIM 撥出的通話可能由電信業者計費。轉接與錄音須遵守適用的法規、電信服務條款及同意要求。Shizuku 提供 Android shell 等級的權限，請使用可信的安裝來源。

上列說明不變更 GPL 授予的權利與義務。SIP通訊與 Google、Motorola、Shizuku、PJSIP、Asterisk、Flexisip 或任何電信業者無隸屬關係，亦未獲其背書。
