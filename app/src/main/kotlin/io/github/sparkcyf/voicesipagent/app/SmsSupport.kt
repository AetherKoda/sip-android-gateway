package io.github.sparkcyf.voicesipagent.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Default handlers persist incoming messages even after logout; being logged out must not lose SMS. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val pending = goAsync()
        smsWorker.execute {
            try {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (messages.isNullOrEmpty()) return@execute
                val address = messages.first().originatingAddress.orEmpty()
                val body = messages.joinToString("") { it.messageBody.orEmpty() }
                val receivedAt = System.currentTimeMillis()
                val subscription = intent.getIntExtra("subscription", -1)
                val slot = runCatching { SubscriptionManager.getSlotIndex(subscription) }.getOrNull()?.takeIf { it in 0..1 }
                // Durable account-bound queue first; only a server storage ACK permits local deletion.
                SmsOutbox(context).use { outbox ->
                    val id = outbox.receive(address, body, receivedAt, messages.first().timestampMillis, subscription, slot)
                    runCatching { outbox.ensureProviderCopy(id) }
                }
                DeviceSyncService.schedule(context)
                SmsSupport.notify(context, "簡訊已安全暫存；後台確認保存後才會清除手機副本")
            } catch (_: Exception) {
                SmsSupport.notify(context, "簡訊儲存異常，請檢查手機空間與權限")
            } finally { pending.finish() }
        }
    }
}

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val data = intent.getByteArrayExtra("data") ?: return
        val pending = goAsync()
        smsWorker.execute {
            try {
                SmsSupport.journal(context, data, "mms")
                SmsSupport.notify(context, "收到 MMS 通知；附件尚未支援，請切回原簡訊 APP 並聯絡傳送者")
            } catch (_: Exception) {
                SmsSupport.notify(context, "MMS 通知無法儲存，請檢查手機空間並聯絡傳送者")
            } finally { pending.finish() }
        }
    }
}

object SmsSupport {
    internal val storageLock = Any()
    /** Preserve pre-upgrade journals without assigning their contents to a new login. */
    fun recoverLegacyJournal(context: Context) = synchronized(storageLock) {
        File(context.filesDir, "pending-messages").listFiles().orEmpty().filter { it.extension == "sms" }.forEach { file ->
            runCatching {
                val j = JSONObject(file.readText())
                val selection = "address=? AND body=? AND date=? AND sub_id=?"
                val args = arrayOf(j.getString("address"), j.getString("body"), j.getLong("received_at").toString(), j.getInt("subscription").toString())
                val exists = context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("_id"), selection, args, null)?.use { it.moveToFirst() } == true
                val inserted = if (exists) true else context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, args[0]); put(Telephony.Sms.BODY, args[1]); put(Telephony.Sms.DATE, j.getLong("received_at"))
                    put(Telephony.Sms.SUBSCRIPTION_ID, j.getInt("subscription")); put(Telephony.Sms.READ, 0)
                }) != null
                if (inserted) file.delete()
            }
        }
    }
    fun journal(context: Context, data: ByteArray, suffix: String): File {
        val dir = File(context.filesDir, "pending-messages").apply { mkdirs() }
        val ready = File(dir, "${System.currentTimeMillis()}-${UUID.randomUUID()}.$suffix")
        val partial = File(dir, ready.name + ".pending")
        partial.outputStream().use { out -> out.write(data); out.flush(); out.fd.sync() }
        check(partial.renameTo(ready)) { "Message journal could not be published" }
        return ready
    }
    fun notify(context: Context, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sms", "簡訊通知", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 7, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching { nm.notify(7, Notification.Builder(context, "sms").setSmallIcon(R.drawable.ic_gateway)
            .setContentTitle("YX 通訊").setContentText(text).setContentIntent(open).setAutoCancel(true).build()) }
    }
    fun send(context: Context, number: String, body: String, subscriptionId: Int) {
        require(SessionStore(context).load() != null) { "請先登入。" }
        require(Telephony.Sms.getDefaultSmsPackage(context) == context.packageName) { "請先設定預設簡訊 APP。" }
        require(number.matches(Regex("\\+?[0-9]{3,20}")) && body.isNotBlank() && body.length <= 1600) { "請確認電話號碼及簡訊內容（最多 1600 字）。" }
        require(SubscriptionManager.isValidSubscriptionId(subscriptionId)) { "請先選擇發送簡訊的 SIM。" }
        @Suppress("DEPRECATION")
        val manager = SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        val parts = manager.divideMessage(body)
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, number); put(Telephony.Sms.BODY, body); put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX); put(Telephony.Sms.READ, 1); put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
        }
        val uri = context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values) ?: error("無法儲存待傳送簡訊。")
        val callbacks = ArrayList(parts.indices.map { index ->
            val result = Intent(context, SmsSentReceiver::class.java).setData(uri.buildUpon().fragment(index.toString()).build())
                .putExtra("message_uri", uri.toString()).putExtra("part", index).putExtra("parts", parts.size)
            PendingIntent.getBroadcast(context, index, result, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        })
        try { manager.sendMultipartTextMessage(number, null, parts, callbacks, null) }
        catch (e: Exception) {
            context.contentResolver.update(uri, ContentValues().apply { put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED) }, null, null)
            throw e
        }
    }
}

class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra("message_uri") ?: return
        val uri = Uri.parse(raw)
        if (uri.scheme != "content" || uri.authority != "sms") return
        val total = intent.getIntExtra("parts", 0)
        val part = intent.getIntExtra("part", -1)
        if (total !in 1..32 || part !in 0 until total) return
        val prefs = context.getSharedPreferences("sms_results", Context.MODE_PRIVATE)
        val ok = resultCode == Activity.RESULT_OK
        val key = "${uri.lastPathSegment}:$part"
        prefs.edit().putBoolean(key, ok).commit()
        val complete = (0 until total).all { prefs.contains("${uri.lastPathSegment}:$it") }
        val failed = (0 until total).any { prefs.contains("${uri.lastPathSegment}:$it") && !prefs.getBoolean("${uri.lastPathSegment}:$it", false) }
        if (complete || failed) {
            context.contentResolver.update(uri, ContentValues().apply {
                put(Telephony.Sms.TYPE, if (failed) Telephony.Sms.MESSAGE_TYPE_FAILED else Telephony.Sms.MESSAGE_TYPE_SENT)
            }, null, null)
            SmsSupport.notify(context, if (failed) "簡訊傳送失敗，請檢查簡訊紀錄" else "簡訊已交給電信網路（不代表對方已讀）")
        }
        if (complete) prefs.edit().apply { (0 until total).forEach { remove("${uri.lastPathSegment}:$it") } }.apply()
    }
}

class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = intent?.data?.schemeSpecificPart.orEmpty()
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        smsWorker.execute {
            runCatching { SmsSupport.send(this, number, text, SubscriptionManager.getDefaultSmsSubscriptionId()) }
                .onFailure { SmsSupport.notify(this, "快速回覆未送出，請登入後開啟簡訊重試") }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}
private val smsWorker = Executors.newSingleThreadExecutor { Thread(it, "sms-storage") }
