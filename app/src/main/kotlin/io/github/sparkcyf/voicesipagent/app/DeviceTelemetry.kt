package io.github.sparkcyf.voicesipagent.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector
import io.github.sparkcyf.voicesipagent.privileged.HealthKeys
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.time.Instant

/** An explicit schema, with unavailable readings kept null; no logs, audio, IP, SSID, IMEI or IMSI. */
internal object DeviceTelemetry {
    private fun obj(vararg fields: Pair<String, Any?>) = JSONObject().apply {
        fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }
    @SuppressLint("MissingPermission", "HardwareIds") // Each restricted read is permission-checked and independently guarded.
    @Suppress("DEPRECATION")
    fun capture(context: Context, runtime: GatewayRuntime): JSONObject {
        fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val setup = GatewayAccess(context).setup()
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 }
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)?.takeIf { it > 0 }
        val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
        val batteryStatus = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val permissions = obj("notifications" to setup.notifications, "dialer_role" to setup.dialer, "sms_role" to setup.smsRole)
        mapOf("call_phone" to Manifest.permission.CALL_PHONE, "read_phone_state" to Manifest.permission.READ_PHONE_STATE,
            "read_phone_numbers" to Manifest.permission.READ_PHONE_NUMBERS, "read_sms" to Manifest.permission.READ_SMS,
            "receive_sms" to Manifest.permission.RECEIVE_SMS, "send_sms" to Manifest.permission.SEND_SMS,
            "receive_mms" to Manifest.permission.RECEIVE_MMS, "receive_wap_push" to Manifest.permission.RECEIVE_WAP_PUSH
        ).forEach { (key, value) -> permissions.put(key, granted(value)) }
        val binder = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val shizukuPermission = if (binder) runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrNull() else null
        val slots = JSONArray()
        var simStatus = if (granted(Manifest.permission.READ_PHONE_STATE)) "unavailable" else "permission_denied"
        if (simStatus != "permission_denied") try {
            val sm = context.getSystemService(SubscriptionManager::class.java)
            val tm = context.getSystemService(TelephonyManager::class.java)
            val active = sm.activeSubscriptionInfoList
            if (active != null) {
                simStatus = "ok"
                active.filter { it.simSlotIndex in 0..1 }.take(2).forEach { sub ->
                    val phone = tm.createForSubscriptionId(sub.subscriptionId)
                    val number = if (granted(Manifest.permission.READ_PHONE_NUMBERS)) runCatching {
                        if (Build.VERSION.SDK_INT >= 33) sm.getPhoneNumber(sub.subscriptionId) else phone.line1Number
                    }.getOrNull()?.takeIf { it.isNotBlank() } else null
                    val iccid = runCatching { phone.simSerialNumber }.getOrNull()?.takeIf { it.isNotBlank() }
                    val signal = runCatching { phone.signalStrength }.getOrNull()
                    val dbm = signal?.cellSignalStrengths?.map { it.dbm }?.filter { it in -160..-1 }?.maxOrNull()
                    val registration = runCatching { phone.serviceState?.state }.getOrNull()
                    slots.put(obj("slot" to sub.simSlotIndex, "carrier" to (sub.carrierName?.toString()?.take(128) ?: ""),
                        "number" to number?.take(64), "iccid" to iccid?.take(32),
                        "signal_dbm" to dbm, "signal_level" to signal?.level?.takeIf { it in 0..4 },
                        "registration" to when (registration) { 0 -> "in_service"; 1 -> "out_of_service"; 2 -> "emergency_only"; 3 -> "power_off"; else -> "unknown" }))
                }
            }
        } catch (_: SecurityException) { simStatus = "permission_denied" }
        catch (_: Exception) { simStatus = "unavailable" }

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = runCatching { cm.activeNetwork }.getOrNull()
        val caps = runCatching { network?.let(cm::getNetworkCapabilities) }.getOrNull()
        val transport = when {
            network == null -> "none"
            caps == null -> "unknown"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val wifi = if (transport == "wifi") runCatching { context.getSystemService(WifiManager::class.java).connectionInfo }.getOrNull() else null
        val h = runtime.health.value
        val audio = runtime.callAudioStatus.value
        val settings = runtime.settings
        return obj("observed_at" to Instant.now().toString(), "app_version" to BuildConfig.VERSION_NAME,
            "app_version_code" to BuildConfig.VERSION_CODE, "android_api" to Build.VERSION.SDK_INT,
            "android_release" to Build.VERSION.RELEASE.take(64), "manufacturer" to Build.MANUFACTURER.take(128), "model" to Build.MODEL.take(128),
            "battery" to obj("percent" to if (level != null && scale != null) (level * 100 / scale).coerceIn(0, 100) else null,
                "temperature_c" to temperature?.let { it / 10.0 }, "charging" to batteryStatus?.takeIf { it in 2..5 }?.let {
                    it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL }),
            "permissions" to permissions, "shizuku" to obj("binder_available" to binder, "permission_granted" to shizukuPermission,
                "helper_connected" to (h.shizuku == ShizukuConnector.State.CONNECTED)),
            "gateway" to obj("running" to h.gatewayRunning, "sip_registered" to h.sip.ready,
                "audio_ready" to h.audioReady, "doze_allowlisted" to h.privileged?.get(HealthKeys.DOZE_ALLOWLISTED),
                "cellular_ready" to h.cellularReady, "incoming_mode" to settings.incomingMode,
                "pending_incoming_mode" to runtime.pendingSettingsFlow.value?.incomingMode,
                "audio_method" to settings.audioMethod, "mute_requested" to settings.muteEarpiece,
                "audio_backend" to audio.backend.takeIf { it in setOf("direct", "redirection-helper", "redirection-manual") },
                "tap_active" to audio.active, "local_silence" to "unsupported_or_unverified"),
            "recent_call_quality" to CallDiagnostics.latestMetrics(AppLog.tail.value),
            "sims" to obj("read_status" to simStatus, "default_slot" to h.systemSimSlot?.takeIf { it in 0..1 },
                "outbound_slot" to when (settings.defaultSimSlot) {
                    io.github.sparkcyf.voicesipagent.telephony.DefaultSim.SYSTEM -> h.systemSimSlot?.takeIf { it in 0..1 }
                    0, 1 -> settings.defaultSimSlot
                    else -> null
                }, "items" to slots),
            "network" to obj("connected" to if (network == null) false else caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                "validated" to caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), "transport" to transport,
                "wifi_rssi_dbm" to wifi?.rssi?.takeIf { it in -126..-1 }, "wifi_link_mbps" to wifi?.linkSpeed?.takeIf { it > 0 }))
    }
}
