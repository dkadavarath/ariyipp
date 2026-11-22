package com.noti.logger.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.data.BillOccurrenceEntity
import com.noti.logger.data.NotiDatabase
import com.noti.logger.data.ParkingSessionEntity
import com.noti.logger.reminder.BillStatus
import com.noti.logger.reminder.FxRates
import com.noti.logger.reminder.ParkingTracker
import com.noti.logger.reminder.ReminderFormat
import com.noti.logger.reminder.ReminderNotifier
import com.noti.logger.reminder.ReminderPolicy
import com.noti.logger.reminder.ReminderScheduler
import com.noti.logger.util.Motion
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Reminders tab: what is outstanding, and what has been settled.
 *
 * Active and History are one list with a scope switch rather than two screens, because the question
 * they answer is the same one ("what about this bill?") at two points in time. Parking sessions and
 * bills share the list too - a day where the car needs moving at 14:30 and the card is due tomorrow
 * is one day, and splitting it across two tabs would hide half of it.
 *
 * When a rule has nothing outstanding, its last settled bill is shown greyed instead - so an empty
 * list means "nothing is due", not "this rule stopped working", which are very different worries.
 */
class RemindersFragment : Fragment(R.layout.fragment_reminders) {

    private enum class Scope { ACTIVE, HISTORY }

    private var scope = Scope.ACTIVE
    private lateinit var adapter: RowAdapter

    /**
     * Selected rows, keyed by kind and id rather than position - the list is rebuilt from the
     * database after every action, so positions would point at the wrong entries the moment one
     * moved. Works the same in History, where deleting is the only thing left to do with an entry.
     */
    private val selected = LinkedHashSet<String>()

    /** Multi-select, the same model as the Messages list: an explicit mode (entered by long-press
     *  or from the overflow), checkboxes on every row, "N selected" in the header, and Back leaves
     *  the mode instead of the app. It stays on with nothing ticked until cancelled. */
    private var selectionMode = false
    private lateinit var selectionBack: androidx.activity.OnBackPressedCallback

    /** One line per entry (item_reminder_compact) instead of the full card. Read once from
     *  Settings; flipping it re-inflates every row through the adapter's view type. */
    private var compact = false

    /** Null = every category. Held by name, so it survives the list being rebuilt. */
    private var categoryFilter: String? = null

    private fun keyOf(row: Row): String = when (row) {
        is Row.Bill -> "bill:${row.bill.id}"
        is Row.Parking -> "parking:${row.session.id}"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val rv = view.findViewById<RecyclerView>(R.id.rv_reminders)
        compact = com.noti.logger.config.Settings.get(requireContext()).remindersCompact
        adapter = RowAdapter()
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        // Same list motion as Messages: rows rise in, and rebinding a row (a checkbox appearing)
        // doesn't cross-fade it.
        rv.itemAnimator = com.noti.logger.util.SoftItemAnimator(liftPx = 8f * resources.displayMetrics.density)

        view.findViewById<View>(R.id.btn_overflow).setOnClickListener { showOverflowMenu(it) }

        selectionBack = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = exitSelectionMode()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, selectionBack)

        val toggle = view.findViewById<MaterialButtonToggleGroup>(R.id.toggle_scope)
        toggle.check(R.id.btn_scope_active)
        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            scope = if (checkedId == R.id.btn_scope_history) Scope.HISTORY else Scope.ACTIVE
            // Selection does not carry across the switch: the two lists hold different things, and
            // keeping ids selected that are no longer on screen invites deleting the unseen.
            if (selectionMode) exitSelectionMode() else reload()
        }
    }

    override fun onResume() {
        super.onResume()
        // Relative labels ("Due in 4 days") are computed at bind time; after a day away the data
        // may be unchanged, so the diff alone would leave them stale.
        adapter.rebindAll()
        reload()
    }

    /** One row in the list. Bills and parking sessions are different enough in their actions that
     *  they stay separate types, and only converge on how they're drawn. */
    private sealed interface Row {
        data class Bill(val bill: BillOccurrenceEntity, val dimmed: Boolean) : Row
        data class Parking(val session: ParkingSessionEntity) : Row
    }

    private fun reload() {
        val ctx = requireContext().applicationContext
        if (!Settings.get(ctx).reminderEnabled) {
            showEmpty(getString(R.string.rm_empty_disabled))
            adapter.submit(emptyList())
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) { load(ctx) }
            if (!isAdded) return@launch
            renderCategories(all)
            renderTotals(ctx)
            val rows = all.filter { row ->
                val c = categoryFilter ?: return@filter true
                (row as? Row.Bill)?.bill?.category == c
            }
            adapter.submit(rows)
            if (rows.isEmpty()) {
                showEmpty(getString(if (scope == Scope.ACTIVE) R.string.rm_empty_active else R.string.rm_empty_history))
            } else {
                view?.findViewById<TextView>(R.id.tv_reminders_empty)?.visibility = View.GONE
            }
        }
    }

    private fun load(ctx: android.content.Context): List<Row> {
        val db = NotiDatabase.get(ctx)
        return try {
            if (scope == Scope.ACTIVE) {
                val parking = db.parkingSessionDao().active().map { Row.Parking(it) }
                val bills = db.billOccurrenceDao().active().map { Row.Bill(it, dimmed = false) }
                // Rules with nothing outstanding contribute their last settled bill, greyed - so the
                // list still shows the rule exists and when it last ran.
                val activeRuleIds = bills.map { it.bill.ruleId }.toSet()
                val trailing = db.reminderRuleDao().enabled()
                    .filter { it.id !in activeRuleIds }
                    .mapNotNull { db.billOccurrenceDao().lastSettledForRule(it.id) }
                    .map { Row.Bill(it, dimmed = true) }
                parking + bills + trailing
            } else {
                db.billOccurrenceDao().history().map { Row.Bill(it, dimmed = true) } +
                    db.parkingSessionDao().history().map { Row.Parking(it) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun showEmpty(text: String) {
        view?.findViewById<TextView>(R.id.tv_reminders_empty)?.apply {
            this.text = text
            visibility = View.VISIBLE
        }
    }

    // ---- Actions ----

    private fun markPaid(bill: BillOccurrenceEntity) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val changed = withContext(Dispatchers.IO) {
                val n = NotiDatabase.get(ctx).billOccurrenceDao()
                    .setStatus(bill.id, BillStatus.PAID, System.currentTimeMillis())
                if (n == 0) return@withContext false
                ReminderScheduler.cancelBill(ctx, bill.id)
                ReminderNotifier.cancelBill(ctx, bill.id)
                // A manual rule's next occurrence only appears once this one is settled, so top it
                // up now rather than making the user wait for tomorrow's scan.
                com.noti.logger.reminder.ReminderScanner.rollManualRules(ctx)
                ReminderScheduler.rebuildAll(ctx)
                true
            }
            Toast.makeText(requireContext(), if (changed) R.string.rm_marked_paid else R.string.rm_already_settled, Toast.LENGTH_SHORT).show()
            reload()
        }
    }

    private fun skipNext(bill: BillOccurrenceEntity) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // Settle this one *and* arm the rule to pass over the following occurrence: "skip
                // next" is what you press when you've already paid ahead, which is a statement
                // about the one after this as much as about this one.
                val n = NotiDatabase.get(ctx).billOccurrenceDao()
                    .setStatus(bill.id, BillStatus.SKIPPED, System.currentTimeMillis())
                if (n > 0 && bill.ruleId > 0) NotiDatabase.get(ctx).reminderRuleDao().setSkipNext(bill.ruleId, 1)
                ReminderScheduler.cancelBill(ctx, bill.id)
                ReminderNotifier.cancelBill(ctx, bill.id)
                ReminderScheduler.rebuildAll(ctx)
            }
            Toast.makeText(requireContext(), R.string.rm_skip_next_set, Toast.LENGTH_LONG).show()
            reload()
        }
    }

    private fun endParking(session: ParkingSessionEntity) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { ParkingTracker.end(ctx, session.id) }
            Toast.makeText(requireContext(), R.string.rm_parking_ended, Toast.LENGTH_SHORT).show()
            reload()
        }
    }

    /**
     * Category chips, built from whatever categories actually exist. Absent entirely when nothing
     * is categorised - a filter bar offering only "All" is furniture.
     */
    private fun renderCategories(rows: List<Row>) {
        val v = view ?: return
        val scroll = v.findViewById<View>(R.id.scroll_categories)
        val group = v.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chips_categories)
        val categories = rows.mapNotNull { (it as? Row.Bill)?.bill?.category?.takeIf { c -> c.isNotBlank() } }
            .distinct()
            .sorted()
        if (categories.isEmpty()) {
            scroll.visibility = View.GONE
            categoryFilter = null
            return
        }
        // A filter pointing at a category that no longer exists would silently empty the list.
        if (categoryFilter != null && categoryFilter !in categories) categoryFilter = null
        scroll.visibility = View.VISIBLE
        group.removeAllViews()
        val labels = listOf(getString(R.string.rm_filter_all)) + categories
        for ((i, label) in labels.withIndex()) {
            val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                text = label
                isCheckable = true
                isChecked = if (i == 0) categoryFilter == null else categoryFilter == label
                setOnClickListener {
                    categoryFilter = if (i == 0) null else label
                    reload()
                }
            }
            group.addView(chip)
        }
    }

    /**
     * What the recurring entries come to per month and per year, totalled per currency rather than
     * converted. Converting would mean fetching exchange rates from somewhere, which is a network
     * dependency and a third party this app does not otherwise need; separate lines are honest and
     * cost nothing.
     */
    /**
     * One figure: everything due in the next 30 days - overdue included, since it still has to be
     * paid - in the default currency, converted at the day's cached rates. The line under it only speaks up when
     * something is missing from the figure, so it is never quietly smaller than it looks.
     */
    private fun renderTotals(ctx: android.content.Context) {
        val v = view ?: return
        val card = v.findViewById<View>(R.id.card_totals)
        if (scope == Scope.HISTORY || !Settings.get(ctx).remindersShowTotal) {
            card.visibility = View.GONE
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val t = withContext(Dispatchers.IO) { computeTotals(ctx) }
            if (!isAdded) return@launch
            if (t.bills == 0) {
                card.visibility = View.GONE
                return@launch
            }
            // The totals land after the list has drawn; slide the list down to make room instead of
            // shoving it.
            if (card.visibility != View.VISIBLE) Motion.animateNextLayout(v as android.view.ViewGroup)
            card.visibility = View.VISIBLE
            val total = money(t.home, t.inHome)
            val notes = buildList {
                add(resources.getQuantityString(R.plurals.rm_total_bills, t.bills, t.bills))
                if (t.withoutAmount > 0) add(getString(
                    if (compact) R.string.rm_total_no_amount_short else R.string.rm_total_no_amount, t.withoutAmount))
                if (t.unconverted.isNotEmpty()) {
                    val what = t.unconverted.entries.joinToString(", ") { (cur, amt) -> money(cur, amt) }
                    add(getString(if (t.ratesAtMs == 0L) R.string.rm_total_no_rates else R.string.rm_total_unconverted, what))
                }
            }
            val ratesNote = if (t.ratesAtMs > 0 && t.converted) getString(R.string.rm_total_rates_of, ReminderFormat.shortDate(t.ratesAtMs)) else null
            val title = v.findViewById<TextView>(R.id.tv_totals_title)
            val figure = v.findViewById<TextView>(R.id.tv_totals)
            val sub = v.findViewById<TextView>(R.id.tv_upcoming)
            val d = resources.displayMetrics.density
            val pad = (16 * d).toInt()
            if (compact) {
                // One line, like the rows under it: label, the figure in bold, then the bill count
                // and any caveat - the rates date is left out, it's the least needed part.
                title.visibility = View.GONE
                sub.visibility = View.GONE
                figure.maxLines = 1
                figure.ellipsize = android.text.TextUtils.TruncateAt.END
                val label = getString(R.string.rm_totals_compact_label)
                val tail = notes
                figure.text = android.text.SpannableStringBuilder().apply {
                    append(label, android.text.style.ForegroundColorSpan(
                        MaterialColors.getColor(figure, com.google.android.material.R.attr.colorOnSurfaceVariant)),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append("  ")
                    append(total, android.text.style.StyleSpan(android.graphics.Typeface.BOLD), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (tail.isNotEmpty()) append(" · " + tail.joinToString(" · "))
                }
                v.findViewById<View>(R.id.totals_body).setPadding(pad, (12 * d).toInt(), pad, (12 * d).toInt())
            } else {
                title.visibility = View.VISIBLE
                figure.maxLines = Int.MAX_VALUE
                figure.ellipsize = null
                figure.text = total
                sub.text = (notes + listOfNotNull(ratesNote)).joinToString(" · ")
                sub.visibility = View.VISIBLE
                v.findViewById<View>(R.id.totals_body).setPadding(pad, pad, pad, pad)
            }
        }
    }

    private class Totals(
        val home: String,
        val inHome: Double,
        val bills: Int,
        val withoutAmount: Int,
        /** Amounts that couldn't be put into [home], by their own currency. */
        val unconverted: Map<String, Double>,
        /** Whether any foreign amount went into [inHome] - only then is the rates date worth showing. */
        val converted: Boolean,
        val ratesAtMs: Long,
    )

    private fun computeTotals(ctx: android.content.Context): Totals {
        val home = FxRates.home(ctx)
        return try {
            val horizon = java.time.LocalDate.now().plusDays(30)
            val due = NotiDatabase.get(ctx).billOccurrenceDao().active()
                .filter { it.dueAt > 0 && !com.noti.logger.reminder.DueDates.fromMs(it.dueAt).isAfter(horizon) }
            // Only touch the network (at most daily) when something actually needs converting.
            val needsRates = due.any { b -> b.amount != null && FxRates.isoOf(b.currency, home) != home }
            val fx = if (needsRates) FxRates.current(ctx, home) else null
            var inHome = 0.0
            var withoutAmount = 0
            var converted = false
            val unconverted = LinkedHashMap<String, Double>()
            for (b in due) {
                val amount = b.amount
                if (amount == null) { withoutAmount++; continue }
                val iso = FxRates.isoOf(b.currency, home)
                val value = iso?.let { FxRates.toHome(amount, it, home, fx?.rates.orEmpty()) }
                if (value == null) {
                    val key = b.currency.ifBlank { "?" }
                    unconverted[key] = (unconverted[key] ?: 0.0) + amount
                } else {
                    inHome += value
                    if (iso != home) converted = true
                }
            }
            Totals(home, inHome, due.size, withoutAmount, unconverted, converted, fx?.fetchedAtMs ?: 0L)
        } catch (e: Exception) {
            Totals(home, 0.0, 0, 0, emptyMap(), false, 0L)
        }
    }

    private fun money(currency: String, value: Double): String {
        // Same grouped formatting as each row's amount, so the totals read the same way.
        return ReminderFormat.amount(requireContext(), value, currency, "")
    }

    private fun toggleSelection(row: Row) {
        val key = keyOf(row)
        if (!selected.remove(key)) selected.add(key)
        updateSelectionTitle()
        // Only the tapped row changes.
        adapter.rows().indexOfFirst { keyOf(it) == key }.takeIf { it >= 0 }?.let { adapter.notifyItemChanged(it) }
    }

    private fun enterSelectionMode(initial: Row? = null) {
        selectionMode = true
        selected.clear()
        initial?.let { selected.add(keyOf(it)) }
        selectionBack.isEnabled = true
        updateSelectionTitle()
        adapter.rebindAll()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selected.clear()
        selectionBack.isEnabled = false
        requireActivity().findViewById<com.google.android.material.appbar.CollapsingToolbarLayout>(R.id.collapsing_toolbar)
            .title = getString(R.string.nav_reminders)
        // The rows themselves may not change (the diff in reload() would then skip them), but every
        // checkbox has to go.
        adapter.rebindAll()
        reload()
    }

    private fun updateSelectionTitle() {
        requireActivity().findViewById<com.google.android.material.appbar.CollapsingToolbarLayout>(R.id.collapsing_toolbar)
            .title = getString(R.string.messages_selected_count, selected.size)
    }

    /** Same shape as the Messages overflow: plain actions, or the selection ones once something is
     *  picked. What is offered depends on the scope, because a settled entry can only be deleted. */
    private fun showOverflowMenu(anchor: View) {
        val popup = android.widget.PopupMenu(requireContext(), anchor)
        val rows = adapter.rows()
        if (!selectionMode) {
            if (rows.isNotEmpty()) popup.menu.add(0, M_SELECT_MODE, 0, R.string.rm_select_entries)
            popup.menu.add(0, M_ADD, 1, R.string.rm_add_manual)
            popup.menu.add(0, M_COMPACT, 2, R.string.rm_compact_view).apply { isCheckable = true; isChecked = compact }
            popup.menu.add(0, M_SHOW_TOTAL, 3, R.string.rm_show_total).apply {
                isCheckable = true; isChecked = Settings.get(requireContext()).remindersShowTotal
            }
        } else {
            if (selected.size < rows.size) popup.menu.add(0, M_SELECT_ALL, 0, R.string.messages_select_all)
            if (selected.isNotEmpty()) {
                popup.menu.add(0, M_DESELECT_ALL, 1, R.string.messages_deselect_all)
                if (scope == Scope.ACTIVE) {
                    popup.menu.add(0, M_MARK_PAID, 2, R.string.rm_mark_paid_selected)
                    popup.menu.add(0, M_END_PARKING, 3, R.string.rm_end_selected)
                }
                popup.menu.add(0, M_DELETE, 4, R.string.rm_delete)
            }
            popup.menu.add(0, M_CANCEL, 5, R.string.messages_cancel_selection)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                M_SELECT_MODE -> enterSelectionMode()
                M_ADD -> startActivity(Intent(requireContext(), ReminderEditActivity::class.java))
                M_SHOW_TOTAL -> {
                    val st = Settings.get(requireContext())
                    st.remindersShowTotal = !st.remindersShowTotal
                    renderTotals(requireContext().applicationContext)
                }
                M_COMPACT -> {
                    compact = !compact
                    com.noti.logger.config.Settings.get(requireContext()).remindersCompact = compact
                    adapter.notifyDataSetChanged() // new view type for every row
                    renderTotals(requireContext().applicationContext)
                }
                M_SELECT_ALL -> { selected.addAll(rows.map { keyOf(it) }); updateSelectionTitle(); adapter.rebindAll() }
                M_DESELECT_ALL -> { selected.clear(); updateSelectionTitle(); adapter.rebindAll() }
                M_CANCEL -> exitSelectionMode()
                M_MARK_PAID -> {
                    selectedRows().filterIsInstance<Row.Bill>().forEach { markPaid(it.bill) }
                    exitSelectionMode()
                }
                M_END_PARKING -> {
                    selectedRows().filterIsInstance<Row.Parking>().forEach { endParking(it.session) }
                    exitSelectionMode()
                }
                M_DELETE -> confirmDeleteSelected()
            }
            true
        }
        popup.show()
    }

    private fun selectedRows(): List<Row> = adapter.rows().filter { keyOf(it) in selected }

    private fun confirmDeleteSelected() {
        val count = selected.size
        if (count == 0) return
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.rm_delete_entries_confirm, count))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rm_delete) { _, _ -> deleteSelected(count) }
            .show()
    }

    private fun deleteSelected(count: Int) {
        val ctx = requireContext().applicationContext
        val rows = selectedRows()
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val db = NotiDatabase.get(ctx)
                for (row in rows) {
                    when (row) {
                        is Row.Bill -> {
                            ReminderScheduler.cancelBill(ctx, row.bill.id)
                            ReminderNotifier.cancelBill(ctx, row.bill.id)
                            db.billOccurrenceDao().delete(row.bill.id)
                        }
                        is Row.Parking -> {
                            ParkingTracker.end(ctx, row.session.id)
                            db.parkingSessionDao().delete(row.session.id)
                        }
                    }
                }
            }
            Toast.makeText(requireContext(), getString(R.string.rm_entries_deleted, count), Toast.LENGTH_SHORT).show()
            exitSelectionMode()
        }
    }

    private companion object {
        const val M_SELECT_MODE = 1
        const val M_ADD = 2
        const val M_SELECT_ALL = 3
        const val M_DESELECT_ALL = 4
        const val M_MARK_PAID = 5
        const val M_END_PARKING = 6
        const val M_DELETE = 7
        const val M_CANCEL = 8
        const val M_COMPACT = 9
        const val M_SHOW_TOTAL = 10
        const val VIEW_FULL = 0
        const val VIEW_COMPACT = 1
    }

    private fun edit(row: Row) {
        val intent = Intent(requireContext(), ReminderEditActivity::class.java)
        when (row) {
            is Row.Bill -> intent.putExtra(ReminderEditActivity.EXTRA_BILL_ID, row.bill.id)
            is Row.Parking -> intent.putExtra(ReminderEditActivity.EXTRA_PARKING_ID, row.session.id)
        }
        startActivity(intent)
    }

    // ---- Adapter ----

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {

        private var items: List<Row> = emptyList()

        /** Diffs against what is showing, so a reload only rebinds the rows that actually changed
         *  (and the list keeps its scroll position instead of being rebuilt). */
        fun submit(rows: List<Row>) {
            val old = items
            val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = rows.size
                override fun areItemsTheSame(o: Int, n: Int) = keyOf(old[o]) == keyOf(rows[n])
                override fun areContentsTheSame(o: Int, n: Int) = old[o] == rows[n]
            })
            // Hold the screen still by *position*. Left to itself the list anchors on the first
            // visible row - so marking the top bill paid, which moves it to the end as its rule's
            // greyed "last paid" row, scrolled the whole list down after it. The row still moves;
            // the view stays where the user was looking.
            val lm = view?.findViewById<RecyclerView>(R.id.rv_reminders)?.layoutManager as? LinearLayoutManager
            val firstPos = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
            val firstTop = lm?.findViewByPosition(firstPos)?.top ?: 0
            items = rows
            diff.dispatchUpdatesTo(this)
            if (lm != null && firstPos != RecyclerView.NO_POSITION && rows.isNotEmpty()) {
                lm.scrollToPositionWithOffset(firstPos.coerceAtMost(rows.size - 1), firstTop)
            }
        }

        /** Selection mode or select-all changed how every row draws (checkboxes, actions), but not
         *  which rows exist: rebind in place rather than rebuilding the list. */
        fun rebindAll() = notifyItemRangeChanged(0, itemCount)

        fun rows(): List<Row> = items

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.card_row)
            val name: TextView = view.findViewById(R.id.tv_row_name)
            val amount: TextView = view.findViewById(R.id.tv_row_amount)
            val sub: TextView = view.findViewById(R.id.tv_row_sub)
            val primary: MaterialButton = view.findViewById(R.id.btn_row_primary)
            val secondary: MaterialButton = view.findViewById(R.id.btn_row_secondary)
            val actions: View = view.findViewById(R.id.row_actions)
            val checkbox: android.widget.CheckBox = view.findViewById(R.id.checkbox_select)
            /** Set by [dim]; decides the outline in [outline]. */
            var dimmed = false
        }

        override fun getItemViewType(position: Int) = if (compact) VIEW_COMPACT else VIEW_FULL

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(
                if (viewType == VIEW_COMPACT) R.layout.item_reminder_compact else R.layout.item_reminder,
                parent, false,
            )
        )

        /** A missing action on a compact row keeps its slot, so amounts line up down the list as a
         *  column; on the full card it collapses as before. */
        private fun gone() = if (compact) View.INVISIBLE else View.GONE

        /**
         * One action, drawn for the current layout: a labelled button on the full card, an icon on
         * the compact row - with the label kept as its content description and long-press tooltip,
         * so the meaning is still one touch away and TalkBack still reads it.
         */
        private fun action(button: MaterialButton, label: Int, icon: Int, onClick: () -> Unit) {
            val text = getString(label)
            if (compact) {
                button.text = null
                button.setIconResource(icon)
                button.contentDescription = text
                androidx.appcompat.widget.TooltipCompat.setTooltipText(button, text)
            } else {
                button.text = text
            }
            button.setOnClickListener { onClick() }
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = items[position]
            when (row) {
                is Row.Bill -> bindBill(holder, row)
                is Row.Parking -> bindParking(holder, row)
            }
            val isSelected = keyOf(row) in selected
            holder.checkbox.visibility = if (selectionMode) View.VISIBLE else View.GONE
            holder.checkbox.isChecked = isSelected
            outline(holder)
            // While selecting, the row's own buttons would be a second meaning for the same tap.
            if (selectionMode) holder.actions.visibility = View.GONE
            // Long-press starts selecting with this row ticked (or toggles it once selecting).
            holder.card.setOnLongClickListener {
                com.noti.sender.util.Haptics.longPress(it)
                if (selectionMode) toggleSelection(row) else enterSelectionMode(row)
                true
            }
            if (selectionMode) {
                holder.card.setOnClickListener { toggleSelection(row) }
            }
        }

        private fun bindBill(holder: VH, row: Row.Bill) {
            val bill = row.bill
            holder.name.text = bill.name
            val ctx = requireContext()
            holder.amount.text =
                if (compact && bill.amount == null && bill.amountRaw.isBlank()) getString(R.string.rm_short_no_amount)
                else ReminderFormat.amount(ctx, bill.amount, bill.currency, bill.amountRaw)
            holder.sub.text = when {
                row.dimmed && bill.settledAt > 0 && bill.autoSettled == 1 ->
                    if (compact) getString(R.string.rm_short_auto_paid, ReminderFormat.shortDate(bill.settledAt))
                    else getString(R.string.rm_auto_paid, ReminderFormat.dateOnly(bill.settledAt))
                row.dimmed && bill.settledAt > 0 ->
                    if (compact) getString(R.string.rm_short_paid, ReminderFormat.shortDate(bill.settledAt))
                    else getString(R.string.rm_last_paid, ReminderFormat.dateOnly(bill.settledAt))
                bill.dueAt > 0 ->
                    if (compact) ReminderFormat.dueShort(ctx, bill.dueAt) else ReminderFormat.dueLabel(ctx, bill.dueAt)
                else -> if (compact) "" else getString(R.string.rm_no_due_date)
            }
            // On one line there is no room to spell out "Overdue by 3 days"; colour carries it.
            val overdue = !row.dimmed && bill.dueAt > 0 &&
                com.noti.logger.reminder.DueDates.fromMs(bill.dueAt).isBefore(java.time.LocalDate.now())
            holder.sub.setTextColor(MaterialColors.getColor(holder.sub,
                if (compact && overdue) androidx.appcompat.R.attr.colorError
                else com.google.android.material.R.attr.colorOnSurfaceVariant))
            dim(holder, row.dimmed)

            if (row.dimmed) {
                holder.actions.visibility = gone()
            } else {
                holder.actions.visibility = View.VISIBLE
                action(holder.primary, R.string.rm_mark_paid, R.drawable.ic_check) { markPaid(bill) }
                action(holder.secondary, R.string.rm_skip_next, R.drawable.ic_skip_next) { skipNext(bill) }
                holder.secondary.visibility = if (bill.ruleId > 0) View.VISIBLE else gone()
            }
            holder.card.setOnClickListener { edit(row) }
        }

        private fun bindParking(holder: VH, row: Row.Parking) {
            val s = row.session
            holder.name.text = if (s.zone.isBlank()) s.name else getString(R.string.rm_parking_title, s.name, s.zone)
            holder.amount.text = if (s.renewals > 0) getString(R.string.rm_renewals, s.renewals) else ""
            holder.sub.text = when {
                s.expiresAt > 0 && compact -> getString(
                    R.string.rm_short_until,
                    ReminderPolicy.fromEpochMillis(s.expiresAt).format(DateTimeFormatter.ofPattern("HH:mm")),
                )
                s.expiresAt > 0 -> getString(
                    R.string.rm_parking_expires_at,
                    ReminderPolicy.fromEpochMillis(s.expiresAt).format(DateTimeFormatter.ofPattern("HH:mm, d MMM")),
                )
                compact -> ""
                else -> getString(R.string.rm_no_expiry)
            }
            holder.sub.setTextColor(MaterialColors.getColor(holder.sub, com.google.android.material.R.attr.colorOnSurfaceVariant))
            val ended = s.active != 1
            dim(holder, ended)
            holder.actions.visibility = if (ended) gone() else View.VISIBLE
            action(holder.primary, R.string.rm_end_parking, R.drawable.ic_close) { endParking(s) }
            holder.secondary.visibility = gone()
            holder.card.setOnClickListener { edit(row) }
        }

        /** Settled or ended rows recede: they lose the card fill and keep only a faint outline, and
         *  their title drops to the secondary text colour. Live rows are solid cards. The shape
         *  carries the difference, so no text is faded below a readable contrast to show it. */
        private fun dim(holder: VH, dimmed: Boolean) {
            val onSurface = MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorOnSurface)
            val onSurfaceVariant = MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorOnSurfaceVariant)
            holder.name.setTextColor(if (dimmed) onSurfaceVariant else onSurface)
            holder.amount.setTextColor(if (dimmed) onSurfaceVariant else onSurface)
            holder.card.setCardBackgroundColor(
                if (dimmed) android.graphics.Color.TRANSPARENT
                else MaterialColors.getColor(holder.card, com.noti.sender.R.attr.notiCardBackground),
            )
            holder.dimmed = dimmed
        }

        /** History: a faint outline standing in for the fill. Live: the theme's own card edge (none,
         *  or AMOLED's hairline). Selection is shown by the checkbox, as in the Messages list. */
        private fun outline(holder: VH) {
            val card = holder.card
            val d = card.resources.displayMetrics.density
            when {
                holder.dimmed -> {
                    card.strokeWidth = kotlin.math.ceil(d).toInt()
                    card.strokeColor = MaterialColors.getColor(card, com.google.android.material.R.attr.colorOutlineVariant)
                }
                else -> {
                    val width = android.util.TypedValue()
                    card.context.theme.resolveAttribute(com.noti.sender.R.attr.notiCardStrokeWidth, width, true)
                    card.strokeWidth = width.getDimension(card.resources.displayMetrics).toInt()
                    card.strokeColor = MaterialColors.getColor(card, com.noti.sender.R.attr.notiCardStrokeColor)
                }
            }
        }
    }
}
