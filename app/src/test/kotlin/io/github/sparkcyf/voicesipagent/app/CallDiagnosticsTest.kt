package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.*
import org.junit.Test

class CallDiagnosticsTest {
    private val tap = "method=direct rate=16000 frameBytes=640 down=500 up=499 downPeak=12000 upPeak=9000 " +
        "downVoiced=200 upVoiced=150 backendFailures= preferred=18 routed=18 " +
        "downGap[max=28ms >40=0 >100=0] upGap[max=25ms >40=0 >100=0] upWrite[max=1ms >40=0 >100=0]"
    private val numbers = "rx[pkt=750 loss=1 discard=2 dup=0 reorder=0 jitter=1/5ms ipdv=2/6ms] " +
        "tx[pkt=749 peerLoss=3] rtt=12ms jbuf[delay=20/40ms prefetch=2 size=3 lost=1 discard=0 empty=0]"
    private val fifo = " fromSip[frames=750 dropped=1 underruns=2] toSip[frames=749 dropped=3 underruns=4]"
    private fun snapshot(stats: String? = tap, logs: List<String> = listOf("12:34:56 sip: call p3 rtp $numbers$fifo")) =
        CallDiagnostics.Snapshot("0.4.0-yx.8", 36, "2026-11-04T10:20:30Z", 2, false, null, null,
            true, true, true, "direct", stats, logs)

    @Test fun activeSnapshotDistinguishesSavedPendingAndActualMethod() {
        val report = CallDiagnostics.format(snapshot().copy(pendingMethod = 1, pendingMute = true))
        assertTrue(report.contains("已儲存音訊模式=直接電話音訊 揚聲器靜音=false"))
        assertTrue(report.contains("待套用音訊模式=通話重新導向 揚聲器靜音=true"))
        assertTrue(report.contains("實際回報方法=direct（目前已開啟）"))
        assertTrue(report.contains("sampleRate=16000"))
        assertTrue(report.contains("upFrames=499"))
        assertTrue(report.contains("upPeak=9000"))
        assertTrue(report.contains("upVoiced=150"))
        assertTrue(report.contains("peak 是整段最大值，不是最近 5 秒"))
    }

    @Test fun rtpCountersAreReconstructedWithTimeAndHistoricalLabel() {
        val report = CallDiagnostics.format(snapshot())
        assertTrue(report.contains("紀錄時間=12:34:56 階段=通話期間快照"))
        assertTrue(report.contains("歷史快照"))
        assertTrue(report.contains("rxPackets=750 rxLoss=1 rxDiscard=2"))
        assertTrue(report.contains("rttMs=12"))
        assertTrue(report.contains("fromSipFrames=750 fromSipDropped=1 fromSipUnderruns=2"))
        assertTrue(report.contains("toSipFrames=749 toSipDropped=3 toSipUnderruns=4"))
        assertFalse(report.contains("sip: call p3"))
    }

    @Test fun endedCallCannotBePresentedAsActiveAndAbsentDataIsExplicit() {
        val report = CallDiagnostics.format(snapshot(null, emptyList()).copy(callPresent = false,
            helperAvailable = false, tapActive = false, backend = ""))
        assertTrue(report.contains("目前無通話／通話已結束"))
        assertTrue(report.contains("音訊服務=未取得"))
        assertTrue(report.contains("非目前作用中"))
        assertTrue(report.contains("tap 計數=未取得"))
        assertTrue(report.contains("RTP/FIFO=未取得"))
        assertFalse(report.contains("upPeak="))
    }

    @Test fun closedTapCountersAreExplicitlyHistorical() {
        val report = CallDiagnostics.format(snapshot().copy(tapActive = false, callPresent = false))
        assertTrue(report.contains("tap 統計範圍=歷史 tap 或無資料"))
        assertTrue(report.contains("upPeak=9000"))
        assertFalse(report.contains("（目前已開啟）"))
    }

    @Test fun rawSettingsSecretsAddressesAndSipMessagesNeverAppear() {
        val secrets = listOf("test-password-S3CRET", "bearer-TOKEN", "0912345678", "198.51.100.44", "secret.example.org")
        val noise = secrets.joinToString(" ")
        val report = CallDiagnostics.format(snapshot("$tap backendFailures=$noise error=up: $noise",
            listOf("12:34:50 runtime starting password=$noise", "12:34:51 sip: INVITE sip:$noise SIP/2.0",
                "12:34:56 sip: call p3 rtp $numbers$fifo")))
        secrets.forEach { assertFalse("Leaked $it", report.contains(it)) }
        assertTrue(report.contains("tapError=UPLINK_IO_FAILED"))
        assertTrue(report.contains("rxPackets=750"))
    }

    @Test fun malformedRtpAndValidPrefixWithArbitrarySuffixAreRejected() {
        val report = CallDiagnostics.format(snapshot(logs = listOf(
            "12:34:56 sip: call p3 rtp $numbers$fifo password=secret",
            "12:34:56 sip: call p3 rtp ${numbers.replace("pkt=750", "pkt=198.51.100.4")}$fifo",
            "25:00:00 sip: call p3 rtp $numbers$fifo",
            "prefix 12:34:56 sip: call p3 rtp $numbers$fifo")))
        assertTrue(report.contains("RTP/FIFO=未取得"))
        assertFalse(report.contains("rxPackets="))
    }

    @Test fun onlyLatestCallAndAtMostSixSnapshotsAreIncluded() {
        val logs = listOf("12:30:00 sip: call p1 rtp ${numbers.replace("pkt=750", "pkt=999")}$fifo") +
            (10..19).map { "12:34:$it sip: call p2 rtp $numbers$fifo" }
        val report = CallDiagnostics.format(snapshot(logs = logs))
        assertEquals(6, Regex("紀錄時間=").findAll(report).count())
        assertFalse(report.contains("rxPackets=999"))
        assertFalse(report.contains("紀錄時間=12:34:13"))
        assertTrue(report.contains("紀錄時間=12:34:14"))
        assertTrue(report.contains("紀錄時間=12:34:19"))
    }

    @Test fun finalRtpWithoutFifoIsNotInventedAndZeroIsValid() {
        val report = CallDiagnostics.format(snapshot(logs = listOf("12:34:56 sip: call p3 final rtp " +
            numbers.replace("pkt=750", "pkt=0"))))
        assertTrue(report.contains("階段=結束統計"))
        assertTrue(report.contains("rxPackets=0"))
        assertFalse(report.contains("fromSipFrames="))
    }

    @Test fun reusedLegIdAfterRestartDoesNotMixPriorCallCounters() {
        val report = CallDiagnostics.format(snapshot(logs = listOf(
            "12:30:00 sip: call p1 rtp ${numbers.replace("pkt=750", "pkt=999")}$fifo",
            "12:34:00 sip: call p1 connected (PCMA)",
            "12:34:05 sip: call p1 rtp $numbers$fifo")))
        assertFalse(report.contains("rxPackets=999"))
        assertEquals(1, Regex("紀錄時間=").findAll(report).count())
    }

    @Test fun signedDelayVariationRemainsNumericAndDoesNotDiscardTheWholeSample() {
        val report = CallDiagnostics.format(snapshot(logs = listOf("12:34:56 sip: call p3 rtp " +
            numbers.replace("ipdv=2/6ms", "ipdv=-2/6ms") + fifo)))
        assertTrue(report.contains("ipdvMeanMs=-2"))
        assertTrue(report.contains("rxPackets=750"))
    }

    @Test fun unrecognizedOrOversizedTapStatsDoNotLeakOrInventCounters() {
        listOf("secret $tap", tap.replace("rate=16000", "rate=secret"), tap + "x".repeat(16_385)).forEach {
            val report = CallDiagnostics.format(snapshot(it))
            assertTrue(report.contains("tap 計數=未取得或格式無法辨識"))
            assertFalse(report.contains("upFrames="))
            assertFalse(report.contains("secret"))
        }
    }

    @Test fun backendAndErrorTextAreAllowlistedNotCopied() {
        val errors = mapOf("track write -6 at 16000 Hz" to "TRACK_WRITE_FAILED",
            "cellular audio mode lost: 3" to "CELLULAR_MODE_LOST", "open at 16000 Hz: private detail" to "AUDIO_OPEN_FAILED",
            "down: private detail" to "DOWNLINK_IO_FAILED", "unknown private detail" to "AUDIO_ERROR_OTHER")
        errors.forEach { (error, expected) ->
            val report = CallDiagnostics.format(snapshot("$tap error=$error").copy(backend = "private detail"))
            assertTrue(report.contains("tapError=$expected"))
            assertTrue(report.contains("實際回報方法=未知"))
            assertFalse(report.contains("private detail"))
        }
    }
}
