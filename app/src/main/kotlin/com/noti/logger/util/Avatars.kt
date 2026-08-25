package com.noti.logger.util

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.TextView

/**
 * Contact avatars: a solid colored circle with the contact's initials (or a person
 * glyph for a bare phone number). The color is deterministic per conversation key, so a given sender
 * always gets the same one.
 */
object Avatars {

    // A curated palette that reads well with white text (Material ~700 tones).
    private val palette = intArrayOf(
        0xFFC2185B.toInt(), // crimson
        0xFF7B1FA2.toInt(), // purple
        0xFF512DA8.toInt(), // deep purple
        0xFF303F9F.toInt(), // indigo
        0xFF1976D2.toInt(), // blue
        0xFF0277BD.toInt(), // light blue
        0xFF00796B.toInt(), // teal
        0xFF388E3C.toInt(), // green
        0xFFF57C00.toInt(), // orange
        0xFFE64A19.toInt(), // deep orange
        0xFF5D4037.toInt(), // brown
        0xFF455A64.toInt(), // blue grey
    )

    // Compiled once, not per row-bind.
    private val SEPARATORS = Regex("[\\s\\-_./]+")

    fun colorFor(key: String): Int = palette[Math.floorMod(key.hashCode(), palette.size)]

    /** Up to two letters: first-of-first + first-of-last word. Empty for a numeric-only sender. */
    fun initials(name: String): String {
        val words = name.trim().split(SEPARATORS).filter { it.any(Char::isLetter) }
        val letters = words.mapNotNull { w -> w.firstOrNull(Char::isLetter) }
        return when {
            letters.isEmpty() -> ""
            letters.size == 1 -> letters[0].uppercaseChar().toString()
            else -> "${letters.first().uppercaseChar()}${letters.last().uppercaseChar()}"
        }
    }

    /** Tints [circle] and shows initials in [initials], or a person glyph in [icon] for a number. */
    fun apply(circle: View, initials: TextView, icon: ImageView, name: String) {
        circle.backgroundTintList = ColorStateList.valueOf(colorFor(name))
        val ini = initials(name)
        if (ini.isEmpty()) {
            icon.visibility = View.VISIBLE
            initials.visibility = View.GONE
        } else {
            initials.text = ini
            initials.visibility = View.VISIBLE
            icon.visibility = View.GONE
        }
    }
}
