package io.github.sparkcyf.voicesipagent.app

import java.net.URI
import java.net.URLDecoder

/** Account QR data only: never fetch a scanned URL or log its password. */
class SipQrAccount private constructor(
    val server: String, val domain: String, val username: String, val password: String,
    val port: Int, val transport: String,
) {
    override fun toString() = "SipQrAccount(redacted)"
    companion object {
        fun parse(value: String): SipQrAccount? = runCatching {
            if (value.length !in 1..4096 || value.any { it.isISOControl() }) return null
            val uri = URI(value.trim())
            if (uri.fragment != null) return null
            val fields: Map<String, String>
            if (uri.scheme == "qqsip") {
                if (uri.rawAuthority != "login" || !uri.path.isNullOrEmpty()) return null
                val pairs = (uri.rawQuery ?: return null).split('&').map {
                    val parts = it.split('=', limit = 2)
                    if (parts.size != 2) return null
                    decode(parts[0]) to decode(parts[1])
                }
                if (pairs.map { it.first }.toSet().size != pairs.size) return null
                fields = pairs.toMap()
                if (fields.keys.any { it !in setOf("domain", "server", "username", "password", "port", "transport") }) return null
            } else if (uri.scheme in setOf("sip", "sips")) {
                val parts = uri.rawSchemeSpecificPart.split(';')
                if (parts.size > 2 || '?' in parts[0]) return null
                val address = URI("sip://" + parts[0])
                if (!address.path.isNullOrEmpty() || address.host == null) return null
                val auth = (address.rawUserInfo ?: return null).split(':', limit = 2)
                if (auth.size != 2) return null
                val transport = if (parts.size == 2) {
                    if (!parts[1].startsWith("transport=")) return null
                    parts[1].removePrefix("transport=")
                } else if (uri.scheme == "sips") "tls" else "udp"
                if (uri.scheme == "sips" && transport != "tls") return null
                fields = mapOf("domain" to address.host, "username" to decode(auth[0]),
                    "password" to decode(auth[1]), "transport" to transport,
                    "port" to (if (address.port < 0) defaultPort(transport) else address.port).toString())
            } else return null
            val domain = fields["domain"] ?: return null
            val server = fields["server"] ?: domain
            val username = fields["username"] ?: return null
            val password = fields["password"] ?: return null
            val transport = fields["transport"] ?: "udp"
            if (transport !in setOf("udp", "tcp", "tls")) return null
            val port = fields["port"]?.toIntOrNull() ?: if ("port" in fields) return null else defaultPort(transport)
            if (port !in 1..65535 || !host(domain) || !host(server) ||
                username.isBlank() || username.length > 128 || username.any { it.isWhitespace() || it.isISOControl() || it in "@/:<>;?" } ||
                password.isEmpty() || password.length > 512 || password.any { it.isISOControl() }) return null
            SipQrAccount(server, domain, username, password, port, transport)
        }.getOrNull()
        private fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        private fun defaultPort(transport: String) = if (transport == "tls") 5061 else 5060
        private fun host(value: String): Boolean = value.length in 1..253 && runCatching {
            val uri = URI("sip://$value")
            uri.host != null && uri.port == -1 && uri.rawAuthority == value && uri.path.isNullOrEmpty()
        }.getOrDefault(false)
    }
}
