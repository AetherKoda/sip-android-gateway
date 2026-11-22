package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.*
import org.junit.Test

class PlatformAccessTest {
    @Test fun manualSipSetupDoesNotRequireSmsOrAPlatformSession() {
        val ready = SetupState(true, true, true, false, false, requiresSms = false)
        assertTrue(ready.complete)
        assertNull(ready.next)
        assertEquals(SetupStep.PHONE, ready.copy(phone = false).next)
        assertTrue(canUseGateway(ready, platformManaged = false, hasPlatformSession = false))
        assertFalse(canUseGateway(ready, platformManaged = true, hasPlatformSession = false))
    }

    @Test fun incomingPolicyDefaultsToRejectAndAcceptsOnlyKnownModes() {
        assertEquals("reject", sip().applyTo(GatewaySettings()).incomingMode)
        for (mode in listOf("reject", "operator", "target")) {
            assertEquals(mode, sip().copy(incomingMode = mode).applyTo(GatewaySettings()).incomingMode)
        }
        try { sip().copy(incomingMode = "unknown"); fail("invalid policy accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun expiredAndEmptyTokenCannotAuthorize() {
        assertTrue(PlatformSession("opaque", 101, "test", "1").valid(100))
        assertFalse(PlatformSession("opaque", 100, "test", "1").valid(100))
        assertFalse(PlatformSession("", 101, "test", "1").valid(100))
    }
    @Test fun setupRequiresEveryPermissionAndBothRoles() {
        val ready = SetupState(true, true, true, true, true)
        assertTrue(ready.complete)
        assertNull(ready.next)
        listOf(ready.copy(notifications = false), ready.copy(phone = false), ready.copy(dialer = false),
            ready.copy(sms = false), ready.copy(smsRole = false)).forEach { assertFalse(it.complete); assertNotNull(it.next) }
        assertEquals(SetupStep.NOTIFICATIONS, SetupState(false, false, false, false, false).next)
        assertEquals(SetupStep.SMS_ROLE, ready.copy(sms = false, smsRole = false).next)
    }
    private fun sip(transport: String = "tls", srtp: String = "mandatory", host: String = "pbx.example.org", port: Int = 5061) =
        ProvisionedSip(host, port, transport, srtp, "pbx.example.org", "b_100", "test-only-secret", "a_100", "09,+886")
    @Test fun rejectsUnsupportedAndIncompleteProvisioning() {
        listOf<() -> Unit>({ sip("udp") }, { sip("tcp") }, { sip(srtp = "off") }, { sip(port = 0) },
            { sip(host = "pbx.example.org\r\nInjected") }).forEach { invalid ->
            try { invalid(); fail("Invalid provisioning accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun provisioningAcceptsOnlyExplicitTransportAndSrtpProfiles() {
        val allowed = setOf("tls" to "mandatory", "udp" to "optional")
        for (transport in listOf("tls", "udp", "tcp", "unknown")) {
            for (srtp in listOf("mandatory", "optional", "off", "unknown")) {
                val result = runCatching { sip(transport, srtp) }
                assertEquals("$transport/$srtp", (transport to srtp) in allowed, result.isSuccess)
                if (result.isFailure) assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            }
        }
    }
    @Test fun udpProvisioningKeepsLocalAudioAndUsesExistingPjsua2AndDialerGates() {
        val old = GatewaySettings(muteEarpiece = false, audioMethod = 2, username = "old", password = "old")
        val next = sip("udp", "optional", port = 5060).applyTo(old)
        assertEquals("udp", next.sipTransport)
        assertEquals("optional", next.srtp)
        assertEquals(5060, next.port)
        assertEquals(SipStack.PJSUA2, next.sipStack)
        assertEquals(io.github.sparkcyf.voicesipagent.privileged.CallAccess.DIALER, next.callAccess)
        assertTrue(next.keepDefaultDialer)
        assertTrue(next.holdSipWhenNotReady)
        assertFalse(next.takeover)
        assertEquals(old.audioMethod, next.audioMethod)
        assertEquals(old.muteEarpiece, next.muteEarpiece)
        assertEquals("b_100", next.username)
        assertEquals("test-only-secret", next.password)
    }
    @Test fun provisioningUsesPlatformSuppliedSipIdentityAndKeepsLocalAudioPreferences() {
        val old = GatewaySettings(muteEarpiece = false, audioMethod = 2, username = "old", password = "old")
        val next = sip().applyTo(old)
        assertEquals("b_100", next.username)
        assertEquals("test-only-secret", next.password)
        assertEquals("tls", next.sipTransport)
        assertEquals("mandatory", next.srtp)
        assertEquals(2, next.audioMethod)
        assertFalse(next.muteEarpiece)
        assertFalse(next.takeover)
        assertFalse(sip().toString().contains("test-only-secret"))
        assertFalse(PlatformSession("token-secret", 100, "x", "1").toString().contains("token-secret"))
    }
}
