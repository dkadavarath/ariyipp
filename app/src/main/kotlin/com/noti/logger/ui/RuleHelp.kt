package com.noti.logger.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.noti.logger.R

/** The rule editor's "how patterns work" panel: static guidance, worked against a sample message
 *  of the rule's kind (a bill statement or a parking ticket). Built only when opened. */
internal object RuleHelp {

    /** Built lazily and rebuilt when the rule type changes, so a parking rule is never taught the
     *  bill fields and vice versa. */
    fun build(container: LinearLayout, parking: Boolean) {
        container.removeAllViews()
        val block = Block(container)
        if (parking) parkingIntro(block) else billIntro(block)

        block(
            "The pieces you'll actually use",
            "\\d   a digit\n" +
                "\\w   a letter or digit\n" +
                "\\s   a space\n" +
                "+    one or more of the thing before it\n" +
                "*    any number of them, including none\n" +
                "{2}  exactly two of them\n" +
                ".    any character at all\n" +
                ".*   anything, of any length\n" +
                "( )  the part you want to keep\n" +
                "|    or, as in (INR|AED)",
        )
        block(
            "Matching is forgiving",
            "Case is ignored, so \"DUE BY\" and \"due by\" both match, and a pattern can run across " +
                "line breaks - which matters for notifications where the amount and the date sit " +
                "on different lines.",
        )
        block(
            "If nothing matches",
            "Press Test with a sample pasted in. \"No match\" means the identify pattern is the " +
                "problem - shorten it until it matches, then tighten it. If it matches but a field " +
                "comes back with a dash, only that field's pattern is wrong; the rest still work.",
        )
        if (!parking) {
            block(
                "Due dates with no date in the message",
                "Some bills never state one. \"Same day each month\" is for a bill always due on, " +
                    "say, the 15th. \"Days after a fixed day\" is how card statements work: " +
                    "statement on the 10th, payment due 28 days later.",
            )
        }
    }

    private fun parkingIntro(block: Block) {
        block(
            "Start from a real ticket",
            "Paste an actual parking notification into the test box at the bottom, then press " +
                "Test to see exactly what each pattern pulled out. The field examples below " +
                "are written against this one:",
            "Parking started. Zone: ZONE-4B North Lot.\nYour session is valid until 14:30.",
        )
        block(
            "Two fields",
            "One pattern finds the zone, one finds the expiry. Each keeps whatever is inside " +
                "its brackets ( ). If a ticket only gives a time that is fine - it is taken as " +
                "today, rolling to tomorrow if the time has already passed.",
        )
    }

    private fun billIntro(block: Block) {
        block(
            "Start from a real message",
            "Paste an actual statement into the test box at the bottom, then press Test to see " +
                "exactly what each pattern pulled out. The field examples below are written " +
                "against this one:",
            "Dear Customer, your HDFC Bank Credit Card\nstatement for Jan 2026 is generated.\n" +
                "Total Amount Due INR 12,480.50.\nPayment due by 07-Feb-2026.",
        )
        block(
            "Four fields",
            "One pattern decides the message is yours, then one each for the amount, the " +
                "currency and the due date. Each keeps whatever is inside its brackets ( ). " +
                "Keeping them separate means a bank changing one line only breaks one field.",
        )
    }

    /** One titled paragraph of help, with an optional example in a code box. */
    private class Block(private val container: LinearLayout) {
        private val inflater = LayoutInflater.from(container.context)

        operator fun invoke(title: String, body: String, code: String? = null) {
            val v = inflater.inflate(R.layout.item_help_block, container, false)
            v.findViewById<TextView>(R.id.tv_block_title).text = title
            v.findViewById<TextView>(R.id.tv_block_body).text = body
            v.findViewById<TextView>(R.id.tv_block_code).apply {
                if (code == null) visibility = View.GONE else { text = code; visibility = View.VISIBLE }
            }
            container.addView(v)
        }
    }
}
