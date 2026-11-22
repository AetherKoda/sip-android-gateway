package io.github.sparkcyf.voicesipagent.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.sparkcyf.voicesipagent.pjsua2.SipTransport
import io.github.sparkcyf.voicesipagent.pjsua2.SrtpPolicy

/** Retained across activity recreation; credentials stay in memory rather than saved-state Bundles. */
class SipSettingsDraft : ViewModel() {
    var server by mutableStateOf("")
    var port by mutableStateOf("5060")
    var user by mutableStateOf("")
    var password by mutableStateOf("")
    var target by mutableStateOf("")
    var prefixes by mutableStateOf("")
    var domain by mutableStateOf("")
    var stack by mutableStateOf(SipStack.PJSUA2)
    var transport by mutableStateOf(SipTransport.UDP)
    var srtp by mutableStateOf(SrtpPolicy.OPTIONAL)
    private var saved: GatewaySettings? = null

    private val validatedPort: Int?
        get() = port.toIntOrNull()?.takeIf { it in 1..65535 }

    val portError: String?
        get() = if (validatedPort == null) "Enter a port number from 1 to 65535." else null

    val manualError: String?
        get() = when {
            !validHost(server.trim()) -> "Enter a SIP server hostname or IP address, without a URL or port."
            portError != null -> portError
            !validSipUser(user.trim()) -> "Enter a SIP username without a domain."
            password.isEmpty() -> "Enter the SIP password."
            password.any { it == '\r' || it == '\n' || it == '\u0000' } -> "The SIP password cannot contain a line break."
            domain.isNotBlank() && !validHost(domain.trim()) -> "Enter a valid SIP domain or leave it blank."
            target.isNotBlank() && !validSipUser(target.trim()) -> "Enter an inbound SIP extension or leave it blank."
            else -> null
        }

    fun manualSettingsUpdate(): (GatewaySettings) -> GatewaySettings {
        val error = manualError
        require(error == null) { error.orEmpty() }
        val update = settingsUpdate()
        return { current ->
            val submitted = update(current)
            submitted.copy(
                server = submitted.server.trim(),
                username = submitted.username.trim(),
                domain = submitted.domain.trim(),
                inboundTarget = submitted.inboundTarget.trim(),
                incomingMode = if (submitted.inboundTarget.isBlank()) "reject" else "target",
                srtp = if (submitted.sipTransport == SipTransport.TLS.id) submitted.srtp else SrtpPolicy.OFF.id,
            )
        }
    }

    private fun validHost(value: String): Boolean = value.isNotEmpty() &&
        runCatching {
            val uri = java.net.URI("sip://$value")
            uri.host != null && uri.port == -1 && uri.rawAuthority == value
        }.getOrDefault(false)

    private fun validSipUser(value: String): Boolean =
        value.isNotEmpty() && value.none { it.isWhitespace() || it.isISOControl() || it in "@/:<>;?" }

    /** Follow saved or queued changes to untouched fields, while keeping unfinished edits. */
    fun syncSaved(applied: GatewaySettings, pending: GatewaySettings? = null) {
        val current = pending ?: applied
        val previous = saved
        if (previous == null || server == previous.server) server = current.server
        if (previous == null || port == previous.port.toString()) port = current.port.toString()
        if (previous == null || user == previous.username) user = current.username
        if (previous == null || password == previous.password) password = current.password
        if (previous == null || target == previous.inboundTarget) target = current.inboundTarget
        if (previous == null || prefixes == previous.allowedPrefixes) prefixes = current.allowedPrefixes
        if (previous == null || domain == previous.domain) domain = current.domain
        if (previous == null || stack == previous.sipStack) stack = current.sipStack
        if (previous == null || transport == (SipTransport.of(previous.sipTransport) ?: SipTransport.UDP))
            transport = SipTransport.of(current.sipTransport) ?: SipTransport.UDP
        if (previous == null || srtp == (SrtpPolicy.of(previous.srtp) ?: SrtpPolicy.OPTIONAL))
            srtp = SrtpPolicy.of(current.srtp) ?: SrtpPolicy.OPTIONAL
        saved = current
    }

    fun hasUnsavedChanges(applied: GatewaySettings, pending: GatewaySettings? = null): Boolean =
        portError != null || !snapshot().hasSameSipSettings(pending ?: applied)

    private fun snapshot() = GatewaySettings(
        server = server, port = requireNotNull(validatedPort) { "PBX port must be between 1 and 65535." },
        username = user, password = password, inboundTarget = target, allowedPrefixes = prefixes,
        domain = domain, sipStack = stack, sipTransport = transport.id, srtp = srtp.id,
    )

    /** Capture the form at the click, before the runtime queues or applies it on another thread. */
    fun settingsUpdate(): (GatewaySettings) -> GatewaySettings {
        val snapshot = snapshot()
        return { current -> current.copy(
            server = snapshot.server, port = snapshot.port, username = snapshot.username, password = snapshot.password,
            inboundTarget = snapshot.inboundTarget, allowedPrefixes = snapshot.allowedPrefixes, domain = snapshot.domain,
            sipStack = snapshot.sipStack, sipTransport = snapshot.sipTransport, srtp = snapshot.srtp,
        ) }
    }
}

/** Gateway preferences do not count as edits to the SIP form. */
internal fun GatewaySettings.hasSameSipSettings(other: GatewaySettings): Boolean =
    server == other.server && port == other.port && username == other.username && password == other.password &&
        inboundTarget == other.inboundTarget && allowedPrefixes == other.allowedPrefixes && domain == other.domain &&
        sipStack == other.sipStack && sipTransport == other.sipTransport && srtp == other.srtp
