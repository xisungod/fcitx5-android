/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

/** Process-local counters. The keyboard renderer and settings both use the main thread. */
internal object SamFrameTiming {
    data class Snapshot(
        val frameIntervals: Long,
        val averageFrameMs: Double?,
        val framesOver20Ms: Long,
        val renders: Long,
        val averageRenderMs: Double?,
        val maxRenderMs: Double?,
        val rendersOver4Ms: Long
    )

    private var frameIntervals = 0L
    private var totalFrameNanos = 0L
    private var framesOver20Ms = 0L
    private var renders = 0L
    private var totalRenderNanos = 0L
    private var maxRenderNanos = 0L
    private var rendersOver4Ms = 0L

    fun recordFrame(nanos: Long) {
        if (nanos <= 0) return
        frameIntervals++
        totalFrameNanos += nanos
        if (nanos > 20_000_000L) framesOver20Ms++
    }

    fun recordRender(nanos: Long) {
        renders++
        totalRenderNanos += nanos
        maxRenderNanos = maxOf(maxRenderNanos, nanos)
        if (nanos > 4_000_000L) rendersOver4Ms++
    }

    fun snapshot() = Snapshot(
        frameIntervals, if (frameIntervals > 0) totalFrameNanos / (frameIntervals * 1_000_000.0) else null,
        framesOver20Ms, renders, if (renders > 0) totalRenderNanos / (renders * 1_000_000.0) else null,
        if (renders > 0) maxRenderNanos / 1_000_000.0 else null, rendersOver4Ms
    )

    fun reset() {
        frameIntervals = 0
        totalFrameNanos = 0
        framesOver20Ms = 0
        renders = 0
        totalRenderNanos = 0
        maxRenderNanos = 0
        rendersOver4Ms = 0
    }
}
