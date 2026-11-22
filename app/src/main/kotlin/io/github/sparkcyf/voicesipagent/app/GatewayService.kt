package io.github.sparkcyf.voicesipagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Keeps the process and SIP registration alive while takeover is on, with a status notification.
 * When takeover is off, keeps a reminder visible while this app still holds the dialer role.
 */
class GatewayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statusJob: Job? = null
    private var sessionJob: Job? = null
    private var expiryJob: Job? = null
    private var foreground = false
    private val runtimeToken = Any()
    private val roleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            GatewayRuntime.get(this@GatewayService).refreshCallAccess()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val runtime = GatewayRuntime.get(this)
        runtime.start()
        runtime.onServiceCreated(runtimeToken)
        ContextCompat.registerReceiver(this, roleReceiver,
            IntentFilter(TelecomManager.ACTION_DEFAULT_DIALER_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        runtime.refreshCallAccess()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = GatewayRuntime.get(this)
        if (!GatewayAccess(this).allowed() || !runtime.health.value.needsForegroundService) {
            stopGateway(startId)
            return START_NOT_STICKY
        }
        // Recheck at each start, including sticky restarts and rapid off/on transitions.
        if (!foreground) {
            try {
                promote(runtime.health.value)
            } catch (e: Exception) {
                AppLog.w("could not promote gateway service", e)
                stopGateway(startId)
                return START_NOT_STICKY
            }
        }
        runtime.onForegroundStarted(runtimeToken)
        if (expiryJob == null) expiryJob = scope.launch {
            while (isActive) {
                if (!GatewayAccess(this@GatewayService).allowed()) {
                    if (BuildConfig.PLATFORM_MANAGED && SessionStore(this@GatewayService).load() == null)
                        runtime.clearPlatformSession()
                    stopGateway()
                    break
                }
                delay(1_000)
            }
        }
        if (BuildConfig.PLATFORM_MANAGED && sessionJob == null) sessionJob = scope.launch {
            val store = SessionStore(this@GatewayService)
            while (isActive) {
                val session = store.load() ?: break
                DeviceSyncService.schedule(this@GatewayService)
                try {
                    val result = PlatformClient().refresh(session)
                    if (store.renew(session.token, result.session) != null)
                        runtime.applySettings { if (store.load()?.token == session.token) result.sip.applyTo(it) else it }
                } catch (e: PlatformException) {
                    if ((e.status == 401 || e.status == 403) && store.clearIfCurrent(session.token)) {
                        runtime.clearPlatformSession()
                        stopGateway()
                        break
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) { /* Keep the bounded local lease during a network outage. */ }
                delay(60_000)
            }
        }
        if (statusJob == null) {
            val nm = getSystemService(NotificationManager::class.java)
            statusJob = scope.launch {
                runtime.health.map { it.needsForegroundService to summary(it) }.distinctUntilChanged().collect { (needed, text) ->
                    if (needed) nm.notify(NOTIFICATION_ID, build(text)) else stopGateway()
                }
            }
        }
        return START_STICKY
    }

    private fun promote(health: HealthState) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, if (BuildConfig.PLATFORM_MANAGED) "通話狀態" else "Call status", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, build(summary(health)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, build(summary(health)))
        }
        foreground = true
    }

    private fun stopGateway(startId: Int? = null) {
        expiryJob?.cancel()
        expiryJob = null
        sessionJob?.cancel()
        sessionJob = null
        statusJob?.cancel()
        statusJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        if (startId == null) stopSelf() else stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        GatewayRuntime.get(this).onForegroundStopped(runtimeToken)
        unregisterReceiver(roleReceiver)
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_gateway)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "gateway"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            if (!GatewayAccess(context).allowed()) return
            val runtime = GatewayRuntime.get(context)
            runtime.start()
            sync(context, runtime.health.value)
        }

        internal fun summary(h: HealthState): String = if (BuildConfig.PLATFORM_MANAGED) managedSummary(h) else when {
            h.session != null -> "Call in progress"
            !h.takeover && h.holdsDialer -> "Gateway off; switch dialers in Android settings if needed"
            !h.takeover -> "Gateway off"
            h.loopbackTest -> "Loopback test"
            h.shizuku == ShizukuConnector.State.SHIZUKU_NOT_RUNNING -> "Start Shizuku, then tap here to reconnect"
            !h.privilegedReady -> "Waiting for Shizuku"
            !h.sip.ready -> "Connecting to the SIP server"
            else -> "SIP registered"
        }

        private fun managedSummary(h: HealthState): String = when {
            h.session != null -> "通話中"
            !h.takeover && h.holdsDialer -> "連線已停止，仍是預設電話 APP；可在系統設定切換。"
            !h.takeover -> "通話連線已停止"
            h.loopbackTest -> "回音測試模式"
            // Shizuku pushes its binder to client apps on uid/activity transitions. A restarted
            // server may skip a running app, so opening the activity is the reconnect path.
            h.shizuku == ShizukuConnector.State.SHIZUKU_NOT_RUNNING -> "Shizuku 未啟動。啟動後點此重新連線。"
            !h.privilegedReady -> "等待 Shizuku 授權與初始化"
            !h.sip.ready -> "正在連接通話伺服器"
            else -> "通話連線已就緒"
        }

        /** Used for both explicit launches and runtime switch/role changes. */
        internal fun sync(context: Context, health: HealthState): Boolean {
            if (!GatewayAccess(context).allowed() || !health.needsForegroundService) {
                context.stopService(Intent(context, GatewayService::class.java))
                return true
            }
            try {
                context.startForegroundService(Intent(context, GatewayService::class.java))
                return true
            } catch (e: Exception) {
                // Background-start restrictions; the activity or boot receiver will retry.
                AppLog.w("could not start foreground service", e)
                return false
            }
        }
    }
}
