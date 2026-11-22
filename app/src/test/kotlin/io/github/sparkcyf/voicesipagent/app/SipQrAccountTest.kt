package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.*
import org.junit.Test

class SipQrAccountTest {
    @Test fun importsDomainCredentialsAndTransport() {
        val result = SipQrAccount.parse("qqsip://login?domain=pbx.example.org&username=test&password=p%40ss%2Bword&transport=tls")!!
        assertEquals("pbx.example.org", result.server)
        assertEquals("p@ss+word", result.password)
        assertEquals(5061, result.port)
        assertEquals("tls", result.transport)
        assertFalse(result.toString().contains(result.password))
    }
    @Test fun importsSipUriWithoutChangingPlusOrEncodedDelimiter() {
        val result = SipQrAccount.parse("sip:alice:p%3Ass+word@pbx.example.org:5070;transport=tcp")!!
        assertEquals("p:ss+word", result.password)
        assertEquals(5070, result.port)
        assertEquals("tcp", result.transport)
        assertEquals("tls", SipQrAccount.parse("sips:alice:secret@pbx.example.org")!!.transport)
    }
    @Test fun rejectsAmbiguousIncompleteExternalAndInvalidInput() {
        val valid = "qqsip://login?domain=pbx.example.org&username=alice&password=secret"
        for (value in listOf(valid+"&password=other", valid+"&port=0", valid+"&port=no", valid+"&port=65536",
            valid+"&transport=ws", valid+"#fragment", valid+"&redirect=https://other.invalid",
            valid.replace("secret", "%0Asecret"), valid.replace("pbx.example.org", "pbx.example.org:5060"),
            "https://pbx.example.org/activate#token=secret", "sip:alice@pbx.example.org", "sips:a:b@pbx.example.org;transport=udp")) {
            assertNull(value.substringBefore("password="), SipQrAccount.parse(value))
        }
    }
}
