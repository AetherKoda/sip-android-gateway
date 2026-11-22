package io.github.sparkcyf.voicesipagent.app

internal data class SmsOwner(val username: String, val lineId: String, val deviceId: String)
internal data class SmsDelivery(val id: String, val owner: SmsOwner, val acknowledged: Boolean)
internal interface SmsDeliveryStore {
    fun pending(owner: SmsOwner): List<SmsDelivery>
    fun acknowledge(id: String)
    /** Delete only this received message, after its durable server acknowledgement. */
    fun removeConfirmed(id: String): Boolean
}

/** Account switches and ambiguous responses leave the durable message untouched. */
internal object SmsDeliveryEngine {
    suspend fun drain(owner: SmsOwner, store: SmsDeliveryStore, stillCurrent: () -> Boolean,
        upload: suspend (String) -> Boolean): Boolean {
        for (message in store.pending(owner)) {
            if (!stillCurrent() || message.owner != owner) return false
            if (!message.acknowledged) {
                if (!upload(message.id) || !stillCurrent()) return false
                store.acknowledge(message.id)
            }
            if (!stillCurrent() || !store.removeConfirmed(message.id)) return false
        }
        return true
    }
}
