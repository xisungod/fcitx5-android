/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * Confirms a change of touch cell from a stable MOVE trajectory. This does not
 * delay ordinary taps: a key is pressed immediately and still commits on UP.
 * Samples use their original event times, including batched MotionEvent history.
 */
internal class TapRetargetGuard {
    enum class Decision { Keep, Settle, Slide }

    private var candidateId: Int? = null
    private var candidateMode = Decision.Keep
    private var startedAt = 0L
    private var lastSampleAt = Long.MIN_VALUE
    private var sampleCount = 0

    fun reset() {
        candidateId = null
        candidateMode = Decision.Keep
        sampleCount = 0
        lastSampleAt = Long.MIN_VALUE
    }

    fun observe(
        keyId: Int?,
        sampleTime: Long,
        maySettle: Boolean,
        maySlide: Boolean
    ): Decision {
        val mode = when {
            maySettle -> Decision.Settle
            maySlide -> Decision.Slide
            else -> Decision.Keep
        }
        if (keyId == null || mode == Decision.Keep) {
            reset()
            return Decision.Keep
        }
        if (candidateId != keyId || candidateMode != mode || sampleTime < lastSampleAt) {
            candidateId = keyId
            candidateMode = mode
            startedAt = sampleTime
            lastSampleAt = sampleTime
            sampleCount = 1
            return Decision.Keep
        }
        if (sampleTime > lastSampleAt) {
            sampleCount++
            lastSampleAt = sampleTime
        }
        val dwell = if (mode == Decision.Settle) SETTLE_DWELL_MS else SLIDE_DWELL_MS
        return if (sampleCount >= MIN_SAMPLES && sampleTime - startedAt >= dwell) mode
        else Decision.Keep
    }

    companion object {
        const val SETTLE_DWELL_MS = 32L
        const val SLIDE_DWELL_MS = 48L
        const val SETTLE_WINDOW_MS = 140L
        const val MIN_SAMPLES = 2
    }
}
