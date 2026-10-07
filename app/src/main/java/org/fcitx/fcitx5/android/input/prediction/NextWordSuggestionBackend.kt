/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.prediction

import org.fcitx.fcitx5.android.core.LibimeNextWordPredictor
import java.io.FileNotFoundException

/** Called on the controller's existing serial worker, never on its key or editor thread. */
internal class NextWordSuggestionBackend(
    private val nativeQuery: (String, Int) -> LibimeNextWordPredictor.Result,
    private val loadIndex: () -> ByteArray,
    private val nowNanos: () -> Long = System::nanoTime
) {
    private var indexAttempted = false
    private var index: NextWordCompletionIndex? = null
    private var indexFailure: String? = null

    fun query(context: String, maxCandidates: Int = 5): LibimeNextWordPredictor.Result {
        val started = nowNanos()
        val native = nativeQuery(context, maxCandidates)
        // An unavailable native model keeps its existing diagnostic semantics. Invalid/empty
        // inputs must not cause asset work or manufacture suggestions the native input rejected.
        if (!native.available || maxCandidates !in 1..8 || context.isEmpty() ||
            !NextWordPredictionRuntime.isHanText(context.takeLast(Character.charCount(context.codePointBefore(context.length))))) {
            return native.copy(elapsedNanos = elapsed(started))
        }
        var initializeNanos = 0L
        var coldIndex = false
        if (!indexAttempted) {
            indexAttempted = true
            coldIndex = true
            val loading = nowNanos()
            try {
                index = NextWordCompletionIndex.parse(loadIndex())
            } catch (_: FileNotFoundException) {
                indexFailure = "CompletionIndexMissing"
            } catch (_: Exception) {
                indexFailure = "CompletionIndexInvalid"
            }
            initializeNanos = elapsed(loading)
        }
        // A known longer prefix defines its completion boundary even if it has no phrases.
        // Never fill a phrase quota by splitting that prefix back into its final character.
        val longest = index?.trailingMatches(context)?.firstOrNull()
        // The bridge owns word segmentation. Without its token boundary, the last character
        // of an unmatched longer context must not be treated as a dictionary word prefix.
        val matches = longest?.takeIf {
            context.codePointCount(0, context.length) == 1 || it.prefix.codePointCount(0, it.prefix.length) > 1
        }?.let(::listOf).orEmpty()
        val values = merge(native, matches, maxCandidates)
        return native.copy(candidates = values, elapsedNanos = elapsed(started),
            coldInitialization = native.coldInitialization || coldIndex,
            completionAvailable = index != null,
            completionInitializationNanos = initializeNanos,
            completionFailureReason = indexFailure)
    }

    private fun merge(native: LibimeNextWordPredictor.Result,
        matches: List<NextWordCompletionIndex.Match>, limit: Int): List<String> {
        val pool = LinkedHashSet<String>()
        fun collect(values: List<String>) {
            for (value in values) {
                if (pool.size == MAX_POOL) break
                if (NextWordPredictionRuntime.isHanText(value)) pool.add(value)
            }
        }
        collect(native.suggestionPool.ifEmpty { native.candidates })
        val model = pool.toList()
        val completionMultis = ArrayList<String>()
        val completionSingles = ArrayList<String>()
        for (match in matches) {
            for (candidate in match.multis) {
                if (pool.size == MAX_POOL) break
                if (pool.add(candidate)) completionMultis.add(candidate)
            }
            for (candidate in match.singles) {
                if (pool.size == MAX_POOL) break
                if (pool.add(candidate)) completionSingles.add(candidate)
            }
        }
        val result = LinkedHashSet<String>()
        fun add(value: String?) { if (value != null && result.size < limit) result.add(value) }
        // The model's strongest prediction survives even when it is a natural single character.
        add(model.firstOrNull())
        // Keep a strong existing phrase pair together instead of replacing it with asset frequency.
        if (model.firstOrNull()?.let(::isMulti) == true) add(model.getOrNull(1))
        // Character length is not information: retain the strongest existing single-character
        // content alternative (for example tea after a drink) before reserving phrase positions.
        // This small display heuristic never rejects a word or changes language-model scores.
        add(native.candidates.firstOrNull {
            NextWordPredictionRuntime.isHanText(it) && !isMulti(it) && it !in LOW_INFORMATION_SINGLES
        })
        var multiCount = result.count(::isInformativeMulti)
        val multiLimit = minOf(2, limit - if (model.firstOrNull()?.let(::isMulti) == false) 1 else 0)
        for (candidate in model.filter(::isInformativeMulti) + completionMultis.filter(::isInformativeMulti)) {
            if (multiCount >= multiLimit || result.size == limit) break
            if (result.add(candidate)) multiCount++
        }
        for (candidate in model) add(candidate)
        for (candidate in completionSingles) add(candidate)
        // A model-empty but known prefix can still use its bounded dictionary continuations.
        for (candidate in completionMultis) add(candidate)
        return result.toList()
    }

    private fun elapsed(started: Long) = (nowNanos() - started).coerceAtLeast(0L)
    private fun isMulti(value: String) = value.codePointCount(0, value.length) > 1
    private fun isInformativeMulti(value: String) = isMulti(value) && value !in LOW_INFORMATION_PHRASES

    companion object {
        private const val MAX_POOL = 128
        // They remain normal candidates; common generic determiners simply do not consume the
        // small diversity allowance intended for concrete continuations such as a question/word.
        private val LOW_INFORMATION_PHRASES = setOf("一个", "一些", "一点", "一种", "这个", "那个", "这种", "那种")
        private val LOW_INFORMATION_SINGLES = setOf("的", "了", "一", "个", "得", "过", "着", "和", "与", "不",
            "在", "是", "也", "又", "就", "把", "比", "之", "其", "于", "及", "而", "或", "所", "等")
    }
}
