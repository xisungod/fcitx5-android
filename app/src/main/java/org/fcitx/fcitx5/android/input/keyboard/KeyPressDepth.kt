/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** One key's floating face and colour tail. Retargeting preserves position and velocity. */
internal class KeyPressDepth(private val clock: () -> Long = SystemClock::uptimeMillis) {
    // Normalised elevation: zero rests on the keyboard, one is fully floating.
    private var position = 0.0
    private var velocity = 0.0
    private var origin = 0.0
    private var originVelocity = 0.0
    private var releaseOrigin = 1.0
    private var changedAt = 0L
    private var held = false
    private var active = false

    // Our design values, not measurements of another keyboard. DOWN starts with
    // a visible lift, then rises quickly. UP floats down and settles in 800–900ms.
    // Critical damping never introduces a second bounce below the keyboard.
    private val pressFrequency = 48.0
    private val releaseFrequency = 10.0
    private val positionTolerance = 0.003
    private val velocityTolerance = 0.05 // normalised elevation per second

    fun currentLift(): Float {
        sample(clock())
        return position.coerceIn(0.0, 1.0).toFloat()
    }

    fun currentScale(): Float = 1f + SCALE_GAIN * currentLift()

    /** The actual lit face uses the same moving state, never a separate 30/100ms timer. */
    fun currentFaceOpacity(): Float {
        sample(clock())
        if (held) return 1f
        val remaining = (position / releaseOrigin).coerceIn(0.0, 1.0)
        // Keep the whole coloured cap readable during the main descent. Smooth
        // the final few levels to black before the tiny physical remainder stops.
        val end = (remaining / 0.04).coerceIn(0.0, 1.0)
        return (sqrt(remaining) * end * end * (3.0 - 2.0 * end)).toFloat()
    }

    internal fun currentVelocity(): Float {
        sample(clock())
        return velocity.toFloat()
    }

    fun isTransitioning(): Boolean {
        sample(clock())
        return active
    }

    fun pressed() {
        if (!held) retarget(true)
    }

    fun released() {
        if (held) retarget(false)
    }

    private fun retarget(pressed: Boolean) {
        val now = clock()
        sample(now)
        // An idle key responds in the first drawn frame. A returning key keeps
        // its exact position and speed, so repeated taps cannot kick it around.
        if (pressed && !active && position == 0.0) position = 0.45
        origin = position
        originVelocity = velocity
        if (!pressed) releaseOrigin = position.coerceAtLeast(0.001)
        changedAt = now
        held = pressed
        active = abs(position - target()) > positionTolerance || abs(velocity) > velocityTolerance
        if (!active) {
            position = target()
            velocity = 0.0
        }
    }

    fun reset() {
        position = 0.0
        velocity = 0.0
        origin = 0.0
        originVelocity = 0.0
        releaseOrigin = 1.0
        changedAt = 0L
        held = false
        active = false
    }

    private fun target() = if (held) 1.0 else 0.0

    private fun sample(now: Long) {
        if (!active) return
        val elapsed = (now - changedAt).coerceAtLeast(0L) / 1000.0
        val goal = target()
        val offset = origin - goal
        val frequency = if (held) pressFrequency else releaseFrequency
        val coefficient = originVelocity + frequency * offset
        val decay = exp(-frequency * elapsed)
        // Exact critical-damping solution: skipped frames and fast repeat taps
        // evaluate the same motion rather than accumulating integration error.
        position = goal + (offset + coefficient * elapsed) * decay
        velocity = (originVelocity - frequency * coefficient * elapsed) * decay
        if (abs(position - goal) <= positionTolerance && abs(velocity) <= velocityTolerance) {
            position = goal
            velocity = 0.0
            active = false
        }
    }

    companion object {
        const val SCALE_GAIN = 0.025f
    }
}
