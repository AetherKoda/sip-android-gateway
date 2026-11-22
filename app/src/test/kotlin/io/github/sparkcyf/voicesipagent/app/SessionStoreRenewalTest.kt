package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.MockedStatic
import org.mockito.Mockito.*
import java.security.KeyStore
import javax.crypto.spec.SecretKeySpec

/** Real session persistence/CAS with an in-memory preferences backend and a host-only test key. */
class SessionStoreRenewalTest {
    private val values = mutableMapOf<String, Any>()
    private val context = mock(Context::class.java)
    private val prefs = mock(SharedPreferences::class.java)
    private lateinit var keyStores: MockedStatic<KeyStore>
    private lateinit var base64: MockedStatic<Base64>
    private lateinit var ui: SessionStore
    private lateinit var service: SessionStore
    private val now = System.currentTimeMillis()
    private val original = PlatformSession("test-token-one", now + 60_000, "test-user", "test-line")

    @Before fun setup() {
        val keyStore = mock(KeyStore::class.java)
        `when`(keyStore.getKey(anyString(), isNull())).thenReturn(SecretKeySpec(ByteArray(32), "AES"))
        keyStores = mockStatic(KeyStore::class.java)
        keyStores.`when`<KeyStore> { KeyStore.getInstance("AndroidKeyStore") }.thenReturn(keyStore)
        base64 = mockStatic(Base64::class.java)
        base64.`when`<String> { Base64.encodeToString(any(ByteArray::class.java), anyInt()) }
            .thenAnswer { java.util.Base64.getEncoder().encodeToString(it.getArgument(0)) }
        base64.`when`<ByteArray> { Base64.decode(anyString(), anyInt()) }
            .thenAnswer { java.util.Base64.getDecoder().decode(it.getArgument<String>(0)) }
        `when`(context.getSharedPreferences("platform_session", Context.MODE_PRIVATE)).thenReturn(prefs)
        `when`(prefs.getString(anyString(), nullable(String::class.java))).thenAnswer {
            values[it.getArgument<String>(0)] as? String ?: it.getArgument<String?>(1)
        }
        `when`(prefs.getLong(anyString(), anyLong())).thenAnswer {
            values[it.getArgument<String>(0)] as? Long ?: it.getArgument<Long>(1)
        }
        `when`(prefs.edit()).thenAnswer {
            val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
            val writes = mutableMapOf<String, Any>()
            val removals = mutableSetOf<String>()
            doAnswer { invocation ->
                val key = invocation.getArgument<String>(0)
                val value = invocation.getArgument<String?>(1)
                if (value == null) removals += key else writes[key] = value
                editor
            }.`when`(editor).putString(anyString(), nullable(String::class.java))
            doAnswer { invocation -> writes[invocation.getArgument(0)] = invocation.getArgument<Long>(1); editor }
                .`when`(editor).putLong(anyString(), anyLong())
            doAnswer { invocation -> removals += invocation.getArgument<String>(0); editor }.`when`(editor).remove(anyString())
            `when`(editor.commit()).thenAnswer { removals.forEach(values::remove); values.putAll(writes); true }
            editor
        }
        ui = SessionStore(context)
        service = SessionStore(context)
        ui.save(original)
    }

    @After fun cleanup() { base64.close(); keyStores.close() }

    @Test fun serverConfirmedRenewalIsVisibleAcrossInstancesWithoutChangingTheToken() {
        val sealedToken = values["token"]
        val response = original.copy(expiresAt = now + 86_400_000)
        assertEquals(response, service.renew(original.token, response))
        assertEquals(response, ui.load())
        assertTrue(ui.load()!!.valid(original.expiresAt + 1))
        assertEquals(sealedToken, values["token"])
    }

    @Test fun outOfOrderSuccessfulResponsesNeverShortenTheLease() {
        val newer = original.copy(expiresAt = now + 86_400_000)
        assertEquals(newer, service.renew(original.token, newer))
        assertEquals(newer, ui.renew(original.token, original.copy(expiresAt = now + 120_000)))
        assertEquals(newer, service.load())
    }

    @Test fun logoutWinsOverARefreshResponseAlreadyInFlight() {
        service.clear()
        assertNull(ui.renew(original.token, original.copy(expiresAt = now + 86_400_000)))
        assertNull(ui.load())
        assertFalse(values.containsKey("token"))
    }

    @Test fun oldSuccessAndOldUnauthorizedResponseCannotReplaceOrClearANewLogin() {
        val replacement = original.copy(token = "test-token-two", username = "other", lineId = "other-line")
        ui.save(replacement)
        assertNull(service.renew(original.token, original.copy(expiresAt = now + 86_400_000)))
        assertFalse(service.clearIfCurrent(original.token))
        assertEquals(replacement, ui.load())
    }

    @Test fun responseMustMatchTheTokenUsernameAndLine() {
        val renewed = original.copy(expiresAt = now + 86_400_000)
        listOf(renewed.copy(token = "wrong"), renewed.copy(username = "wrong"), renewed.copy(lineId = "wrong"))
            .forEach { assertNull(service.renew(original.token, it)); assertEquals(original, ui.load()) }
        assertNull(service.renew("wrong-request-token", renewed))
    }

    @Test fun expiredLocalLeaseCannotBeRevivedEvenByALateSuccess() {
        ui.save(original.copy(expiresAt = now - 1))
        assertNull(ui.load())
        assertNull(service.renew(original.token, original.copy(expiresAt = now + 86_400_000)))
        assertEquals(now - 1, values["expires_at"])
    }

    @Test fun invalidExpiryCannotExtendAndMatchingRevocationClearsTheLease() {
        assertNull(service.renew(original.token, original.copy(expiresAt = now - 1)))
        assertEquals(original, ui.load())
        assertTrue(service.clearIfCurrent(original.token))
        assertNull(ui.load())
        assertFalse(ui.clearIfCurrent(original.token))
    }

    @Test fun allocationIsPersistedAndCannotBeChangedByARefreshReply() {
        val allocated = original.copy(allocationId = "captured-allocation")
        ui.save(allocated)
        assertEquals(allocated, service.load())
        assertNull(service.renew(allocated.token, allocated.copy(allocationId = "another-customer")))
        assertEquals("captured-allocation", ui.load()!!.allocationId)
        ui.save(original)
        assertNull(service.load()!!.allocationId)
    }

    @Test fun logoutClearsAllocationSoAnotherLoginCannotReuseIt() {
        ui.save(original.copy(allocationId = "old-allocation"))
        service.clear()
        assertFalse(values.containsKey("allocation_id"))
        ui.save(original.copy(token = "new-token"))
        assertNull(service.load()!!.allocationId)
    }
}
