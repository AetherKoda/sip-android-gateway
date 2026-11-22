package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

data class PlatformSession(val token: String, val expiresAt: Long, val username: String, val lineId: String,
    val allocationId: String? = null) {
    fun valid(now: Long = System.currentTimeMillis()) = token.isNotBlank() && expiresAt > now
    override fun toString() = "PlatformSession(expiresAt=$expiresAt)"
}

data class ProvisionedSip(val server: String, val port: Int, val transport: String, val srtp: String,
    val domain: String, val username: String, val password: String, val target: String, val prefixes: String,
    val incomingMode: String = "reject") {
    init {
        require(server.matches(Regex("[a-zA-Z0-9.:-]{1,253}")) && port in 1..65535)
        require((transport == "tls" && srtp == "mandatory") ||
            (transport == "udp" && srtp == "optional")) { "Unsupported SIP provisioning profile" }
        require(username.matches(Regex("[a-zA-Z0-9_.-]{1,128}")) && password.isNotBlank())
        require(domain.matches(Regex("[a-zA-Z0-9.:-]{1,253}")))
        require(target.matches(Regex("[a-zA-Z0-9_+.*#-]{0,128}")))
        require(prefixes.matches(Regex("[0-9+, *#-]{0,512}")))
        require(incomingMode in setOf("reject", "operator", "target"))
    }
    fun applyTo(old: GatewaySettings) = old.copy(server = server, port = port, sipTransport = transport,
        srtp = srtp, domain = domain, username = username, password = password, inboundTarget = target,
        allowedPrefixes = prefixes, sipStack = SipStack.PJSUA2, sipDebugLog = false,
        callAccess = io.github.sparkcyf.voicesipagent.privileged.CallAccess.DIALER,
        keepDefaultDialer = true, holdSipWhenNotReady = true, incomingMode = incomingMode,
        inboundExcludedSlots = emptySet())
    override fun toString() = "ProvisionedSip(redacted)"
}

data class LoginResult(val session: PlatformSession, val sip: ProvisionedSip)
class PlatformException(val status: Int, message: String) : Exception(message)

/** No customer-entered PBX endpoint, permissive trust manager, redirect, or cleartext fallback. */
class PlatformClient(baseUrl: String = BuildConfig.API_BASE_URL) {
    private val base = baseUrl.trimEnd('/')
    private fun endpoint(path: String): java.net.URL {
        val uri = URI(base)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.path.isNullOrEmpty()) { "尚未設定安全登入服務，請聯絡管理員。" }
        return URI(base + path).toURL()
    }
    suspend fun login(username: String, password: String, deviceId: String): LoginResult = withContext(Dispatchers.IO) {
        val json = request("/api/v1/auth/login", "POST", JSONObject().put("username", username)
            .put("password", password).put("device_id", deviceId))!!
        val user = json.getJSONObject("user")
        val session = PlatformSession(json.getString("access_token"), Instant.parse(json.getString("expires_at")).toEpochMilli(),
            user.getString("username"), user.getString("line_id"), json.optString("allocation_id").takeIf { it.isNotBlank() && it != "null" })
        require(session.valid()) { "伺服器回傳的登入期限已失效。" }
        LoginResult(session, parseSip(json))
    }
    suspend fun config(session: PlatformSession): ProvisionedSip = withContext(Dispatchers.IO) {
        parseSip(request("/api/v1/device/config", "GET", token = session.token)!!)
    }
    suspend fun refresh(session: PlatformSession): LoginResult = withContext(Dispatchers.IO) {
        val json = request("/api/v1/auth/refresh", "POST", token = session.token)!!
        val user = json.getJSONObject("user")
        require(user.getString("username") == session.username && user.getString("line_id") == session.lineId) {
            "Session identity changed"
        }
        val renewed = session.copy(expiresAt = Instant.parse(json.getString("expires_at")).toEpochMilli(),
            allocationId = json.optString("allocation_id").takeIf { it.isNotBlank() && it != "null" })
        require(renewed.valid()) { "Server returned an expired session" }
        LoginResult(renewed, parseSip(json))
    }
    suspend fun logout(session: PlatformSession) = withContext(Dispatchers.IO) {
        request("/api/v1/auth/logout", "POST", token = session.token)
        Unit
    }
    private fun parseSip(response: JSONObject): ProvisionedSip {
        val j = response.getJSONObject("sip")
        return ProvisionedSip(j.getString("server"), j.getInt("port"),
            j.getString("transport"), j.getString("srtp"), j.getString("domain"), j.getString("username"),
            j.getString("password"), j.optString("inbound_target"), j.optString("allowed_prefixes"),
            response.optJSONObject("device_policy")?.optString("incoming_mode", "reject") ?: "reject")
    }
    suspend fun uploadSms(session: PlatformSession, message: JSONObject): Boolean = withContext(Dispatchers.IO) {
        val ack = request("/api/v1/device/sms", "POST", message, session.token)!!
        ack.optBoolean("stored", false) && ack.optString("message_id") == message.getString("message_id")
    }
    suspend fun telemetry(session: PlatformSession, snapshot: JSONObject) = withContext(Dispatchers.IO) {
        snapshot.put("allocation_id", session.allocationId ?: JSONObject.NULL)
        request("/api/v1/device/telemetry", "POST", snapshot, session.token)
        Unit
    }

    private fun request(path: String, method: String, body: JSONObject? = null, token: String? = null): JSONObject? {
        val conn = endpoint(path).openConnection() as HttpsURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            if (status !in 200..299) throw PlatformException(status, when (status) {
                401, 403 -> "帳號、密碼或登入狀態無效，請重新登入。"
                409 -> "此帳號已綁定其他裝置，請聯絡管理員。"
                429 -> "登入嘗試過於頻繁，請稍後再試。"
                else -> "登入服務暫時無法使用（$status）。"
            })
            if (status == 204) return null
            val bytes = conn.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (output.size() <= 65_536) {
                    val size = input.read(buffer, 0, minOf(buffer.size, 65_537 - output.size()))
                    if (size < 0) break
                    output.write(buffer, 0, size)
                }
                output.toByteArray()
            }
            require(bytes.size <= 65_536) { "伺服器回應過大。" }
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally { conn.disconnect() }
    }
}

/** Only the opaque token is retained; platform passwords never enter preferences or logs. */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("platform_session", Context.MODE_PRIVATE)
    fun deviceId(): String = synchronized(lock) {
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString("device_id", it).commit())
        }
    }
    fun load(): PlatformSession? = synchronized(lock) { readSession()?.takeIf { it.valid() } }
    private fun readSession(): PlatformSession? = runCatching {
        val sealed = prefs.getString("token", null) ?: return null
        val token = decrypt(sealed)
        PlatformSession(token, prefs.getLong("expires_at", 0), prefs.getString("username", "").orEmpty(),
            prefs.getString("line_id", "").orEmpty(), prefs.getString("allocation_id", null))
    }.getOrNull()
    fun save(session: PlatformSession) = synchronized(lock) {
        check(prefs.edit().putString("token", encrypt(session.token)).putLong("expires_at", session.expiresAt)
            .putString("username", session.username).putString("line_id", session.lineId)
            .putString("allocation_id", session.allocationId).commit())
    }
    /** Only a live matching login may accept a server-confirmed extension. Delayed replies cannot revive it. */
    fun renew(expectedToken: String, renewed: PlatformSession): PlatformSession? = synchronized(lock) {
        val current = readSession()?.takeIf { it.valid() } ?: return@synchronized null
        if (current.token != expectedToken || renewed.token != expectedToken ||
            current.username != renewed.username || current.lineId != renewed.lineId ||
            current.allocationId != renewed.allocationId || !renewed.valid()) return@synchronized null
        val accepted = current.copy(expiresAt = maxOf(current.expiresAt, renewed.expiresAt))
        if (accepted.expiresAt != current.expiresAt) check(prefs.edit().putLong("expires_at", accepted.expiresAt).commit())
        accepted
    }
    /** A failure from an old request must never clear a newer login. */
    fun clearIfCurrent(expectedToken: String): Boolean = synchronized(lock) {
        if (readSession()?.token != expectedToken) return@synchronized false
        clear()
        true
    }
    fun clear() = synchronized(lock) {
        check(prefs.edit().remove("token").remove("expires_at").remove("username").remove("line_id").remove("allocation_id").commit())
    }
    private fun key(): SecretKey = synchronized(lock) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    private fun encrypt(value: String): String = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key())
        Base64.encodeToString(iv + doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size >= 28)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }
    }
    companion object { private const val KEY_ALIAS = "yx-platform-token"; private val lock = Any() }
}
