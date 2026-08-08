package com.noti.logger.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
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
import com.noti.logger.util.Avatars
import com.noti.logger.util.ChatTime
import com.noti.logger.util.Theming
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A single conversation's messages as chat bubbles, with a compose bar and per-message actions. */
class ChatActivity : AppCompatActivity() {

    private lateinit var sender: String
    private lateinit var adapter: MessageAdapter
    private lateinit var recycler: RecyclerView

    /** Message id to scroll to and flash once, when opened from a notification. */
    private var highlightId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        Theming.applyDynamicColorIfEnabled(this)
        Theming.applyAmoledIfEnabled(this)
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
        highlightId = intent.getLongExtra(EXTRA_HIGHLIGHT_ID, -1L)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = ""
        }
        findViewById<TextView>(R.id.toolbar_title).text = sender
        Avatars.apply(
            findViewById(R.id.toolbar_avatar),
            findViewById(R.id.avatar_initials),
            findViewById(R.id.avatar_icon),
            sender,
        )

        adapter = MessageAdapter()
        recycler = findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply { stackFromEnd = true }
            adapter = this@ChatActivity.adapter
        }

        findViewById<View>(R.id.btn_send).setOnClickListener { sendComposed() }
        load()
    }

    private fun sendComposed() {
        val field = findViewById<android.widget.EditText>(R.id.et_compose)
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
                val dao = NotiDatabase.get(this@ChatActivity).relayedMessageDao()
                val msgs = dao.messagesFor(sender)
                dao.markRead(sender) // viewing the conversation clears its unread state
                msgs
            }
            adapter.submit(messages)
            if (highlightId > 0) {
                val index = adapter.rowIndexOfMessage(highlightId)
                if (index >= 0) {
                    adapter.flashId = highlightId
                    recycler.post { recycler.scrollToPosition(index); adapter.notifyItemChanged(index) }
                }
                highlightId = -1L // only once
            }
        }
    }

    private fun onMessageLongPress(m: RelayedMessageEntity) {
        val actions = arrayOf(
            getString(R.string.msg_copy),
            getString(R.string.msg_select_text),
            getString(R.string.msg_forward),
            getString(R.string.msg_delete),
        )
        MaterialAlertDialogBuilder(this)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> copyText(m.body)
                    1 -> showSelectableText(m.body)
                    2 -> startActivity(
                        Intent(this, ComposeActivity::class.java)
                            .putExtra(ComposeActivity.EXTRA_PREFILL_BODY, m.body)
                    )
                    3 -> deleteMessage(m.id)
                }
            }
            .show()
    }

    private fun copyText(text: String) {
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("message", text))
        Toast.makeText(this, R.string.msg_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showSelectableText(text: String) {
        val tv = TextView(this).apply {
            setTextIsSelectable(true)
            setText(text)
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.msg_select_text)
            .setView(tv)
            .setPositiveButton(R.string.chat_close, null)
            .show()
    }

    private fun deleteMessage(id: Long) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                NotiDatabase.get(this@ChatActivity).relayedMessageDao().deleteMessage(id)
            }
            load()
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

    /** A chat row is either a day separator or a message bubble. */
    private sealed interface Row {
        data class Day(val label: String) : Row
        data class Msg(val message: RelayedMessageEntity) : Row
    }

    private inner class MessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val rows = ArrayList<Row>()

        /** When set, the matching row flashes once on bind then clears (notification deep-link). */
        var flashId: Long = -1L

        /** Rebuilds the row list, inserting a day separator whenever the calendar day changes. */
        fun submit(messages: List<RelayedMessageEntity>) {
            rows.clear()
            var lastAt = 0L
            for (m in messages) {
                if (rows.isEmpty() || !ChatTime.sameDay(lastAt, m.receivedAt)) {
                    rows.add(Row.Day(ChatTime.daySeparator(this@ChatActivity, m.receivedAt)))
                }
                rows.add(Row.Msg(m))
                lastAt = m.receivedAt
            }
            notifyDataSetChanged()
        }

        fun rowIndexOfMessage(id: Long) =
            rows.indexOfFirst { it is Row.Msg && it.message.id == id }

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) =
            if (rows[position] is Row.Day) TYPE_DAY else TYPE_MSG

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_DAY) {
                DayVH(inflater.inflate(R.layout.item_date_header, parent, false))
            } else {
                MessageVH(inflater.inflate(R.layout.item_message, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Day -> (holder as DayVH).date.text = row.label
                is Row.Msg -> {
                    val prev = rows.getOrNull(position - 1)
                    val grouped = prev is Row.Msg && prev.message.outgoing == row.message.outgoing
                    bindMessage(holder as MessageVH, row.message, grouped)
                }
            }
        }
    }

    private fun bindMessage(holder: MessageVH, m: RelayedMessageEntity, groupedWithPrev: Boolean) {
        val outgoing = m.outgoing != 0
        holder.body.text = m.body
        holder.time.text = ChatTime.clock(this, m.receivedAt)
        holder.bubble.setBackgroundResource(if (outgoing) R.drawable.bubble_out else R.drawable.bubble_in)
        (holder.bubble.layoutParams as FrameLayout.LayoutParams).gravity =
            if (outgoing) Gravity.END else Gravity.START

        // Cap the bubble to ~80% of the screen (LinearLayout ignores maxWidth, so bound the text).
        val metrics = resources.displayMetrics
        holder.body.maxWidth = (metrics.widthPixels * 0.80f).toInt() - (28f * metrics.density).toInt()

        // Tighter spacing within a run from the same side, more air when the side changes.
        val h = (12f * metrics.density).toInt()
        val topGap = ((if (groupedWithPrev) 2f else 8f) * metrics.density).toInt()
        holder.itemView.setPadding(h, topGap, h, (2f * metrics.density).toInt())

        // On the accent outgoing bubble, text is white; incoming uses on-surface tones.
        val bodyColor = MaterialColors.getColor(
            holder.body,
            if (outgoing) com.google.android.material.R.attr.colorOnPrimary
            else com.google.android.material.R.attr.colorOnSurface,
        )
        val timeColor = MaterialColors.getColor(
            holder.time,
            if (outgoing) com.google.android.material.R.attr.colorOnPrimary
            else com.google.android.material.R.attr.colorOnSurfaceVariant,
        )
        holder.body.setTextColor(bodyColor)
        holder.time.setTextColor(if (outgoing) ColorUtils.setAlphaComponent(timeColor, 190) else timeColor)

        holder.bubble.setOnLongClickListener { onMessageLongPress(m); true }
        if (m.id == adapter.flashId) {
            adapter.flashId = -1L
            flash(holder.itemView)
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    /** Briefly tints a row's background to draw the eye to a deep-linked message. */
    private fun flash(view: View) {
        val accent = MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimaryContainer)
        ValueAnimator.ofObject(ArgbEvaluator(), accent, Color.TRANSPARENT).apply {
            duration = 1600
            addUpdateListener { view.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
    }

    private inner class MessageVH(v: View) : RecyclerView.ViewHolder(v) {
        val bubble: LinearLayout = v.findViewById(R.id.bubble)
        val body: TextView = v.findViewById(R.id.txt_body)
        val time: TextView = v.findViewById(R.id.txt_time)
    }

    private inner class DayVH(v: View) : RecyclerView.ViewHolder(v) {
        val date: TextView = v.findViewById(R.id.txt_date)
    }

    companion object {
        const val EXTRA_SENDER = "sender"
        const val EXTRA_HIGHLIGHT_ID = "highlight_id"
        private const val MENU_DELETE = 1
        private const val TYPE_DAY = 0
        private const val TYPE_MSG = 1
    }
}
