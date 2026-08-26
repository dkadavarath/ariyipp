package com.noti.logger.ui

import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.EditText
import androidx.appcompat.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.RelayedMessageEntity
import com.noti.logger.push.NotiCommandSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compose a new message (or forward one): pick a contact or type a number, choose which SIM sndi
 * should send from, and send. The message is recorded locally under the recipient and pushed to sndi
 * as a WireMessage.Command; we then open that recipient's chat.
 */
class ComposeActivity : ScreenActivity() {

    override val layoutRes = R.layout.activity_compose
    override val titleRes = R.string.compose_title

    private lateinit var toField: EditText
    private lateinit var bodyField: EditText
    private var selectedSim: Int = -1 // -1 = default SIM, 0 = SIM 1, 1 = SIM 2

    /**
     * Pick a phone number directly (ACTION_PICK on the phone data type). The picker grants read
     * access to just the chosen row, so we can read its number without holding READ_CONTACTS.
     */
    private val pickContact = registerForActivityResult(
        object : ActivityResultContract<Unit, Uri?>() {
            override fun createIntent(context: android.content.Context, input: Unit) =
                Intent(Intent.ACTION_PICK).apply {
                    type = ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE
                }

            override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
        }
    ) { uri -> uri?.let { fillNumberFrom(it) } }

    override fun onScreenCreated() {
        toField = findViewById(R.id.et_to)
        bodyField = findViewById(R.id.et_body)

        intent.getStringExtra(EXTRA_TO)?.let { toField.setText(it) }
        intent.getStringExtra(EXTRA_PREFILL_BODY)?.let { bodyField.setText(it) }

        findViewById<MaterialButton>(R.id.btn_pick_contact).setOnClickListener { pickContact.launch(Unit) }
        findViewById<MaterialButton>(R.id.btn_send).setOnClickListener { send() }
        findViewById<MaterialButton>(R.id.btn_sim).setOnClickListener { v -> showSimMenu(v as MaterialButton) }
    }

    private fun showSimMenu(anchor: MaterialButton) {
        PopupMenu(this, anchor).apply {
            menu.add(0, -1, 0, R.string.compose_sim_default)
            menu.add(0, 0, 1, R.string.compose_sim_1)
            menu.add(0, 1, 2, R.string.compose_sim_2)
            setOnMenuItemClickListener { item ->
                selectedSim = item.itemId
                anchor.text = item.title
                true
            }
            show()
        }
    }

    private fun fillNumberFrom(uri: Uri) {
        val number = try {
            contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
        if (number != null) toField.setText(number) else
            Toast.makeText(this, R.string.compose_contact_failed, Toast.LENGTH_SHORT).show()
    }

    private fun send() {
        val to = toField.text?.toString()?.trim().orEmpty()
        val body = bodyField.text?.toString()?.trim().orEmpty()
        if (to.isEmpty() || body.isEmpty()) {
            Toast.makeText(this, R.string.compose_need_to_and_body, Toast.LENGTH_SHORT).show()
            return
        }
        if (!NotiCommandSender.isConfigured(Settings.get(this))) {
            Toast.makeText(this, R.string.chat_send_not_configured, Toast.LENGTH_LONG).show()
            return
        }
        val sim = selectedSim
        lifecycleScope.launch {
            val msgId = withContext(Dispatchers.IO) {
                NotiDatabase.get(this@ComposeActivity).relayedMessageDao().insert(
                    RelayedMessageEntity(
                        sender = to, sim = "", body = body,
                        receivedAt = System.currentTimeMillis(), outgoing = 1
                    )
                )
            }
            val ok = withContext(Dispatchers.IO) {
                NotiCommandSender.send(applicationContext, to, body, sim, msgId)
            }
            if (!ok) Toast.makeText(this@ComposeActivity, R.string.chat_send_failed, Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(this@ComposeActivity, ChatActivity::class.java)
                    .putExtra(ChatActivity.EXTRA_SENDER, to)
            )
            finish()
        }
    }

    companion object {
        const val EXTRA_TO = "to"
        const val EXTRA_PREFILL_BODY = "prefill_body"
    }
}
