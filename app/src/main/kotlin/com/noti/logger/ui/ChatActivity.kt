package com.noti.logger.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
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

    // Bubble colours, resolved once (stable for this activity instance) instead of per bind.
    private var colorPrimary = 0
    private var colorSurfaceVariant = 0
    private var colorOnPrimary = 0
    private var colorOnSurface = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        Theming.applyDynamicColorIfEnabled(this)
        super.onCreate(savedInstanceState)
        Theming.applyAmoledIfEnabled(this) // after super so AppCompat doesn't reset the overlay
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

        colorPrimary = MaterialColors.getColor(root, com.google.android.material.R.attr.colorPrimary)
        colorSurfaceVariant = MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurfaceVariant)
        colorOnPrimary = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnPrimary)
        colorOnSurface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurface)

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
            load(scrollToEnd = true)
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

    private fun load(scrollToEnd: Boolean = false) {
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
            } else if (scrollToEnd && adapter.itemCount > 0) {
                // Ease the just-sent bubble into view above the keyboard (stackFromEnd only helps the
                // initial layout). Smooth-scroll pairs with the item-add animation for a chat feel.
                recycler.post { recycler.smoothScrollToPosition(adapter.itemCount - 1) }
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

    /** A chat row is either a centered time separator or a message bubble (with its group position). */
    private sealed interface Row {
        // key = id of the message that opens this cluster, so two same-day clusters (labelled alike)
        // still read as distinct items to DiffUtil.
        data class Separator(val label: String, val key: Long) : Row
        data class Msg(
            val message: RelayedMessageEntity,
            val firstInGroup: Boolean,
            val lastInGroup: Boolean,
        ) : Row
    }

    /** Minimal-change diff so a new bubble animates in (and its neighbour's corners reflow) instead
     *  of the whole list repainting. */
    private class RowDiff(val old: List<Row>, val new: List<Row>) : androidx.recyclerview.widget.DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(o: Int, n: Int): Boolean {
            val a = old[o]; val b = new[n]
            return when {
                a is Row.Separator && b is Row.Separator -> a.key == b.key
                a is Row.Msg && b is Row.Msg -> a.message.id == b.message.id
                else -> false
            }
        }
        override fun areContentsTheSame(o: Int, n: Int) = old[o] == new[n]
    }

    private inner class MessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val rows = ArrayList<Row>()

        /** When set, the matching row flashes once on bind then clears (notification deep-link). */
        var flashId: Long = -1L

        /**
         * Rebuilds the rows Google-Messages style: a centered time separator starts each cluster (new
         * day or a gap over [CLUSTER_GAP_MS]); consecutive same-side messages inside a cluster are a
         * "group" whose bubbles share connected corners.
         */
        fun submit(messages: List<RelayedMessageEntity>) {
            val newRows = ArrayList<Row>()
            messages.forEachIndexed { i, m ->
                val prev = messages.getOrNull(i - 1)
                val next = messages.getOrNull(i + 1)
                val newCluster = prev == null || startsNewCluster(prev, m)
                if (newCluster) newRows.add(Row.Separator(ChatTime.clusterHeader(this@ChatActivity, m.receivedAt), m.id))
                val firstInGroup = newCluster || prev!!.outgoing != m.outgoing
                val lastInGroup = next == null || startsNewCluster(m, next) || next.outgoing != m.outgoing
                newRows.add(Row.Msg(m, firstInGroup, lastInGroup))
            }
            if (rows.isEmpty()) {
                // First population: show it settled, don't animate the whole history in.
                rows.addAll(newRows)
                notifyDataSetChanged()
            } else {
                val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(RowDiff(rows, newRows))
                rows.clear()
                rows.addAll(newRows)
                diff.dispatchUpdatesTo(this)
            }
        }

        private fun startsNewCluster(prev: RelayedMessageEntity, m: RelayedMessageEntity): Boolean =
            !ChatTime.sameDay(prev.receivedAt, m.receivedAt) ||
                (m.receivedAt - prev.receivedAt) > CLUSTER_GAP_MS

        fun rowIndexOfMessage(id: Long) =
            rows.indexOfFirst { it is Row.Msg && it.message.id == id }

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) =
            if (rows[position] is Row.Separator) TYPE_SEPARATOR else TYPE_MSG

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_SEPARATOR) {
                SeparatorVH(inflater.inflate(R.layout.item_date_header, parent, false))
            } else {
                MessageVH(inflater.inflate(R.layout.item_message, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Separator -> (holder as SeparatorVH).date.text = row.label
                is Row.Msg -> bindMessage(holder as MessageVH, row)
            }
        }
    }

    private fun bindMessage(holder: MessageVH, row: Row.Msg) {
        val m = row.message
        val outgoing = m.outgoing != 0
        holder.body.text = m.body

        val metrics = resources.displayMetrics
        holder.body.maxWidth = (metrics.widthPixels * 0.80f).toInt() - (32f * metrics.density).toInt()
        (holder.body.layoutParams as FrameLayout.LayoutParams).gravity =
            if (outgoing) Gravity.END else Gravity.START

        val fill = if (outgoing) colorPrimary else colorSurfaceVariant
        holder.body.setTextColor(if (outgoing) colorOnPrimary else colorOnSurface)
        holder.body.background = groupedBubble(outgoing, row.firstInGroup, row.lastInGroup, fill)

        holder.body.setOnLongClickListener { com.noti.logger.util.Haptics.longPress(it); onMessageLongPress(m); true }
        if (m.id == adapter.flashId) {
            adapter.flashId = -1L
            flash(holder.itemView)
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    /**
     * Builds a bubble background whose corners connect within a same-side group: the "free" side is
     * always fully rounded; on the spine side, the top rounds only for the first message and the
     * bottom only for the last, so a run of messages reads as one shape (Google Messages).
     */
    private fun groupedBubble(outgoing: Boolean, first: Boolean, last: Boolean, fill: Int): GradientDrawable {
        val d = resources.displayMetrics.density
        val big = 20f * d
        val small = 6f * d
        val topSpine = if (first) big else small
        val botSpine = if (last) big else small
        val radii = if (outgoing) {
            // spine = right edge
            floatArrayOf(big, big, topSpine, topSpine, botSpine, botSpine, big, big)
        } else {
            // spine = left edge
            floatArrayOf(topSpine, topSpine, big, big, big, big, botSpine, botSpine)
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
            setColor(fill)
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
        val body: TextView = v.findViewById(R.id.txt_body)
    }

    private inner class SeparatorVH(v: View) : RecyclerView.ViewHolder(v) {
        val date: TextView = v.findViewById(R.id.txt_date)
    }

    companion object {
        const val EXTRA_SENDER = "sender"
        const val EXTRA_HIGHLIGHT_ID = "highlight_id"
        private const val MENU_DELETE = 1
        private const val TYPE_SEPARATOR = 0
        private const val TYPE_MSG = 1
        private const val CLUSTER_GAP_MS = 15 * 60 * 1000L
    }
}
