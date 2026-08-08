package com.noti.logger.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.RelayedMessageEntity
import com.noti.logger.push.NotiCommandSender
import com.noti.logger.util.Theming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A single conversation's messages, as chat bubbles. Read-only for now; composing/sending is Phase B. */
class ChatActivity : AppCompatActivity() {

    private lateinit var sender: String
    private lateinit var adapter: MessageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        Theming.applyDynamicColorIfEnabled(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        val root = findViewById<View>(R.id.screen_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, maxOf(bars.bottom, ime.bottom))
            insets
        }

        sender = intent.getStringExtra(EXTRA_SENDER).orEmpty()
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = sender
        }

        adapter = MessageAdapter()
        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply { stackFromEnd = true }
            adapter = this@ChatActivity.adapter
        }

        findViewById<View>(R.id.btn_send).setOnClickListener { sendComposed() }
        load()
    }

    private fun sendComposed() {
        val field = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_compose)
        val text = field.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (!NotiCommandSender.isConfigured(Settings.get(this))) {
            android.widget.Toast.makeText(this, R.string.chat_send_not_configured, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        field.setText("")
        lifecycleScope.launch {
            // Record it locally right away (optimistic), then push the command to sndi.
            withContext(Dispatchers.IO) {
                NotiDatabase.get(this@ChatActivity).relayedMessageDao().insert(
                    RelayedMessageEntity(sender = sender, sim = "", body = text, receivedAt = System.currentTimeMillis(), outgoing = 1)
                )
            }
            load()
            val ok = withContext(Dispatchers.IO) { NotiCommandSender.send(applicationContext, sender, text) }
            if (!ok) {
                android.widget.Toast.makeText(this@ChatActivity, R.string.chat_send_failed, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val messages = withContext(Dispatchers.IO) {
                NotiDatabase.get(this@ChatActivity).relayedMessageDao().messagesFor(sender)
            }
            adapter.submit(messages)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_DELETE, 0, R.string.chat_delete)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        MENU_DELETE -> { confirmDelete(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.chat_delete_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.chat_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        NotiDatabase.get(this@ChatActivity).relayedMessageDao().deleteConversation(sender)
                    }
                    finish()
                }
            }
            .show()
    }

    private inner class MessageAdapter : RecyclerView.Adapter<MessageVH>() {
        private val items = ArrayList<RelayedMessageEntity>()

        fun submit(list: List<RelayedMessageEntity>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
            return MessageVH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: MessageVH, position: Int) {
            val m = items[position]
            holder.body.text = m.body
            holder.time.text = DateUtils.getRelativeTimeSpanString(
                m.receivedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            val outgoing = m.outgoing != 0
            holder.bubble.setBackgroundResource(if (outgoing) R.drawable.bubble_out else R.drawable.bubble_in)
            (holder.bubble.layoutParams as FrameLayout.LayoutParams).gravity =
                if (outgoing) android.view.Gravity.END else android.view.Gravity.START
        }
    }

    private inner class MessageVH(v: View) : RecyclerView.ViewHolder(v) {
        val bubble: LinearLayout = v.findViewById(R.id.bubble)
        val body: TextView = v.findViewById(R.id.txt_body)
        val time: TextView = v.findViewById(R.id.txt_time)
    }

    companion object {
        const val EXTRA_SENDER = "sender"
        private const val MENU_DELETE = 1
    }
}
