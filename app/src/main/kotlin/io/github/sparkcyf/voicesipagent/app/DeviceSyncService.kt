package io.github.sparkcyf.voicesipagent.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** OS-scheduled durable retry survives process death; independent of call/audio workers. */
class DeviceSyncService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        jobs[params.jobId] = scope.launch {
            var retry = false
            try { syncLock.withLock { retry = !synchronizeDevice() } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { retry = true } // No payload or exception details in logs.
            finally { withContext(NonCancellable + Dispatchers.Main) { jobs.remove(params.jobId); jobFinished(params, retry) } }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { jobs.remove(params.jobId)?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private suspend fun synchronizeDevice(): Boolean {
        val sessions = SessionStore(this)
        val session = sessions.load() ?: return true
        val owner = SmsOwner(session.username, session.lineId, sessions.deviceId())
        val client = PlatformClient()
        fun current() = sessions.load()?.token == session.token
        SmsSupport.recoverLegacyJournal(this)
        val smsComplete = try {
            SmsOutbox(this).use { outbox ->
                SmsDeliveryEngine.drain(owner, outbox, ::current) { id ->
                    runCatching { outbox.ensureProviderCopy(id) }
                    client.uploadSms(session, outbox.payload(id))
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false } // SMS retries must not prevent diagnostic telemetry.
        if (current()) client.telemetry(session, DeviceTelemetry.capture(this, GatewayRuntime.get(this)))
        return smsComplete
    }
    companion object {
        private const val IMMEDIATE = 2101
        private const val PERIODIC = 2102
        private val syncLock = Mutex()
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val component = ComponentName(context, DeviceSyncService::class.java)
            fun job(id: Int) = JobInfo.Builder(id, component).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true).setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            if (scheduler.getPendingJob(PERIODIC) == null) scheduler.schedule(job(PERIODIC).setPeriodic(15 * 60_000L).build())
            if (scheduler.getPendingJob(IMMEDIATE) == null) scheduler.schedule(job(IMMEDIATE).setMinimumLatency(1_000).build())
        }
    }
}
