package com.noti.logger.util

import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.noti.logger.R

/**
 * The app's motion vocabulary, in code. Mirrors :sender's res/interpolator and animator files.
 *
 * - Entrances and reactions use [EASE_OUT]: fast off the mark, gentle landing.
 * - UI motion stays under 300ms, and exits are quicker than entrances.
 * - Nothing grows from scale 0 - things start at 90-96% so they look like they were already there.
 * - Everything is a ViewPropertyAnimator or Animator, so it can be interrupted mid-flight and
 *   follows the system animator scale ("Remove animations" makes it instant).
 * - Things seen hundreds of times a day (tab content, list rows on open) don't get entrance
 *   choreography; motion is kept for changes the user should notice.
 */
object Motion {

    /** cubic-bezier(0.23, 1, 0.32, 1). */
    val EASE_OUT = PathInterpolator(0.23f, 1f, 0.32f, 1f)

    /** cubic-bezier(0.77, 0, 0.175, 1) - for on-screen A-to-B movement. */
    val EASE_IN_OUT = PathInterpolator(0.77f, 0f, 0.175f, 1f)

    const val ENTER_MS = 200L
    const val EXIT_MS = 130L
    const val LAYOUT_MS = 240L

    /**
     * Shows or hides [view] with a fade and a slight scale, instead of popping. Interruptible:
     * flipping back mid-animation continues from wherever the view currently is. A no-op when the
     * view is already in (or heading to) the requested state, so it is cheap to call on every scroll
     * tick.
     */
    fun setShown(view: View, shown: Boolean, fromScale: Float = 0.96f) {
        val exiting = view.getTag(R.id.motion_exiting) == true
        if (shown) {
            if (view.visibility == View.VISIBLE && !exiting) return
            view.animate().cancel()
            if (view.visibility != View.VISIBLE) {
                view.alpha = 0f
                view.scaleX = fromScale
                view.scaleY = fromScale
                view.visibility = View.VISIBLE
            }
            view.setTag(R.id.motion_exiting, false)
            view.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(ENTER_MS)
                .setInterpolator(EASE_OUT)
                .setListener(null)
                .start()
        } else {
            if (view.visibility != View.VISIBLE || exiting) return
            view.setTag(R.id.motion_exiting, true)
            view.animate().cancel()
            view.animate()
                .alpha(0f).scaleX(fromScale).scaleY(fromScale)
                .setDuration(EXIT_MS)
                .setInterpolator(EASE_OUT)
                .setListener(null)
                .withEndAction {
                    view.visibility = View.GONE
                    view.alpha = 1f
                    view.scaleX = 1f
                    view.scaleY = 1f
                    view.setTag(R.id.motion_exiting, false)
                }
                .start()
        }
    }

    /** Sets the final state immediately, cancelling anything in flight (initial binds, restores). */
    fun setShownNow(view: View, shown: Boolean) {
        view.animate().cancel()
        view.setTag(R.id.motion_exiting, false)
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.visibility = if (shown) View.VISIBLE else View.GONE
    }

    /**
     * Animates the next layout change inside [root] - a card appearing or a button collapsing
     * slides its neighbours into place instead of making them jump. Only once [root] has been laid
     * out: the first bind of a screen should appear settled, not assemble itself.
     */
    fun animateNextLayout(root: ViewGroup) {
        if (!root.isLaidOut) return
        TransitionManager.beginDelayedTransition(
            root,
            AutoTransition().setDuration(LAYOUT_MS).setInterpolator(EASE_OUT),
        )
    }
}
