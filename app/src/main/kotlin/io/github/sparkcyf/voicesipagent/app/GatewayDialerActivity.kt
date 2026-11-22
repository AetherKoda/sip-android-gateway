package io.github.sparkcyf.voicesipagent.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector

/** Safe to display over the lockscreen: no configuration, caller numbers, or diagnostic logs. */
class GatewayDialerActivity : DialerRoleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = GatewayRuntime.get(this)
        setContent {
            val health by runtime.health.collectAsState()
            val settings by runtime.settingsFlow.collectAsState()
            val message by roleMessage.collectAsState()
            LaunchedEffect(health.holdsDialer, health.calls) {
                setShowWhenLocked(health.holdsDialer && health.calls.count > 0)
                setTurnScreenOn(health.holdsDialer && health.calls.incoming)
            }
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Scaffold { inset ->
                    Column(Modifier.fillMaxSize().padding(inset).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("YX 通訊", style = MaterialTheme.typography.headlineSmall)
                        Text(when { health.calls.incoming -> "來電中"; health.calls.count > 0 -> "通話進行中"; else -> "待命中" })
                        Text(if (health.session == null) "目前沒有通話連線" else "通話連線進行中")
                        // Avoid putting server names or low-level failure details on a locked screen.
                        Text(when {
                            health.takeoverOffPending -> "目前通話結束後將停止連線。"
                            health.takeoverOnPending -> "目前通話結束後將啟用連線。"
                            health.dialerChangePending -> "等待目前通話結束後切換電話 APP。"
                            !health.takeover -> "通話連線已停止，請切回原電話 APP 處理一般通話。"
                            health.cellularReady -> "手機已準備好轉接通話。"
                            else -> "目前無法轉接，請開啟設定或切回原電話 APP。"
                        })
                        Button(onClick = { setTakeoverFromUi(!settings.takeover) }, enabled = !health.dialerChangeRunning) {
                            Text(if (settings.takeover) "停止通話連線" else "啟用通話連線")
                        }
                        Text("如需手動撥號，請在系統設定切回原電話 APP。")
                        if (health.holdsDialer && settings.takeover && health.shizuku == ShizukuConnector.State.CONNECTED) {
                            Button(onClick = ::switchDialer, enabled = !health.dialerChangeRunning) { Text("還原原電話 APP") }
                        }
                        OutlinedButton(onClick = ::chooseDialerInSettings, enabled = !health.dialerChangeRunning) { Text("選擇預設電話 APP") }
                        if (health.dialerChangePending) OutlinedButton(onClick = ::cancelDialerChange) { Text("取消切換電話 APP") }
                        if (!health.holdsDialer && health.dialerRoleAvailable) {
                            OutlinedButton(onClick = { setTakeoverFromUi(true) }, enabled = !health.dialerChangeRunning) { Text("啟用通話連線") }
                        }
                        OutlinedButton(onClick = ::openConfiguration) { Text("開啟設定") }
                        if (message.isNotEmpty()) Text(message)
                    }
                }
            }
        }
    }
}
