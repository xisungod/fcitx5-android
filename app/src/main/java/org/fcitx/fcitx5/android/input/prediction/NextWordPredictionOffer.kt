/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.prediction

/** A separate idle suggestion. Its indices are never native candidate indices. */
data class NextWordPredictionOffer(val token: Long, val candidates: List<String>)

/** Captured before enqueueing input, never reconstructed from a delayed commit event. */
internal data class NextWordPredictionOrigin(
    val editorEpoch: Long,
    val contextEpoch: Long,
    val actionSequence: Long,
    /** Optional host receipt, such as an explicitly active typing-test observation. */
    val observation: Any? = null
) {
    fun sameGeneration(other: NextWordPredictionOrigin) = editorEpoch == other.editorEpoch &&
        contextEpoch == other.contextEpoch && actionSequence == other.actionSequence
}

/** Does not contain preceding text. Hosts can record timings without recording ordinary input. */
internal data class NextWordPredictionAnchor(
    val commitToken: Long,
    val origin: NextWordPredictionOrigin,
    val committedAtNanos: Long
)
