package com.noti.logger.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * A list item animator whose new rows rise into place and fade up (optionally from a slightly
 * smaller scale, pivoting where [pivot] says) with [Motion.EASE_OUT], instead of the stock linear
 * fade. Moves and removals keep [DefaultItemAnimator]'s behaviour.
 *
 * Change animations are off: a row whose text updates (a new preview line, a bubble's corners
 * reflowing next to a new message) rebinds in place; the stock cross-fade makes it flicker.
 *
 * RecyclerView never animates its first layout, so opening a screen shows the list settled. Only
 * rows that arrive while the user is looking animate in.
 */
class SoftItemAnimator(
    private val liftPx: Float,
    private val startScale: Float = 1f,
    private val pivot: ((RecyclerView.ViewHolder) -> Pair<Float, Float>?)? = null,
) : DefaultItemAnimator() {

    private val pendingAdds = ArrayList<RecyclerView.ViewHolder>()
    private val runningAdds = ArrayList<RecyclerView.ViewHolder>()

    init {
        supportsChangeAnimations = false
        addDuration = 260
        removeDuration = 120
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        endAnimation(holder)
        holder.itemView.apply {
            alpha = 0f
            translationY = liftPx
            scaleX = startScale
            scaleY = startScale
        }
        pendingAdds += holder
        return true
    }

    override fun runPendingAnimations() {
        val adds = ArrayList(pendingAdds)
        pendingAdds.clear()
        super.runPendingAnimations()
        // Adds run alongside the moves rather than after them (the stock order): in a chat, the
        // older bubbles moving up and the new one rising in read as one motion.
        adds.forEach(::startAdd)
    }

    private fun startAdd(holder: RecyclerView.ViewHolder) {
        val view = holder.itemView
        pivot?.invoke(holder)?.let { (x, y) ->
            view.pivotX = x
            view.pivotY = y
        }
        runningAdds += holder
        view.animate()
            .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(addDuration)
            .setInterpolator(Motion.EASE_OUT)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) = dispatchAddStarting(holder)

                override fun onAnimationCancel(animation: Animator) = settle(view)

                override fun onAnimationEnd(animation: Animator) {
                    view.animate().setListener(null)
                    settle(view)
                    runningAdds.remove(holder)
                    dispatchAddFinished(holder)
                    if (!isRunning) dispatchAnimationsFinished()
                }
            })
            .start()
    }

    private fun settle(view: View) {
        view.alpha = 1f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    override fun endAnimation(item: RecyclerView.ViewHolder) {
        if (pendingAdds.remove(item)) {
            settle(item.itemView)
            dispatchAddFinished(item)
        }
        // Cancelling fires onAnimationEnd above, which settles the view and dispatches.
        if (item in runningAdds) item.itemView.animate().cancel()
        super.endAnimation(item)
    }

    override fun endAnimations() {
        pendingAdds.forEach {
            settle(it.itemView)
            dispatchAddFinished(it)
        }
        pendingAdds.clear()
        ArrayList(runningAdds).forEach { it.itemView.animate().cancel() }
        super.endAnimations()
    }

    override fun isRunning(): Boolean =
        pendingAdds.isNotEmpty() || runningAdds.isNotEmpty() || super.isRunning()
}
