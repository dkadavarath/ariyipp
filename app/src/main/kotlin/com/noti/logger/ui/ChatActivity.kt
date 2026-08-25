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
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.addTextChangedListener
import com.noti.shared.WireMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A single conversation's messages as chat bubbles, with a compose bar and per-message actions. */
class ChatActivity : AppCompatActivity() {

    private lateinit var sender: String
    private lateinit var adapter: MessageAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var stickyDate: TextView
    private lateinit var scrollBottomBtn: View

    /** Message id to scroll to and flash once, when opened from a notification. */
    private var highlightId: Long = -1L

    // Scroll-up pagination: load the newest page first, then older pages as the user scrolls up,
    // so a years-long history doesn't reload in full on every resume.
    private var loadedCount = 0
    private var allLoaded = false
    private var loadingOlder = false

    // Bubble colours, resolved once (stable for this activity instance) instead of per bind.
    private var colorPrimary = 0
    private var colorSurfaceVariant = 0
    private var colorOnPrimary = 0
    private var colorOnSurface = 0
    // In-bubble timestamp colours: dimmed against each bubble fill.
    private var colorTimeOnPrimary = 0
    private var colorTimeOnSurface = 0

    /** The body TextView's max width in px (set in onCreate, applied per bind) - the line-width
     *  budget [reserveMetaSpace] measures against. */
    private var bodyMaxPx = 0
    // Distinct accent for a "delivered" (blue) read-receipt tick, against the outgoing bubble fill.
    private var colorTickDelivered = 0

    /** Sized to match txt_time (11sp) so [reserveMetaSpace] can measure how much blank room the
     *  time/ticks need at the end of a bubble's text. */
    private val metaPaint = android.text.TextPaint()

    /** Per-bind-state bubble corner radii (2 sides × 4 group positions), computed once and reused -
     *  a float array is immutable once built, so sharing it *by reference* across many
     *  GradientDrawables is safe (unlike sharing a whole Drawable instance - see [groupedBubble]). */
    private val bubbleCache = HashMap<Triple<Boolean, Boolean, Boolean>, FloatArray>()

    /** reserveMetaSpace result per (clock text, outgoing) - avoids re-measuring on every bind. */
    private val metaReserveCache = HashMap<String, Int>()

    /** The px width the meta row needs, per (clock text, ticks) - see [reserveMetaSpace]. */
    private val metaReserveWidthCache = HashMap<String, Float>()

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
        colorTimeOnPrimary = ColorUtils.setAlphaComponent(colorOnPrimary, 179) // ~70%
        colorTimeOnSurface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        colorTickDelivered = ContextCompat.getColor(this, R.color.chat_tick_delivered)
        metaPaint.textSize = 11f * resources.displayMetrics.scaledDensity
        bodyMaxPx = (resources.displayMetrics.widthPixels * 0.80f).toInt() - (32f * resources.displayMetrics.density).toInt()

        adapter = MessageAdapter()
        recycler = findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply { stackFromEnd = true }
            adapter = this@ChatActivity.adapter
        }

        // Live updates: any insert/delete or delivery-status change reloads (paged) while the chat
        // is open, so ticks appear without leaving the screen. DiffUtil keeps the rebinding minimal.
        lifecycleScope.launch {
            NotiDatabase.get(this@ChatActivity).relayedMessageDao().changeToken()
                .distinctUntilChanged()
                .drop(1) // onCreate's load() covers the initial state
                .collect {
                    // Respect deep pagination: don't yank the user back to the newest page.
                    if (loadedCount <= PAGE_SIZE) {
                        val lm = recycler.layoutManager as LinearLayoutManager
                        val last = lm.findLastVisibleItemPosition()
                        val atBottom = last == RecyclerView.NO_POSITION || last >= adapter.itemCount - 1
                        load(scrollToEnd = atBottom)
                    }
                }
        }
        stickyDate = findViewById(R.id.txt_sticky_date)
        scrollBottomBtn = findViewById<View>(R.id.btn_scroll_bottom).apply {
            setOnClickListener { recycler.smoothScrollToPosition(adapter.itemCount - 1) }
        }
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updateStickyDate()
                updateScrollBottomVisibility()
                maybeLoadOlder()
            }
        })

        val sendButton = findViewById<View>(R.id.btn_send)
        sendButton.setOnClickListener { sendComposed() }
        val composeField = findViewById<android.widget.EditText>(R.id.et_compose)
        updateSendEnabled(sendButton, composeField.text?.isNotBlank() == true)
        composeField.addTextChangedListener(afterTextChanged = {
            updateSendEnabled(sendButton, it?.isNotBlank() == true)
        })
        load()
    }

    /** The send action only reads as "live" once there's something to send. */
    private fun updateSendEnabled(button: View, hasText: Boolean) {
        button.isEnabled = hasText
        button.alpha = if (hasText) 1f else 0.4f
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
            val msgId = withContext(Dispatchers.IO) {
                val dao = NotiDatabase.get(this@ChatActivity).relayedMessageDao()
                val id = dao.insert(
                    RelayedMessageEntity(sender = sender, sim = "", body = text, receivedAt = System.currentTimeMillis(), outgoing = 1)
                )
                // Same retention rule the inbound path applies (best-effort).
                try {
                    val days = Settings.get(this@ChatActivity).retentionDays.coerceAtLeast(0)
                    dao.purgeOlderThan(System.currentTimeMillis() - days * 86_400_000L)
                } catch (_: Exception) {
                }
                id
            }
            load(scrollToEnd = true)
            val ok = withContext(Dispatchers.IO) { NotiCommandSender.send(applicationContext, sender, text, msgId = msgId) }
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
                val msgs = dao.messagesPage(sender, PAGE_SIZE, 0)
                dao.markRead(sender) // viewing the conversation clears its unread state
                msgs
            }
            loadedCount = messages.size
            allLoaded = messages.size < PAGE_SIZE
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
            recycler.post { updateStickyDate(); updateScrollBottomVisibility() }
        }
    }

    /** Near the top of the list and more history exists → fetch and prepend the previous page. */
    private fun maybeLoadOlder() {
        if (allLoaded || loadingOlder || adapter.itemCount == 0) return
        val first = (recycler.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
        if (first in 0..LOAD_OLDER_THRESHOLD) {
            loadingOlder = true
            lifecycleScope.launch {
                val older = withContext(Dispatchers.IO) {
                    NotiDatabase.get(this@ChatActivity).relayedMessageDao()
                        .messagesPage(sender, PAGE_SIZE, loadedCount)
                }
                if (older.isEmpty()) {
                    allLoaded = true
                    loadingOlder = false
                    return@launch
                }
                loadedCount += older.size
                if (older.size < PAGE_SIZE) allLoaded = true

                // The diff prepends rows; shift the viewport by the added count so the content the
                // user was looking at stays put instead of jumping.
                val before = adapter.itemCount
                val anchor = (recycler.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                adapter.prepend(older)
                recycler.post {
                    (recycler.layoutManager as LinearLayoutManager).scrollToPosition(anchor + (adapter.itemCount - before))
                    updateStickyDate()
                    updateScrollBottomVisibility()
                }
                loadingOlder = false
            }
        }
    }

    /** The floating date pill shows whichever day is at the top of the viewport,
     *  and hides itself when the real separator for that day is already sitting right there. */
    private fun updateStickyDate() {
        val lm = recycler.layoutManager as LinearLayoutManager
        val firstVisible = lm.findFirstVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION || adapter.isSeparatorAt(firstVisible)) {
            stickyDate.visibility = View.GONE
            return
        }
        val label = adapter.dateLabelAt(firstVisible)
        if (label == null) {
            stickyDate.visibility = View.GONE
        } else {
            stickyDate.text = label
            stickyDate.visibility = View.VISIBLE
        }
    }

    private fun updateScrollBottomVisibility() {
        val lm = recycler.layoutManager as LinearLayoutManager
        val lastVisible = lm.findLastVisibleItemPosition()
        val atBottom = lastVisible == RecyclerView.NO_POSITION || lastVisible >= adapter.itemCount - 1
        scrollBottomBtn.visibility = if (atBottom) View.GONE else View.VISIBLE
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

        /** The chronological messages behind [rows]; kept so older pages can be prepended and the
         *  full row list (separators, groups) rebuilt without re-querying the DB. */
        private val messages = ArrayList<RelayedMessageEntity>()

        /** When set, the matching row flashes once on bind then clears (notification deep-link). */
        var flashId: Long = -1L

        /**
         * Rebuilds the rows: a date separator starts each new calendar day (each bubble carries its
         * own clock time, so a separator doesn't need to repeat it). Consecutive
         * same-side messages on the same day form a "group" whose bubbles share connected corners and
         * sit close together - no separate time window on top of the day boundary, since a real
         * back-and-forth (a sender switch) already splits the group on its own.
         */
        fun submit(messages: List<RelayedMessageEntity>) {
            this.messages.clear()
            this.messages.addAll(messages)
            rebuildRows(firstPopulation = rows.isEmpty())
        }

        /** Prepends an older page (scroll-up pagination), rebuilding rows so separators/groups
         *  reflow across the junction. */
        fun prepend(older: List<RelayedMessageEntity>) {
            this.messages.addAll(0, older)
            rebuildRows(firstPopulation = false)
        }

        private fun rebuildRows(firstPopulation: Boolean) {
            val newRows = ArrayList<Row>()
            messages.forEachIndexed { i, m ->
                val prev = messages.getOrNull(i - 1)
                val next = messages.getOrNull(i + 1)
                val newDay = prev == null || !ChatTime.sameDay(prev.receivedAt, m.receivedAt)
                if (newDay) newRows.add(Row.Separator(ChatTime.daySeparator(this@ChatActivity, m.receivedAt), m.id))
                val firstInGroup = newDay || prev!!.outgoing != m.outgoing
                val lastInGroup = next == null || !ChatTime.sameDay(m.receivedAt, next.receivedAt) || next.outgoing != m.outgoing
                newRows.add(Row.Msg(m, firstInGroup, lastInGroup))
            }
            if (firstPopulation) {
                // First population: show it settled, don't animate the whole history in.
                rows.clear()
                rows.addAll(newRows)
                notifyDataSetChanged()
            } else {
                val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(RowDiff(rows, newRows))
                rows.clear()
                rows.addAll(newRows)
                diff.dispatchUpdatesTo(this)
            }
        }

        fun rowIndexOfMessage(id: Long) =
            rows.indexOfFirst { it is Row.Msg && it.message.id == id }

        fun isSeparatorAt(position: Int): Boolean = rows.getOrNull(position) is Row.Separator

        /** The label of the nearest date separator at or before [position] - used to drive the
         *  floating sticky date pill while scrolling. */
        fun dateLabelAt(position: Int): String? {
            for (i in position downTo 0) {
                val row = rows.getOrNull(i)
                if (row is Row.Separator) return row.label
            }
            return null
        }

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
        val timeText = ChatTime.clock(this, m.receivedAt)
        holder.time.text = timeText

        val metrics = resources.displayMetrics
        val d = metrics.density
        holder.body.maxWidth = bodyMaxPx
        (holder.bubble.layoutParams as FrameLayout.LayoutParams).gravity =
            if (outgoing) Gravity.END else Gravity.START

        // Tight within a group, a clear gap between groups.
        val topGap = if (row.firstInGroup) (8f * d).toInt() else (2f * d).toInt()
        holder.itemView.setPadding(holder.itemView.paddingLeft, topGap, holder.itemView.paddingRight, 0)

        val timeColor = if (outgoing) colorTimeOnPrimary else colorTimeOnSurface
        holder.body.setTextColor(if (outgoing) colorOnPrimary else colorOnSurface)
        holder.time.setTextColor(timeColor)
        holder.bubble.background = groupedBubble(outgoing, row.firstInGroup, row.lastInGroup)

        // Read-receipt ticks: only on messages this device sent, and only once the companion has
        // acknowledged something - a still-pending send shows no ticks at all, same as incoming. A
        // failed send also shows none (just the time), rather than inventing a distinct failure icon.
        val showTicks = outgoing && m.status in WireMessage.DeliveryAck.RECEIVED..WireMessage.DeliveryAck.SMS_DELIVERED
        holder.ticks.visibility = if (showTicks) View.VISIBLE else View.GONE
        if (showTicks) {
            holder.ticks.setImageResource(
                if (m.status >= WireMessage.DeliveryAck.SMS_SENT) R.drawable.ic_double_check else R.drawable.ic_single_check
            )
            holder.ticks.setColorFilter(
                if (m.status == WireMessage.DeliveryAck.SMS_DELIVERED) colorTickDelivered else timeColor
            )
        }

        // The bubble must never be narrower than its meta row: the overlay is pinned to the
        // bubble's end, so if the reserved inline space is ever out of sync (e.g. a row last bound
        // while pending gets its ticks later), the ticks would render past the bubble's edge.
        holder.bubble.minimumWidth = kotlin.math.ceil(
            metaPaint.measureText(timeText) + (if (showTicks) (15f * d) + (3f * d) else 0f) + 10f * d
        ).toInt()

        // Reserve blank room at the end of the text for the time/ticks: they
        // ride the last line when it fits, or wrap to their own line when it doesn't - either way no
        // separate row is added, so single-line bubbles stay as short as their text. This only works
        // for LTR-first text: appended characters land at the *logical* end of the string, which for
        // an RTL paragraph renders on the visual left, while the overlay below is pinned to the visual
        // right - so for RTL messages, skip the inline trick and just give the text enough bottom
        // padding to clear the meta row instead, guaranteeing no overlap regardless of the text's own
        // internal alignment.
        val isRtl = android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(m.body, 0, m.body.length)
        val basePadding = intArrayOf(holder.body.paddingLeft, holder.body.paddingTop, holder.body.paddingRight)
        if (isRtl) {
            holder.body.text = m.body
            holder.body.setPadding(basePadding[0], basePadding[1], basePadding[2], (31f * d).toInt())
        } else {
            holder.body.text = m.body + reserveMetaSpace(m.body, holder.body, timeText, showTicks, d)
            holder.body.setPadding(basePadding[0], basePadding[1], basePadding[2], (7f * d).toInt())
        }

        // The meta row (time + ticks) always trails at the bubble's bottom-right - that's where the
        // reserved blank space actually lands in the (LTR) text layout: even incoming bubbles keep
        // their timestamp bottom-right, not mirrored to the bubble's side.
        val metaLp = holder.metaRow.layoutParams as FrameLayout.LayoutParams
        metaLp.gravity = Gravity.BOTTOM or Gravity.END
        holder.metaRow.layoutParams = metaLp

        holder.body.setOnLongClickListener { com.noti.logger.util.Haptics.longPress(it); onMessageLongPress(m); true }
        if (m.id == adapter.flashId) {
            adapter.flashId = -1L
            flash(holder.itemView)
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    /**
     * Appends the blank room the time/ticks meta row needs at the end of the bubble text: either
     * inline on the last line (when it fits, so single-line bubbles stay as
     * short as their text), or on its own final line (when it doesn't). The overlay is pinned to the
     * bubble's bottom-right either way.
     *
     * The fit check matters: the inline reserve is an unbreakable non-breaking-space run, and when
     * it wraps, Android trims the resulting whitespace-only line entirely - the bubble doesn't grow,
     * and the overlay lands on top of the real last text line. Measuring up front (with a
     * StaticLayout using the view's own paint/break settings, so wrapping matches) lets us choose
     * the own-line form deterministically instead.
     */
    private fun reserveMetaSpace(body: String, bodyView: TextView, timeText: String, showTicks: Boolean, d: Float): String {
        if (body.isEmpty()) return ""
        val spaceCount = metaReserveCache.getOrPut("n|$timeText|$showTicks") {
            val ticksWidth = if (showTicks) (15f * d) + (3f * d) else 0f
            val endPadding = 10f * d
            val reserveWidthPx = metaPaint.measureText(timeText) + ticksWidth + endPadding
            val spaceWidthPx = bodyView.paint.measureText(" ").coerceAtLeast(1f)
            kotlin.math.ceil(reserveWidthPx / spaceWidthPx).toInt().coerceAtLeast(1)
        }
        val reserveWidth = metaReserveWidthCache.getOrPut("$timeText|$showTicks") {
            val ticksWidth = if (showTicks) (15f * d) + (3f * d) else 0f
            metaPaint.measureText(timeText) + ticksWidth + 10f * d
        }

        // Would time+ticks fit after the body's real last line? Measure the body alone with the
        // same constraints the actual layout will use.
        val available = bodyMaxPx - bodyView.paddingLeft - bodyView.paddingRight
        if (available > 0) {
            val sl = android.text.StaticLayout.Builder
                .obtain(body, 0, body.length, bodyView.paint, available)
                .setBreakStrategy(bodyView.breakStrategy)
                .setHyphenationFrequency(bodyView.hyphenationFrequency)
                .build()
            val fitsInline = sl.getLineRight(sl.lineCount - 1) + reserveWidth <= available
            if (!fitsInline) return "\n" + " ".repeat(spaceCount)
        }
        return " " + " ".repeat(spaceCount)
    }

    /**
     * Builds a bubble background whose corners connect within a same-side group: the "free" side is
     * always fully rounded; on the spine side, the top rounds only for the first message and the
     * bottom only for the last, so a run of messages reads as one shape (Google Messages).
     * Cached per (side, group position) - see [bubbleCache].
     */
    private fun groupedBubble(outgoing: Boolean, first: Boolean, last: Boolean): GradientDrawable {
        val radii = bubbleCache.getOrPut(Triple(outgoing, first, last)) {
            val d = resources.displayMetrics.density
            // Kept safely under half the shortest possible bubble height (a single line of body
            // text plus its fixed padding, ~31dp regardless of content) so the "big" radius never
            // exceeds what the shape can actually draw - past that point GradientDrawable clamps
            // it into an oversized, blown-out pill instead of a normal rounded corner.
            val big = 12f * d
            val small = 3f * d
            val topSpine = if (first) big else small
            val botSpine = if (last) big else small
            if (outgoing) {
                // spine = right edge
                floatArrayOf(big, big, topSpine, topSpine, botSpine, botSpine, big, big)
            } else {
                // spine = left edge
                floatArrayOf(topSpine, topSpine, big, big, big, big, botSpine, botSpine)
            }
        }
        // A fresh Drawable per bubble - Drawable state (bounds, invalidation callback) belongs to a
        // single View, so several concurrently visible bubbles can never end up sharing one and
        // fighting over it. The radii array itself is immutable once built, so reusing that *array*
        // (not the Drawable) by reference across instances is safe and skips re-deriving it.
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
            setColor(if (outgoing) colorPrimary else colorSurfaceVariant)
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
        val bubble: View = v.findViewById(R.id.bubble)
        val body: TextView = v.findViewById(R.id.txt_body)
        val metaRow: android.widget.LinearLayout = v.findViewById(R.id.meta_row)
        val time: TextView = v.findViewById(R.id.txt_time)
        val ticks: android.widget.ImageView = v.findViewById(R.id.img_ticks)
    }

    private inner class SeparatorVH(v: View) : RecyclerView.ViewHolder(v) {
        val date: TextView = v.findViewById(R.id.txt_date)
    }

    companion object {
        const val EXTRA_SENDER = "sender"
        const val EXTRA_HIGHLIGHT_ID = "highlight_id"

        /** Newest-page size for the scroll-up pagination (and the stride for older pages). */
        private const val PAGE_SIZE = 300

        /** Start loading older history when the user is this close to the top of the list. */
        private const val LOAD_OLDER_THRESHOLD = 10

        private const val MENU_DELETE = 1
        private const val TYPE_SEPARATOR = 0
        private const val TYPE_MSG = 1
    }
}
