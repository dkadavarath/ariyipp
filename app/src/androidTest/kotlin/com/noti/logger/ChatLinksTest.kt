package com.noti.logger

import android.text.Spanned
import android.text.style.URLSpan
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noti.logger.util.ChatLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Link detection in chat bubbles, and - the part that actually decides whether copying a URL works
 * - resolving a touch to the link under it.
 *
 * [ChatLinks.linkAt] is measured against a real laid-out TextView because that is the only place
 * the interesting cases exist: a URL that wrapped across two lines, and the blank run the bubble
 * appends to reserve room for its timestamp, which sits immediately after the text and must not
 * count as part of a link that ends it.
 */
@RunWith(AndroidJUnit4::class)
class ChatLinksTest {

    private fun spans(text: CharSequence): List<URLSpan> =
        (text as? Spanned)?.getSpans(0, text.length, URLSpan::class.java)?.toList() ?: emptyList()

    /**
     * Lays out [text] in a real TextView at [widthPx] and hands it back for touch resolution.
     *
     * No Activity: linkAt only reads the view's Layout and padding, and launching one here would
     * drag in the role-onboarding redirect a fresh install performs. The main thread is still the
     * right place to build a View.
     */
    private fun laidOut(text: CharSequence, widthPx: Int, block: (TextView) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val tv = TextView(instrumentation.targetContext).apply {
                setPadding(0, 0, 0, 0)
                setText(text)
                measure(
                    View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                layout(0, 0, measuredWidth, measuredHeight)
            }
            assertNotNull("TextView must be laid out for linkAt to work", tv.layout)
            block(tv)
        }
    }

    /** The centre of the character at [index], in the view's coordinates. */
    private fun pointAt(tv: TextView, index: Int): Pair<Float, Float> {
        val layout = tv.layout
        val line = layout.getLineForOffset(index)
        val x = (layout.getPrimaryHorizontal(index) + layout.getPrimaryHorizontal(index + 1)) / 2f
        val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
        return x + tv.totalPaddingLeft to y + tv.totalPaddingTop
    }

    // ---- Detection ----

    @Test fun a_plain_body_is_returned_untouched() {
        val body = "Your OTP is 449120. Do not share it with anyone."
        val linked = ChatLinks.linkify(body)
        assertEquals("no links means no Spannable", body, linked)
        assertTrue(spans(linked).isEmpty())
    }

    @Test fun an_https_url_is_marked_up_whole() {
        val url = "https://www.hdfcbank.com/pay/statement?ref=88421-AF"
        val linked = ChatLinks.linkify("Statement ready. Pay at $url before 07-Feb.")
        val found = spans(linked).single()
        assertEquals(url, found.url)
        // The span must cover exactly the URL - not the trailing full stop, not the word before it.
        val text = linked as Spanned
        assertEquals(url, text.subSequence(text.getSpanStart(found), text.getSpanEnd(found)).toString())
    }

    @Test fun a_bare_host_gets_a_scheme_so_it_can_be_opened() {
        val linked = ChatLinks.linkify("Track it on www.bluedart.com today")
        assertEquals("http://www.bluedart.com", spans(linked).single().url)
    }

    @Test fun several_links_in_one_message_are_all_found() {
        val linked = ChatLinks.linkify("Pay https://a.example.com or view https://b.example.com/x")
        assertEquals(listOf("https://a.example.com", "https://b.example.com/x"), spans(linked).map { it.url })
    }

    @Test fun only_web_schemes_are_produced() {
        // Linkify.WEB_URLS is the only matcher used, so nothing here should become tappable.
        val linked = ChatLinks.linkify("Call tel:+919876543210 or mail to help@example.com")
        assertTrue("no non-web link should be produced, got ${spans(linked).map { it.url }}", spans(linked).isEmpty())
    }

    // ---- Touch resolution ----

    @Test fun touching_a_url_that_wrapped_across_lines_still_resolves_to_the_whole_url() {
        val url = "https://www.hdfcbank.com/netbanking/statement/download?id=99120445&fmt=pdf"
        val linked = ChatLinks.linkify("Your statement: $url")
        // Narrow enough to force the URL onto more than one line - the case that made hand-selecting
        // it pick up a line break.
        laidOut(linked, 320) { tv ->
            assertTrue("URL should wrap for this test to mean anything", tv.layout.lineCount > 1)
            val text = linked as Spanned
            val span = text.getSpans(0, text.length, URLSpan::class.java).single()
            // Sample the URL at its start, somewhere in the middle, and near its end - the middle
            // and end land on later lines.
            for (i in listOf(text.getSpanStart(span), (text.getSpanStart(span) + text.getSpanEnd(span)) / 2, text.getSpanEnd(span) - 1)) {
                val (x, y) = pointAt(tv, i)
                assertEquals("offset $i should resolve to the full URL", url, ChatLinks.linkAt(tv, x, y))
            }
        }
    }

    @Test fun touching_the_words_beside_a_link_resolves_to_nothing() {
        val linked = ChatLinks.linkify("Statement ready at https://example.com/x now")
        laidOut(linked, 600) { tv ->
            val (x, y) = pointAt(tv, 2) // inside "Statement"
            assertNull(ChatLinks.linkAt(tv, x, y))
        }
    }

    @Test fun the_blank_space_reserved_for_the_timestamp_is_not_part_of_a_trailing_link() {
        val url = "https://example.com/p"
        // What bindMessage builds: the linked body plus the run of spaces the timestamp overlays.
        val linked = android.text.SpannableStringBuilder(ChatLinks.linkify("Pay at $url")).append("          ")
        laidOut(linked, 600) { tv ->
            val layout = tv.layout
            val line = layout.getLineForOffset(linked.length - 1)
            val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f + tv.totalPaddingTop
            // Well past the end of the real text, where the time and ticks are drawn.
            val x = layout.getLineRight(line) + 40f + tv.totalPaddingLeft
            assertNull("the meta area must not count as a link hit", ChatLinks.linkAt(tv, x, y))
            // The URL itself, on the same line, still resolves.
            val (ux, uy) = pointAt(tv, linked.indexOf(url) + 4)
            assertEquals(url, ChatLinks.linkAt(tv, ux, uy))
        }
    }

    @Test fun a_touch_below_the_last_line_resolves_to_nothing() {
        val linked = ChatLinks.linkify("https://example.com/only")
        laidOut(linked, 600) { tv ->
            val below = tv.layout.getLineBottom(tv.layout.lineCount - 1) + tv.totalPaddingTop + 60f
            assertNull(ChatLinks.linkAt(tv, 10f, below))
        }
    }

    @Test fun a_body_with_no_links_never_resolves() {
        val body = "No links here at all"
        laidOut(ChatLinks.linkify(body), 600) { tv ->
            val (x, y) = pointAt(tv, 3)
            assertNull(ChatLinks.linkAt(tv, x, y))
        }
    }
}
