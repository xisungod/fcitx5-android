/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

/**
 * A confirmed letter tap, captured in the same unanimated coordinates as its key cells.
 * Confirmation may precede the physical UP when overlapping fingers are released in
 * reverse order. A missing physicalUpTime is deliberate; dispatchTime is not an UP.
 */
data class PinyinTapEvidence(
    val tap: TapEvidence,
    val cells: List<KeyCell>,
    val diagnosticTraceId: String? = null,
    val diagnosticToken: Long? = null,
    val pointerId: Int = -1,
    val downTime: Long = 0L,
    /** Process-unique contact identity; unlike pointerId it is never reused by another gesture. */
    val contactId: Long = 0L,
    val downSequence: Long? = null,
    val dispatchSequence: Long? = null,
    /** Monotonic MotionEvent clock, matching downTime and physicalUpTime. */
    val dispatchTime: Long? = null,
    val physicalUpTime: Long? = null,
    val physicalUpX: Float? = null,
    val physicalUpY: Float? = null
)
