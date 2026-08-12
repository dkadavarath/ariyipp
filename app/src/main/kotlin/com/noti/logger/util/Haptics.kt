package com.noti.logger.util

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton

/**
 * Small, tasteful haptics for control interactions. Every call goes through [View.performHapticFeedback],
 * which honors the system "Touch feedback" setting — so if the user has haptics off, these are no-ops.
 * Constants added in newer APIs fall back to a sensible older one (minSdk is 26).
 */
object Haptics {

    /** Tab / selection tap. */
    fun tabSelect(v: View) = v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)

    /** Up / back navigation — a light tick. */
    fun navUp(v: View) = v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

    /** Long-press that opens a context menu. */
    fun longPress(v: View) = v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

    /** A successful action (saved, sent, paired). */
    fun confirm(v: View) = v.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
    )

    /** A failed or blocked action (bad passphrase, error, not configured). */
    fun reject(v: View) = v.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    )

    private fun toggle(v: View, on: Boolean) = v.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 34) {
            if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
        } else HapticFeedbackConstants.CLOCK_TICK
    )

    /**
     * Recursively adds a toggle tick to every switch/checkbox/radio under [root]. Uses the click slot,
     * which is separate from a control's own OnCheckedChangeListener, so it never clashes with one.
     */
    fun attachToToggles(root: View) {
        if (root is CompoundButton) {
            root.setOnClickListener { toggle(it, (it as CompoundButton).isChecked) }
        } else if (root is ViewGroup) {
            for (i in 0 until root.childCount) attachToToggles(root.getChildAt(i))
        }
    }
}
