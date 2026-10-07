/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.typingtest

/**
 * Bounded, test-only receipts. Context and candidate strings are retained only transiently for
 * prescribed-target evaluation, never in exported prediction records or decoder input.
 * The Session validates editor/generation before calling this object.
 */
internal class TypingTestPredictionRecorder(private val prompt: TypingTestPrompt) {
    companion object {
        const val MAX_COMMITS = 64
        const val MAX_OFFERS = 64
        const val MAX_EVENTS = 256
        const val MAX_TEXT = 128
        const val MAX_VISIBLE_INDICES = 8
        private val queryOutcomes = setOf("Published", "NoCandidates", "Unavailable", "Timeout",
            "Stale", "Failed")
    }
    private data class Anchor(val observation: Any, val before: String, val after: String,
        val record: TypingTestPredictionCommit)
    private data class Offer(val observation: Any, val anchor: Anchor, val token: Long,
        val warmth: TypingTestPredictionWarmth, var drawn: Boolean = false,
        var selectedIndex: Int? = null, var resolved: Boolean = false)
    data class Resolution(val prefixBeforeAppend: String, val prefixAfterAppend: String)

    private val anchors = linkedMapOf<Long, Anchor>()
    private val offers = linkedMapOf<Long, Offer>()
    private val actualPrefix = StringBuilder()
    private var scopeReliable = true
    private val mutableCommits = mutableListOf<TypingTestPredictionCommit>()
    private val mutableEvents = mutableListOf<TypingTestPredictionEvent>()
    private val mutableQueries = mutableListOf<TypingTestPredictionQuery>()
    var omittedRecordCount = 0
        private set
    val commits get() = mutableCommits.toList()
    val events get() = mutableEvents.toList()
    val queries get() = mutableQueries.toList()

    /** Editing/moving the field breaks append-only target alignment, not usage/latency evidence. */
    fun invalidateScope() { scopeReliable = false }

    fun commit(observation: Any, token: Long, success: Boolean, text: String, atNanos: Long) {
        if (token < 0 || token in anchors) return
        if (anchors.size >= MAX_COMMITS) {
            omittedRecordCount++
            scopeReliable = false
            return
        }
        val before = actualPrefix.toString()
        if (success) {
            if (text.isEmpty() || text.length > MAX_TEXT - actualPrefix.length) scopeReliable = false
            actualPrefix.append(text.take((MAX_TEXT - actualPrefix.length).coerceAtLeast(0)))
        }
        val record = TypingTestPredictionCommit(token, success, atNanos, scopeReliable && success)
        anchors[token] = Anchor(observation, before, actualPrefix.toString(), record)
        mutableCommits.add(record)
        // A failed editor insertion leaves no dependable append-only alignment for later actions.
        if (!success) scopeReliable = false
    }

    fun publish(observation: Any, commitToken: Long, token: Long, warmth: TypingTestPredictionWarmth) {
        if (token < 0 || token in offers) return
        val anchor = anchors[commitToken]?.takeIf { it.observation === observation && it.record.success }
            ?: return
        if (offers.size >= MAX_OFFERS) { omittedRecordCount++; return }
        if (!reserveEvent()) return
        offers[token] = Offer(observation, anchor, token, warmth)
        mutableEvents.add(TypingTestPredictionEvent(commitToken, token,
            TypingTestPredictionEventKind.Published, warmth))
    }

    fun draw(observation: Any, token: Long, indices: List<Int>, atNanos: Long) {
        val offer = offer(observation, token) ?: return
        val visible = indices.distinct().filter { it in 0 until MAX_VISIBLE_INDICES }
            .take(MAX_VISIBLE_INDICES)
        if (visible.isEmpty()) return
        if (offer.drawn) {
            // Horizontal scrolling can reveal additional full bodies. Preserve the FIRST draw
            // timestamp and one latency sample while accumulating real per-index draw evidence.
            val position = mutableEvents.indexOfFirst { it.offerToken == token &&
                it.kind == TypingTestPredictionEventKind.Drawn }
            if (position >= 0) {
                val first = mutableEvents[position]
                val allVisible = (first.visibleIndices + visible).distinct().take(MAX_VISIBLE_INDICES)
                if (allVisible != first.visibleIndices)
                    mutableEvents[position] = first.copy(visibleIndices = allVisible)
            }
            return
        }
        if (!reserveEvent()) return
        offer.drawn = true
        val duration = runCatching { Math.subtractExact(atNanos, offer.anchor.record.committedAtNanos) }
            .getOrNull()?.takeIf { it >= 0 }
        mutableEvents.add(TypingTestPredictionEvent(offer.anchor.record.commitToken, token,
            TypingTestPredictionEventKind.Drawn, offer.warmth, visibleIndices = visible,
            commitToDrawNanos = duration ?: -1,
            unscoredReason = if (duration == null) "invalid_draw_timestamp" else null))
    }

    fun select(observation: Any, token: Long, index: Int) {
        val offer = offer(observation, token) ?: return
        if (index !in 0 until MAX_VISIBLE_INDICES || offer.selectedIndex != null || !reserveEvent()) return
        offer.selectedIndex = index
        mutableEvents.add(TypingTestPredictionEvent(offer.anchor.record.commitToken, token,
            TypingTestPredictionEventKind.Selected, offer.warmth, selectedIndex = index))
    }

    /** Returns an append receipt only once for actual successful, explicitly selected insertions. */
    fun resolve(observation: Any, token: Long, index: Int, appendText: String,
                success: Boolean): Resolution? {
        val offer = offer(observation, token) ?: return null
        if (offer.resolved || offer.selectedIndex != index || !reserveEvent()) return null
        offer.resolved = true
        val prefix = offer.anchor.after
        val evaluation = when {
            !success -> Evaluation(null, null, "commit_failed")
            !offer.anchor.record.scopeReliable || !scopeReliable || actualPrefix.toString() != prefix ->
                Evaluation(null, null, "append_scope_not_reliable")
            else -> evaluateAppend(prompt, prefix, appendText)
        }
        mutableEvents.add(TypingTestPredictionEvent(offer.anchor.record.commitToken, token,
            TypingTestPredictionEventKind.Resolved, offer.warmth, selectedIndex = index,
            success = success, targetMatched = evaluation.matched,
            estimatedPinyinLetterKeys = evaluation.letters, unscoredReason = evaluation.reason))
        if (!success || appendText.isEmpty() || appendText.length > MAX_TEXT - prefix.length) return null
        return Resolution(prefix, prefix + appendText)
    }

    fun query(observation: Any, commitToken: Long, outcome: String, warmth: TypingTestPredictionWarmth,
        available: Boolean?, elapsedNanos: Long?, initializationNanos: Long?, queryNanos: Long?) {
        val anchor = anchors[commitToken]?.takeIf { it.observation === observation && it.record.success }
            ?: return
        if (mutableQueries.any { it.commitToken == anchor.record.commitToken }) return
        if (mutableQueries.size >= MAX_COMMITS) { omittedRecordCount++; return }
        mutableQueries.add(TypingTestPredictionQuery(commitToken,
            outcome.takeIf { it in queryOutcomes } ?: "Unknown", warmth, available,
            elapsedNanos, initializationNanos, queryNanos))
    }

    private fun reserveEvent(): Boolean {
        if (mutableEvents.size < MAX_EVENTS) return true
        omittedRecordCount++
        return false
    }
    private fun offer(observation: Any, token: Long) = offers[token]?.takeIf { it.observation === observation }

    internal data class Evaluation(val matched: Boolean?, val letters: Int?, val reason: String?)
    internal fun evaluateAppend(prompt: TypingTestPrompt, prefix: String, append: String): Evaluation {
        if (!prompt.text.startsWith(prefix)) return Evaluation(null, null, "committed_prefix_not_target")
        if (prefix == prompt.text) return Evaluation(null, null, "target_already_complete")
        if (append.isEmpty() || append.length > MAX_TEXT) return Evaluation(null, null, "invalid_append")
        // An exact code-point boundary is required; a surrogate half cannot be a target prefix.
        if (prefix.isNotEmpty() && prefix.last().isHighSurrogate())
            return Evaluation(null, null, "unaligned_unicode_boundary")
        val matches = prompt.text.substring(prefix.length).startsWith(append) &&
            !append.last().isHighSurrogate()
        if (!matches) return Evaluation(false, null, "target_mismatch")
        val syllables = prompt.pinyinSyllables
        if (syllables.size != prompt.text.codePointCount(0, prompt.text.length) ||
            syllables.isEmpty() || syllables.any { it.isEmpty() || it.length > 8 ||
                it.any { letter -> letter !in 'a'..'z' } } ||
            syllables.joinToString("") != prompt.pinyin)
            return Evaluation(true, null, "missing_or_invalid_pinyin_alignment")
        val start = prefix.codePointCount(0, prefix.length)
        val count = append.codePointCount(0, append.length)
        return Evaluation(true, syllables.subList(start, start + count).sumOf { it.length }, null)
    }
}
