/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** One key compresses, rebounds once, then lands softly. Retargeting preserves position and velocity. */
internal class KeyPressDepth(private val clock: () -> Long = SystemClock::uptimeMillis) {
    private enum class Phase { Rest, Pressed, Rebound, Settle }

    // Signed motion: -1 is fully compressed, 0 rests, +1 is the upper drawing limit.
    private var phase = Phase.Rest
    private var position = 0.0
    private var velocity = 0.0
    private var origin = 0.0
    private var originVelocity = 0.0
    private var changedAt = 0.0
    private var peakAfter = 0.0
    private var releaseOrigin = 1.0
    private var active = false

    // Design values, not inferred Samsung constants. UP changes the spring target
    // immediately; even a zero-duration tap reaches one visible peak around 190ms.
    private val pressFrequency = 48.0
    private val reboundTarget = 0.75
    private val reboundFrequency = 20.0
    private val reboundDamping = 0.55
    private val decayFrequency = reboundDamping * reboundFrequency
    private val oscillationFrequency = reboundFrequency * sqrt(1.0 - reboundDamping * reboundDamping)
    private val settleFrequency = 10.0
    private val positionTolerance = 0.003
    private val velocityTolerance = 0.05

    fun currentLift(): Float {
        sample(clock().toDouble())
        return position.coerceIn(-1.0, 1.0).toFloat()
    }

    fun currentScale(): Float = 1f + scaleDelta(currentLift())

    fun currentFaceOpacity(): Float {
        sample(clock().toDouble())
        if (phase == Phase.Pressed || phase == Phase.Rebound) return 1f
        if (phase == Phase.Rest) return 0f
        // Crossing zero during the rise must never extinguish the coloured cap.
        // Only the descent from the actual positive peak fades the shared face.
        val remaining = (position / releaseOrigin).coerceIn(0.0, 1.0)
        val end = (remaining / 0.04).coerceIn(0.0, 1.0)
        return (sqrt(remaining) * end * end * (3.0 - 2.0 * end)).toFloat()
    }

    internal fun currentVelocity(): Float {
        sample(clock().toDouble())
        return velocity.toFloat()
    }

    fun isTransitioning(): Boolean {
        sample(clock().toDouble())
        return active
    }

    fun pressed() {
        if (phase == Phase.Pressed) return
        val now = clock().toDouble()
        sample(now)
        // Only a resting key gets an immediate 4% compression. Repeated touches
        // inherit the current signed position and speed without an artificial kick.
        if (phase == Phase.Rest) position = -0.5
        begin(Phase.Pressed, now)
    }

    fun released() {
        if (phase != Phase.Pressed) return
        val now = clock().toDouble()
        sample(now)
        begin(Phase.Rebound, now)
        // v(t)=exp(-a*t)*(v0*cos(b*t)-D*sin(b*t)). The first + to -
        // zero is the exact upper peak; calculating it now avoids frame-dependent
        // phase changes, including a frame that skips the entire rebound.
        val sineVelocity = -(decayFrequency * originVelocity +
            reboundFrequency * reboundFrequency * (origin - reboundTarget)) / oscillationFrequency
        var angle = atan2(sineVelocity, originVelocity) + PI / 2.0
        if (angle < 0.0) angle += 2.0 * PI
        peakAfter = angle / oscillationFrequency
    }

    private fun begin(next: Phase, now: Double) {
        phase = next
        origin = position
        originVelocity = velocity
        changedAt = now
        active = true
    }

    fun reset() {
        phase = Phase.Rest
        position = 0.0
        velocity = 0.0
        origin = 0.0
        originVelocity = 0.0
        changedAt = 0.0
        peakAfter = 0.0
        releaseOrigin = 1.0
        active = false
    }

    private fun sample(now: Double) {
        if (!active) return
        var elapsed = (now - changedAt).coerceAtLeast(0.0) / 1000.0
        if (phase == Phase.Rebound) {
            if (elapsed < peakAfter) {
                sampleRebound(elapsed)
                return
            }
            sampleRebound(peakAfter)
            // The analytic peak has zero velocity (remove floating-point residue).
            velocity = 0.0
            releaseOrigin = position.coerceAtLeast(0.001)
            begin(Phase.Settle, changedAt + peakAfter * 1000.0)
            elapsed = (now - changedAt).coerceAtLeast(0.0) / 1000.0
        }
        val goal = if (phase == Phase.Pressed) -1.0 else 0.0
        val frequency = if (phase == Phase.Pressed) pressFrequency else settleFrequency
        val offset = origin - goal
        val coefficient = originVelocity + frequency * offset
        val decay = exp(-frequency * elapsed)
        position = goal + (offset + coefficient * elapsed) * decay
        velocity = (originVelocity - frequency * coefficient * elapsed) * decay
        if (abs(position - goal) <= positionTolerance && abs(velocity) <= velocityTolerance) {
            position = goal
            velocity = 0.0
            active = false
            if (phase == Phase.Settle) phase = Phase.Rest
        }
    }

    private fun sampleRebound(elapsed: Double) {
        val offset = origin - reboundTarget
        val coefficient = (originVelocity + decayFrequency * offset) / oscillationFrequency
        val cosine = cos(oscillationFrequency * elapsed)
        val sine = sin(oscillationFrequency * elapsed)
        val decay = exp(-decayFrequency * elapsed)
        position = reboundTarget + decay * (offset * cosine + coefficient * sine)
        velocity = decay * (originVelocity * cosine -
            (decayFrequency * originVelocity + reboundFrequency * reboundFrequency * offset) /
                oscillationFrequency * sine)
    }

    companion object {
        const val SCALE_GAIN = 0.03f
        const val PRESS_SCALE_LOSS = 0.08f

        /** Match slopes at the neutral crossing, despite the different press/rebound sizes. */
        fun scaleDelta(lift: Float): Float = if (lift <= 0f) PRESS_SCALE_LOSS * lift
        else lift * (0.08f + lift * (-0.085f + 0.035f * lift))
    }
}
