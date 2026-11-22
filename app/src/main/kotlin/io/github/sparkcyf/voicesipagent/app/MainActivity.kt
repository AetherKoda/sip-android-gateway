package io.github.sparkcyf.voicesipagent.app

import android.Manifest
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.HealthKeys
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector
import io.github.sparkcyf.voicesipagent.telephony.DefaultSim
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import rikka.shizuku.Shizuku

class MainActivity : DialerRoleActivity() {
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: SessionStore
    private val runtime get() = GatewayRuntime.get(this)
    private var session by mutableStateOf<PlatformSession?>(null)
    private var setup by mutableStateOf(SetupState(false, false, false, false, false))
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var configError by mutableStateOf("")
    private var reconnecting by mutableStateOf(false)
    private var copyingDiagnostics by mutableStateOf(false)
    private var reconnectJob: Job? = null
    private var shizukuRunning by mutableStateOf(false)
    private var shizukuGranted by mutableStateOf(false)
    private var settingsOpen by mutableStateOf(false)
    private val attempted = mutableSetOf<SetupStep>()
    private val detailsOpened = mutableSetOf<SetupStep>()
    private var pendingStep by mutableStateOf<SetupStep?>(null)
    private var returningFromDetails = false
    private var configJob: Job? = null
    private var firstLaunch by mutableStateOf(true)
    private var smsWarning by mutableStateOf(false)
    private var smsWarningAcknowledged = false
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val step = pendingStep
        pendingStep = null
        refreshSetup()
        if (step != null && setup.next == step) message = "尚未完成${step.label}，請點選下方按鈕重新設定。"
        else advanceSetup()
    }
    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val step = pendingStep
        pendingStep = null
        refreshSetup()
        if (step != null && setup.next == step) {
            message = "尚未設為預設 APP。若系統顯示「受限設定」，請在應用程式資訊的選單允許受限設定後返回。"
            if (detailsOpened.add(step)) {
                returningFromDetails = true
                openAppDetails()
            }
        } else advanceSetup()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.PLATFORM_MANAGED) {
            startActivity(Intent(this, SipAccountActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
            return
        }
        store = SessionStore(this)
        session = store.load()
        firstLaunch = !getPreferences(MODE_PRIVATE).getBoolean("intro_read", false)
        refreshSetup()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Scaffold { insets ->
                    val compactSettings = session != null && settingsOpen
                    Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(if (compactSettings) 16.dp else 22.dp),
                        verticalArrangement = Arrangement.spacedBy(if (compactSettings) 8.dp else 16.dp)) {
                        Text("YX 通訊", style = MaterialTheme.typography.headlineLarge)
                        Text(if (session == null) "登入你的通訊線路" else "線路與裝置狀態")
                        if (session == null) LoginScreen() else if (settingsOpen) SettingsScreen() else Dashboard()
                        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
                        if (configError.isNotBlank()) Text(configError, color = MaterialTheme.colorScheme.error)
                    }
                    if (smsWarning) AlertDialog(onDismissRequest = { smsWarning = false }, title = { Text("預設簡訊 APP 說明") },
                        text = { Text("此版本支援文字 SMS，尚不支援 MMS 附件。設為預設簡訊 APP 後，MMS 附件可能無法接收；保留原始通知不代表能恢復。若你需要 MMS，請取消並等待完整版本。") },
                        confirmButton = { TextButton(onClick = { smsWarning = false; smsWarningAcknowledged = true; advanceSetup(true) }) { Text("了解，繼續設定") } },
                        dismissButton = { TextButton(onClick = { smsWarning = false }) { Text("取消") } })
                }
            }
        }
        jobs.launch {
            while (isActive) {
                refreshShizuku()
                if (session != null) {
                    val persisted = store.load()
                    if (persisted == null) localLogout("登入已失效，請重新登入。")
                    else session = persisted // The service may have renewed the same token while this UI was paused.
                }
                delay(1_000)
            }
        }
    }
    override fun onResume() {
        super.onResume()
        if (!::store.isInitialized) return
        refreshShizuku()
        refreshSetup()
        if (session != null) {
            if (returningFromDetails) {
                returningFromDetails = false
                setup.next?.let { attempted.remove(it) }
                window.decorView.post { advanceSetup() }
            } else if (!setup.complete && pendingStep == null) window.decorView.post { advanceSetup() }
            refreshConfig()
        }
    }
    override fun onDestroy() { jobs.cancel(); super.onDestroy() }
    private fun refreshShizuku() {
        shizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        shizukuGranted = shizukuRunning && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    }
    private fun shizukuAction() {
        if (!shizukuRunning) {
            val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/"))
            runCatching { startActivity(launch) }.onFailure { message = "請先安裝並開啟 Shizuku。" }
        } else if (!shizukuGranted) {
            runCatching { Shizuku.requestPermission(4201) }.onFailure { message = "請開啟 Shizuku，允許 YX 通訊使用。" }
        }
    }
    private fun refreshSetup() { setup = GatewayAccess(this).setup() }
    private fun openAppDetails() {
        returningFromDetails = true
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            .onFailure { message = "請開啟系統設定 → 應用程式 → YX 通訊，允許權限／受限設定。" }
    }
    private fun advanceSetup(explicit: Boolean = false) {
        if (session == null || busy || pendingStep != null) return
        refreshSetup()
        val step = setup.next
        if (step == null) { runtime.setTakeover(true); GatewayService.start(this); return }
        if (step == SetupStep.SMS_ROLE && !smsWarningAcknowledged) { smsWarning = true; return }
        if (!explicit && step in attempted) return
        attempted += step
        pendingStep = step
        val role = getSystemService(RoleManager::class.java)
        try {
            when (step) {
                SetupStep.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permissionRequest.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                } else {
                    pendingStep = null; returningFromDetails = true
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                }
                SetupStep.PHONE -> permissionRequest.launch(GatewayAccess.PHONE_PERMISSIONS)
                SetupStep.SMS -> permissionRequest.launch(GatewayAccess.SMS_PERMISSIONS)
                SetupStep.DIALER, SetupStep.SMS_ROLE -> {
                    val name = if (step == SetupStep.DIALER) RoleManager.ROLE_DIALER else RoleManager.ROLE_SMS
                    if (!role.isRoleAvailable(name)) { pendingStep = null; message = "此裝置無法設定${step.label}，請聯絡管理員。" }
                    else {
                        if (step == SetupStep.DIALER) runtime.rememberDialerForSystemRequest()
                        roleRequest.launch(role.createRequestRoleIntent(name))
                    }
                }
            }
        } catch (_: Exception) { pendingStep = null; message = "無法開啟系統設定，請從應用程式資訊手動完成。" }
    }
    private fun login(username: String, password: String) {
        if (busy) return
        if (username.isBlank() || password.isEmpty()) { message = "請填寫帳號與密碼。"; return }
        busy = true; message = ""; configError = ""
        jobs.launch {
            var candidate: LoginResult? = null
            try {
                val result = PlatformClient().login(username.trim(), password, store.deviceId())
                candidate = result
                withContext(Dispatchers.IO) { runtime.provisionPlatform(result.sip).get(15, java.util.concurrent.TimeUnit.SECONDS) }
                store.save(result.session)
                session = result.session
                DeviceSyncService.schedule(this@MainActivity)
                attempted.clear()
                detailsOpened.clear()
                message = "登入成功，請完成手機設定。"
            } catch (e: Exception) {
                store.clear()
                runtime.clearPlatformSession()
                candidate?.let { failed -> withContext(NonCancellable) { runCatching { PlatformClient().logout(failed.session) } } }
                if (e is CancellationException) throw e
                message = friendlyError(e)
            }
            finally { busy = false }
            if (session != null) advanceSetup()
        }
    }
    private fun refreshConfig() {
        val current = session ?: return
        if (configJob?.isActive == true) return
        configJob = jobs.launch {
            try {
                val result = PlatformClient().refresh(current)
                if (session?.token != current.token) return@launch
                val renewed = store.renew(current.token, result.session) ?: return@launch
                session = renewed
                runtime.applySettings { if (store.load()?.token == current.token) result.sip.applyTo(it) else it }
                configError = ""
                if (setup.complete) GatewayService.start(this@MainActivity)
            } catch (e: PlatformException) {
                if (session?.token != current.token) return@launch
                if (e.status == 401 || e.status == 403) localLogout("登入狀態已失效，請重新登入。", current.token)
                else configError = "暫時無法更新設定（${e.status}）。請稍後返回 APP 重試。"
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (session?.token == current.token) configError = "暫時無法更新設定，請檢查網路後返回 APP 重試。"
            }
        }
    }
    private fun logout() {
        val current = session ?: return
        localLogout("已登出，通話連線已停止。若要使用一般電話或簡訊，請在系統設定切回原 APP。")
        jobs.launch { runCatching { PlatformClient().logout(current) } }
    }
    private fun copyCallDiagnostics() {
        if (copyingDiagnostics) return
        val current = session ?: return
        copyingDiagnostics = true
        jobs.launch {
            try {
                val report = withTimeout(4_000) { CallDiagnosticReader.read(runtime) }
                if (session?.token != current.token || store.load()?.token != current.token) return@launch
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("YX 通話診斷", report))
                message = "已複製通話診斷，可貼給管理員。峰值是累計值，請註明本次哪一方聽不到聲音。"
            } catch (_: TimeoutCancellationException) {
                if (session?.token == current.token) message = "音訊服務暫未回應，未複製診斷；通話不受此操作中斷。"
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (session?.token == current.token) message = "暫時無法取得或複製診斷，請稍後重試。"
            } finally { copyingDiagnostics = false }
        }
    }
    private fun reconnect() {
        if (reconnecting) return
        val current = session ?: return
        refreshSetup()
        val health = runtime.health.value
        if (health.calls.count > 0 || health.session != null) { message = "請先結束通話再重連。"; return }
        if (!current.valid() || !setup.complete) { message = "請先完成登入與手機設定。"; return }
        if (!runtime.settings.takeover) { message = "請先在設定啟用通話連線。"; return }
        if (health.shizuku != ShizukuConnector.State.CONNECTED) { message = "請先啟動並授權 Shizuku，再重連。"; return }
        reconnecting = true
        message = "正在重新檢查並連線，請稍候。"
        reconnectJob = jobs.launch {
            try {
                val checkpoint = CompletableDeferred<Unit>()
                GatewayService.start(this@MainActivity)
                runtime.runSetup()
                runtime.restartSip()
                runtime.afterQueued { checkpoint.complete(Unit) }
                val connected = withTimeoutOrNull(45_000) {
                    checkpoint.await() // Dispatch is not a successful registration.
                    runtime.health.first { h ->
                        h.calls.count > 0 || h.session != null ||
                            (h.takeover && h.gatewayRunning && !h.settingsPending && h.sip.ready && h.audioReady &&
                                h.shizuku == ShizukuConnector.State.CONNECTED &&
                                h.privileged?.get(HealthKeys.DOZE_ALLOWLISTED) == true)
                    }
                }
                if (session?.token == current.token) message = when {
                    connected == null -> "尚未確認重連完成，請查看四個狀態燈，確認 Shizuku 與網路後再試。"
                    connected.calls.count > 0 || connected.session != null -> "目前有通話，請待通話結束後查看連線狀態。"
                    else -> "連線條件已備妥，請再測試通話。"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (session?.token == current.token) message = "無法完成重連要求，請查看連線狀態後重試。"
            } finally { reconnecting = false }
        }
    }
    private fun localLogout(reason: String, expectedToken: String? = session?.token) {
        val cleared = expectedToken?.let { store.clearIfCurrent(it) } == true
        if (!cleared) store.load()?.let { session = it; return }
        session = null; settingsOpen = false; configError = ""
        configJob?.cancel()
        reconnectJob?.cancel()
        runtime.clearPlatformSession()
        stopService(Intent(this, GatewayService::class.java))
        message = reason
    }
    @Composable private fun ShizukuStatusPanel() {
        Panel("Shizuku") {
            StatusLine(when { !shizukuRunning -> "尚未連接 Shizuku"; !shizukuGranted -> "[待授權] Shizuku"; else -> "完成-Shizuku" }, shizukuGranted)
            if (!shizukuGranted) Button(onClick = ::shizukuAction, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (shizukuRunning) "允許 YX 通訊" else "開啟 Shizuku")
            }
            Text("Shizuku 已啟動時，返回 APP 等待狀態更新；若未啟動，請到 Shizuku 啟動。")
            Text("更新 APP 不需先解除安裝，請直接覆蓋安裝。無線偵錯配對不是每次更新必做；若配對失效，再依 Shizuku 指示處理。", style = MaterialTheme.typography.bodySmall)
        }
    }
    @Composable private fun LoginScreen() {
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        ShizukuStatusPanel()
        if (firstLaunch) Panel("首次使用說明") {
            Text("1. 輸入管理員提供的帳號密碼登入。\n2. 安裝並啟動 Shizuku，允許 YX 通訊使用；可在登入前或登入後完成。\n3. 登入後允許手機權限，選擇本程式為預設電話與簡訊 APP。\n4. 四個狀態燈全部綠色後，再測試通話。")
            TextButton(onClick = { firstLaunch = false; getPreferences(MODE_PRIVATE).edit().putBoolean("intro_read", true).apply() }) { Text("我知道了") }
        } else TextButton(onClick = { firstLaunch = true }) { Text("查看首次使用說明") }
        OutlinedTextField(username, { username = it }, label = { Text("帳號") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("密碼") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Button(onClick = { login(username, password); password = "" }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "登入中…" else "登入") }
        if (!shizukuGranted) Text("可以先登入帳號；通話仍需完成 Shizuku 授權、手機權限與預設 APP 設定。")
        if (BuildConfig.API_BASE_URL.isBlank()) Text("此建置尚未設定登入服務，請向管理員取得已部署版本。", color = MaterialTheme.colorScheme.error)
    }
    @Composable private fun Dashboard() {
        val h by runtime.health.collectAsState()
        val current by runtime.settingsFlow.collectAsState()
        var hadCall by remember { mutableStateOf(false) }
        var ended by remember { mutableStateOf(false) }
        LaunchedEffect(h.calls.count, h.session) {
            val active = h.calls.count > 0 || h.session != null
            if (active) { ended = false; hadCall = true }
            else if (hadCall) { hadCall = false; ended = true; delay(4_000); ended = false }
        }
        Panel("線路 ${session?.lineId.orEmpty()}") {
            Text("版本：${BuildConfig.VERSION_NAME}")
            for (slot in 0..1) Text("SIM-${slot + 1}：" + (h.sims.firstOrNull { it.slot == slot }?.carrier ?: if (setup.phone) "未偵測到啟用的 SIM" else "尚未允許電話權限"))
        }
        if (!shizukuGranted) ShizukuStatusPanel()
        if (!setup.complete) Panel("完成手機設定後才能使用") {
            StatusLine("允許通知", setup.notifications); StatusLine("允許電話", setup.phone); StatusLine("預設電話 APP", setup.dialer)
            StatusLine("允許簡訊", setup.sms); StatusLine("預設簡訊 APP", setup.smsRole)
            Text("Android 可能先要求設定預設簡訊 APP，再授予簡訊權限。若顯示受限設定，請在應用程式資訊右上角選單允許後返回。")
            Button(onClick = { advanceSetup(true) }, enabled = pendingStep == null) { Text(setup.next?.label ?: "重新檢查") }
            TextButton(onClick = ::openAppDetails) { Text("開啟應用程式資訊") }
        }
        val fourGreen = h.shizuku == ShizukuConnector.State.CONNECTED && h.audioReady && h.privileged?.get(HealthKeys.DOZE_ALLOWLISTED) == true && h.sip.ready
        Panel("連線檢查") {
            StatusLine("Shizuku 服務", h.shizuku == ShizukuConnector.State.CONNECTED); StatusLine("通話音訊存取", h.audioReady)
            StatusLine("允許背景持續運作", h.privileged?.get(HealthKeys.DOZE_ALLOWLISTED) == true); StatusLine("通話伺服器註冊", h.sip.ready)
            Text(if (fourGreen && setup.complete) "連接完成" else "尚未連接完成", style = MaterialTheme.typography.titleLarge)
            Text("四個綠燈表示連線條件已備妥，雙向聲音仍需實際通話確認。", style = MaterialTheme.typography.bodySmall)
        }
        Text("當前狀態：" + when {
            ended -> "結束通話"; h.calls.incoming -> "來電中"
            h.calls.summary.contains("active") || h.session?.contains("CONNECTED") == true -> "通話中"
            h.calls.count > 0 || h.session != null -> "撥話中"
            reconnecting -> "重新連線中"
            !setup.complete -> "等待完成設定"; !current.takeover -> "已暫停"; fourGreen -> "待命中"; else -> "連線中"
        }, style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = ::logout, enabled = h.calls.count == 0 && h.session == null, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("登出") }
            Button(onClick = { settingsOpen = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("設定") }
            OutlinedButton(onClick = ::reconnect, enabled = !reconnecting && setup.complete && current.takeover && h.calls.count == 0 && h.session == null,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (reconnecting) "重連中" else "重連") }
        }
        if (h.calls.count > 0 || h.session != null) Text("請先結束通話再登出或重連。")
    }
    @Composable private fun SettingsScreen() {
        val s by runtime.settingsFlow.collectAsState()
        val h by runtime.health.collectAsState()
        TextButton(onClick = { settingsOpen = false }) { Text("← 返回首頁") }
        Panel("通話設定", compact = true) {
            SettingToggle("啟用通話連線", s.takeover, { if (it) advanceSetup(true) else runtime.setTakeover(false) })
            val compatibilitySelected = s.audioMethod == AudioMethod.LEGACY && !s.muteEarpiece
            OutlinedButton(
                onClick = { runtime.applySettings { it.copy(audioMethod = AudioMethod.LEGACY, muteEarpiece = false) } },
                enabled = !compatibilitySelected && !h.settingsPending && h.calls.count == 0 && h.session == null
            ) { Text(if (compatibilitySelected) "已套用相容通話設定" else "套用相容通話設定") }
            Text("相容設定使用直接電話音訊並關閉揚聲器靜音，下次通話生效。B 手機可能播放通話聲音；套用後仍需確認雙向聲音。",
                style = MaterialTheme.typography.bodySmall)
            Text("本機完全靜音：尚未驗證此機型有不影響雙向通話的獨立靜音能力；目前不能保證撥號與通話無聲。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            val audioOptions = listOf(AudioMethod.AUTO to "自動", AudioMethod.LEGACY to "直接電話音訊",
                AudioMethod.OFFICIAL to if (Build.VERSION.SDK_INT >= 33) "通話重新導向" else "通話重新導向（需 Android 13 以上）")
            SettingDropdown("音訊模式（下次通話生效）", s.audioMethod, audioOptions,
                optionEnabled = { it != AudioMethod.OFFICIAL || Build.VERSION.SDK_INT >= 33 }) { mode -> runtime.applySettings { it.copy(audioMethod = mode) } }
            val simOptions = listOf(DefaultSim.SYSTEM to "跟隨系統") +
                h.sims.map { it.slot to "SIM-${it.slot + 1} ${it.carrier}" }
            val selectedSimMissing = s.defaultSimSlot >= 0 && h.sims.none { it.slot == s.defaultSimSlot }
            SettingDropdown("外撥使用的 SIM", s.defaultSimSlot,
                if (selectedSimMissing) simOptions + (s.defaultSimSlot to "SIM-${s.defaultSimSlot + 1}（目前不可用）") else simOptions,
                optionEnabled = { it < 0 || h.sims.any { sim -> sim.slot == it } }, selected = runtime::setDefaultSimSlot)
            if (s.defaultSimSlot == DefaultSim.REFUSE) {
                Text("舊設定「由伺服器指定」不支援目前線路，會拒絕外撥。請改選「跟隨系統」或指定 SIM 後重試。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text("SIM 來電：" + when (s.incomingMode) { "operator" -> "轉接線路接聽手機"; "target" -> "轉接管理員指定手機"; else -> "拒接（預設）" })
            Text("來電政策由後台管理；手機離線、失去電話角色或系統不允許拒接時，無法保證阻擋。", style = MaterialTheme.typography.bodySmall)
            Text("PBX 主機、通訊加密及線路認證由管理員自動派送。")
            Button(onClick = ::reconnect, enabled = !reconnecting && setup.complete && s.takeover && h.calls.count == 0 && h.session == null,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(if (reconnecting) "重新連線中…" else "重新檢查並連線") }
        }
        Panel("診斷", compact = true) {
            Text("Android ${Build.VERSION.RELEASE}，API ${Build.VERSION.SDK_INT}")
            Button(onClick = ::copyCallDiagnostics, enabled = !copyingDiagnostics,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(if (copyingDiagnostics) "正在取得診斷…" else "複製通話診斷") }
            Text("接通後先複製一次，再請 A 與接聽方輪流各說話約 10 秒，每段結束各複製一次，以比較雙向計數。只複製狀態與計數，不含帳密、電話或錄音；無資料與歷史統計會註明。",
                style = MaterialTheme.typography.bodySmall)
            SettingToggle("診斷記錄", s.sipDebugLog, runtime::setSipDebugLog)
            SettingToggle("來電回音測試", h.loopbackTest, runtime::setLoopbackTest)
            Text("回音測試會改由本機回播來電聲音；一般通話請關閉。")
            if (h.settingsPending) Text("設定將在目前通話結束後生效。")
            Button(onClick = ::logout, enabled = h.calls.count == 0 && h.session == null) { Text("登出帳號") }
        }
    }
}
private fun friendlyError(error: Exception) = when (error) {
    is PlatformException -> error.message.orEmpty()
    is javax.net.ssl.SSLException -> "安全連線驗證失敗，請確認手機時間或聯絡管理員。"
    is java.io.IOException -> "無法連上登入服務，請檢查網路後重試。"
    is IllegalArgumentException -> "登入服務設定或回應無效，請聯絡管理員。"
    else -> "登入未完成，請稍後重試或聯絡管理員。"
}
@Composable internal fun Panel(title: String, compact: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(if (compact) 12.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium); content()
    } }
}
@Composable internal fun StatusLine(label: String, ready: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (ready) "●" else "○", color = if (ready) Color(0xFF278348) else MaterialTheme.colorScheme.error); Text(label)
    }
}
@Composable private fun SettingToggle(label: String, value: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(value, changed) }
}
@Composable private fun SettingDropdown(label: String, value: Int, options: List<Pair<Int, String>>,
    optionEnabled: (Int) -> Boolean = { true }, selected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text(label, style = MaterialTheme.typography.labelLarge)
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(options.firstOrNull { it.first == value }?.second ?: "目前選項不可用", Modifier.weight(1f))
            Text("▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, title) ->
                DropdownMenuItem(text = { Text((if (id == value) "● " else "") + title) },
                    onClick = { expanded = false; selected(id) }, enabled = optionEnabled(id), modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
}
