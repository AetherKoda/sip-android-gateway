package io.github.sparkcyf.voicesipagent.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.sparkcyf.voicesipagent.pjsua2.SipTransport
import io.github.sparkcyf.voicesipagent.pjsua2.SrtpPolicy
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector
import io.github.sparkcyf.voicesipagent.telephony.DefaultSim
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku

class SipAccountActivity : DialerRoleActivity() {
    private val draft: SipSettingsDraft by viewModels()
    private val runtime get() = GatewayRuntime.get(this)
    private var phoneSetup by mutableStateOf(SetupState(false, false, false, false, false, requiresSms = false))
    private var shizukuRunning by mutableStateOf(false)
    private var shizukuGranted by mutableStateOf(false)
    private var message by mutableStateOf("")
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshSetup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Scaffold { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { AccountScreen() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        GatewayService.start(this)
    }

    private fun refreshSetup() {
        phoneSetup = GatewayAccess(this).setup()
        shizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        shizukuGranted = shizukuRunning && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    private fun openShizuku() {
        runCatching {
            if (shizukuRunning) Shizuku.requestPermission(4201)
            else startActivity(packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/")))
        }.onFailure { message = "Open Shizuku and grant access to SIP Communications." }
    }

    private fun requestPhoneSetup() {
        refreshSetup()
        when (phoneSetup.next) {
            SetupStep.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    permissionRequest.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                } else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
            SetupStep.PHONE -> permissionRequest.launch(GatewayAccess.PHONE_PERMISSIONS)
            SetupStep.DIALER -> requestDialer()
            else -> Unit
        }
    }

    private fun saveAndConnect() {
        val error = draft.manualError
        if (error != null) {
            message = error
            return
        }
        runtime.applySettings(draft.manualSettingsUpdate())
        refreshSetup()
        if (!shizukuGranted || !phoneSetup.complete) {
            message = "SIP settings saved. Finish phone setup, then select Save and connect again."
            return
        }
        message = ""
        runtime.setTakeover(true)
        runtime.afterQueued {
            runOnUiThread { if (!isFinishing && !isDestroyed) GatewayService.start(this) }
        }
    }

    @Composable
    private fun AccountScreen() {
        val settings by runtime.settingsFlow.collectAsState()
        val pending by runtime.pendingSettingsFlow.collectAsState()
        val health by runtime.health.collectAsState()
        var advanced by remember { mutableStateOf(false) }
        val callActive = health.calls.count > 0 || health.session != null
        LaunchedEffect(settings, pending) { draft.syncSaved(settings, pending) }
        LaunchedEffect(Unit) {
            while (true) {
                refreshSetup()
                delay(1_000)
            }
        }

        Text(getString(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Panel("SIP account") {
            OutlinedTextField(draft.server, { draft.server = it }, label = { Text("SIP server") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft.user, { draft.user = it }, label = { Text("SIP username") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft.password, { draft.password = it }, label = { Text("SIP password") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft.port, { draft.port = it }, label = { Text("Port") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f))
                Box(Modifier.weight(1f)) {
                    Choice("Transport", draft.transport.name, SipTransport.entries.map { it to it.name }) { transport ->
                        if (draft.port == draft.transport.defaultPort.toString()) draft.port = transport.defaultPort.toString()
                        draft.transport = transport
                    }
                }
            }
            TextButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "Hide advanced settings" else "Advanced settings")
            }
            if (advanced) {
                OutlinedTextField(draft.domain, { draft.domain = it }, label = { Text("SIP domain (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.target, { draft.target = it }, label = { Text("Incoming SIP extension (optional)") },
                    supportingText = { Text("Incoming cellular calls are rejected while the gateway is active if this is blank.") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (draft.transport == SipTransport.TLS) {
                    Choice("Media encryption", draft.srtp.name, SrtpPolicy.entries.map { it to it.name }) { draft.srtp = it }
                }
            }
            Button(onClick = ::saveAndConnect, enabled = !callActive && !health.settingsPending,
                modifier = Modifier.fillMaxWidth()) { Text("Save and connect") }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodyMedium)
        }

        Panel("Connection") {
            StatusLine("Shizuku", health.shizuku == ShizukuConnector.State.CONNECTED)
            if (!shizukuGranted) OutlinedButton(onClick = ::openShizuku) {
                Text(if (shizukuRunning) "Authorize Shizuku" else "Open Shizuku")
            }
            StatusLine("Android phone setup", phoneSetup.complete)
            if (!phoneSetup.complete) OutlinedButton(onClick = ::requestPhoneSetup) {
                Text(when (phoneSetup.next) {
                    SetupStep.NOTIFICATIONS -> "Allow notifications"
                    SetupStep.PHONE -> "Allow phone access"
                    SetupStep.DIALER -> "Set default phone app"
                    else -> "Finish phone setup"
                })
            }
            StatusLine("SIP registered", health.sip.ready)
            if (settings.takeover && !health.sip.ready) Text(health.sip.detail)
            if (settings.takeover) TextButton(onClick = { runtime.setTakeover(false) }) { Text("Disconnect gateway") }
        }

        if (advanced) Panel("Call settings") {
            val simOptions = listOf(DefaultSim.SYSTEM to "System default") +
                health.sims.map { it.slot to "SIM ${it.slot + 1} ${it.carrier}" }
            Choice("Outbound SIM", simOptions.firstOrNull { it.first == settings.defaultSimSlot }?.second ?: "Unavailable",
                simOptions, runtime::setDefaultSimSlot)
            OutlinedButton(onClick = { runtime.applySettings { it.copy(audioMethod = AudioMethod.LEGACY, muteEarpiece = false) } },
                enabled = !callActive && (settings.audioMethod != AudioMethod.LEGACY || settings.muteEarpiece)) {
                Text("Use direct telephony audio")
            }
        }
    }
}

@Composable
private fun <T> Choice(label: String, selected: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: $selected")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((value, title) in options) DropdownMenuItem(text = { Text(title) }, onClick = {
                expanded = false
                onSelect(value)
            })
        }
    }
}
