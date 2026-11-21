package com.noti.logger.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.RelayedMessageEntity
import com.noti.logger.push.NotiCommandSender
import com.noti.logger.util.Avatars
import com.noti.logger.util.ChatLinks
import com.noti.logger.util.ChatTime
import com.noti.logger.util.Motion
import com.noti.logger.util.SoftItemAnimator
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

    /** The body TextView's max width in px (set in onCreate, applied per bind) - it caps the
     *  whole bubble (see ChatBubbleLayout). */
    private var bodyMaxPx = 0
    // Distinct accent for a "delivered" (blue) read-receipt tick, against the outgoing bubble fill.
    private var colorTickDelivered = 0
    private lateinit var tickReceived: android.graphics.drawable.Drawable
    private lateinit var tickSent: android.graphics.drawable.Drawable
    private lateinit var tickDelivered: android.graphics.drawable.Drawable

    /** Per-bind-state bubble corner radii (2 sides × 4 group positions), computed once and reused -
     *  a float array is immutable once built, so sharing it *by reference* across many
     *  GradientDrawables is safe (unlike sharing a whole Drawable instance - see [groupedBubble]). */
    private val bubbleCache = HashMap<Triple<Boolean, Boolean, Boolean>, FloatArray>()

    /** Link-marked body per message id - a body never changes, and Linkify's URL scan is not worth
     *  repeating on every bind while scrolling. */
    private val linkCache = HashMap<Long, CharSequence>()

    /** Where the in-progress gesture went down, in the bubble's own coordinates. Recorded without
     *  consuming the event (see [bindMessage]) because neither a click nor a long-press carries the
     *  position, and [ChatLinks.linkAt] needs it to tell a tap on a link from a tap on the text
     *  beside it. One gesture is in flight at a time, so a single pair is enough. */
    private var touchX = 0f
    private var touchY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        Theming.applyDynamicColorIfEnabled(this)
        super.onCreate(savedInstanceState)
        // Messages, chats and reminders: behind the "protect messages" screenshot setting.
        com.noti.sender.ui.ScreenGuard.apply(this, com.noti.sender.ui.ScreenGuard.Kind.MESSAGES)
        Theming.applyAmoledIfEnabled(this) // after super so AppCompat doesn't reset the overlay
        Theming.applyPredictiveBackTransitions(this)
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

        colorPrimary = MaterialColors.getColor(root, androidx.appcompat.R.attr.colorPrimary)
        // Read the fixed resource directly rather than the colorSurfaceVariant theme attribute: under
        // Material You, DynamicColors overrides that attribute with a wallpaper-derived tone
        // independent of our own pinned window background, which can land the incoming bubble only a
        // few units off the page - barely visible. This role (bubble/date-pill/compose-field fill) is
        // deliberately neutral and untouched by dynamic color, same as it already is in AMOLED mode.
        colorSurfaceVariant = ContextCompat.getColor(this, com.noti.sender.R.color.app_surface_variant)
        colorOnPrimary = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnPrimary)
        colorOnSurface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurface)
        colorTimeOnPrimary = ColorUtils.setAlphaComponent(colorOnPrimary, 217) // 85%: the lowest that keeps 11sp time at 4.5:1 on the bubble
        colorTimeOnSurface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        colorTickDelivered = ContextCompat.getColor(this, R.color.chat_tick_delivered)
        // The three tick states, tinted once here rather than per bind.
        fun tick(res: Int, color: Int) = ContextCompat.getDrawable(this, res)!!.mutate().apply {
            val size = (15f * resources.displayMetrics.density).toInt()
            setBounds(0, 0, size, size)
            setTint(color)
        }
        tickReceived = tick(R.drawable.ic_single_check, colorTimeOnPrimary)
        tickSent = tick(R.drawable.ic_double_check, colorTimeOnPrimary)
        tickDelivered = tick(R.drawable.ic_double_check, colorTickDelivered)
        bodyMaxPx = (resources.displayMetrics.widthPixels * 0.80f).toInt() - (32f * resources.displayMetrics.density).toInt()

        adapter = MessageAdapter()
        recycler = findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply { stackFromEnd = true }
            adapter = this@ChatActivity.adapter
            // A new bubble grows out of its own bottom corner - the side it was sent from - while
            // the ones above it move up to make room.
            val d = resources.displayMetrics.density
            itemAnimator = SoftItemAnimator(liftPx = 10f * d, startScale = 0.94f) { holder ->
                if (holder !is MessageVH) return@SoftItemAnimator null
                val v = holder.itemView
                val outgoing = (holder.bubble.layoutParams as FrameLayout.LayoutParams).gravity == Gravity.END
                (if (outgoing) (v.width - v.paddingRight).toFloat() else v.paddingLeft.toFloat()) to v.height.toFloat()
            }
        }

        // Live updates: any insert/delete or delivery-status change reloads (paged) while the chat
        // is open, so ticks appear without leaving the screen. DiffUtil keeps the rebinding minimal.
        // Only while STARTED: load() marks the conversation read, so collecting in the background
        // (user pressed Home with the chat open) silently cleared unread state for messages nobody
        // had seen. onResume's load() catches up on return; drop(1) skips the restart's replay.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NotiDatabase.get(this@ChatActivity).relayedMessageDao().changeToken()
                    .distinctUntilChanged()
                    .drop(1) // onCreate's/onResume's load() covers the initial state
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
        updateSendEnabled(sendButton, composeField.text?.isNotBlank() == true, animate = false)
        composeField.addTextChangedListener(afterTextChanged = {
            updateSendEnabled(sendButton, it?.isNotBlank() == true, animate = true)
        })
        load()
    }

    /** The send action only reads as "live" once there's something to send: it eases up to full
     *  size and opacity with the first character, and back down when the field empties. */
    private fun updateSendEnabled(button: View, hasText: Boolean, animate: Boolean) {
        if (button.isEnabled == hasText && animate) return
        button.isEnabled = hasText
        val alpha = if (hasText) 1f else 0.4f
        val scale = if (hasText) 1f else 0.9f
        if (animate) {
            button.animate().alpha(alpha).scaleX(scale).scaleY(scale)
                .setDuration(Motion.ENTER_MS).setInterpolator(Motion.EASE_OUT).start()
        } else {
            button.alpha = alpha
            button.scaleX = scale
            button.scaleY = scale
        }
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
                // Same chat-retention rule the inbound path applies (best-effort; default keeps all).
                com.noti.logger.data.ChatRetention
                    .cutoff(Settings.get(this@ChatActivity).chatRetentionDays, System.currentTimeMillis())
                    ?.let { cutoff ->
                        try {
                            dao.purgeOlderThan(cutoff)
                        } catch (_: Exception) {
                        }
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
        com.noti.sender.ui.ScreenGuard.apply(this, com.noti.sender.ui.ScreenGuard.Kind.MESSAGES)
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
            Motion.setShown(stickyDate, false)
            return
        }
        val label = adapter.dateLabelAt(firstVisible)
        if (label == null) {
            Motion.setShown(stickyDate, false)
        } else {
            stickyDate.text = label
            Motion.setShown(stickyDate, true)
        }
    }

    private fun updateScrollBottomVisibility() {
        val lm = recycler.layoutManager as LinearLayoutManager
        val lastVisible = lm.findLastVisibleItemPosition()
        val atBottom = lastVisible == RecyclerView.NO_POSITION || lastVisible >= adapter.itemCount - 1
        Motion.setShown(scrollBottomBtn, !atBottom, fromScale = 0.8f)
    }

    /**
     * The per-message menu. [link] is the URL the long-press landed on, if any: it adds the two
     * link actions at the top rather than replacing the menu, so a message that is nothing but a
     * URL keeps Forward, Share and Delete instead of losing them to a link-only menu.
     *
     * Built as label-to-action pairs because the list is no longer fixed - a positional `when` over
     * the index would silently shift every action by one as soon as a link is present.
     */
    private fun onMessageLongPress(m: RelayedMessageEntity, link: String?) {
        val actions = buildList<Pair<String, () -> Unit>> {
            if (link != null) {
                add(getString(R.string.msg_open_link) to { openLink(link) })
                add(getString(R.string.msg_copy_link) to { copyText(link, R.string.msg_link_copied) })
            }
            add(getString(R.string.msg_copy) to { copyText(m.body) })
            add(getString(R.string.msg_select_text) to { showSelectableText(m.body) })
            add(getString(R.string.msg_forward) to {
                startActivity(
                    Intent(this@ChatActivity, ComposeActivity::class.java)
                        .putExtra(ComposeActivity.EXTRA_PREFILL_BODY, m.body)
                )
            })
            add(getString(R.string.msg_share) to { shareText(m.body) })
            // Only for what arrived (a bill never comes in something you sent), and only while the
            // Reminders module is on - a rule made with it off would never run.
            if (m.outgoing == 0 && com.noti.logger.config.Settings.get(this@ChatActivity).reminderEnabled) {
                add(getString(R.string.rm_create_rule_from_msg) to {
                    startActivity(
                        Intent(this@ChatActivity, ReminderRuleActivity::class.java)
                            .putExtra(ReminderRuleActivity.EXTRA_SAMPLE, m.body)
                            .putExtra(ReminderRuleActivity.EXTRA_SENDER, m.sender)
                    )
                })
                add(getString(R.string.rm_use_as_payment) to { useAsPayment(m) })
            }
            add(getString(R.string.msg_delete) to { deleteMessage(m.id) })
        }
        MaterialAlertDialogBuilder(this)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    /** A payment confirmation → pick which bill rule it settles → that rule opens with its payment
     *  section switched on and drafted from this message. */
    private fun useAsPayment(m: RelayedMessageEntity) {
        lifecycleScope.launch {
            val rules = withContext(Dispatchers.IO) {
                try {
                    NotiDatabase.get(applicationContext).reminderRuleDao().all()
                        .filter { it.type == com.noti.logger.reminder.RuleType.BILL.name }
                } catch (e: Exception) { emptyList() }
            }
            if (rules.isEmpty()) {
                Toast.makeText(this@ChatActivity, R.string.rm_no_bill_rules, Toast.LENGTH_LONG).show()
                return@launch
            }
            MaterialAlertDialogBuilder(this@ChatActivity)
                .setTitle(R.string.rm_pick_rule_for_payment)
                .setItems(rules.map { it.name }.toTypedArray()) { _, which -> confirmAutoPaid(rules[which], m) }
                .show()
        }
    }

    /** Says what is about to change before the rule opens - turning the resolver on, or (if it is
     *  already on) replacing the payment details it has with ones drafted from this message. */
    private fun confirmAutoPaid(rule: com.noti.logger.data.ReminderRuleEntity, m: RelayedMessageEntity) {
        val replacing = rule.payEnabled == 1
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.rm_pay_enable_title, rule.name))
            .setMessage(
                if (replacing) getString(R.string.rm_pay_replace_body, rule.name)
                else getString(R.string.rm_pay_enable_body)
            )
            .setPositiveButton(if (replacing) R.string.rm_pay_replace_yes else R.string.rm_pay_enable_yes) { _, _ ->
                startActivity(
                    Intent(this, ReminderRuleActivity::class.java)
                        .putExtra(ReminderRuleActivity.EXTRA_RULE_ID, rule.id)
                        .putExtra(ReminderRuleActivity.EXTRA_PAY_SAMPLE, m.body)
                        .putExtra(ReminderRuleActivity.EXTRA_PAY_SENDER, m.sender)
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Hands a tapped link to whatever the user browses with. Only ever http/https - see [ChatLinks]. */
    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            // No browser installed, or one that refuses the URL. The link is still copyable.
            Toast.makeText(this, R.string.msg_link_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Hands the message off to the system share sheet, so it can be forwarded through any other
     *  installed app - distinct from "Forward", which relays it as a new SMS through the companion. */
    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.msg_share)))
    }

    private fun copyText(text: String, confirmation: Int = R.string.msg_copied) {
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val data = ClipData.newPlainText("message", text)
        // Messages carry OTPs and balances: keep the OS clipboard preview from showing them, as the
        // notification's "Copy code" action already does (Android 13+).
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            data.description.extras = android.os.PersistableBundle().apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clip.setPrimaryClip(data)
        Toast.makeText(this, confirmation, Toast.LENGTH_SHORT).show()
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
        menu.add(0, MENU_MUTE, 0, "")
        menu.add(0, MENU_MARK_READ, 1, R.string.conv_mark_read)
        menu.add(0, MENU_DELETE, 2, R.string.chat_delete)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val muted = Settings.get(this).isMuted(sender)
        menu.findItem(MENU_MUTE).setTitle(if (muted) R.string.conv_unmute else R.string.conv_mute)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        MENU_MUTE -> { toggleMute(); true }
        MENU_MARK_READ -> { markRead(); true }
        MENU_DELETE -> { confirmDelete(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun toggleMute() {
        val s = Settings.get(this)
        s.setMuted(sender, !s.isMuted(sender))
        invalidateOptionsMenu()
    }

    private fun markRead() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                NotiDatabase.get(this@ChatActivity).relayedMessageDao().markRead(sender)
            }
        }
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

    @android.annotation.SuppressLint("ClickableViewAccessibility") // the listener returns false; performClick() still runs
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
        val ticks = when {
            !showTicks -> null
            m.status == WireMessage.DeliveryAck.SMS_DELIVERED -> tickDelivered
            m.status >= WireMessage.DeliveryAck.SMS_SENT -> tickSent
            else -> tickReceived
        }
        // A fresh Drawable per view from the shared state (see groupedBubble on why a Drawable
        // instance must never be shared between views).
        holder.time.setCompoundDrawablesRelative(null, null, ticks?.constantState?.newDrawable()?.also { it.bounds = ticks.bounds }, null)

        // Time and ticks never collide with the text: ChatBubbleLayout measures the body's real
        // last line and puts them on it only when they fit, otherwise on a row of their own.
        // Links take the bubble's own foreground on outgoing (where a blue would be unreadable on
        // the primary fill) and the accent on incoming; either way the URLSpan's underline is what
        // actually marks them out, so the distinction survives any palette Material You picks.
        holder.body.setLinkTextColor(if (outgoing) colorOnPrimary else colorPrimary)
        holder.body.text = linkCache.getOrPut(m.id) { ChatLinks.linkify(m.body) }

        // Returns false throughout: the gesture still reaches the click/long-press handlers below
        // and the RecyclerView's scroll, which is the whole reason the links aren't driven by a
        // MovementMethod. performClick() is therefore still called by the framework.
        holder.body.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                touchX = e.x
                touchY = e.y
            }
            false
        }
        holder.body.setOnClickListener { ChatLinks.linkAt(holder.body, touchX, touchY)?.let { openLink(it) } }
        holder.body.setOnLongClickListener {
            com.noti.logger.util.Haptics.longPress(it)
            onMessageLongPress(m, ChatLinks.linkAt(holder.body, touchX, touchY))
            true
        }
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
     * Cached per (side, group position) - see [bubbleCache].
     */
    private fun groupedBubble(outgoing: Boolean, first: Boolean, last: Boolean): GradientDrawable {
        val radii = bubbleCache.getOrPut(Triple(outgoing, first, last)) {
            val d = resources.displayMetrics.density
            // Kept safely under half the shortest possible bubble height (a single line of body
            // text plus its fixed padding, ~31dp regardless of content) so the "big" radius never
            // exceeds what the shape can actually draw - past that point GradientDrawable clamps
            // it into an oversized, blown-out pill instead of a normal rounded corner.
            val big = 14f * d
            val small = 4f * d
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
        val time: TextView = v.findViewById(R.id.txt_time)
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

        private const val MENU_MUTE = 1
        private const val MENU_MARK_READ = 2
        private const val MENU_DELETE = 3
        private const val TYPE_SEPARATOR = 0
        private const val TYPE_MSG = 1
    }
}
