package com.noti.logger.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import com.google.android.material.color.MaterialColors
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Messages tab: the list of conversations relayed from sndi, with search. */
class MessagesFragment : Fragment(R.layout.fragment_messages) {

    private lateinit var adapter: ConversationAdapter
    private lateinit var search: TextInputEditText

    /** While the search is active (focused or non-empty), Back dismisses it instead of leaving the app. */
    private lateinit var searchBack: OnBackPressedCallback

    /** Debounce so a burst of keystrokes triggers one query, not one per character. */
    private var searchJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = ConversationAdapter(
            onClick = { sender ->
                startActivity(Intent(requireContext(), ChatActivity::class.java).putExtra(ChatActivity.EXTRA_SENDER, sender))
            },
            onLongClick = { sender -> showConversationMenu(sender) },
        )
        adapter.resolveColors(view) // theme colours are stable for this fragment; resolve once, not per row
        view.findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@MessagesFragment.adapter
        }
        search = view.findViewById(R.id.et_search)
        search.doAfterTextChanged {
            updateSearchBack()
            searchJob?.cancel()
            searchJob = viewLifecycleOwner.lifecycleScope.launch { delay(220); refresh() }
        }
        search.setOnFocusChangeListener { _, _ -> updateSearchBack() }

        searchBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = closeSearch()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, searchBack)
        // The compose FAB lives in MainActivity (pinned to the screen); it opens ComposeActivity.
    }

    private fun updateSearchBack() {
        searchBack.isEnabled = search.hasFocus() || !search.text.isNullOrEmpty()
    }

    /** Clears the query, drops focus, and hides the keyboard - the search's "close" action. */
    private fun closeSearch() {
        search.setText("")
        search.clearFocus()
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(search.windowToken, 0)
        searchBack.isEnabled = false
    }

    override fun onResume() {
        super.onResume()
        refresh() // pick up messages that arrived while away
    }

    private fun refresh() {
        val query = search.text?.toString().orEmpty().trim()
        viewLifecycleOwner.lifecycleScope.launch {
            val (convos, muted) = withContext(Dispatchers.IO) {
                val dao = NotiDatabase.get(requireContext()).relayedMessageDao()
                val list = if (query.isEmpty()) dao.conversations() else dao.searchConversations(query)
                // Read the muted set once here instead of decrypting it per row in onBindViewHolder.
                list to Settings.get(requireContext()).mutedSenders
            }
            adapter.submit(convos, muted)
            view?.findViewById<View>(R.id.txt_empty)?.visibility =
                if (convos.isEmpty()) View.VISIBLE else View.GONE
        }
    }

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
                    0 -> { s.setMuted(sender, !muted); refresh() }
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
            refresh()
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
                    refresh()
                    (activity as? MainActivity)?.updateMessagesBadge()
                }
            }
            .show()
    }

    private inner class ConversationAdapter(
        val onClick: (String) -> Unit,
        val onLongClick: (String) -> Unit,
    ) : RecyclerView.Adapter<ConversationVH>() {

        private val items = ArrayList<ConversationSummary>()
        private var mutedSenders: Set<String> = emptySet()

        // Theme colours, resolved once (they don't change while the fragment is alive).
        private var colorOnSurface = 0
        private var colorOnSurfaceVariant = 0
        private var colorPrimary = 0

        fun resolveColors(v: View) {
            colorOnSurface = MaterialColors.getColor(v, com.google.android.material.R.attr.colorOnSurface)
            colorOnSurfaceVariant = MaterialColors.getColor(v, com.google.android.material.R.attr.colorOnSurfaceVariant)
            colorPrimary = MaterialColors.getColor(v, com.google.android.material.R.attr.colorPrimary)
        }

        fun submit(list: List<ConversationSummary>, muted: Set<String>) {
            items.clear(); items.addAll(list); mutedSenders = muted; notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_conversation, parent, false)
            return ConversationVH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: ConversationVH, position: Int) {
            val c = items[position]
            val ctx = holder.itemView.context
            holder.sender.text = c.sender
            holder.last.text = c.lastBody
            holder.time.text = ChatTime.listStamp(ctx, c.lastAt)
            Avatars.apply(holder.avatar, holder.avatarInitials, holder.avatarIcon, c.sender)

            // Signal-style: an unread conversation gets a bold name, a darker preview, an accent
            // timestamp, and a count badge; a read one is quieter.
            val unread = c.unread > 0
            holder.sender.setTypeface(null, if (unread) Typeface.BOLD else Typeface.NORMAL)
            holder.last.setTypeface(null, if (unread) Typeface.BOLD else Typeface.NORMAL)
            holder.last.setTextColor(if (unread) colorOnSurface else colorOnSurfaceVariant)
            holder.time.setTextColor(if (unread) colorPrimary else colorOnSurfaceVariant)
            if (unread) {
                holder.unread.visibility = View.VISIBLE
                holder.unread.text = if (c.unread > 99) "99+" else c.unread.toString()
            } else {
                holder.unread.visibility = View.GONE
            }

            holder.muted.visibility = if (c.sender in mutedSenders) View.VISIBLE else View.GONE

            holder.itemView.setOnClickListener { onClick(c.sender) }
            holder.itemView.setOnLongClickListener { Haptics.longPress(it); onLongClick(c.sender); true }
        }
    }

    private inner class ConversationVH(v: View) : RecyclerView.ViewHolder(v) {
        val sender: TextView = v.findViewById(R.id.txt_sender)
        val last: TextView = v.findViewById(R.id.txt_last)
        val time: TextView = v.findViewById(R.id.txt_time)
        val unread: TextView = v.findViewById(R.id.txt_unread)
        val muted: ImageView = v.findViewById(R.id.ic_muted)
        val avatar: View = v.findViewById(R.id.avatar)
        val avatarInitials: TextView = v.findViewById(R.id.avatar_initials)
        val avatarIcon: ImageView = v.findViewById(R.id.avatar_icon)
    }
}
