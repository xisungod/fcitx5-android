/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.prediction

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** Memory-only state. Public methods are synchronized because native receipts cross threads. */
internal class NextWordPredictionRuntime {
    data class Environment(
        val editorIdentity: Any?,
        val inputConnectionIdentity: Any?,
        val eligible: Boolean,
        val visible: Boolean,
        val nativeIdle: Boolean,
        val cursorStart: Int,
        val cursorEnd: Int
    )

    data class Query(val anchor: NextWordPredictionAnchor, val context: String, val cursor: Int,
                     val exposureSequence: Long)
    data class Commit(val anchor: NextWordPredictionAnchor, val query: Query?)
    data class Pending(val offer: NextWordPredictionOffer, val query: Query)
    data class Selection(val pending: Pending, val origin: NextWordPredictionOrigin,
                         val text: String, val startCursor: Int, val endCursor: Int)

    private var editorIdentity: Any? = null
    private var inputConnectionIdentity: Any? = null
    private var editorEpoch = 0L
    private var contextEpoch = 0L
    private var actionSequence = 0L
    private var context = ""
    private var cursor: Int? = null
    private var latestAnchor: NextWordPredictionAnchor? = null
    private var pending: Pending? = null
    private var ownSelectionCursor: Int? = null
    private var exposureSequence = 0L
    private var exposureBlocked = false
    private val mutableOffer = MutableStateFlow<NextWordPredictionOffer?>(null)
    val offer = mutableOffer.asStateFlow()

    @Synchronized fun attach(editor: Any?, connection: Any?) {
        resetEditor()
        editorIdentity = editor
        inputConnectionIdentity = connection
    }

    @Synchronized fun resetEditor() {
        // Old native events can outlive an IME/controller instance, not only its current field.
        editorEpoch = editorEpochs.incrementAndGet()
        invalidate(clearContext = true)
        editorIdentity = null
        inputConnectionIdentity = null
    }

    @Synchronized fun captureOrigin(observation: Any? = null) =
        NextWordPredictionOrigin(editorEpoch, contextEpoch, actionSequence, observation)

    @Synchronized fun onUserAction(clearContext: Boolean = false): NextWordPredictionOrigin {
        invalidate(clearContext)
        return captureOrigin()
    }

    @Synchronized fun invalidate(clearContext: Boolean = true) {
        actionSequence++
        pending = null
        mutableOffer.value = null
        ownSelectionCursor = null
        exposureSequence++
        exposureBlocked = false
        if (clearContext) {
            contextEpoch++
            context = ""
            cursor = null
            latestAnchor = null
        }
    }

    private fun sameEditor(environment: Environment) = editorIdentity != null &&
        inputConnectionIdentity != null && environment.editorIdentity === editorIdentity &&
        environment.inputConnectionIdentity === inputConnectionIdentity && environment.eligible &&
        environment.visible

    private fun sameContext(origin: NextWordPredictionOrigin) = origin.editorEpoch == editorEpoch &&
        origin.contextEpoch == contextEpoch && origin.actionSequence <= actionSequence

    /** An earlier queued commit may extend context, but can never publish after a later action. */
    @Synchronized fun onActualCommitted(origin: NextWordPredictionOrigin?, text: String,
        startCursor: Int, endCursor: Int, atNanos: Long, environment: Environment): Commit? {
        if (origin == null || !sameContext(origin) || !sameEditor(environment)) return null
        ownSelectionCursor = null
        pending = null
        mutableOffer.value = null
        val anchor = NextWordPredictionAnchor(commitTokens.incrementAndGet(), origin, atNanos)
        if (!isHanText(text) || startCursor < 0 || endCursor.toLong() != startCursor.toLong() + text.length ||
            (cursor != null && cursor != startCursor)) {
            // An unknown insertion, a selected range, punctuation or Latin text is a boundary.
            contextEpoch++
            context = ""
            cursor = null
            latestAnchor = null
            return Commit(anchor, null)
        }
        context = lastHanCodePoints(context + text, MAX_CONTEXT_CODE_POINTS)
        cursor = endCursor
        latestAnchor = anchor
        return Commit(anchor, refresh(environment))
    }

    /** Native panel events may hide suggestions without changing a user action's receipt. */
    @Synchronized fun refresh(environment: Environment): Query? {
        if (!sameEditor(environment) || !environment.nativeIdle || cursor == null ||
            environment.cursorStart != cursor || environment.cursorEnd != cursor) {
            pending = null
            mutableOffer.value = null
            if (!exposureBlocked) exposureSequence++
            exposureBlocked = true
            return null
        }
        exposureBlocked = false
        val anchor = latestAnchor ?: return null
        if (!anchor.origin.sameGeneration(captureOrigin()) || context.isEmpty()) return null
        return Query(anchor, context, cursor!!, exposureSequence)
    }

    @Synchronized fun isCurrent(query: Query) =
        query.anchor.origin.sameGeneration(captureOrigin()) &&
            latestAnchor?.commitToken == query.anchor.commitToken &&
            context == query.context && cursor == query.cursor &&
            query.exposureSequence == exposureSequence && !exposureBlocked

    @Synchronized fun allows(query: Query, environment: Environment) = isCurrent(query) &&
        sameEditor(environment) && environment.nativeIdle && environment.cursorStart == query.cursor &&
        environment.cursorEnd == query.cursor

    @Synchronized fun publish(query: Query, candidates: List<String>, environment: Environment): Pending? {
        if (!allows(query, environment)) return null
        val safe = candidates.asSequence().filter(::isHanText).distinct().take(MAX_CANDIDATES).toList()
        if (safe.isEmpty()) return null
        val next = Pending(NextWordPredictionOffer(offerTokens.incrementAndGet(), safe), query)
        pending = next
        mutableOffer.value = next.offer
        return next
    }

    @Synchronized fun pending(token: Long, environment: Environment): Pending? = pending?.takeIf {
        it.offer.token == token && allows(it.query, environment)
    }

    /** Consume before calling the editor. Repeated clicks and synchronous callbacks are safe. */
    @Synchronized fun claim(token: Long, index: Int, observation: Any?, environment: Environment): Selection? {
        val selected = pending(token, environment) ?: return null
        val text = selected.offer.candidates.getOrNull(index) ?: return null
        pending = null
        mutableOffer.value = null
        actionSequence++
        val end = selected.query.cursor.toLong() + text.length
        if (end > Int.MAX_VALUE) return null
        ownSelectionCursor = end.toInt()
        return Selection(selected, captureOrigin(observation), text, selected.query.cursor, end.toInt())
    }

    @Synchronized fun selectionStillAllowed(selection: Selection, environment: Environment) =
        selection.origin.sameGeneration(captureOrigin()) && sameEditor(environment) && environment.nativeIdle &&
            environment.cursorStart == selection.startCursor && environment.cursorEnd == selection.startCursor

    @Synchronized fun onSelectionChanged(start: Int, end: Int) {
        // An editor may acknowledge commitText synchronously, before it returns its Boolean.
        if (start == end && (start == ownSelectionCursor || start == cursor)) return
        invalidate(clearContext = true)
    }

    @Synchronized fun contextSnapshot(): String = context

    companion object {
        const val MAX_CONTEXT_CODE_POINTS = 64
        const val MAX_CANDIDATES = 5
        private val commitTokens = AtomicLong()
        private val offerTokens = AtomicLong()
        private val editorEpochs = AtomicLong()

        fun isHanText(text: String): Boolean {
            if (text.isEmpty() || text.length > 4096) return false
            var index = 0
            while (index < text.length) {
                val point = text.codePointAt(index)
                // Character.UnicodeScript is Android API 24; AXiang also supports API 23.
                val han = point in 0x3400..0x4DBF || point in 0x4E00..0x9FFF ||
                    point in 0xF900..0xFAFF || point in 0x20000..0x2A6DF ||
                    point in 0x2A700..0x2B73F || point in 0x2B740..0x2B81F ||
                    point in 0x2B820..0x2CEAF || point in 0x2CEB0..0x2EBEF ||
                    point in 0x2EBF0..0x2EE5F || point in 0x2F800..0x2FA1F ||
                    point in 0x30000..0x3134F || point in 0x31350..0x323AF
                if (!han || !Character.isLetter(point)) return false
                index += Character.charCount(point)
            }
            return true
        }

        private fun lastHanCodePoints(text: String, maximum: Int): String {
            val count = text.codePointCount(0, text.length)
            return if (count <= maximum) text else text.substring(text.offsetByCodePoints(0, count - maximum))
        }
    }
}
