package io.github.sparkcyf.voicesipagent.app

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class SetupState(val notifications: Boolean, val phone: Boolean, val dialer: Boolean,
    val sms: Boolean, val smsRole: Boolean, val requiresSms: Boolean = true) {
    val complete get() = next == null
    val next: SetupStep? get() = when {
        !notifications -> SetupStep.NOTIFICATIONS
        !phone -> SetupStep.PHONE
        !dialer -> SetupStep.DIALER
        requiresSms && !smsRole -> SetupStep.SMS_ROLE // Android restricted SMS grants require the default role first.
        requiresSms && !sms -> SetupStep.SMS
        else -> null
    }
}
enum class SetupStep(val label: String) {
    NOTIFICATIONS("允許通知"), PHONE("允許電話"), DIALER("設為預設電話 APP"),
    SMS("允許簡訊"), SMS_ROLE("設為預設簡訊 APP")
}

internal fun canUseGateway(setup: SetupState, platformManaged: Boolean, hasPlatformSession: Boolean): Boolean =
    setup.complete && (!platformManaged || hasPlatformSession)

class GatewayAccess(private val context: Context) {
    fun allowed(): Boolean {
        val managed = BuildConfig.PLATFORM_MANAGED
        return canUseGateway(setup(), managed, managed && SessionStore(context).load() != null)
    }
    fun setup(): SetupState {
        fun granted(vararg permissions: String) = permissions.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        val roles = context.getSystemService(RoleManager::class.java)
        return SetupState(
            (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) &&
                context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
            granted(*PHONE_PERMISSIONS), roles.isRoleHeld(RoleManager.ROLE_DIALER),
            BuildConfig.PLATFORM_MANAGED && granted(*SMS_PERMISSIONS),
            BuildConfig.PLATFORM_MANAGED && roles.isRoleHeld(RoleManager.ROLE_SMS),
            requiresSms = BuildConfig.PLATFORM_MANAGED,
        )
    }
    companion object {
        val PHONE_PERMISSIONS = arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS)
        val SMS_PERMISSIONS = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_MMS, Manifest.permission.RECEIVE_WAP_PUSH)
    }
}
