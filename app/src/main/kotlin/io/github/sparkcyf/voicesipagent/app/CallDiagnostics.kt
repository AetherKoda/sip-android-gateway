package io.github.sparkcyf.voicesipagent.app

/** An allowlist formatter. Never exports raw logs, settings, SIP messages or exception text. */
internal object CallDiagnostics {
    data class Snapshot(
        val version: String, val sdk: Int, val capturedAt: String,
        val savedMethod: Int, val savedMute: Boolean,
        val pendingMethod: Int?, val pendingMute: Boolean?,
        val callPresent: Boolean, val helperAvailable: Boolean, val tapActive: Boolean,
        val backend: String, val tapStats: String?, val logs: List<String>,
    )

    private const val N = "([0-9]{1,20})"
    private const val D = "(-?[0-9]{1,20}(?:\\.[0-9]{1,8})?)"
    private const val TIME = "((?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9])"
    private val tapPrefix = Regex("^method=(direct|redirection-helper|redirection-manual|none) rate=$N frameBytes=$N " +
        "down=$N up=$N downPeak=$N upPeak=$N downVoiced=$N upVoiced=$N(?: |$)")
    private val route = Regex(" preferred=(null|[0-9]{1,3}) routed=(null|[0-9]{1,3})(?= |$)")
    private val rtpPrefix = Regex("^$TIME sip: call (p[0-9]{1,10}) (final )?rtp (.*)$")
    private val connected = Regex("^$TIME sip: call (p[0-9]{1,10}) connected \\([^\\r\\n]*\\)$")
    private val rtpNumbers = Regex("rx\\[pkt=$N loss=$N discard=$N dup=$N reorder=$N jitter=$D/$D" +
        "ms ipdv=$D/$D" + "ms] tx\\[pkt=$N peerLoss=$N] rtt=${D}ms " +
        "jbuf\\[delay=$D/$D" + "ms prefetch=$N size=$N lost=$N discard=$N empty=$N]" +
        "(?: fromSip\\[frames=$N dropped=$N underruns=$N] toSip\\[frames=$N dropped=$N underruns=$N])?")
    private val rtpKeys = listOf("rxPackets", "rxLoss", "rxDiscard", "rxDup", "rxReorder", "jitterMeanMs", "jitterMaxMs",
        "ipdvMeanMs", "ipdvMaxMs", "txPackets", "peerLoss", "rttMs", "jbufDelayMeanMs", "jbufDelayMaxMs",
        "jbufPrefetch", "jbufSize", "jbufLost", "jbufDiscard", "jbufEmpty",
        "fromSipFrames", "fromSipDropped", "fromSipUnderruns", "toSipFrames", "toSipDropped", "toSipUnderruns")
    private data class Rtp(val time: String, val id: String, val ended: Boolean, val values: List<String>)

    private fun method(value: Int?) = when (value) { null -> "無"; 0 -> "自動"; 1 -> "通話重新導向"; 2 -> "直接電話音訊"; else -> "未知" }
    private fun backend(value: String) = when (value) {
        "direct", "redirection-helper", "redirection-manual", "none" -> value
        else -> "未知"
    }
    private fun parseRtp(line: String): Rtp? {
        if (line.length > 1500) return null
        val prefix = rtpPrefix.matchEntire(line) ?: return null
        val numbers = rtpNumbers.matchEntire(prefix.groupValues[4]) ?: return null
        return Rtp(prefix.groupValues[1], prefix.groupValues[2], prefix.groupValues[3].isNotEmpty(), numbers.groupValues.drop(1))
    }

    /** Numeric allowlist only; historical counters never establish audible two-way speech. */
    fun latestMetrics(lines: List<String>): org.json.JSONObject {
        val sample = lines.takeLast(200).mapNotNull(::parseRtp).lastOrNull()
        return org.json.JSONObject().apply {
            put("status", if (sample == null) "unknown" else "measured_transport_only")
            put("sample_time_local", sample?.time ?: org.json.JSONObject.NULL)
            put("call_finished", sample?.ended ?: org.json.JSONObject.NULL)
            put("metrics", org.json.JSONObject().apply {
                sample?.values?.forEachIndexed { index, value ->
                    value.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { put(rtpKeys[index], it) }
                }
            })
        }
    }

    fun format(s: Snapshot): String = buildString {
        appendLine("YX 通話診斷（不含帳密、電話、地址或錄音）")
        appendLine("版本=${s.version.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,40}")) } ?: "未知"} AndroidAPI=${s.sdk}")
        appendLine("取得時間=${s.capturedAt.takeIf { it.matches(Regex("[0-9TZ:.+-]{10,40}")) } ?: "未知"}")
        appendLine("已儲存音訊模式=${method(s.savedMethod)} 揚聲器靜音=${s.savedMute}")
        appendLine("待套用音訊模式=${method(s.pendingMethod)} 揚聲器靜音=${s.pendingMute?.toString() ?: "無"}")
        appendLine("通話狀態=${if (s.callPresent) "有通話或橋接工作" else "目前無通話／通話已結束"}")
        appendLine("音訊服務=${if (s.helperAvailable) "已取得" else "未取得"} tapActive=${s.tapActive}")
        appendLine("狀態與計數分次讀取；通話切換時可能不同步。")
        appendLine("實際回報方法=${backend(s.backend)}（${if (s.helperAvailable && s.tapActive) "目前已開啟" else "最後回報或未知，非目前作用中"}）")
        appendLine("tap 統計範圍=${if (s.helperAvailable && s.tapActive) "本次 tap 累計" else "歷史 tap 或無資料"}；peak 是整段最大值，不是最近 5 秒。")
        val raw = s.tapStats?.takeIf { it.length <= 16_384 }
        val tap = raw?.let { tapPrefix.find(it) }
        if (tap == null) appendLine("tap 計數=未取得或格式無法辨識") else {
            val v = tap.groupValues
            appendLine("tapMethod=${backend(v[1])} sampleRate=${v[2]} frameBytes=${v[3]} downFrames=${v[4]} upFrames=${v[5]}")
            appendLine("downPeak=${v[6]} upPeak=${v[7]} downVoiced=${v[8]} upVoiced=${v[9]}（voiced 為峰值 >300 的框數）")
            route.find(raw)?.let { appendLine("preferredDeviceType=${it.groupValues[1]} routedDeviceType=${it.groupValues[2]}") }
            val error = raw.substringAfter(" error=", "")
            val classification = when {
                error.isEmpty() -> "未回報"
                error.startsWith("track write ") -> "TRACK_WRITE_FAILED"
                error.startsWith("uplink packet size ") -> "UPLINK_FRAME_SIZE"
                error.startsWith("cellular audio mode lost:") -> "CELLULAR_MODE_LOST"
                error.startsWith("open at ") -> "AUDIO_OPEN_FAILED"
                error.startsWith("up:") -> "UPLINK_IO_FAILED"
                error.startsWith("down:") -> "DOWNLINK_IO_FAILED"
                else -> "AUDIO_ERROR_OTHER"
            }
            appendLine("tapError=$classification")
        }
        val lines = s.logs.takeLast(200)
        val samples = lines.mapIndexedNotNull { index, line -> parseRtp(line)?.let { index to it } }
        val last = samples.lastOrNull()
        // PJSIP leg IDs can be reused after an endpoint restart. Do not mix the prior call's counters.
        val boundary = if (last == null) -1 else lines.take(last.first + 1).indexOfLast { line ->
            line.length <= 1500 && connected.matchEntire(line)?.groupValues?.get(2) == last.second.id
        }
        appendLine("RTP/FIFO：最近一次有資料的 SIP 通話之歷史快照；時間為手機本機時間，非即時傳送成功證明。")
        val recent = samples.filter { it.first >= boundary && it.second.id == last?.second?.id }.takeLast(6).map { it.second }
        if (recent.isEmpty()) appendLine("RTP/FIFO=未取得（通話建立後約每 5 秒記錄；重開 APP 可能清除歷史）")
        recent.forEach { r ->
            appendLine("紀錄時間=${r.time} 階段=${if (r.ended) "結束統計" else "通話期間快照"}")
            appendLine(rtpKeys.zip(r.values).filter { it.second.isNotEmpty() }.joinToString(" ") { (key, value) -> "$key=$value" })
        }
        append("有封包或有聲 PCM 不代表 SIM 對方已聽到；請搭配同次通話結果判讀。")
    }
}
