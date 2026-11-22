package io.github.sparkcyf.voicesipagent.app

import android.os.Build
import io.github.sparkcyf.voicesipagent.privileged.CallAudioStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Independent read-only IPC worker: a blocked helper must not block audio cleanup or the UI. */
internal object CallDiagnosticReader {
    // No queue: repeated requests cannot accumulate behind an unresponsive Binder transaction.
    private val worker = ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS, SynchronousQueue<Runnable>()) { task ->
        Thread(task, "call-diagnostics").apply { isDaemon = true }
    }

    suspend fun read(runtime: GatewayRuntime): String = suspendCancellableCoroutine { continuation ->
        try {
            worker.execute {
                if (!continuation.isActive) return@execute
                try {
                    var available = false
                    var status = CallAudioStatus(available = false)
                    var stats: String? = null
                    try {
                        runtime.shizuku.service?.let { helper ->
                            val before = CallAudioStatus.fromBundle(helper.callAudioStatus())
                            val observed = helper.audioStats()
                            val after = CallAudioStatus.fromBundle(helper.callAudioStatus())
                            // Discard obvious lifecycle changes; the report also labels the non-atomic snapshot.
                            if (before.active == after.active && before.backend == after.backend) {
                                available = true
                                status = after
                                stats = observed
                            }
                        }
                    } catch (_: Exception) { /* Fixed unavailable label; never export Binder exception text. */ }
                    val saved = runtime.settings
                    val pending = runtime.pendingSettingsFlow.value
                    val health = runtime.health.value
                    val result = CallDiagnostics.format(CallDiagnostics.Snapshot(
                        version = BuildConfig.VERSION_NAME, sdk = Build.VERSION.SDK_INT, capturedAt = Instant.now().toString(),
                        savedMethod = saved.audioMethod, savedMute = saved.muteEarpiece,
                        pendingMethod = pending?.audioMethod, pendingMute = pending?.muteEarpiece,
                        callPresent = health.calls.count > 0 || health.session != null,
                        helperAvailable = available, tapActive = status.active,
                        backend = status.backend, tapStats = stats, logs = AppLog.tail.value,
                    ))
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        } catch (e: Exception) { continuation.resumeWithException(e) }
    }
}
