/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

/** A completed letter tap, captured in the same unanimated coordinates as its key cells. */
data class PinyinTapEvidence(
    val tap: TapEvidence,
    val cells: List<KeyCell>,
    val diagnosticTraceId: String? = null,
    val diagnosticToken: Long? = null,
    val pointerId: Int = -1,
    val downTime: Long = 0L
)
