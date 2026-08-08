package com.noti.logger.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.noti.logger.R
import com.noti.logger.data.ConversationSummary
import com.noti.logger.data.NotiDatabase
import com.noti.logger.util.Avatars
import com.noti.logger.util.ChatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Messages tab: the list of conversations relayed from sndi, with search. */
class MessagesFragment : Fragment(R.layout.fragment_messages) {

    private lateinit var adapter: ConversationAdapter
    private lateinit var search: TextInputEditText

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = ConversationAdapter { sender ->
            startActivity(Intent(requireContext(), ChatActivity::class.java).putExtra(ChatActivity.EXTRA_SENDER, sender))
        }
        view.findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@MessagesFragment.adapter
        }
        search = view.findViewById(R.id.et_search)
        search.doAfterTextChanged { refresh() }

        view.findViewById<View>(R.id.fab_compose).setOnClickListener {
            startActivity(Intent(requireContext(), ComposeActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh() // pick up messages that arrived while away
    }

    private fun refresh() {
        val query = search.text?.toString().orEmpty().trim()
        viewLifecycleOwner.lifecycleScope.launch {
            val convos = withContext(Dispatchers.IO) {
                val dao = NotiDatabase.get(requireContext()).relayedMessageDao()
                if (query.isEmpty()) dao.conversations() else dao.searchConversations(query)
            }
            adapter.submit(convos)
            view?.findViewById<View>(R.id.txt_empty)?.visibility =
                if (convos.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private inner class ConversationAdapter(
        val onClick: (String) -> Unit,
    ) : RecyclerView.Adapter<ConversationVH>() {

        private val items = ArrayList<ConversationSummary>()

        fun submit(list: List<ConversationSummary>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_conversation, parent, false)
            return ConversationVH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: ConversationVH, position: Int) {
            val c = items[position]
            holder.sender.text = c.sender
            holder.last.text = c.lastBody
            holder.time.text = ChatTime.listStamp(holder.itemView.context, c.lastAt)
            Avatars.apply(holder.avatar, holder.avatarInitials, holder.avatarIcon, c.sender)
            holder.itemView.setOnClickListener { onClick(c.sender) }
        }
    }

    private inner class ConversationVH(v: View) : RecyclerView.ViewHolder(v) {
        val sender: TextView = v.findViewById(R.id.txt_sender)
        val last: TextView = v.findViewById(R.id.txt_last)
        val time: TextView = v.findViewById(R.id.txt_time)
        val avatar: View = v.findViewById(R.id.avatar)
        val avatarInitials: TextView = v.findViewById(R.id.avatar_initials)
        val avatarIcon: ImageView = v.findViewById(R.id.avatar_icon)
    }
}
