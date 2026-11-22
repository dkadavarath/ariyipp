package com.noti.logger.push

import android.content.Context
import com.noti.logger.config.Settings
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Binds a notification's inline-reply action to the conversation it was posted for. The action is
 * a mutable PendingIntent (the system attaches the typed text), so its extras can be replaced by
 * whoever fires it - including any app with notification access, which receives it with the
 * notification. Without this, such an app could make the companion text any number.
 *
 * [sign] is an HMAC-SHA256 over the sender and notification id with a secret only this phone
 * holds (Settings.replySecret); [verify] accepts the reply only if they still match. A genuine
 * reply - from the shade, a watch, Android Auto - arrives with the extras untouched and passes.
 */
object ReplySignature {

    fun sign(context: Context, sender: String, notifId: Int): String =
        android.util.Base64.encodeToString(mac(context, sender, notifId), android.util.Base64.NO_WRAP)

    fun verify(context: Context, sender: String, notifId: Int, sig: String?): Boolean {
        if (sig.isNullOrBlank()) return false
        val given = try { android.util.Base64.decode(sig, android.util.Base64.NO_WRAP) } catch (e: IllegalArgumentException) { return false }
        return MessageDigest.isEqual(given, mac(context, sender, notifId))
    }

    private fun mac(context: Context, sender: String, notifId: Int): ByteArray {
        val key = android.util.Base64.decode(Settings.get(context).replySecret, android.util.Base64.NO_WRAP)
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal("$sender\u0000$notifId".toByteArray(Charsets.UTF_8))
        }
    }
}
