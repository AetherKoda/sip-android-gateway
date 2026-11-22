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
import io.github.sparkcyf.voicesipagent.privileged.bootstrap.LocalBootstrap
import io.github.sparkcyf.voicesipagent.privileged.bootstrap.BootstrapActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.sparkcyf.voicesipagent.telephony.DefaultSim
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku

class SipAccountActivity : DialerRoleActivity() {
    private val draft: SipSettingsDraft by viewModels()
    private val runtime get() = GatewayRuntime.get(this)
    private var phoneSetup by mutableStateOf(SetupState(false, false, false, false, false, requiresSms = false))
    private var shizukuRunning by mutableStateOf(false)
    private var shizukuGranted by mutableStateOf(false)
    private var serviceReady by mutableStateOf(false)
    private var message by mutableStateOf("")
    private val embedded get() = LocalBootstrap.supported()
    private val scanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::importQr)
    }
    private fun importQr(value: String) {
        if (runtime.health.value.calls.count > 0 || runtime.health.value.session != null) {
            message = getString(R.string.qr_busy); return
        }
        val account = SipQrAccount.parse(value)
        if (account == null) { message = getString(R.string.qr_invalid); return }
        draft.server = account.server; draft.domain = account.domain
        draft.user = account.username; draft.password = account.password
        draft.port = account.port.toString(); draft.transport = SipTransport.of(account.transport)!!
        draft.srtp = if (draft.transport == SipTransport.TLS) SrtpPolicy.MANDATORY else SrtpPolicy.OFF
        draft.target = ""; draft.prefixes = ""; draft.stack = SipStack.PJSUA2
        saveAndConnect()
    }
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
        serviceReady = embedded && LocalBootstrap.get(this).state == LocalBootstrap.State.CONNECTED
        shizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        shizukuGranted = shizukuRunning && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    private fun openShizuku() {
        if (embedded) {
            startActivity(Intent(this, BootstrapActivity::class.java)); return
        }
        runCatching {
            if (shizukuRunning) Shizuku.requestPermission(4201)
            else startActivity(packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/")))
        }.onFailure { message = getString(R.string.open_shizuku_error) }
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
        if ((!embedded && !shizukuGranted) || !phoneSetup.complete ||
            (embedded && LocalBootstrap.get(this).state != LocalBootstrap.State.CONNECTED)) {
            message = getString(R.string.saved_finish_setup)
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
        var qrText by remember { mutableStateOf("") }
        val callActive = health.calls.count > 0 || health.session != null
        LaunchedEffect(settings, pending) { draft.syncSaved(settings, pending) }
        LaunchedEffect(Unit) {
            while (true) {
                refreshSetup()
                delay(1_000)
            }
        }

        Text(getString(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Panel(getString(R.string.sip_account)) {
            OutlinedTextField(draft.server, { draft.server = it; draft.domain = it }, label = { Text(getString(R.string.sip_domain)) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft.user, { draft.user = it }, label = { Text(getString(R.string.sip_username)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft.password, { draft.password = it }, label = { Text(getString(R.string.sip_password)) },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft.port, { draft.port = it }, label = { Text(getString(R.string.sip_port)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f))
                Box(Modifier.weight(1f)) {
                    Choice(getString(R.string.sip_transport), draft.transport.name, SipTransport.entries.map { it to it.name }) { transport ->
                        if (draft.port == draft.transport.defaultPort.toString()) draft.port = transport.defaultPort.toString()
                        draft.transport = transport
                    }
                }
            }
            TextButton(onClick = { advanced = !advanced }) {
                Text(getString(if (advanced) R.string.hide_advanced else R.string.advanced))
            }
            if (advanced) {
                OutlinedTextField(draft.domain, { draft.domain = it }, label = { Text(getString(R.string.auth_domain)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.target, { draft.target = it }, label = { Text(getString(R.string.incoming_extension)) },
                    supportingText = { Text(getString(R.string.incoming_hint)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (draft.transport == SipTransport.TLS) {
                    Choice(getString(R.string.media_encryption), draft.srtp.name, SrtpPolicy.entries.map { it to it.name }) { draft.srtp = it }
                }
            }
            Button(onClick = ::saveAndConnect, enabled = !callActive && !health.settingsPending,
                modifier = Modifier.fillMaxWidth()) { Text(getString(R.string.sip_login)) }
            OutlinedButton(onClick = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.scan_prompt)).setBeepEnabled(false).setOrientationLocked(false)) },
                enabled = !callActive && !health.settingsPending, modifier = Modifier.fillMaxWidth()) {
                Text(getString(R.string.scan_login))
            }
            if (advanced) {
                OutlinedTextField(qrText, { qrText = it }, label = { Text(getString(R.string.paste_qr)) },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { val value = qrText; qrText = ""; importQr(value) }, enabled = !callActive && qrText.isNotBlank()) {
                    Text(getString(R.string.import_login))
                }
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodyMedium)
        }

        Panel(getString(R.string.connection)) {
            StatusLine(getString(R.string.call_service), health.shizuku == ShizukuConnector.State.CONNECTED || serviceReady)
            if (embedded || !shizukuGranted) OutlinedButton(onClick = ::openShizuku) {
                Text(getString(if (embedded) R.string.pair_start else if (shizukuRunning) R.string.authorize_shizuku else R.string.open_shizuku))
            }
            StatusLine(getString(R.string.phone_setup), phoneSetup.complete)
            if (!phoneSetup.complete) OutlinedButton(onClick = ::requestPhoneSetup) {
                Text(when (phoneSetup.next) {
                    SetupStep.NOTIFICATIONS -> getString(R.string.allow_notifications)
                    SetupStep.PHONE -> getString(R.string.allow_phone)
                    SetupStep.DIALER -> getString(R.string.default_phone)
                    else -> getString(R.string.phone_setup)
                })
            }
            StatusLine(getString(R.string.sip_registered), health.sip.ready)
            if (settings.takeover && !health.sip.ready) Text(health.sip.detail)
            if (settings.takeover) TextButton(onClick = { runtime.setTakeover(false) }) { Text(getString(R.string.disconnect)) }
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
