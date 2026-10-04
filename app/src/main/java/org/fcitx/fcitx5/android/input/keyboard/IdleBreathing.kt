/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/** Monotonic-clock envelope, independent of Android drawing and input delivery. */
internal class IdleBreathing(
    idleDelayMs: Long = 1800L,
    cycleMs: Long = 5200L,
    fadeInMs: Long = 850L,
    fadeOutMs: Long = 180L,
    brightness: Float = 0.28f,
    timeoutMs: Long = 60000L
) {
    private val idleDelay = idleDelayMs.coerceIn(500L, 10000L)
    private val cycle = cycleMs.coerceIn(2000L, 15000L)
    private val fadeInDuration = fadeInMs.coerceIn(100L, 3000L)
    private val fadeOutDuration = fadeOutMs.coerceIn(50L, 1500L)
    private val peak = brightness.coerceIn(0.05f, 0.6f)
    var running = false
        private set
    private var touching = false
    private var idleAt = 0L
    private var fadeAt = 0L
    private var fadeFrom = 0f
    private var fadePhase = 0f
    private val timeout = timeoutMs.coerceIn(15000L, 300000L)
    private var stopAt = 0L

    fun resume(now: Long) {
        running = true
        touching = false
        idleAt = now + idleDelay
        stopAt = now + timeout
        fadeFrom = 0f
    }

    fun stop() {
        running = false
        touching = false
        fadeFrom = 0f
    }

    fun activity(now: Long, held: Boolean) {
        if (!running) return
        fadePhase = phase(now)
        fadeFrom = alpha(now)
        fadeAt = now
        touching = held
        // A long exit fade must finish before a newly configured short idle wait restarts it.
        idleAt = now + max(idleDelay, if (fadeFrom > 0f) fadeOutDuration else 0L)
        stopAt = now + timeout
    }

    fun phase(now: Long): Float =
        if (fadeFrom > 0f && now - fadeAt in 0L until fadeOutDuration) fadePhase
        else ((now - idleAt).coerceAtLeast(0L) % cycle).toFloat() / cycle

    fun alpha(now: Long): Float {
        if (!running || now >= stopAt) return 0f
        val fadeElapsed = now - fadeAt
        if (fadeFrom > 0f && fadeElapsed in 0L until fadeOutDuration) {
            return fadeFrom * (1f - fadeElapsed.toFloat() / fadeOutDuration)
        }
        if (touching || now < idleAt) return 0f
        val fadeIn = ((now - idleAt).toFloat() / fadeInDuration).coerceIn(0f, 1f)
        val pulse = (0.5 - 0.5 * cos(2.0 * PI * phase(now))).toFloat()
        val exitFade = ((stopAt - now).toFloat() / fadeOutDuration).coerceIn(0f, 1f)
        return peak * (0.04f + 0.96f * pulse) * fadeIn * exitFade
    }

    /** No timer while hidden or holding a key; a single timer during the idle delay. */
    fun nextFrameDelay(now: Long): Long? {
        if (!running || now >= stopAt) return null
        if (fadeFrom > 0f && now - fadeAt in 0L until fadeOutDuration) return 0L
        if (touching) return null
        return if (now < idleAt) idleAt - now else IDLE_FRAME_MS
    }

    companion object {
        const val IDLE_FRAME_MS = 42L
    }
}
