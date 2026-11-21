package com.noti.logger.util

import android.text.SpannableString
import android.text.Spanned
import android.text.style.URLSpan
import android.text.util.Linkify
import android.widget.TextView
import androidx.core.text.util.LinkifyCompat

/**
 * Web links inside a chat bubble.
 *
 * Bodies arrive as plain text, so a URL used to be indistinguishable from the words around it: the
 * only way to get one out was the Select text dialog, and hand-dragging across a URL the bubble had
 * wrapped onto two lines picked the line break up with it - which breaks the link on the clipboard.
 * Marking links up front removes the manual selection entirely: the span knows exactly where the
 * URL starts and ends, so a tap opens it and Copy link copies it whole.
 *
 * These are ordinary [URLSpan]s, but the bubble deliberately gets no MovementMethod:
 * LinkMovementMethod consumes touch events, which would cost the bubble its long-press menu and
 * interfere with the list's scrolling. [linkAt] resolves the touch itself instead, from coordinates
 * the caller records without consuming them.
 */
object ChatLinks {

    /**
     * [body] with its web links marked up, or the original string when it has none - the common
     * case, and not worth a Spannable.
     */
    fun linkify(body: String): CharSequence {
        if (body.isEmpty()) return body
        val spannable = SpannableString(body)
        // Linkify's own WEB_URLS pattern rather than a hand-rolled one: it already knows the gTLD
        // list, where a URL stops against trailing punctuation, and how to give a bare "www." host
        // a scheme. It only ever produces http/https, so a crafted message can't talk a tap into
        // some other scheme.
        return if (LinkifyCompat.addLinks(spannable, Linkify.WEB_URLS)) spannable else body
    }

    /**
     * The URL under ([x], [y]) in [tv]'s own coordinates, or null if that point isn't on a link.
     *
     * The bounds checks are what stop a near miss from counting as a hit: getLineForVertical and
     * getOffsetForHorizontal both clamp to the nearest line and character, so without them every
     * touch in the blank space a bubble reserves for its timestamp would "hit" whatever link
     * happened to end that line.
     */
    fun linkAt(tv: TextView, x: Float, y: Float): String? {
        val text = tv.text as? Spanned ?: return null
        val layout = tv.layout ?: return null
        val ly = (y - tv.totalPaddingTop + tv.scrollY).toInt()
        val lx = x - tv.totalPaddingLeft + tv.scrollX
        val line = layout.getLineForVertical(ly)
        if (ly < layout.getLineTop(line) || ly > layout.getLineBottom(line)) return null
        if (lx < layout.getLineLeft(line) || lx > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, lx)
        return text.getSpans(offset, offset, URLSpan::class.java).firstOrNull()?.url
    }
}
