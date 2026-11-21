package com.noti.logger.ui.widget

import android.content.Context
import android.text.Layout
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * A chat bubble: a message body and a meta row (time + ticks) that never overlap.
 *
 * The meta row rides the body's last line when that line leaves room for it (so a short message
 * stays one line tall), and otherwise drops to its own row under the text. The decision is made
 * from the body's real laid-out text - its last line's actual width and direction - rather than by
 * padding the text with spaces and hoping they come out as wide as the timestamp, which fails with
 * font scaling, emoji, link styling and right-to-left text.
 *
 * Children, in order: the body [TextView], then the meta row. Both are measured wrap_content; the
 * body's own maxWidth caps the bubble.
 */
class ChatBubbleLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    private val body: TextView get() = getChildAt(0) as TextView
    private val meta: View get() = getChildAt(1)

    /** Space kept between the end of the last line and the time when they share a line. */
    private val inlineGap = 8f * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val specWidth = MeasureSpec.getSize(widthMeasureSpec)
        val maxWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) body.maxWidth
        else minOf(specWidth, body.maxWidth)

        body.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        meta.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))

        val bodyW = body.measuredWidth
        val bodyH = body.measuredHeight
        val metaW = meta.measuredWidth
        val metaH = meta.measuredHeight

        val inlineWidth = lastLineEnd()?.let { it + inlineGap + metaW }
        val width: Int
        val height: Int
        if (inlineWidth != null && inlineWidth <= maxWidth) {
            // Shares the last line: widen the bubble only as far as the time needs.
            width = maxOf(bodyW, kotlin.math.ceil(inlineWidth).toInt())
            height = maxOf(bodyH, metaH)
        } else {
            // Own row: the text's bottom padding gives way to the meta row's.
            width = maxOf(bodyW, metaW)
            height = bodyH - body.paddingBottom + metaH
        }
        setMeasuredDimension(
            resolveSize(width + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    /**
     * Where the body's last line visually ends, in this bubble's coordinates - or null when the
     * meta row can't share it: the text hasn't laid out, or the last line runs right-to-left (its
     * free space is on the left, but the meta row is pinned bottom-right).
     */
    private fun lastLineEnd(): Float? {
        val layout = body.layout ?: return null
        if (body.text.isNullOrEmpty()) return 0f
        val last = layout.lineCount - 1
        if (last < 0) return null
        if (layout.getParagraphDirection(last) == Layout.DIR_RIGHT_TO_LEFT) return null
        return body.totalPaddingLeft + layout.getLineRight(last)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        body.layout(paddingLeft, paddingTop, paddingLeft + body.measuredWidth, paddingTop + body.measuredHeight)
        val metaRight = w - paddingRight
        val metaBottom = h - paddingBottom
        meta.layout(metaRight - meta.measuredWidth, metaBottom - meta.measuredHeight, metaRight, metaBottom)
    }
}
