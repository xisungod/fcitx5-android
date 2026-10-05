/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.os.SystemClock
import org.fcitx.fcitx5.android.data.theme.KeyMotionSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One key compresses, rebounds once, then lands softly. Retargeting preserves position and velocity. */
internal class KeyPressDepth(
    settings: KeyMotionSettings = KeyMotionSettings(),
    private val clock: () -> Long = SystemClock::uptimeMillis
) {
    private enum class Phase { Rest, Pressed, Rebound, Settle, Brake }

    var configuration = settings.normalized()
        private set
    private var held = false
    private var brakeNext = Phase.Rest
    private var brakeDuration = 0.0
    private var brakeAcceleration = 0.0
    private var brakeFromRise = false

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
    private val pressFrequency get() = 48.0 * 180.0 / configuration.pressDuration
    private val reboundTarget = 0.75
    private val reboundFrequency get() = 20.0 * 1000.0 / configuration.reboundDuration
    private val reboundDamping = 0.55
    private val decayFrequency get() = reboundDamping * reboundFrequency
    private val oscillationFrequency get() = reboundFrequency * sqrt(1.0 - reboundDamping * reboundDamping)
    private val settleFrequency get() = 10.0 * 1000.0 / configuration.reboundDuration
    private val positionTolerance = 0.003
    private val velocityTolerance = 0.05

    fun currentLift(): Float {
        sample(clock().toDouble())
        return position.coerceIn(-1.0, 1.0).toFloat()
    }

    fun currentScale(): Float = 1f + scaleDelta(currentLift(),
        configuration.pressAmplitude / 100f, configuration.reboundAmplitude / 100f)

    fun configure(settings: KeyMotionSettings) {
        val next = settings.normalized()
        if (next == configuration) return
        val now = clock().toDouble()
        sample(now)
        val nextPhase = when {
            phase == Phase.Brake && brakeFromRise -> Phase.Rebound
            phase == Phase.Brake -> brakeNext
            else -> phase
        }
        configuration = next
        if (active) startMotion(nextPhase, now)
    }

    fun currentFaceOpacity(): Float {
        sample(clock().toDouble())
        if (phase == Phase.Pressed || phase == Phase.Rebound ||
            (phase == Phase.Brake && (brakeNext != Phase.Settle || brakeFromRise))) return 1f
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
        if (held) return
        val now = clock().toDouble()
        sample(now)
        // Only a resting key gets the immediate default 4% compression seed. Repeated touches
        // inherit the current signed position and speed without an artificial kick.
        if (phase == Phase.Rest) position = -0.5
        held = true
        startMotion(Phase.Pressed, now)
    }

    fun released() {
        if (!held) return
        val now = clock().toDouble()
        sample(now)
        held = false
        startMotion(Phase.Rebound, now)
    }

    private fun startMotion(next: Phase, now: Double) {
        begin(next, now)
        if (next == Phase.Rebound) {
            // Find the exact first upper peak, independently of frame cadence.
            val sineVelocity = -(decayFrequency * originVelocity +
                reboundFrequency * reboundFrequency * (origin - reboundTarget)) / oscillationFrequency
            var angle = atan2(sineVelocity, originVelocity) + PI / 2.0
            if (angle < 0.0) angle += 2.0 * PI
            peakAfter = angle / oscillationFrequency
            val valleyAfter = peakAfter - PI / oscillationFrequency
            val escapesDown = originVelocity < 0.0 && valleyAfter > 0.0 &&
                reboundPosition(valleyAfter) < -1.0
            val escapesUp = originVelocity > 0.0 && reboundPosition(peakAfter) > 1.0
            if (escapesDown) beginBrake(next, -1.0, now)
            else if (escapesUp) beginBrake(Phase.Settle, 1.0, now)
        } else if (next == Phase.Pressed || next == Phase.Settle) {
            val goal = if (next == Phase.Pressed) -1.0 else 0.0
            val frequency = if (next == Phase.Pressed) pressFrequency else settleFrequency
            val coefficient = originVelocity + frequency * (origin - goal)
            val extremum = originVelocity / (frequency * coefficient)
            if (extremum.isFinite() && extremum > 0.0) {
                val value = goal + (origin - goal + coefficient * extremum) * exp(-frequency * extremum)
                if (value < goal && originVelocity < 0.0) beginBrake(next, goal, now)
                else if (value > 1.0 && originVelocity > 0.0) beginBrake(next, 1.0, now)
            }
        }
    }

    private fun beginBrake(next: Phase, boundary: Double, now: Double) {
        // A fast press followed by a very slow release can carry excessive
        // momentum. Stop inside half the remaining margin, analytically, without
        // clipping position or injecting velocity. A new touch can retarget now.
        val remaining = abs(boundary - position)
        if (remaining <= 0.0 || velocity == 0.0) return
        brakeDuration = remaining / abs(velocity)
        brakeAcceleration = -velocity / brakeDuration
        brakeNext = next
        brakeFromRise = phase == Phase.Rebound
        begin(Phase.Brake, now)
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
        held = false
        brakeNext = Phase.Rest
        brakeDuration = 0.0
        brakeAcceleration = 0.0
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
        if (phase == Phase.Brake) {
            val sampled = elapsed.coerceAtMost(brakeDuration)
            position = origin + originVelocity * sampled + brakeAcceleration * sampled * sampled / 2.0
            velocity = originVelocity + brakeAcceleration * sampled
            if (elapsed < brakeDuration) return
            velocity = 0.0
            if (brakeNext == Phase.Settle && brakeFromRise) releaseOrigin = position.coerceAtLeast(0.001)
            startMotion(brakeNext, changedAt + brakeDuration * 1000.0)
            elapsed = (now - changedAt).coerceAtLeast(0.0) / 1000.0
        }
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
        if (abs(position - goal) <= positionTolerance && abs(velocity) <= velocityTolerance *
                (if (phase == Phase.Pressed) 180.0 / configuration.pressDuration
                else 1000.0 / configuration.reboundDuration)) {
            position = goal
            velocity = 0.0
            active = false
            if (phase == Phase.Settle) phase = Phase.Rest
        }
    }

    private fun reboundPosition(elapsed: Double): Double {
        val offset = origin - reboundTarget
        val coefficient = (originVelocity + decayFrequency * offset) / oscillationFrequency
        return reboundTarget + exp(-decayFrequency * elapsed) *
            (offset * cos(oscillationFrequency * elapsed) + coefficient * sin(oscillationFrequency * elapsed))
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
        fun scaleDelta(
            lift: Float,
            pressLoss: Float = PRESS_SCALE_LOSS,
            reboundGain: Float = SCALE_GAIN
        ): Float {
            // Keep the original default curve; independently selected endpoints
            // use a shared bounded tangent so neither branch reverses direction.
            if (pressLoss == PRESS_SCALE_LOSS && reboundGain == SCALE_GAIN) {
                return if (lift <= 0f) PRESS_SCALE_LOSS * lift
                else lift * (0.08f + lift * (-0.085f + 0.035f * lift))
            }
            val slope = min(pressLoss, 3f * reboundGain)
            return if (lift <= 0f) {
                val t = -lift
                -t * (slope + t * (2f * pressLoss - 2f * slope + t * (slope - pressLoss)))
            } else lift * (slope + lift * (2.5f * reboundGain - 2f * slope +
                lift * (slope - 1.5f * reboundGain)))
        }

        /** Monotone down/up travel with the same velocity at the neutral crossing. */
        fun translation(lift: Float, sink: Float, rise: Float): Float {
            val slope = min((sink + rise) / 2f, min(3f * sink, 3f * rise))
            val amplitude = if (lift <= 0f) sink else rise
            val other = if (lift <= 0f) rise else sink
            val endSlope = ((3f * amplitude - other) / 2f).coerceIn(0f, 3f * amplitude)
            val t = abs(lift)
            val value = t * (slope + t * (3f * amplitude - 2f * slope - endSlope +
                t * (slope + endSlope - 2f * amplitude)))
            return if (lift <= 0f) value else -value
        }
    }
}
