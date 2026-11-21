package com.noti.sender.ui

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.google.android.material.color.MaterialColors
import com.noti.sender.R

/**
 * Binds a `ds_status_row` include: an icon tile, a title, a plain-language detail line, and a
 * badge naming the state ("Allowed", "Connected", "Failed") in its tone's colours. Replaces
 * "Label: value" lines, which read like debug output.
 */
class StatusRow(private val root: View) {

    companion object {
        /** "just now", "5 min. ago", "yesterday" - how long ago [atMs] was, for a detail line. */
        fun ago(context: android.content.Context, atMs: Long, nowMs: Long = System.currentTimeMillis()): CharSequence =
            if (nowMs - atMs < 60_000L) context.getString(R.string.st_just_now)
            else android.text.format.DateUtils.getRelativeTimeSpanString(
                atMs, nowMs, android.text.format.DateUtils.MINUTE_IN_MILLIS,
                android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
            )
    }

    /** How the state should read at a glance: GOOD all is well, WARN the user should act (a fix-it
     *  button sits under the row), BAD something is broken, NEUTRAL off or not applicable. */
    enum class Tone { GOOD, WARN, BAD, NEUTRAL }

    private val icon: ImageView = root.findViewById(R.id.status_icon)
    private val title: TextView = root.findViewById(R.id.status_title)
    private val detail: TextView = root.findViewById(R.id.status_detail)
    private val badge: TextView = root.findViewById(R.id.status_badge)

    fun bind(
        @DrawableRes iconRes: Int,
        titleText: CharSequence,
        detailText: CharSequence?,
        badgeText: CharSequence,
        tone: Tone,
    ) {
        // All from the theme, so the badges follow the app's accent (and Material You's wallpaper
        // colours) instead of bringing their own green and amber. Tone is carried by weight as much
        // as hue: healthy is the quiet accent tint, needs-action the solid accent, broken the
        // theme's error colour.
        fun attr(a: Int) = MaterialColors.getColor(root, a)
        val (fg, bg) = when (tone) {
            Tone.GOOD -> attr(com.google.android.material.R.attr.colorOnPrimaryContainer) to
                attr(com.google.android.material.R.attr.colorPrimaryContainer)
            Tone.WARN -> attr(com.google.android.material.R.attr.colorOnPrimary) to
                attr(androidx.appcompat.R.attr.colorPrimary)
            Tone.BAD -> attr(com.google.android.material.R.attr.colorOnErrorContainer) to
                attr(com.google.android.material.R.attr.colorErrorContainer)
            Tone.NEUTRAL -> attr(com.google.android.material.R.attr.colorOnSurfaceVariant) to
                attr(com.google.android.material.R.attr.colorSurfaceContainerHigh)
        }
        icon.setImageResource(iconRes)
        icon.imageTintList = ColorStateList.valueOf(fg)
        icon.backgroundTintList = ColorStateList.valueOf(bg)
        title.text = titleText
        detail.text = detailText
        detail.visibility = if (detailText.isNullOrEmpty()) View.GONE else View.VISIBLE
        badge.text = badgeText
        badge.setTextColor(fg)
        badge.backgroundTintList = ColorStateList.valueOf(bg)
        // Read as one sentence by TalkBack: "Notification access, Allowed. Captures ...".
        root.contentDescription = listOfNotNull(titleText, badgeText, detailText?.takeIf { it.isNotEmpty() }).joinToString(", ")
        root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        for (child in listOf(icon, title, detail, badge)) child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}
