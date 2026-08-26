package com.noti.logger.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import androidx.appcompat.widget.PopupMenu
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import android.widget.EditText
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.data.ConversationSummary
import com.noti.logger.data.NotiDatabase
import com.noti.logger.util.Avatars
import com.noti.logger.util.ChatTime
import com.noti.logger.util.Haptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Last-known unfiltered conversation list, kept warm across tab switches - MainActivity recreates
 *  this Fragment on every switch, so without this, returning to Messages would show an empty list
 *  while Room re-queries from scratch instead of the real content it just showed moments ago.
 *  Search results aren't cached here - they're already a fast, scoped query the user just triggered. */
private object MessagesCache {
    @Volatile var convos: List<ConversationSummary>? = null
    @Volatile var muted: Set<String>? = null
}

/** Messages tab: the list of conversations relayed from sndi, with search and multi-select. */
class MessagesFragment : Fragment(R.layout.fragment_messages) {

    private lateinit var adapter: ConversationAdapter
    private lateinit var search: EditText
    private lateinit var searchClear: View
    private lateinit var rowCollapsed: View
    private lateinit var rowSearch: View

    /** While the search is active (focused or non-empty), Back dismisses it instead of leaving the app. */
    private lateinit var searchBack: OnBackPressedCallback

    /** While in multi-select mode, Back exits selection instead of leaving the app. */
    private lateinit var selectionBack: OnBackPressedCallback

    /** Debounce so a burst of keystrokes triggers one query, not one per character. */
    private var searchJob: Job? = null

    /** Active conversation-list collection; restarted when the search query changes. */
    private var listJob: Job? = null

    private var selectionMode = false
    private val selected = LinkedHashSet<String>()

    /** Latest emission, cached so selection-state changes can re-render without re-querying. */
    private var latestConvos: List<ConversationSummary> = emptyList()
    private var latestMuted: Set<String> = emptySet()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Warm cache from a previous visit this process: render it synchronously below instead of
        // waiting on Room, so switching back to this tab is instant. Only hold the tab-switch
        // transition (capped so a slow/stuck query can't hang it forever) when there's nothing to
        // show yet - a cold start, or the first launch this process.
        val cachedConvos = MessagesCache.convos
        val cachedMuted = MessagesCache.muted
        if (cachedConvos == null) postponeEnterTransition(500, TimeUnit.MILLISECONDS)

        adapter = ConversationAdapter(
            onClick = { sender ->
                startActivity(Intent(requireContext(), ChatActivity::class.java).putExtra(ChatActivity.EXTRA_SENDER, sender))
            },
            onLongClick = { sender -> showConversationMenu(sender) },
            onToggleSelect = { sender -> toggleSelected(sender) },
            onAvatarLongPress = { sender -> onAvatarLongPress(sender) },
        )
        adapter.resolveColors(view) // theme colours are stable for this fragment; resolve once, not per row
        view.findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@MessagesFragment.adapter
        }
        if (cachedConvos != null && cachedMuted != null) {
            latestConvos = cachedConvos
            latestMuted = cachedMuted
            renderList()
            view.findViewById<View>(R.id.txt_empty).visibility = if (cachedConvos.isEmpty()) View.VISIBLE else View.GONE
        }
        search = view.findViewById(R.id.et_search)
        searchClear = view.findViewById(R.id.img_search_clear)
        rowCollapsed = view.findViewById(R.id.row_collapsed)
        rowSearch = view.findViewById(R.id.row_search)

        view.findViewById<View>(R.id.btn_search).setOnClickListener { expandSearch() }
        view.findViewById<View>(R.id.btn_overflow).setOnClickListener { showOverflowMenu(it) }

        search.doAfterTextChanged {
            searchClear.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE
            updateSearchBack()
            searchJob?.cancel()
            searchJob = viewLifecycleOwner.lifecycleScope.launch { delay(220); observeConversations() }
        }
        search.setOnFocusChangeListener { _, _ -> updateSearchBack() }
        searchClear.setOnClickListener { closeSearch() }

        searchBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = closeSearch()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, searchBack)

        selectionBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = exitSelectionMode()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, selectionBack)
        // The compose FAB lives in MainActivity (pinned to the screen); it opens ComposeActivity.
    }

    private fun updateSearchBack() {
        searchBack.isEnabled = search.hasFocus() || !search.text.isNullOrEmpty()
    }

    /** Swaps the collapsed search icon for the full search pill and focuses it. */
    private fun expandSearch() {
        rowCollapsed.visibility = View.GONE
        rowSearch.visibility = View.VISIBLE
        search.requestFocus()
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Clears the query, drops focus, and hides the keyboard - the search's "close" action - then
     *  collapses the pill back to the icon. */
    private fun closeSearch() {
        search.setText("")
        search.clearFocus()
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(search.windowToken, 0)
        searchBack.isEnabled = false
        rowSearch.visibility = View.GONE
        rowCollapsed.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        // No manual reload needed: the Room Flow below re-emits on any table change. (Mute toggles
        // are prefs, not Room, so they re-observe explicitly.)
        observeConversations()
    }

    /**
     * Collects the conversation list as a Room Flow: inserts/reads/deletes anywhere in the app push
     * a fresh aggregate here, instead of re-querying the whole table on every resume.
     */
    private fun observeConversations() {
        val query = search.text?.toString().orEmpty().trim()
        listJob?.cancel()
        listJob = viewLifecycleOwner.lifecycleScope.launch {
            val dao = NotiDatabase.get(requireContext()).relayedMessageDao()
            val flow = if (query.isEmpty()) dao.conversationsFlow() else dao.searchConversationsFlow(query)
            flow.map { list ->
                // Read the muted set once per emission instead of decrypting it per row in onBind.
                list to Settings.get(requireContext()).mutedSenders
            }.collect { (convos, muted) ->
                latestConvos = convos
                latestMuted = muted
                if (query.isEmpty()) {
                    MessagesCache.convos = convos
                    MessagesCache.muted = muted
                }
                // Drop any selected sender that fell out of the (possibly filtered) list, e.g.
                // deleted elsewhere while multi-select was open.
                if (selected.retainAll(convos.map { it.sender }.toSet()) && selectionMode) updateSelectionTitle()
                renderList()
                view?.findViewById<View>(R.id.txt_empty)?.visibility =
                    if (convos.isEmpty()) View.VISIBLE else View.GONE
                startPostponedEnterTransition()
            }
        }
    }

    private fun renderList() {
        adapter.submit(latestConvos, latestMuted, selectionMode, selected)
    }

    // ---- Single-conversation quick actions (long-press outside selection mode) ----

    private fun showConversationMenu(sender: String) {
        val s = Settings.get(requireContext())
        val muted = s.isMuted(sender)
        val items = arrayOf(
            getString(if (muted) R.string.conv_unmute else R.string.conv_mute),
            getString(R.string.conv_mark_read),
            getString(R.string.chat_delete),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(sender)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { s.setMuted(sender, !muted); observeConversations() }
                    1 -> markConversationRead(sender)
                    2 -> deleteConversation(sender)
                }
            }
            .show()
    }

    private fun markConversationRead(sender: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                NotiDatabase.get(requireContext()).relayedMessageDao().markRead(sender)
            }
            (activity as? MainActivity)?.updateMessagesBadge()
        }
    }

    private fun deleteConversation(sender: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.chat_delete_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.chat_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        NotiDatabase.get(requireContext()).relayedMessageDao().deleteConversation(sender)
                    }
                    (activity as? MainActivity)?.updateMessagesBadge()
                }
            }
            .show()
    }

    // ---- Overflow menu (normal + contextual multi-select) ----

    private fun showOverflowMenu(anchor: View) {
        val allSelectedMuted = selected.isNotEmpty() && selected.all { it in latestMuted }
        val popup = PopupMenu(requireContext(), anchor)
        if (!selectionMode) {
            popup.menu.add(0, M_MARK_ALL_READ, 0, R.string.messages_mark_all_read)
            popup.menu.add(0, M_SELECT_MODE, 1, R.string.messages_select_conversations)
        } else {
            if (selected.size < latestConvos.size) popup.menu.add(0, M_SELECT_ALL, 0, R.string.messages_select_all)
            if (selected.isNotEmpty()) popup.menu.add(0, M_DESELECT_ALL, 1, R.string.messages_deselect_all)
            popup.menu.add(0, M_MARK_READ_SELECTED, 2, R.string.conv_mark_read)
            popup.menu.add(0, M_MUTE_SELECTED, 3, if (allSelectedMuted) R.string.conv_unmute else R.string.conv_mute)
            popup.menu.add(0, M_DELETE_SELECTED, 4, R.string.chat_delete)
            popup.menu.add(0, M_CANCEL_SELECTION, 5, R.string.messages_cancel_selection)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                M_MARK_ALL_READ -> markAllRead()
                M_SELECT_MODE -> enterSelectionMode()
                M_SELECT_ALL -> selectAll()
                M_DESELECT_ALL -> deselectAll()
                M_MARK_READ_SELECTED -> markSelectedRead()
                M_MUTE_SELECTED -> muteSelected(!allSelectedMuted)
                M_DELETE_SELECTED -> confirmDeleteSelected()
                M_CANCEL_SELECTION -> exitSelectionMode()
            }
            true
        }
        popup.show()
    }

    private fun markAllRead() {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                NotiDatabase.get(requireContext()).relayedMessageDao().markAllRead()
            }
            (activity as? MainActivity)?.updateMessagesBadge()
        }
    }

    // ---- Multi-select ----

    private fun enterSelectionMode(initialSender: String? = null) {
        selectionMode = true
        selected.clear()
        if (initialSender != null) selected.add(initialSender)
        selectionBack.isEnabled = true
        updateSelectionTitle()
        renderList()
    }

    /** Long-pressing a row's avatar is a dedicated entry point into selection mode - the row body's
     *  long-press keeps showing the existing single-conversation menu, unchanged. */
    private fun onAvatarLongPress(sender: String) {
        if (!selectionMode) enterSelectionMode(sender) else toggleSelected(sender)
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selected.clear()
        selectionBack.isEnabled = false
        restoreTitle()
        renderList()
    }

    private fun toggleSelected(sender: String) {
        if (!selected.remove(sender)) selected.add(sender)
        updateSelectionTitle()
        renderList()
    }

    private fun selectAll() {
        selected.clear()
        selected.addAll(latestConvos.map { it.sender })
        updateSelectionTitle()
        renderList()
    }

    private fun deselectAll() {
        selected.clear()
        updateSelectionTitle()
        renderList()
    }

    private fun updateSelectionTitle() {
        requireActivity().findViewById<CollapsingToolbarLayout>(R.id.collapsing_toolbar).title =
            getString(R.string.messages_selected_count, selected.size)
    }

    private fun restoreTitle() {
        requireActivity().findViewById<CollapsingToolbarLayout>(R.id.collapsing_toolbar).title =
            getString(R.string.nav_messages)
    }

    private fun markSelectedRead() {
        val senders = selected.toList()
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                NotiDatabase.get(requireContext()).relayedMessageDao().markRead(senders)
            }
            (activity as? MainActivity)?.updateMessagesBadge()
            exitSelectionMode()
        }
    }

    private fun muteSelected(mute: Boolean) {
        val s = Settings.get(requireContext())
        selected.forEach { s.setMuted(it, mute) }
        exitSelectionMode()
        observeConversations() // mute is a pref, not Room - the Flow won't re-emit on its own
    }

    private fun confirmDeleteSelected() {
        val senders = selected.toList()
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.chat_delete_selected_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.chat_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        NotiDatabase.get(requireContext()).relayedMessageDao().deleteConversations(senders)
                    }
                    (activity as? MainActivity)?.updateMessagesBadge()
                    exitSelectionMode()
                }
            }
            .show()
    }

    /** ListAdapter + DiffUtil: only the rows that changed rebind, instead of the whole list
     *  repainting on every refresh (ChatActivity's bubbles already diff the same way). */
    private class ConversationAdapter(
        val onClick: (String) -> Unit,
        val onLongClick: (String) -> Unit,
        val onToggleSelect: (String) -> Unit,
        val onAvatarLongPress: (String) -> Unit,
    ) : ListAdapter<ConversationAdapter.Row, ConversationVH>(DIFF) {

        /** A row = the aggregate plus everything bind() needs that isn't in the DB (muted/selection state). */
        data class Row(val summary: ConversationSummary, val muted: Boolean, val selectionMode: Boolean, val selected: Boolean)

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<Row>() {
                override fun areItemsTheSame(oldItem: Row, newItem: Row) =
                    oldItem.summary.sender == newItem.summary.sender

                override fun areContentsTheSame(oldItem: Row, newItem: Row) = oldItem == newItem
            }
        }

        // Theme colours, resolved once (they don't change while the fragment is alive).
        private var colorOnSurface = 0
        private var colorOnSurfaceVariant = 0
        private var colorPrimary = 0

        fun resolveColors(v: View) {
            colorOnSurface = MaterialColors.getColor(v, com.google.android.material.R.attr.colorOnSurface)
            colorOnSurfaceVariant = MaterialColors.getColor(v, com.google.android.material.R.attr.colorOnSurfaceVariant)
            colorPrimary = MaterialColors.getColor(v, com.google.android.material.R.attr.colorPrimary)
        }

        fun submit(list: List<ConversationSummary>, muted: Set<String>, selectionMode: Boolean, selected: Set<String>) {
            submitList(list.map { Row(it, it.sender in muted, selectionMode, it.sender in selected) })
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_conversation, parent, false)
            return ConversationVH(v)
        }

        override fun onBindViewHolder(holder: ConversationVH, position: Int) {
            val c = getItem(position)
            val ctx = holder.itemView.context
            holder.sender.text = c.summary.sender
            holder.last.text = c.summary.lastBody
            holder.time.text = ChatTime.listStamp(ctx, c.summary.lastAt)
            Avatars.apply(holder.avatar, holder.avatarInitials, holder.avatarIcon, c.summary.sender)

            // An unread conversation gets a bold name, a darker preview, an accent
            // timestamp, and a count badge; a read one is quieter.
            val unread = c.summary.unread > 0
            holder.sender.setTypeface(null, if (unread) Typeface.BOLD else Typeface.NORMAL)
            holder.last.setTypeface(null, if (unread) Typeface.BOLD else Typeface.NORMAL)
            holder.last.setTextColor(if (unread) colorOnSurface else colorOnSurfaceVariant)
            holder.time.setTextColor(if (unread) colorPrimary else colorOnSurfaceVariant)
            if (unread) {
                holder.unread.visibility = View.VISIBLE
                holder.unread.text = if (c.summary.unread > 99) "99+" else c.summary.unread.toString()
            } else {
                holder.unread.visibility = View.GONE
            }

            holder.muted.visibility = if (c.muted) View.VISIBLE else View.GONE

            holder.checkbox.visibility = if (c.selectionMode) View.VISIBLE else View.GONE
            holder.checkbox.isChecked = c.selected

            holder.itemView.setOnClickListener {
                if (c.selectionMode) onToggleSelect(c.summary.sender) else onClick(c.summary.sender)
            }
            holder.itemView.setOnLongClickListener {
                Haptics.longPress(it)
                if (c.selectionMode) onToggleSelect(c.summary.sender) else onLongClick(c.summary.sender)
                true
            }

            // The avatar is a dedicated long-press target for entering/toggling selection, distinct
            // from the row body's long-press (which keeps showing the single-conversation menu). A
            // plain tap on the avatar still behaves like tapping the body.
            holder.avatar.setOnClickListener {
                if (c.selectionMode) onToggleSelect(c.summary.sender) else onClick(c.summary.sender)
            }
            holder.avatar.setOnLongClickListener {
                Haptics.longPress(it)
                onAvatarLongPress(c.summary.sender)
                true
            }
        }
    }

    companion object {
        private const val M_MARK_ALL_READ = 1
        private const val M_SELECT_MODE = 2
        private const val M_SELECT_ALL = 3
        private const val M_DESELECT_ALL = 4
        private const val M_MARK_READ_SELECTED = 5
        private const val M_MUTE_SELECTED = 6
        private const val M_DELETE_SELECTED = 7
        private const val M_CANCEL_SELECTION = 8
    }
}

private class ConversationVH(v: View) : RecyclerView.ViewHolder(v) {
    val sender: TextView = v.findViewById(R.id.txt_sender)
    val last: TextView = v.findViewById(R.id.txt_last)
    val time: TextView = v.findViewById(R.id.txt_time)
    val unread: TextView = v.findViewById(R.id.txt_unread)
    val muted: ImageView = v.findViewById(R.id.ic_muted)
    val checkbox: MaterialCheckBox = v.findViewById(R.id.checkbox_select)
    val avatar: View = v.findViewById(R.id.avatar)
    val avatarInitials: TextView = v.findViewById(R.id.avatar_initials)
    val avatarIcon: ImageView = v.findViewById(R.id.avatar_icon)
}
