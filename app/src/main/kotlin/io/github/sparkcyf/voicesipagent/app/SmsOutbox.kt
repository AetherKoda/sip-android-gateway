package io.github.sparkcyf.voicesipagent.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.Telephony
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Private durable queue. Never reassign messages received under another account (or while logged out). */
internal class SmsOutbox(private val context: Context) : SQLiteOpenHelper(context, "sms-upload.db", null, 2), SmsDeliveryStore {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE received (id TEXT PRIMARY KEY, username TEXT, line_id TEXT, device_id TEXT, " +
            "sender TEXT NOT NULL, body TEXT NOT NULL, received_at INTEGER NOT NULL, sent_at INTEGER NOT NULL, " +
            "subscription INTEGER NOT NULL, slot INTEGER, provider_uri TEXT, acknowledged INTEGER NOT NULL DEFAULT 0, capture_allocation_id TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Old messages remain unassigned; never fill their owner from a later login.
        if (oldVersion < 2) db.execSQL("ALTER TABLE received ADD COLUMN capture_allocation_id TEXT")
    }

    fun receive(sender: String, body: String, receivedAt: Long, sentAt: Long, subscription: Int, slot: Int?): String {
        val sessions = SessionStore(context)
        val session = sessions.load()
        val id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("id", id); put("sender", sender); put("body", body); put("received_at", receivedAt)
            put("sent_at", sentAt); put("subscription", subscription)
            if (slot != null) put("slot", slot)
            if (session != null) {
                put("username", session.username); put("line_id", session.lineId); put("device_id", sessions.deviceId())
                put("capture_allocation_id", session.allocationId)
            }
        }
        writableDatabase.insertOrThrow("received", null, values)
        return id
    }

    private fun row(id: String): JSONObject = readableDatabase.query("received", null, "id=?", arrayOf(id), null, null, null).use { c ->
        check(c.moveToFirst())
        JSONObject().apply { c.columnNames.forEachIndexed { i, name ->
            put(name, if (c.isNull(i)) JSONObject.NULL else c.getString(i))
        } }
    }
    fun payload(id: String): JSONObject {
        val r = row(id)
        return JSONObject().put("message_id", id).put("sender", r.getString("sender"))
            .put("body", r.getString("body")).put("received_at", Instant.ofEpochMilli(r.getLong("received_at")).toString())
            .put("sim_slot", if (r.isNull("slot")) JSONObject.NULL else r.getInt("slot"))
            .apply {
                if (!r.isNull("capture_allocation_id")) {
                    put("capture_allocation_id", r.getString("capture_allocation_id"))
                    put("capture_device_id", r.getString("device_id"))
                }
            }
    }

    /** Reconcile a crash between provider insert and queue update without creating a second SMS. */
    fun ensureProviderCopy(id: String) = synchronized(SmsSupport.storageLock) {
        val r = row(id)
        if (!r.isNull("provider_uri")) return@synchronized
        val found = findProviderCopy(r)
        val uri = found ?: context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
            put(Telephony.Sms.ADDRESS, r.getString("sender")); put(Telephony.Sms.BODY, r.getString("body"))
            put(Telephony.Sms.DATE, r.getLong("received_at")); put(Telephony.Sms.DATE_SENT, r.getLong("sent_at"))
            put(Telephony.Sms.READ, 0); put(Telephony.Sms.SEEN, 0); put(Telephony.Sms.SUBSCRIPTION_ID, r.getInt("subscription"))
        })
        if (uri != null) writableDatabase.update("received", ContentValues().apply { put("provider_uri", uri.toString()) }, "id=?", arrayOf(id))
    }
    private fun findProviderCopy(r: JSONObject): Uri? = context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
        arrayOf(Telephony.Sms._ID), "address=? AND body=? AND date=? AND sub_id=?",
        arrayOf(r.getString("sender"), r.getString("body"), r.getString("received_at"), r.getString("subscription")), null)?.use {
        if (it.moveToFirst()) Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, it.getString(0)) else null
    }
    override fun pending(owner: SmsOwner): List<SmsDelivery> = readableDatabase.query("received",
        arrayOf("id", "acknowledged"), "username=? AND line_id=? AND device_id=?",
        arrayOf(owner.username, owner.lineId, owner.deviceId), null, null, "received_at ASC", "20").use { c ->
        buildList { while (c.moveToNext()) add(SmsDelivery(c.getString(0), owner, c.getInt(1) == 1)) }
    }
    override fun acknowledge(id: String) {
        check(writableDatabase.update("received", ContentValues().apply { put("acknowledged", 1) }, "id=?", arrayOf(id)) == 1)
    }
    override fun removeConfirmed(id: String): Boolean = synchronized(SmsSupport.storageLock) {
        val r = row(id)
        if (r.getInt("acknowledged") != 1) return@synchronized false
        // Even an insert whose result was lost must be reconciled before local cleanup.
        val uri = if (r.isNull("provider_uri")) findProviderCopy(r) else Uri.parse(r.getString("provider_uri"))
        if (uri != null) {
            check(uri.scheme == "content" && uri.authority == "sms")
            context.contentResolver.delete(uri, "address=? AND body=? AND date=? AND sub_id=?",
                arrayOf(r.getString("sender"), r.getString("body"), r.getString("received_at"), r.getString("subscription")))
            // A failed/ignored provider deletion must not discard the retry record.
            if (findProviderCopy(r) != null) return@synchronized false
        }
        writableDatabase.delete("received", "id=? AND acknowledged=1", arrayOf(id)) == 1
    }
}
