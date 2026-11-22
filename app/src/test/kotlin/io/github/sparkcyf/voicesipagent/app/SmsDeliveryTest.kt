package io.github.sparkcyf.voicesipagent.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SmsDeliveryTest {
    private val owner = SmsOwner("test-user", "test-line", "test-device")
    private class Store(var record: SmsDelivery) : SmsDeliveryStore {
        val events = mutableListOf<String>()
        var deletionSucceeds = true
        override fun pending(owner: SmsOwner) = listOf(record)
        override fun acknowledge(id: String) { events += "ack:$id"; record = record.copy(acknowledged = true) }
        override fun removeConfirmed(id: String): Boolean { check(record.acknowledged); events += "delete:$id"; return deletionSucceeds }
    }
    @Test fun failedOrAmbiguousUploadDoesNotDeleteLocalMessage() = runBlocking {
        val store = Store(SmsDelivery("stable-id", owner, false))
        assertFalse(SmsDeliveryEngine.drain(owner, store, { true }) { false })
        assertTrue(store.events.isEmpty())
        assertFalse(store.record.acknowledged)
    }
    @Test fun successfulRetryUsesSameIdAndAcknowledgesBeforeDeleting() = runBlocking {
        val store = Store(SmsDelivery("stable-id", owner, false))
        val attempts = mutableListOf<String>()
        SmsDeliveryEngine.drain(owner, store, { true }) { attempts += it; false }
        assertTrue(SmsDeliveryEngine.drain(owner, store, { true }) { attempts += it; true })
        assertEquals(listOf("stable-id", "stable-id"), attempts)
        assertEquals(listOf("ack:stable-id", "delete:stable-id"), store.events)
    }
    @Test fun accountSwitchDuringRequestDoesNotAcknowledgeOrDelete() = runBlocking {
        val store = Store(SmsDelivery("stable-id", owner, false))
        var current = true
        assertFalse(SmsDeliveryEngine.drain(owner, store, { current }) { current = false; true })
        assertTrue(store.events.isEmpty())
    }
    @Test fun aDifferentAccountQueueCannotBeUploaded() = runBlocking {
        val store = Store(SmsDelivery("stable-id", owner.copy(username = "other"), false))
        assertFalse(SmsDeliveryEngine.drain(owner, store, { true }) { error("must not upload") })
        assertTrue(store.events.isEmpty())
    }
    @Test fun acknowledgedButUndeletedMessageRetriesCleanupWithoutUploading() = runBlocking {
        val store = Store(SmsDelivery("stable-id", owner, false))
        store.deletionSucceeds = false
        assertFalse(SmsDeliveryEngine.drain(owner, store, { true }) { true })
        store.deletionSucceeds = true
        assertTrue(SmsDeliveryEngine.drain(owner, store, { true }) { error("must not upload twice after ACK") })
        assertEquals(listOf("ack:stable-id", "delete:stable-id", "delete:stable-id"), store.events)
    }
}
