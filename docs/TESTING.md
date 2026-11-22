# QQ-SIP / SIP通訊 Testing

Test results are scoped to the version, handset, and calling setup used for each check.

## Standalone APK checks

For `0.4.0-standalone.2`, local checks on November 23, 2026 passed:

| Suite | Tests | Failures / errors |
| --- | ---: | ---: |
| Standalone app | 139 | 0 |
| Managed app compatibility | 139 | 0 |
| Privileged service / bootstrap | 67 | 0 |
| PJSUA2 lifecycle | 18 | 0 |
| JVM SIP | 22 | 0 |
| Gateway | 20 | 0 |
| Total | 405 | 0 |

The checks cover account QR parsing/rejection, discovery recovery and ownership, authenticated local
service startup, and rejection of incoming sources when the configured server cannot be resolved.
The optimized release build and release lint completed. The packaging check verifies AES-256-GCM
round trips, rejection of modified ciphertext, and preservation of native libraries and resources.
Both the embedded helper and external Shizuku service have explicit entry points for encrypted code.

This public APK has **not** completed emulator installation, camera scanning, wireless pairing,
external Shizuku startup, PBX registration, or physical two-way calls in this release run. It is a
prerelease for testing. Older device results below do not replace these checks. The native ARM64
PJSUA2 libraries were reused from the previously checked payload, not freshly rebuilt with the NDK.

For `0.4.0-standalone.1`, the standalone and managed app suites each passed 131 tests. The SIP and gateway suites passed 22 and 20 tests respectively. The checks include manual SIP input validation, deferred settings updates, and the standalone permission gate.

The signed release package was checked for its ARM64 libraries, SIP account fields, Android package identifier, and 16 KiB ZIP alignment. The standalone package requests no SMS permissions. Phone installation, live PBX registration, and physical calls remain to be verified for this APK.

## Public source checks

On November 23, 2026, the following JVM tests passed against the public `0.4.0-yx.12` source snapshot.

| Suite | Passed | Failed |
| --- | ---: | ---: |
| SIP (`:sip:test`) | 22 | 0 |
| Gateway (`:gateway:test`) | 20 | 0 |

These suites cover the JVM SIP stack and call bridge. The public source check did not build an APK, install an update on a handset, or repeat physical calls.

A SIP socket shutdown test failed in an earlier CI run in the development history. The result above comes from a later run on the public source. That run does not establish the cause of the earlier failure.

## Reported handset tests

- The project owner reported successful physical call tests on **OPPO Reno10**, **OPPO Reno15**, and **vivo V60**. Exact Android builds and a complete scenario matrix were not recorded for all three devices.
- Earlier reports include two-way audio and normal hang-up with Linphone, recognition of in-call DTMF tones, and a two-way call through Bria with the compatibility audio settings.
- One-way audio has also been reported. A ringtone, test voice, or a ready indicator can establish part of the call path. Verify both directions with two people speaking during the same connected call.

## Single-app startup candidate

The separate `0.4.0-yx.15-rc1-test` candidate completed one outbound call with two-way audio on a **REDMI 15C**. It used an in-app startup flow for the privileged call service. The historical `0.4.0-yx.12` source used a separately installed Shizuku service; standalone.2 now includes the integrated startup implementation and later recovery fixes.

Long calls, repeated calls, inbound cellular calls, reboot recovery, and other handsets remain to be checked for this candidate. [Implementation provenance](SOURCE_REVIEW.md)

## Multi-SIM and group transfer trial

The project owner reported the following live call test. The tested APK version was not recorded in the report. These results are listed separately from the public standalone APK checks above.

### Device setup

| Role | Phone |
| --- | --- |
| Main device / 主裝置－勳 | iPhone 13 |
| Extension 1 / 分機 1－霏 | iPhone 13 |
| Extension 2 / 分機 2－林 | OPPO Reno15 |
| Extension 3 / 分機 3－天 | vivo 1920 |
| SIM A | OPPO Reno10 |
| SIM B | REDMI 15C |
| SIM C | vivo V60 |

接聽方：第一通為 **婷**，第二通為 **萱**。使用者已確認第一通使用 SIM-A、第二通使用 SIM-B；先前最後一行將第二通寫成 SIM-A 的筆誤已修正。

### Call sequence

```mermaid
sequenceDiagram
    participant Main as 主裝置－勳（iPhone 13）
    participant PBX as PBX
    participant A as SIM-A（OPPO Reno10）
    participant B as SIM-B（REDMI 15C）
    participant Group as 分機群組（霏／林／天）

    Main->>PBX: Dial call 1
    PBX->>A: 第一通撥給婷，通話正常
    A-->>PBX: Call 1 connected
    Main->>PBX: Transfer call 1
    PBX->>Group: 多隻分機同時響鈴
    Group-->>PBX: 分機 1－霏接起，通話正常
    Note over A,Group: 霏 ⇄ SIM-A ⇄ 婷，整個過程保持通話

    Main->>PBX: Dial call 2 while call 1 stays connected
    PBX->>B: 第二通改用 SIM-B 撥給萱，通話正常
    B-->>PBX: Call 2 connected
    Note over A,B: The two cellular calls overlap
    Main->>PBX: Transfer call 2
    PBX->>Group: 多隻分機同時響鈴
    Group-->>PBX: 分機 3－天接起，通話正常
    Note over B,Group: 天 ⇄ SIM-B ⇄ 萱，整個過程保持通話
```

### Reported results

| Check | Call 1 | Call 2 |
| --- | --- | --- |
| Cellular handset | SIM A | SIM B, selected while call 1 was active |
| Initial call | Normal audio reported | Normal audio reported |
| Group ringing | Multiple extensions rang together | Multiple extensions rang together |
| Answering extension | 分機 1－霏 | 分機 3－天 |
| After transfer | 通話正常；霏 ⇄ SIM-A ⇄ 婷持續通話。 | 通話正常；天 ⇄ SIM-B ⇄ 萱持續通話。 |

六項問答均已記錄：① 第一通初撥通話正常；② 第一通轉接時多隻分機同時響鈴；③ 霏接起後通話正常；④ 第二通初撥通話正常；⑤ 第二通轉接時多隻分機同時響鈴；⑥ 天接起後通話正常。霏接起第一通後，勳立即撥第二通，選用 SIM-B。

SIM C was present in the setup. Selection was observed on SIM A and SIM B in this trial. The report covers two calls, including the overlap between them.

This is a user-reported trial of a PBX deployment, not a new acceptance run for standalone.2. It does not establish full A→B→C rotation, all-busy behavior, or multi-VPS operation. Multi-VPS mode is the next research direction; cross-host routing, shared state and failover remain unverified.

## Device validation checklist

1. Record the app version, handset, Android build, Shizuku state, audio mode, and routing scenario. Keep account details and real telephone numbers out of shared notes.
2. Establish a call and have both parties speak. Verify both directions, listen for interruptions, and check that either end can hang up cleanly.
3. Transfer a call to a configured ringing group. Confirm that other group members stop ringing after one answers and that the original SIM remains busy until the call ends.
4. In a shared-line setup, hold the first call and confirm the PBX selects only another eligible, idle handset for the next call.
5. Check concurrent queued calls, declined or unanswered calls, timeout routing, and cleanup after a transfer.
6. Check handset output muting separately from call audio. Test network loss and background recovery before relying on that handset.
7. Verify that SMS survives a network interruption or account change until the expected acknowledgement arrives.

[Changelog](../CHANGELOG.md)
