package com.noti.logger.push

/**
 * Splits a relayed message's display title back into sender + SIM. sndi formats it as
 * "<sender> on <sim>" (e.g. "+971500000000 on e&"); a title without " on " is treated as a bare
 * sender. Pure, so it's unit-tested.
 */
object RelayTitle {
    fun parse(title: String): Pair<String, String> {
        val i = title.lastIndexOf(" on ")
        return if (i > 0) title.substring(0, i) to title.substring(i + 4) else title to ""
    }
}
