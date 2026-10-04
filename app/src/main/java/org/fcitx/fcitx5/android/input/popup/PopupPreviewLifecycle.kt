/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.animation.ValueAnimator
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

/** One reusable preview's enter, readable hold and exit, independent of layout. */
internal class PopupPreviewLifecycle(
    private val view: View,
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val animationsEnabled: () -> Boolean = PopupAnimationPolicy::enabled
) {
    private var generation = 0L
    private var shownAt = 0L
    private var releaseRequested = false
    private var pendingExit: Runnable? = null

    fun show() {
        // Advance before cancelling: even a late animator callback belongs to the old press.
        generation++
        cancelPending()
        shownAt = clock()
        releaseRequested = false
        val wasVisible = view.visibility == View.VISIBLE
        view.visibility = View.VISIBLE
        // Keep glyphs and bubble edges on fixed pixels throughout rapid typing.
        view.scaleX = 1f
        view.scaleY = 1f
        if (!animationsEnabled()) {
            resetTransform()
            return
        }
        // The character is readable on the first frame; a re-press never starts from zero.
        view.alpha = if (wasVisible) view.alpha.coerceAtLeast(0.85f) else 0.78f
        view.animate().alpha(1f)
            .setStartDelay(0L).setDuration(ENTER_MS)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    fun release(onHidden: () -> Unit) {
        // Gesture cancellation and UP may both dismiss the same entry.
        if (releaseRequested) return
        releaseRequested = true
        val ticket = generation
        if (!animationsEnabled()) {
            finish(ticket, onHidden)
            return
        }
        val now = clock()
        val wait = (maxOf(shownAt + READABLE_MS, now + RELEASE_HOLD_MS) - now).coerceAtLeast(0L)
        val exit = Runnable {
            if (generation != ticket) return@Runnable
            pendingExit = null
            if (!animationsEnabled()) {
                finish(ticket, onHidden)
                return@Runnable
            }
            // Finish entry at full opacity before exiting, even when a quick tap
            // reaches its readable hold before entry's final animation frame.
            view.animate().withEndAction(null).setListener(null).cancel()
            resetTransform()
            view.animate().alpha(0f)
                .setStartDelay(0L).setDuration(EXIT_MS)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction { finish(ticket, onHidden) }.start()
        }
        pendingExit = exit
        view.postDelayed(exit, wait)
    }

    /** Window switches have no visual tail and cannot return a reused entry to the pool. */
    fun hideImmediately() {
        generation++
        releaseRequested = true
        cancelPending()
        view.visibility = View.INVISIBLE
        resetTransform()
    }

    private fun finish(ticket: Long, onHidden: () -> Unit) {
        if (generation != ticket) return
        hideImmediately()
        onHidden()
    }

    private fun cancelPending() {
        pendingExit?.let(view::removeCallbacks)
        pendingExit = null
        view.animate().withEndAction(null).setListener(null).cancel()
    }

    private fun resetTransform() {
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    companion object {
        private const val ENTER_MS = 35L
        private const val READABLE_MS = 60L
        private const val RELEASE_HOLD_MS = 15L
        private const val EXIT_MS = 50L
    }
}

internal object PopupAnimationPolicy {
    fun systemAnimationsEnabled() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        ValueAnimator.areAnimatorsEnabled()

    fun enabled() = !AppPrefs.getInstance().advanced.disableAnimation.getValue() &&
        systemAnimationsEnabled()

    /** A stopped press effect does not publish a colour for this touch. */
    fun canMatchPressColor(
        pressEnabled: Boolean,
        appAnimationsDisabled: Boolean,
        followSystemAnimation: Boolean,
        systemAnimationsEnabled: Boolean
    ) = pressEnabled && !appAnimationsDisabled && (!followSystemAnimation || systemAnimationsEnabled)
}
