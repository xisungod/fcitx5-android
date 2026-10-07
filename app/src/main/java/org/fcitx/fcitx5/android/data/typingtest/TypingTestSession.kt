/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.typingtest

import android.content.Context
import android.content.res.Configuration
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.RimeTouchProbe
import org.fcitx.fcitx5.android.core.RimeTouchProbeStatus
import org.fcitx.fcitx5.android.data.diagnostics.TouchDiagnosticPolicy
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinTapEvidence
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinTouchProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.security.MessageDigest
import kotlin.math.ceil
import java.util.IdentityHashMap

enum class TypingTestPhase { Intro, Typing, Completed, Report }

data class TypingTestState(
    val phase: TypingTestPhase = TypingTestPhase.Intro,
    val prompt: TypingTestPrompt? = null,
    val index: Int = 0,
    val total: Int = 0,
    val completed: Int = 0,
    val results: List<TypingTestTrialResult> = emptyList(),
    val reportSummary: String? = null,
    val failure: String? = null,
    val reportAvailable: Boolean = false,
    val reportWarning: String? = null,
    val lastCommittedText: String? = null
)

/** Identity is checked again inside the queued input job; stale work cannot enter a new trial. */
class TypingTestKeyTicket internal constructor(
    internal val generation: Long,
    internal val editor: EditorInfo,
    internal val letter: Char?,
    internal val letterOrdinal: Int,
    internal val enqueueNanos: Long,
    internal val keyOrdinal: Int,
    internal val backspace: Boolean,
    internal val contactId: Long?,
    internal val belongsToRawAttempt: Boolean,
    internal var startNanos: Long? = null,
    internal var finished: Boolean = false
)

/** A delayed probe may outlive a key callback, but never this test editor/generation. */
class TypingTestObservationTicket internal constructor(
    internal val generation: Long,
    internal val editor: EditorInfo,
    internal val enqueueNanos: Long
)

/**
 * Memory-only observation is armed solely by the foreground test screen's explicit Start.
 * This does not enable the global diagnostic logger or train/apply a touch profile.
 */
object TypingTestSession {
    private const val MAX_KEYS = 512
    private const val MAX_COMMITTED = 128
    private const val MAX_ALTERNATIVE_EVENTS = 256
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storageMutex = Mutex()
    private val mutableState = MutableStateFlow(TypingTestState())
    val state = mutableState.asStateFlow()
    private var context: Context? = null
    private var fieldId = 0
    private var editor: EditorInfo? = null
    private var active = false
    private var generation = 0L
    private var pending = 0
    private var prompts: List<TypingTestPrompt> = emptyList()
    private var trial: Trial? = null
    private var reportJson: String? = null
    private val inputs = mutableListOf<TypingTestTrialInput>()
    private val trialMetadata = mutableListOf<JSONObject>()
    private var startedAt = 0L
    private var sessionReceipt = UUID.randomUUID().toString()
    private var reportRevision = 0L
    private var readyGeneration = -1L
    private val nativeReceipts = IdentityHashMap<FcitxEvent.CommitStringEvent, CommitReceipt>()
    private var discardedTrials = 0
    private data class ContactReceipt(val generation: Long, val trial: Trial, val index: Int)
    private val contactReceipts = mutableMapOf<Long, ContactReceipt>()
    private data class CommitReceipt(val generation: Long, val editor: EditorInfo)
    private data class PublishedOfferReceipt(val ticket: TypingTestObservationTicket, val publishedNanos: Long)

    private data class KeyTiming(val keyOrdinal: Int, val letter: Char?, val backspace: Boolean,
        val contactId: Long?, val enqueueNanos: Long, val startNanos: Long?, val finishedNanos: Long)

    private class Trial(val prompt: TypingTestPrompt, val settings: JSONObject) {
        val firstLetters = StringBuilder()
        val firstTouches = mutableListOf<TypingTestTouch>()
        var firstFrozen = false
        var firstComplete = false
        var firstOmittedLetters = 0
        var firstKind: TypingTestInputKind? = null
        var rawKind: TypingTestInputKind? = null
        var pendingRawKeys = 0
        var actions = 0
        var letters = 0
        val keyTimeline = mutableListOf<KeyTiming>()
        var backspaces = 0
        var commits = 0
        var nativeCommits = 0
        val committed = StringBuilder()
        var latestRaw: String? = null
        var latestSnapshot: TypingTestCandidateSnapshot? = null
        var initialRaw: String? = null
        var initialSnapshot: TypingTestCandidateSnapshot? = null
        var initialCaptured = false
        var displayedSnapshot: TypingTestCandidateSnapshot? = null
        var selectedRaw: String? = null
        var selectedSnapshot: TypingTestCandidateSnapshot? = null
        val turnaroundNanos = mutableListOf<Long>()
        val queueNanos = mutableListOf<Long>()
        val engineNanos = mutableListOf<Long>()
        val snapshotNanos = mutableListOf<Long>()
        val sendKeyNanos = mutableListOf<Long>()
        val touchSearchNanos = mutableListOf<Long>()
        val alternativeQueryNanos = mutableListOf<Long>()
        val offerReadyNanos = mutableListOf<Long>()
        val probeLibraryLoadNanos = mutableListOf<Long>()
        val probeInitializationNanos = mutableListOf<Long>()
        val probeNativeQueryNanos = mutableListOf<Long>()
        val timedOfferTokens = mutableSetOf<Long>()
        val publishedOfferReceipts = mutableMapOf<Long, PublishedOfferReceipt>()
        val omittedStageSamples = mutableMapOf<TypingTestStage, Int>()
        val alternativeEvents = mutableListOf<TypingTestAlternativeEvent>()
        val probeQueries = mutableListOf<TypingTestProbeQuery>()
        var omittedProbeQueries = 0
        var omittedAlternativeEvents = 0
        val unsupported = linkedSetOf<String>()
        val predictions = TypingTestPredictionRecorder(prompt)
    }

    suspend fun loadHistory(context: Context) {
        val app = context.applicationContext
        val revision = synchronized(lock) { this.context = app; reportRevision }
        val loaded = withContext(Dispatchers.IO) { TypingTestReportStore.readLatest(app) }
        synchronized(lock) {
            if (revision != reportRevision || trial != null) return
            reportJson = loaded
            mutableState.value = mutableState.value.copy(reportAvailable = loaded != null)
        }
    }

    fun start(context: Context, count: Int, editorFieldId: Int) = synchronized(lock) {
        require(count == 5 || count == 20)
        require(editorFieldId == R.id.typing_test_input)
        this.context = context.applicationContext
        fieldId = editorFieldId
        prompts = TypingTestPrompts.all.take(count)
        inputs.clear()
        trialMetadata.clear()
        startedAt = System.currentTimeMillis()
        sessionReceipt = UUID.randomUUID().toString()
        discardedTrials = 0
        generation++
        nativeReceipts.clear()
        contactReceipts.clear()
        pending = 0
        active = false
        trial = Trial(prompts.first(), currentSettings())
        mutableState.value = TypingTestState(TypingTestPhase.Typing, prompts.first(), total = count,
            reportAvailable = reportJson != null)
    }

    fun setActive(value: Boolean) = synchronized(lock) {
        val next = value && mutableState.value.phase == TypingTestPhase.Typing
        if (active && !next) invalidatePending("interrupted_pending_key")
        active = next
    }

    fun attachEditor(info: EditorInfo?) = synchronized(lock) {
        val allowed = info?.takeIf { it.packageName == BuildConfig.APPLICATION_ID &&
            it.fieldId == R.id.typing_test_input && TouchDiagnosticPolicy.allows(it) }
        if (editor !== allowed) {
            invalidatePending("editor_changed_with_pending_key")
            editor = allowed
        }
    }

    fun revokeEditor() = synchronized(lock) {
        invalidatePending("editor_revoked_with_pending_key")
        editor = null
    }

    private fun invalidatePending(reason: String) {
        trial?.takeIf { it.actions > 0 || it.commits > 0 || it.predictions.commits.isNotEmpty() }
            ?.predictions?.invalidateScope()
        if (pending > 0) trial?.let { current ->
            current.unsupported.add(reason)
            if (current.pendingRawKeys > 0) current.rawKind = TypingTestInputKind.UNKNOWN
            current.pendingRawKeys = 0
        }
        generation++
        pending = 0
        nativeReceipts.clear()
        contactReceipts.clear()
    }

    fun isEligibleEditor(info: EditorInfo?): Boolean = synchronized(lock) { eligible(info) }

    /** Capture this before scheduling work; checking only the current editor later is unsafe. */
    fun beginObservation(info: EditorInfo?): TypingTestObservationTicket? = synchronized(lock) {
        if (!eligible(info)) null else TypingTestObservationTicket(generation, info!!, System.nanoTime())
    }

    private fun valid(ticket: TypingTestObservationTicket?) = ticket != null &&
        ticket.generation == generation && eligible(ticket.editor)

    /** Actual elapsed samples only. No query, percentile work, JSON or disk I/O on the key path. */
    fun recordStage(ticket: TypingTestObservationTicket?, stage: TypingTestStage,
                    elapsedNanos: Long): Unit = synchronized(lock) {
        if (!valid(ticket)) return
        val current = trial ?: return
        val samples = when (stage) {
            TypingTestStage.SendKey -> current.sendKeyNanos
            TypingTestStage.TouchSearch -> current.touchSearchNanos
            TypingTestStage.AlternativeQuery -> current.alternativeQueryNanos
            TypingTestStage.ProbeLibraryLoad -> current.probeLibraryLoadNanos
            TypingTestStage.ProbeInitialization -> current.probeInitializationNanos
            TypingTestStage.ProbeNativeQuery -> current.probeNativeQueryNanos
            // This duration must be bound to an actually published token, never fabricated.
            TypingTestStage.OfferReady -> return
        }
        if (samples.size < TypingTestMetrics.MAX_TIMING_SAMPLES) samples.add(elapsedNanos)
        else current.omittedStageSamples[stage] = (current.omittedStageSamples[stage] ?: 0) + 1
    }

    /** Only the actual completed call is recorded; cache and cold creation remain distinguishable. */
    internal fun recordProbeQuery(ticket: TypingTestObservationTicket?, offerToken: Long,
                                  result: RimeTouchProbe.QueryResult) = synchronized(lock) {
        if (!valid(ticket) || offerToken < 0) return@synchronized
        val current = trial ?: return@synchronized
        if (current.probeQueries.any { it.offerToken == offerToken }) return@synchronized
        if (current.probeQueries.size >= MAX_ALTERNATIVE_EVENTS) {
            current.omittedProbeQueries++
            return@synchronized
        }
        current.probeQueries.add(TypingTestProbeQuery(offerToken, result.elapsedNanos,
            result.libraryLoadNanos, result.initializationNanos, result.nativeQueryNanos,
            result.cacheHit, result.coldInitialization, result.available, result.withinBudget,
            result.nativeWithinBudget, result.failureReason?.take(64)))
    }

    /** The same enqueue receipt survives key completion, search and query callbacks. */
    fun recordOfferReady(ticket: TypingTestObservationTicket?, offerToken: Long): Unit = synchronized(lock) {
        if (!valid(ticket)) return
        val current = trial ?: return
        val published = current.publishedOfferReceipts[offerToken] ?: return
        if (published.ticket !== ticket || offerToken in current.timedOfferTokens) return
        current.timedOfferTokens.add(offerToken)
        if (current.offerReadyNanos.size < TypingTestMetrics.MAX_TIMING_SAMPLES)
            current.offerReadyNanos.add(published.publishedNanos - ticket!!.enqueueNanos)
        else current.omittedStageSamples[TypingTestStage.OfferReady] =
            (current.omittedStageSamples[TypingTestStage.OfferReady] ?: 0) + 1
    }

    /**
     * Counts describe the real offer lifecycle, not guesses about the user's intention.
     * Published means the offer reached the UI data stream; Displayed requires a UI callback.
     * The existing original-Rime first-attempt snapshot remains independent of these events.
     */
    fun recordAlternative(ticket: TypingTestObservationTicket?, offerToken: Long,
                          event: TypingTestAlternativeEventKind,
                          originalPinyin: String? = null, alternativePinyin: String? = null,
                          candidateText: String? = null, reason: String? = null,
                          success: Boolean? = null, originalRank: Int? = null,
                          searchPathCount: Int? = null): Unit = synchronized(lock) {
        if (!valid(ticket) || offerToken < 0) return
        val current = trial ?: return
        if (current.alternativeEvents.any { it.offerToken == offerToken && it.kind == event }) return
        if (current.alternativeEvents.size >= MAX_ALTERNATIVE_EVENTS) {
            current.omittedAlternativeEvents++
            return
        }
        val prior = current.alternativeEvents.lastOrNull { it.offerToken == offerToken &&
            it.kind == TypingTestAlternativeEventKind.Published }
            ?: current.alternativeEvents.lastOrNull { it.offerToken == offerToken }
        // Selection/resolve metadata must originate from this generation's published offer.
        if (event in setOf(TypingTestAlternativeEventKind.Displayed,
                TypingTestAlternativeEventKind.Selected, TypingTestAlternativeEventKind.Resolved) &&
            current.alternativeEvents.none { it.offerToken == offerToken &&
                it.kind == TypingTestAlternativeEventKind.Published }) return
        val raw = (originalPinyin ?: prior?.originalPinyin)?.take(TypingTestAligner.MAX_LENGTH)
        val alternative = (alternativePinyin ?: prior?.alternativePinyin)?.take(TypingTestAligner.MAX_LENGTH)
        val text = (candidateText ?: prior?.candidateText)?.take(MAX_COMMITTED)
        val fullPrompt = if (event in setOf(TypingTestAlternativeEventKind.Displayed,
                TypingTestAlternativeEventKind.Selected, TypingTestAlternativeEventKind.Resolved))
            prior?.fullPromptComposition == true else current.commits == 0 &&
            current.nativeCommits == 0 && current.unsupported.isEmpty() &&
            raw != null && raw.length == current.prompt.pinyin.length && raw.all { it in 'a'..'z' }
        current.alternativeEvents.add(TypingTestAlternativeEvent(offerToken, event, raw, alternative,
            text, reason?.take(64), success, fullPrompt,
            originalRank?.takeIf { it >= 0 } ?: prior?.originalRank,
            searchPathCount?.takeIf { it >= 0 } ?: prior?.searchPathCount,
            prior?.inputKindAtCapture ?: if (fullPrompt) TypingTestInputKind.FULL_PINYIN else qualification(current)))
        if (event == TypingTestAlternativeEventKind.Published)
            current.publishedOfferReceipts[offerToken] = PublishedOfferReceipt(ticket!!, System.nanoTime())
    }

    /** Actual InputConnection success only, tied to the original action receipt. */
    fun recordPredictionCommit(ticket: TypingTestObservationTicket?, commitToken: Long,
        success: Boolean, committedText: String, committedAtNanos: Long): Unit = synchronized(lock) {
        if (!valid(ticket)) return@synchronized
        trial?.predictions?.commit(ticket!!, commitToken, success, committedText, committedAtNanos)
    }

    fun recordPredictionPublished(ticket: TypingTestObservationTicket?, commitToken: Long,
        offerToken: Long, warmth: TypingTestPredictionWarmth): Unit = synchronized(lock) {
        if (!valid(ticket)) return@synchronized
        trial?.predictions?.publish(ticket!!, commitToken, offerToken, warmth)
    }

    /** Invoked only after UI dispatchDraw has drawn complete visible candidate bodies. */
    fun recordPredictionDrawn(ticket: TypingTestObservationTicket?, offerToken: Long,
        visibleIndices: List<Int>, drawnAtNanos: Long): Unit = synchronized(lock) {
        if (!valid(ticket)) return@synchronized
        trial?.predictions?.draw(ticket!!, offerToken, visibleIndices, drawnAtNanos)
    }

    fun recordPredictionSelected(ticket: TypingTestObservationTicket?, offerToken: Long,
        index: Int): Unit = synchronized(lock) {
        if (!valid(ticket)) return@synchronized
        trial?.predictions?.select(ticket!!, offerToken, index)
    }

    /** Direct prediction commits bypass native receipts, so add only their successful append. */
    fun recordPredictionResolved(ticket: TypingTestObservationTicket?, offerToken: Long,
        index: Int, appendText: String, success: Boolean): Unit = synchronized(lock) {
        if (!valid(ticket)) return@synchronized
        val current = trial ?: return@synchronized
        val append = current.predictions.resolve(ticket!!, offerToken, index, appendText, success)
            ?: return@synchronized
        freezeFirstAttempt(current)
        when (current.committed.toString()) {
            append.prefixBeforeAppend -> recordCommit(appendText)
            append.prefixAfterAppend -> Unit // A delivered receipt already accounted for this append.
            else -> {
                current.unsupported.add("prediction_commit_ledger_mismatch")
                current.predictions.invalidateScope()
            }
        }
        // Prediction usage is not manually typed raw pinyin, and cannot create touch labels.
        current.unsupported.add("prediction_input_used")
    }

    fun recordPredictionQuery(ticket: TypingTestObservationTicket?, commitToken: Long,
        outcome: String, warmth: TypingTestPredictionWarmth, available: Boolean?,
        elapsedNanos: Long?, initializationNanos: Long?, queryNanos: Long?): Unit = synchronized(lock) {
        if (!valid(ticket)) return@synchronized
        trial?.predictions?.query(ticket!!, commitToken, outcome, warmth, available,
            elapsedNanos, initializationNanos, queryNanos)
    }

    private fun eligible(info: EditorInfo?): Boolean = active && trial != null &&
        mutableState.value.phase == TypingTestPhase.Typing && info != null && info === editor &&
        info.packageName == BuildConfig.APPLICATION_ID && info.fieldId == fieldId &&
        fieldId != 0 && TouchDiagnosticPolicy.allows(info)

    fun beginKey(info: EditorInfo?, letter: Char?, evidence: PinyinTapEvidence?,
                 backspace: Boolean = false, supported: Boolean = true,
                 orientation: Int = 0): TypingTestKeyTicket? = synchronized(lock) {
        if (!eligible(info)) return null
        val current = trial ?: return null
        if (current.actions >= MAX_KEYS) {
            current.unsupported.add("key_limit_reached")
            return null
        }
        if (!supported) {
            current.predictions.invalidateScope()
            current.unsupported.add("unsupported_key")
            if (!current.firstFrozen) current.rawKind = TypingTestInputKind.UNKNOWN
        }
        if (current.settings.toString() != currentSettings().toString()) {
            current.predictions.invalidateScope()
            current.unsupported.add("settings_changed_during_trial")
            if (!current.firstFrozen) current.rawKind = TypingTestInputKind.UNKNOWN
        }
        val belongsToRawAttempt = letter != null && !current.firstFrozen
        if (belongsToRawAttempt) current.pendingRawKeys++
        if (backspace) {
            current.predictions.invalidateScope()
            current.backspaces++
            freezeFirstAttempt(current)
        }
        if (letter != null) {
            current.letters++
            if (letter !in 'a'..'z') current.unsupported.add("non_lowercase_letter")
            if (!current.firstFrozen && current.firstLetters.length >= TypingTestAligner.MAX_LENGTH)
                current.firstOmittedLetters++
            if (!current.firstFrozen && current.firstLetters.length < TypingTestAligner.MAX_LENGTH) {
                current.firstLetters.append(letter)
                if (evidence != null && evidence.tap.original == letter) {
                    current.firstTouches.add(TypingTestTouch(letter, evidence.tap.downX,
                        evidence.tap.downY, evidence.tap.density, evidence.cells.toList(),
                        when (orientation) {
                            Configuration.ORIENTATION_PORTRAIT -> "portrait"
                            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
                            else -> "unknown"
                        }, contactId = evidence.contactId.takeIf { it > 0 },
                        pointerId = evidence.pointerId, downTime = evidence.downTime,
                        downSequence = evidence.downSequence, dispatchSequence = evidence.dispatchSequence,
                        dispatchTime = evidence.dispatchTime, physicalUpTime = evidence.physicalUpTime,
                        physicalUpX = evidence.physicalUpX, physicalUpY = evidence.physicalUpY))
                    if (evidence.contactId > 0) contactReceipts[evidence.contactId] =
                        ContactReceipt(generation, current, current.firstTouches.lastIndex)
                }
                if (current.firstLetters.length == current.prompt.pinyin.length) {
                    current.firstComplete = true
                    freezeFirstQualification(current)
                }
            }
        }
        pending++
        current.actions++
        TypingTestKeyTicket(generation, info!!, letter, current.letters, System.nanoTime(),
            current.actions, backspace, evidence?.contactId?.takeIf { it > 0 }, belongsToRawAttempt)
    }

    fun startKey(ticket: TypingTestKeyTicket) = synchronized(lock) {
        if (valid(ticket)) {
            ticket.startNanos = System.nanoTime()
            readyGeneration = generation
        }
    }

    private fun valid(ticket: TypingTestKeyTicket) = !ticket.finished &&
        ticket.generation == generation && eligible(ticket.editor)

    fun finishKey(ticket: TypingTestKeyTicket, finishedAtNanos: Long,
                  raw: String?, candidates: List<String>?, schemaSupported: Boolean,
                  snapshotNanos: Long = 0) = synchronized(lock) {
        if (!valid(ticket)) return
        ticket.finished = true
        pending = (pending - 1).coerceAtLeast(0)
        val current = trial ?: return
        if (current.keyTimeline.size < MAX_KEYS) current.keyTimeline.add(KeyTiming(ticket.keyOrdinal,
            ticket.letter, ticket.backspace, ticket.contactId, ticket.enqueueNanos, ticket.startNanos,
            finishedAtNanos))
        if (ticket.belongsToRawAttempt) current.pendingRawKeys = (current.pendingRawKeys - 1).coerceAtLeast(0)
        if (!schemaSupported) {
            current.predictions.invalidateScope()
            if (ticket.belongsToRawAttempt) current.rawKind = TypingTestInputKind.UNKNOWN
            current.unsupported.add("not_full_chinese_rime")
            if (ticket.letter != null && ticket.letterOrdinal <= current.firstLetters.length &&
                !current.initialCaptured)
                current.firstKind = TypingTestInputKind.UNKNOWN
        }
        if (ticket.letter != null) {
            current.turnaroundNanos.add(finishedAtNanos - ticket.enqueueNanos)
            ticket.startNanos?.let {
                current.queueNanos.add(it - ticket.enqueueNanos)
                current.engineNanos.add(finishedAtNanos - it)
            }
            current.snapshotNanos.add(snapshotNanos)
        }
        if (raw != null && raw.isNotEmpty() && raw.length <= 64) {
            current.latestRaw = raw
            current.latestSnapshot = candidates?.let { TypingTestCandidateSnapshot(raw,
                it.take(3).map { word -> word.take(MAX_COMMITTED) },
                completePromptComposition = current.nativeCommits == 0 && current.commits == 0 &&
                    raw.length == current.prompt.pinyin.length,
                coherent = schemaSupported,
                inputKindAtCapture = qualification(current),
                priorCommitCountAtCapture = maxOf(current.nativeCommits, current.commits)) }
            if (!current.initialCaptured && current.firstComplete &&
                ticket.letter != null && ticket.letterOrdinal == current.prompt.pinyin.length) {
                current.initialCaptured = true
                current.initialRaw = raw
                // Later edits and commits must not reclassify evidence that was valid now.
                current.initialSnapshot = current.latestSnapshot?.copy(
                    inputKindAtCapture = if (schemaSupported) current.firstKind else TypingTestInputKind.UNKNOWN)
            }
        }
    }

    /** First visible layout for the first complete composition, independent of native ordering. */
    fun recordDisplayedCandidates(info: EditorInfo?, raw: String, candidates: List<String>) =
        synchronized(lock) {
            if (!eligible(info)) return@synchronized
            val current = trial ?: return@synchronized
            if (current.displayedSnapshot != null || !current.initialCaptured || current.firstFrozen ||
                raw != current.initialRaw || raw != current.latestRaw ||
                current.nativeCommits != 0 || current.commits != 0) return@synchronized
            val initial = current.initialSnapshot ?: return@synchronized
            if (!initial.completePromptComposition || !initial.coherent ||
                initial.inputKindAtCapture != TypingTestInputKind.FULL_PINYIN) return@synchronized
            current.displayedSnapshot = initial.copy(candidates = candidates.take(3)
                .map { it.take(MAX_COMMITTED) })
        }

    /** A physically later UP can complete an early DOWN-order confirmation, never another trial. */
    fun completeTouchEvidence(contactId: Long, physicalUpTime: Long, x: Float, y: Float) =
        synchronized(lock) {
            val receipt = contactReceipts.remove(contactId) ?: return@synchronized
            if (receipt.generation != generation || trial !== receipt.trial || !eligible(editor) ||
                !x.isFinite() || !y.isFinite()) return@synchronized
            val touch = receipt.trial.firstTouches.getOrNull(receipt.index) ?: return@synchronized
            if (touch.contactId != contactId || touch.physicalUpTime != null ||
                touch.downTime?.let { physicalUpTime < it } == true) return@synchronized
            receipt.trial.firstTouches[receipt.index] = touch.copy(physicalUpTime = physicalUpTime,
                physicalUpX = x, physicalUpY = y)
        }

    private fun qualification(current: Trial) = if (current.unsupported.isEmpty() &&
        current.commits == 0 && current.nativeCommits == 0) TypingTestInputKind.FULL_PINYIN
        else TypingTestInputKind.UNKNOWN

    private fun freezeFirstQualification(current: Trial) {
        if (current.firstKind == null) current.firstKind = qualification(current)
    }

    private fun freezeFirstAttempt(current: Trial) {
        if (current.firstFrozen) return
        current.firstFrozen = true
        freezeFirstQualification(current)
        if (current.rawKind == null) current.rawKind = qualification(current)
    }

    fun markUnsupported(info: EditorInfo?, reason: String) = synchronized(lock) {
        if (eligible(info)) trial?.let { current ->
            if (reason == "prediction_context_edited") {
                current.predictions.invalidateScope()
                return@synchronized // Prediction-only guard; do not reclassify legacy raw evidence.
            }
            if (reason == "non_typing_action") freezeFirstAttempt(current)
            current.unsupported.add(reason.take(64))
            // Normal candidate selection and optional snapshot errors do not edit the field.
            if (reason !in setOf("non_typing_action", "candidate_snapshot_failed"))
                current.predictions.invalidateScope()
        }
    }

    /** Freeze on fcitx-main before the asynchronous UI flow can deliver a newer composition. */
    fun prepareNativeCommit(event: FcitxEvent.CommitStringEvent) = synchronized(lock) {
        val bound = editor ?: return
        if (readyGeneration != generation || !eligible(bound) || event.data.text.isEmpty()) return
        if (nativeReceipts.size >= 16) {
            trial?.unsupported?.add("commit_receipt_limit")
            return
        }
        val current = trial ?: return
        if (current.nativeCommits == 0) freezeFirstCommit(current)
        current.nativeCommits++
        nativeReceipts[event] = CommitReceipt(generation, bound)
    }

    /** Only a receipt from this editor/generation can become a delivered test commit. */
    fun observeCommit(info: EditorInfo?, event: FcitxEvent.CommitStringEvent) = synchronized(lock) {
        val receipt = nativeReceipts.remove(event) ?: return
        if (receipt.generation != generation || receipt.editor !== info || !eligible(info)) return
        recordCommit(event.data.text)
    }

    /** Internal test adapter: production uses generation-bound native event receipts above. */
    internal fun observeCommit(info: EditorInfo?, text: String) = synchronized(lock) {
        if (!eligible(info) || text.isEmpty()) return
        val current = trial ?: return
        if (current.commits == 0 && current.nativeCommits == 0) freezeFirstCommit(current)
        recordCommit(text)
    }

    private fun freezeFirstCommit(current: Trial) {
        freezeFirstAttempt(current)
        current.selectedRaw = current.latestRaw
        current.selectedSnapshot = current.latestSnapshot
    }

    private fun recordCommit(text: String) {
        val current = trial ?: return
        current.commits++
        if (current.committed.length < MAX_COMMITTED)
            current.committed.append(text.take(MAX_COMMITTED - current.committed.length))
    }

    /** A recreated Android view must start a clean trial rather than append to a lost field. */
    fun onViewDestroyed() = synchronized(lock) {
        active = false
        invalidatePending("view_recreated_with_pending_key")
        val current = trial ?: return
        if (mutableState.value.phase == TypingTestPhase.Typing &&
            (current.letters > 0 || current.backspaces > 0 || current.commits > 0)) {
            discardedTrials++
            trial = Trial(current.prompt, currentSettings())
            mutableState.value = mutableState.value.copy(failure =
                "页面已重建，本句请重新输入；已完成句子保留")
        }
    }

    fun completePhrase(committedText: String) = synchronized(lock) {
        val current = trial ?: return
        if (mutableState.value.phase != TypingTestPhase.Typing) return
        if (pending > 0) {
            mutableState.value = mutableState.value.copy(failure = "按键仍在处理，请稍候再完成")
            return
        }
        if (committedText.length > MAX_COMMITTED) current.unsupported.add("text_limit_reached")
        freezeFirstAttempt(current)
        val final = committedText.take(MAX_COMMITTED)
        if (current.commits == 0 || current.committed.toString() != final)
            current.unsupported.add("unobserved_or_edited_commit")
        val kind = if (current.unsupported.isNotEmpty()) TypingTestInputKind.UNKNOWN
            else if (current.commits != 1) TypingTestInputKind.SHORTHAND_OR_MIXED
            else TypingTestInputKind.FULL_PINYIN
        val input = TypingTestTrialInput(current.prompt, kind,
            firstAttemptPinyin = current.firstLetters.toString().ifEmpty { null },
            firstAttemptComplete = current.firstComplete,
            firstAttemptTouches = current.firstTouches.toList(),
            finalInputPinyin = current.selectedRaw,
            observedAttemptPinyin = current.initialRaw,
            finalCandidateSnapshot = current.initialSnapshot,
            committedText = final, backspaceCount = current.backspaces,
            letterKeyCount = current.letters, processingNanos = current.turnaroundNanos.toList(),
            priorCommitCount = (current.commits - 1).coerceAtLeast(0),
            candidateInputPinyin = current.initialRaw,
            stageTimings = TypingTestStageTimings(current.sendKeyNanos.toList(),
                current.touchSearchNanos.toList(), current.alternativeQueryNanos.toList(),
                current.offerReadyNanos.toList(), current.probeLibraryLoadNanos.toList(),
                current.probeInitializationNanos.toList(), current.probeNativeQueryNanos.toList()),
            alternativeEvents = current.alternativeEvents.toList(),
            firstAttemptInputKind = current.firstKind ?: qualification(current),
            displayedCandidateSnapshot = current.displayedSnapshot,
            calibrationConfirmed = current.commits == 1 && current.nativeCommits <= 1 && current.committed.toString() == final &&
                current.unsupported.all { it == "non_typing_action" } &&
                current.selectedRaw == current.prompt.pinyin && final == current.prompt.text,
            probeQueries = current.probeQueries.toList(), firstAttemptOmittedLetterCount = current.firstOmittedLetters,
            rawAttemptInputKind = current.rawKind ?: qualification(current),
            predictionCommits = current.predictions.commits,
            predictionEvents = current.predictions.events,
            predictionQueries = current.predictions.queries,
            predictionOmittedRecordCount = current.predictions.omittedRecordCount)
        inputs.add(input)
        trialMetadata.add(JSONObject().put("settings", current.settings)
            .put("session_exclusions", JSONArray(current.unsupported.toList()))
            .put("queue_wait_ns", JSONArray(current.queueNanos))
            .put("engine_processing_ns", JSONArray(current.engineNanos))
            .put("test_snapshot_ns", JSONArray(current.snapshotNanos))
            .put("key_timeline", JSONArray().apply {
                current.keyTimeline.forEach { key -> put(JSONObject()
                    .put("key_ordinal", key.keyOrdinal).put("letter", key.letter?.toString() ?: JSONObject.NULL)
                    .put("backspace", key.backspace).put("contact_id", key.contactId ?: JSONObject.NULL)
                    .put("enqueue_monotonic_ns", key.enqueueNanos)
                    .put("started_monotonic_ns", key.startNanos ?: JSONObject.NULL)
                    .put("finished_monotonic_ns", key.finishedNanos)
                    .put("turnaround_ns", key.finishedNanos - key.enqueueNanos)
                    .put("queue_wait_ns", key.startNanos?.let { it - key.enqueueNanos } ?: JSONObject.NULL)) }
            })
            .put("stage_omitted_counts", JSONObject().apply {
                current.omittedStageSamples.forEach { (stage, count) -> put(stage.name, count) }
            })
            .put("alternative_event_omitted_count", current.omittedAlternativeEvents)
            .put("probe_query_omitted_count", current.omittedProbeQueries))
        val receipt = MessageDigest.getInstance("SHA-256")
            .digest("$sessionReceipt:${inputs.lastIndex}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val evaluated = TypingTestMetrics.evaluate(input)
        val result = evaluated.copy(calibrationSamples = evaluated.calibrationSamples.map {
            it.copy(confirmationId = receipt)
        })
        val results = mutableState.value.results + result
        active = false
        generation++
        trial = null
        mutableState.value = mutableState.value.copy(phase = TypingTestPhase.Completed,
            completed = results.size, results = results, failure = null,
            lastCommittedText = final, reportSummary = summary(results), reportWarning = queryWarning(results))
        saveReport(false)
    }

    fun nextPhrase() = synchronized(lock) {
        val old = mutableState.value
        if (old.phase != TypingTestPhase.Completed) return
        if (old.completed >= prompts.size) {
            mutableState.value = old.copy(phase = TypingTestPhase.Report, prompt = null,
                reportSummary = summary(old.results), reportWarning = queryWarning(old.results))
            return
        }
        generation++
        pending = 0
        active = false
        trial = Trial(prompts[old.completed], currentSettings())
        mutableState.value = old.copy(phase = TypingTestPhase.Typing, index = old.completed,
            prompt = prompts[old.completed], failure = null, lastCommittedText = null)
    }

    fun abort() = synchronized(lock) {
        active = false
        generation++
        pending = 0
        trial = null
        val old = mutableState.value
        if (old.results.isNotEmpty()) {
            saveReport(true)
            mutableState.value = mutableState.value.copy(phase = TypingTestPhase.Report,
                prompt = null, reportSummary = summary(old.results), reportWarning = queryWarning(old.results), failure = null)
        } else mutableState.value = TypingTestState(reportAvailable = reportJson != null)
    }

    fun exportReport(): String? = synchronized(lock) { reportJson }

    fun returnToIntro() = synchronized(lock) {
        active = false
        invalidatePending("returned_to_intro")
        trial = null
        mutableState.value = TypingTestState(reportAvailable = reportJson != null)
    }

    suspend fun clearReports() {
        val app: Context?
        synchronized(lock) {
            reportRevision++
            reportJson = null
            mutableState.value = mutableState.value.copy(reportAvailable = false)
            app = context
        }
        if (app != null) withContext(Dispatchers.IO) {
            storageMutex.withLock { TypingTestReportStore.clear(app) }
        }
    }

    private fun currentSettings(): JSONObject {
        val prefs = AppPrefs.getInstance().keyboard
        return JSONObject().put("down_order", prefs.pinyinDownOrder.getValue())
            .put("touch_alternatives", prefs.pinyinTouchAlternatives.getValue())
            .put("inline_correction", prefs.pinyinTouchCorrection.getValue())
            .put("boundary_settling", prefs.touchBoundarySettling.getValue())
            .put("personalization", prefs.pinyinTouchPersonalization.getValue())
            .put("candidate_promotion", prefs.pinyinTouchPromotion.getValue())
            .put("next_word_prediction", prefs.localNextWordPrediction.getValue())
    }

    /** Prescribed, explicitly completed labels only. Reading never trains or applies a profile. */
    fun confirmedCalibrationSamples(): List<TypingTestCalibrationSample> = synchronized(lock) {
        mutableState.value.results.flatMap { it.calibrationSamples }.toList()
    }

    private fun queryWarning(results: List<TypingTestTrialResult>): String? {
        val reasons = results.flatMap { it.alternativeMetrics.rejectionReasons.entries }
        val unavailable = reasons.filter { it.key.startsWith("ProbeUnavailable") }.sumOf { it.value }
        val timeouts = reasons.filter { it.key == "ProbeOverBudget" || it.key == "ProbeTimeout" ||
            it.key == "ProbeColdInitializationOverBudget" }.sumOf { it.value }
        val touchWarning = when {
            unavailable > 0 -> "触点纠错查询不可用 $unavailable 次；这些请求没有产生建议，请先查看触点纠错自检。" +
                if (timeouts > 0) "另有 $timeouts 次查询超时。" else ""
            timeouts > 0 -> "触点纠错查询超时 $timeouts 次；对应建议已丢弃，不能视作纠错成功。"
            else -> null
        }
        val predictionWarning = context?.let { TypingTestPredictionReport.warning(it,
            TypingTestMetrics.summarize(results).predictionMetrics) }
        return listOfNotNull(touchWarning, predictionWarning).takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun summary(results: List<TypingTestTrialResult>): String {
        fun fraction(selector: (TypingTestTrialResult) -> TypingTestFraction): String {
            val values = results.map(selector)
            val n = values.sumOf { it.numerator }
            val d = values.sumOf { it.denominator }
            return if (d == 0) "--（无可评分样本）" else
                String.format(Locale.ROOT, "%.1f%%（%d/%d）", n * 100.0 / d, n, d)
        }
        fun p95(samples: List<Long>): String {
            val times = samples.filter { it >= 0 }.sorted()
            return if (times.isEmpty()) "--（无样本）" else String.format(Locale.ROOT,
                "%.2f ms（%d 次）", times[(ceil(times.size * .95).toInt() - 1)
                    .coerceIn(0, times.lastIndex)] / 1_000_000.0, times.size)
        }
        val aggregate = TypingTestMetrics.summarize(results)
        val completeCoverage = results.count { it.rawEditRate.denominator > 0 }
        val originalCoverage = results.count { it.top1HitRate.denominator > 0 }
        val visibleCoverage = results.count { it.displayedTop1HitRate.denominator > 0 }
        val prefixCoverage = results.count { it.earlyPrefixMismatchRate.denominator > 0 }
        val exclusions = results.flatMap { result -> result.unscoredReasons }
            .groupingBy { it }.eachCount()
        val reasonLabels = mapOf("unsupported_target" to "目标拼音不支持",
            "unsupported_input_kind" to "未确认全拼输入",
            "unsupported_raw_attempt_window" to "首轮原始拼音窗口不支持", "incomplete_first_attempt" to "首轮提前停止（只记前缀）",
            "unsupported_first_attempt" to "首轮拼音格式不支持",
            "first_attempt_capture_limit" to "首轮超过记录长度上限",
            "first_attempt_exceeds_alignment_budget" to "错位过多，无法安全对齐触点",
            "ambiguous_alignment" to "触点对齐存在多解",
            "no_coherent_full_phrase_candidate_snapshot" to "缺少完整一致的原候选快照",
            "no_visible_full_phrase_candidate_snapshot" to "缺少完整首轮可见候选快照",
            "no_confirmed_prompted_touch_alignment" to "无法确认校准标签",
            "invalid_key_counts" to "按键计数无效",
            "final_input_kind_changed_after_first_attempt" to "后续输入方式改变（首轮仍保留）")
        val exclusionText = exclusions.entries.joinToString(" · ") { (reason, count) ->
            "${reasonLabels[reason] ?: "其他记录限制"}：$count 句"
        }
        val repairMean = aggregate.backspacesPerRepairTrial.value?.let {
            String.format(Locale.ROOT, "%.1f 次（%d 次退格 / %d 条发生退格的句子）", it,
                aggregate.backspacesPerRepairTrial.numerator, aggregate.backspacesPerRepairTrial.denominator)
        } ?: "--（本轮无退格句子）"
        val alternatives = results.map { it.alternativeMetrics }
        val limited = trialMetadata.any { metadata ->
            metadata.optInt("alternative_event_omitted_count", 0) > 0 ||
                metadata.optInt("probe_query_omitted_count", 0) > 0 ||
                metadata.optJSONObject("stage_omitted_counts")?.length()?.let { it > 0 } == true
        }
        val counts = "生成 ${alternatives.sumOf { it.generatedCount }} · " +
            "提供 ${alternatives.sumOf { it.publishedCount }} · " +
            "显示 ${alternatives.sumOf { it.displayedCount }}\n" +
            "拒绝 ${alternatives.sumOf { it.rejectedCount }} · " +
            "选中 ${alternatives.sumOf { it.selectedCount }} · " +
            "执行成功 ${alternatives.sumOf { it.resolvedSuccessCount }}"
        return "完成 ${results.size} 句 · 最终文字正确 ${results.count { it.targetCompleted }} 句\n\n" +
            (queryWarning(results)?.let { "注意：$it\n\n" } ?: "") +
            "首轮原始拼音覆盖：$completeCoverage/${results.size} 句；原 Rime 快照覆盖：$originalCoverage/${results.size} 句\n" +
            "实际可见候选覆盖：$visibleCoverage/${results.size} 句；早退格前缀覆盖：$prefixCoverage/${results.size} 句\n" +
            "原 Rime 第一候选命中：${fraction { it.top1HitRate }}\n" +
            "原 Rime 前三候选命中：${fraction { it.top3HitRate }}\n" +
            "首次可见第一候选命中：${fraction { it.displayedTop1HitRate }}\n" +
            "首次可见前三候选命中：${fraction { it.displayedTop3HitRate }}\n" +
            "首轮拼音编辑率：${fraction { it.rawEditRate }}\n" +
            "首轮邻键替换率：${fraction { it.adjacentSubstitutionRate }}\n" +
            "早退格已输入前缀错位率：${fraction { it.earlyPrefixMismatchRate }}\n" +
            "早退格前缀邻键错位率：${fraction { it.earlyPrefixAdjacentRate }}\n" +
            "退格 / 字母按键：${fraction { it.backspaceRate }}\n" +
            "每条发生退格句子的平均退格：$repairMean\n" +
            "\n* 建议：$counts\n" +
            "* 全句建议命中：${fraction { it.alternativeMetrics.publishedHitRate }}\n" +
            "* 选择执行成功：${fraction { it.alternativeMetrics.selectionSuccessRate }}\n" +
            "* 已选建议匹配目标：${fraction { it.alternativeMetrics.targetSelectionRate }}\n" +
            "\n按键入队到处理完成 P95：${p95(inputs.flatMap { it.processingNanos })}\n" +
            "原引擎按键调用 P95：${p95(inputs.flatMap { it.stageTimings.sendKeyNanos })}\n" +
            "触点备选搜索 P95：${p95(inputs.flatMap { it.stageTimings.touchSearchNanos })}\n" +
            "备选查询总耗时 P95：${p95(inputs.flatMap { it.stageTimings.alternativeQueryNanos })}\n" +
            "查询库加载 P95：${p95(inputs.flatMap { it.stageTimings.probeLibraryLoadNanos })}\n" +
            "查询引擎初始化 P95：${p95(inputs.flatMap { it.stageTimings.probeInitializationNanos })}\n" +
            "实际原生查词 P95：${p95(inputs.flatMap { it.stageTimings.probeNativeQueryNanos })}\n\n" +
            "按键入队到建议提供 P95：${p95(inputs.flatMap { it.stageTimings.offerReadyNanos })}\n\n" +
            "原 Rime 指标取首次达到目标字母数时的快照；原始首轮拼音另采集到首次退格、上屏或停止。* 建议独立统计。\n" +
            "提供表示已发布到候选数据流；显示需可见 UI 回调。执行成功不等于目标正确。\n" +
            "各段时延分别计样，不相加；按键完成不等待独立后台搜索。\n" +
            "建议提供时延仅取成功提供的建议，含等待与排队；它与分段耗时重叠，不是绘帧时间。\n" +
            "排队与快照耗时另存报告，不含手指按住和屏幕绘制。\n" +
            (if (limited) "部分记录达到上限，以上统计仅使用保留样本。\n" else "") +
            "首轮资格在输入时冻结；后续删改不抹掉当时的失败。早退格前缀只比较已输入位置，未知后缀不计漏字。\n" +
            "不可评分原因（各指标可重叠）：$exclusionText\n" +
            "简拼、分段上屏、外部输入和无法确认的片段不参与对应评分。\n" +
            "练习不会自动训练；只有点击应用校准才导入已确认样本。" +
            (context?.let { "\n\n" + TypingTestPredictionReport.summary(it, aggregate.predictionMetrics) } ?: "")
    }

    private fun saveReport(aborted: Boolean) {
        val app = context ?: return
        val probe = RimeTouchProbe.diagnostics()
        val root = JSONObject().put("format", "axiang-typing-test-v2")
            .put("version", BuildConfig.VERSION_NAME).put("source_commit", BuildConfig.BUILD_GIT_HASH)
            .put("started_at", startedAt).put("finished_at", System.currentTimeMillis())
            .put("requested_trials", prompts.size).put("completed_trials", inputs.size)
            .put("complete_test", inputs.size == prompts.size)
            .put("discarded_on_view_recreation", discardedTrials)
            .put("aborted", aborted).put("automatic_touch_training", false)
            .put("measurement", "enqueue_to_key_action_completion_ns")
            .put("key_timeline_clock", "System.nanoTime_ns")
            .put("touch_event_clock", "MotionEvent_uptime_ms")
            .put("candidate_measurement", "original_rime_first_target_length_observation_top3")
            .put("first_attempt_qualification", "frozen_at_first_target_length_observation_or_early_stop")
            .put("raw_first_attempt_window", "letters_until_first_backspace_commit_or_explicit_stop_max64")
            .put("displayed_candidate_measurement", "first_visible_layout_same_first_complete_composition_top3")
            .put("early_prefix_measurement", "position_mismatch_on_observed_prefix_only")
            .put("query_warning", queryWarning(mutableState.value.results) ?: JSONObject.NULL)
            .put("alternative_measurement", "explicit_touch_offer_lifecycle_full_prompt_target")
            .put("alternative_display_measurement", "displayed_requires_visible_ui_callback")
            .put("prediction_measurement", "test_editor_only_successful_commit_bound_offer_lifecycle")
            .put("prediction_draw_measurement", "actual_commit_success_to_first_full_body_dispatch_draw_ns_not_hardware_frame")
            .put("prediction_savings_measurement", "estimated_prescribed_pinyin_letter_keys_not_actual_total_taps")
            .put("prediction_target_metadata", "evaluation_only_never_passed_to_predictor_or_learning")
            .put("prediction_summary", predictionMetricsJson(
                TypingTestMetrics.summarize(mutableState.value.results).predictionMetrics))
            .put("stage_measurements", JSONObject()
                .put("SendKey", "native_send_key_call_ns")
                .put("TouchSearch", "touch_alternative_search_ns")
                .put("AlternativeQuery", "native_read_only_rime_probe_total_ns")
                .put("ProbeLibraryLoad", "probe_library_load_ns")
                .put("ProbeInitialization", "probe_initialization_ns")
                .put("ProbeNativeQuery", "probe_native_query_ns"))
            .put("offer_ready_measurement", "enqueue_to_offer_publish_ns")
            .put("touch_probe_runtime", JSONObject()
                .put("measurement", "cached_bridge_status_at_report_save")
                .put("counter_scope", "process_lifetime_calls_including_self_check_and_cache")
                .put("expected_version", RimeTouchProbeStatus.EXPECTED_VERSION)
                .put("library_available", probe.libraryAvailable ?: JSONObject.NULL)
                .put("status", probe.runtimeStatus?.code ?: JSONObject.NULL)
                .put("runtime_version", probe.runtimeStatus?.runtimeVersion ?: JSONObject.NULL)
                .put("last_failure", probe.lastFailure ?: JSONObject.NULL)
                .put("last_outcome", probe.lastOutcome ?: JSONObject.NULL)
                .put("last_query_elapsed_ns", probe.lastQueryElapsedNanos ?: JSONObject.NULL)
                .put("last_library_load_ns", probe.lastLibraryLoadNanos ?: JSONObject.NULL)
                .put("last_initialization_ns", probe.lastInitializationNanos ?: JSONObject.NULL)
                .put("last_native_query_ns", probe.lastNativeQueryNanos ?: JSONObject.NULL)
                .put("last_query_monotonic_ns", probe.lastQueryMonotonicNanos ?: JSONObject.NULL)
                .put("last_cache_hit", probe.lastCacheHit ?: JSONObject.NULL)
                .put("query_count", probe.queryCount).put("success_count", probe.successCount)
                .put("no_candidate_count", probe.noCandidateCount).put("timeout_count", probe.timeoutCount)
                .put("unavailable_count", probe.unavailableCount).put("cache_hit_count", probe.cacheHitCount)
                .put("native_query_count", probe.nativeQueryCount)
                .put("last_success_monotonic_ns", probe.lastSuccessMonotonicNanos ?: JSONObject.NULL)
                .put("ready_handle", probe.readyHandle))
            .put("summary", summary(mutableState.value.results))
        val layouts = linkedMapOf<String, JSONObject>()
        fun layoutOf(touch: TypingTestTouch): String {
            val cells = JSONArray().apply {
                touch.cells.take(26).forEach { cell -> put(JSONObject()
                    .put("key", cell.letter.toString()).put("left", finite(cell.left))
                    .put("top", finite(cell.top)).put("right", finite(cell.right))
                    .put("bottom", finite(cell.bottom))) }
            }
            val definition = JSONObject().put("cells", cells).put("density", finite(touch.density))
                .put("orientation", touch.orientation).put("profile_layout_signature",
                    PinyinTouchProfile.layoutSignature(touch.cells, touch.density) ?: JSONObject.NULL)
            val signature = MessageDigest.getInstance("SHA-256")
                .digest(definition.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            layouts.putIfAbsent(signature, definition)
            return signature
        }
        val rows = JSONArray()
        inputs.forEachIndexed { i, input ->
            val result = mutableState.value.results[i]
            val row = JSONObject().put("prompt_id", input.prompt.id).put("target", input.prompt.text)
                .put("target_pinyin", input.prompt.pinyin)
                .put("target_pinyin_syllables", JSONArray(input.prompt.pinyinSyllables))
                .put("input_kind", input.inputKind.name)
                .put("first_attempt_pinyin", input.firstAttemptPinyin ?: JSONObject.NULL)
                .put("first_attempt_complete", input.firstAttemptComplete)
                .put("first_attempt_omitted_letters", input.firstAttemptOmittedLetterCount)
                .put("first_attempt_input_kind", input.firstAttemptInputKind?.name ?: JSONObject.NULL)
                .put("raw_attempt_input_kind", input.rawAttemptInputKind?.name ?: JSONObject.NULL)
                .put("calibration_confirmed", input.calibrationConfirmed ?: JSONObject.NULL)
                .put("final_pinyin", input.finalInputPinyin ?: JSONObject.NULL)
                .put("initial_candidate_pinyin", input.candidateInputPinyin ?: JSONObject.NULL)
                .put("committed_text", input.committedText).put("target_completed", result.targetCompleted)
                .put("letters", input.letterKeyCount).put("backspaces", input.backspaceCount)
                .put("first_attempt_touches", JSONArray().apply {
                    input.firstAttemptTouches.forEach { touch -> put(JSONObject()
                        .put("original", touch.originalKey.toString()).put("down_x", finite(touch.downX))
                        .put("down_y", finite(touch.downY)).put("density", finite(touch.density))
                        .put("orientation", touch.orientation).put("hand", touch.hand)
                        .put("layout", layoutOf(touch)).put("contact_id", touch.contactId ?: JSONObject.NULL)
                        .put("pointer_id", touch.pointerId ?: JSONObject.NULL)
                        .put("down_time_ms", touch.downTime ?: JSONObject.NULL)
                        .put("down_sequence", touch.downSequence ?: JSONObject.NULL)
                        .put("dispatch_sequence", touch.dispatchSequence ?: JSONObject.NULL)
                        .put("dispatch_time_ms", touch.dispatchTime ?: JSONObject.NULL)
                        .put("physical_up_time_ms", touch.physicalUpTime ?: JSONObject.NULL)
                        .put("physical_up_x", touch.physicalUpX?.let(::finite) ?: JSONObject.NULL)
                        .put("physical_up_y", touch.physicalUpY?.let(::finite) ?: JSONObject.NULL)) }
                }).put("unscored_reasons", JSONArray(result.unscoredReasons))
                .put("candidate_snapshot", input.finalCandidateSnapshot?.let {
                    JSONObject().put("raw", it.rawPinyin).put("candidates", JSONArray(it.candidates))
                        .put("complete_prompt", it.completePromptComposition).put("coherent", it.coherent)
                        .put("input_kind_at_capture", it.inputKindAtCapture?.name ?: JSONObject.NULL)
                        .put("prior_commit_count_at_capture", it.priorCommitCountAtCapture ?: JSONObject.NULL)
                } ?: JSONObject.NULL)
                .put("displayed_candidate_snapshot", input.displayedCandidateSnapshot?.let {
                    JSONObject().put("raw", it.rawPinyin).put("candidates", JSONArray(it.candidates))
                        .put("complete_prompt", it.completePromptComposition).put("coherent", it.coherent)
                        .put("input_kind_at_capture", it.inputKindAtCapture?.name ?: JSONObject.NULL)
                        .put("prior_commit_count_at_capture", it.priorCommitCountAtCapture ?: JSONObject.NULL)
                } ?: JSONObject.NULL)
                .put("repair_burden", JSONObject().put("backspaces", result.backspaceCount)
                    .put("had_backspace", result.backspaceCount > 0)
                    .put("measurement", "backspaces_per_completed_trial_not_per_error"))
                .put("turnaround_ns", JSONArray(input.processingNanos))
                .put("send_key_ns", JSONArray(input.stageTimings.sendKeyNanos))
                .put("touch_search_ns", JSONArray(input.stageTimings.touchSearchNanos))
                .put("alternative_query_ns", JSONArray(input.stageTimings.alternativeQueryNanos))
                .put("probe_library_load_ns", JSONArray(input.stageTimings.probeLibraryLoadNanos))
                .put("probe_initialization_ns", JSONArray(input.stageTimings.probeInitializationNanos))
                .put("probe_native_query_ns", JSONArray(input.stageTimings.probeNativeQueryNanos))
                .put("enqueue_to_offer_publish_ns", JSONArray(input.stageTimings.offerReadyNanos))
                .put("probe_queries", JSONArray().apply {
                    input.probeQueries.forEach { query -> put(JSONObject()
                        .put("offer_token", query.offerToken).put("elapsed_ns", query.elapsedNanos)
                        .put("library_load_ns", query.libraryLoadNanos)
                        .put("initialization_ns", query.initializationNanos)
                        .put("native_query_ns", query.nativeQueryNanos).put("cache_hit", query.cacheHit)
                        .put("cold_initialization", query.coldInitialization).put("available", query.available)
                        .put("within_budget", query.withinBudget).put("native_within_budget", query.nativeWithinBudget)
                        .put("failure_reason", query.failureReason ?: JSONObject.NULL)) }
                })
                .put("prediction_commits", JSONArray().apply {
                    input.predictionCommits.forEach { commit -> put(JSONObject()
                        .put("commit_token", commit.commitToken).put("success", commit.success)
                        .put("committed_monotonic_ns", commit.committedAtNanos)
                        .put("scope_reliable", commit.scopeReliable)) }
                })
                .put("prediction_events", JSONArray().apply {
                    input.predictionEvents.forEach { event -> put(JSONObject()
                        .put("commit_token", event.commitToken).put("offer_token", event.offerToken)
                        .put("kind", event.kind.name).put("warmth", event.warmth.name)
                        .put("visible_indices", JSONArray(event.visibleIndices))
                        .put("selected_index", event.selectedIndex ?: JSONObject.NULL)
                        .put("success", event.success ?: JSONObject.NULL)
                        .put("commit_to_draw_ns", event.commitToDrawNanos ?: JSONObject.NULL)
                        .put("target_matched", event.targetMatched ?: JSONObject.NULL)
                        .put("estimated_pinyin_letter_keys", event.estimatedPinyinLetterKeys ?: JSONObject.NULL)
                        .put("unscored_reason", event.unscoredReason ?: JSONObject.NULL)) }
                })
                .put("prediction_queries", predictionQueriesJson(input.predictionQueries))
                .put("prediction_omitted_record_count", input.predictionOmittedRecordCount)
                .put("prediction_metrics", predictionMetricsJson(result.predictionMetrics))
                .put("alternative_events", JSONArray().apply {
                    input.alternativeEvents.forEach { event -> put(JSONObject()
                        .put("offer_token", event.offerToken).put("kind", event.kind.name)
                        .put("original_pinyin", event.originalPinyin ?: JSONObject.NULL)
                        .put("alternative_pinyin", event.alternativePinyin ?: JSONObject.NULL)
                        .put("candidate_text", event.candidateText ?: JSONObject.NULL)
                        .put("reason", event.reason ?: JSONObject.NULL)
                        .put("success", event.success ?: JSONObject.NULL)
                        .put("full_prompt_composition", event.fullPromptComposition)
                        .put("original_rank_zero_based", event.originalRank ?: JSONObject.NULL)
                        .put("search_path_count", event.searchPathCount ?: JSONObject.NULL)
                        .put("input_kind_at_capture", event.inputKindAtCapture?.name ?: JSONObject.NULL)) }
                })
                .put("calibration_labels", JSONArray().apply {
                    result.calibrationSamples.forEach { sample -> put(JSONObject()
                        .put("index", sample.inputIndex).put("original", sample.originalKey.toString())
                        .put("intended", sample.intendedKey.toString()).put("layout", sample.layoutSignature)
                        .put("orientation", sample.orientation).put("hand", sample.hand)
                        .put("offset_x", sample.normalizedOffsetX).put("offset_y", sample.normalizedOffsetY)
                        .put("confirmation_id", sample.confirmationId ?: JSONObject.NULL)) }
                })
            val metadata = trialMetadata[i]
            metadata.keys().forEach { key -> row.put(key, metadata.get(key)) }
            fun metric(key: String, value: TypingTestFraction) {
                row.put(key, JSONObject().put("numerator", value.numerator)
                    .put("denominator", value.denominator).put("rate", value.value ?: JSONObject.NULL))
            }
            metric("raw_edit_rate", result.rawEditRate)
            metric("adjacent_substitution_rate", result.adjacentSubstitutionRate)
            metric("top1_hit_rate", result.top1HitRate)
            metric("top3_hit_rate", result.top3HitRate)
            metric("backspace_rate", result.backspaceRate)
            metric("early_prefix_mismatch_rate", result.earlyPrefixMismatchRate)
            metric("early_prefix_adjacent_rate", result.earlyPrefixAdjacentRate)
            metric("displayed_top1_hit_rate", result.displayedTop1HitRate)
            metric("displayed_top3_hit_rate", result.displayedTop3HitRate)
            val alternative = result.alternativeMetrics
            row.put("alternative_counts", JSONObject().put("generated", alternative.generatedCount)
                .put("rejected", alternative.rejectedCount).put("published", alternative.publishedCount)
                .put("displayed", alternative.displayedCount).put("selected", alternative.selectedCount)
                .put("resolved", alternative.resolvedCount).put("resolved_success", alternative.resolvedSuccessCount)
                .put("rejection_reasons", JSONObject(alternative.rejectionReasons)))
            metric("alternative_published_hit_rate", alternative.publishedHitRate)
            metric("alternative_selection_success_rate", alternative.selectionSuccessRate)
            metric("alternative_target_selection_rate", alternative.targetSelectionRate)
            row.put("stage_latencies", JSONObject().apply {
                fun addStage(name: String, latency: TypingTestLatency) {
                    put(name, JSONObject().put("samples", latency.sampleCount)
                        .put("p50_ns", latency.p50Nanos ?: JSONObject.NULL)
                        .put("p95_ns", latency.p95Nanos ?: JSONObject.NULL)
                        .put("maximum_ns", latency.maximumNanos ?: JSONObject.NULL)
                        .put("invalid_samples", latency.invalidSampleCount)
                        .put("omitted_samples", latency.omittedSampleCount)
                        .put("measurement", latency.measurement))
                }
                addStage("send_key", result.stageLatencies.sendKey)
                addStage("touch_search", result.stageLatencies.touchSearch)
                addStage("alternative_query", result.stageLatencies.alternativeQuery)
                addStage("offer_ready", result.stageLatencies.offerReady)
                addStage("probe_library_load", result.stageLatencies.probeLibraryLoad)
                addStage("probe_initialization", result.stageLatencies.probeInitialization)
                addStage("probe_native_query", result.stageLatencies.probeNativeQuery)
            })
            rows.put(row)
        }
        root.put("trials", rows)
        root.put("layouts", JSONObject().apply { layouts.forEach { (key, value) -> put(key, value) } })
        val json = root.toString(2)
        val reportId = startedAt
        reportJson = json
        reportRevision++
        val revision = reportRevision
        mutableState.value = mutableState.value.copy(reportAvailable = true)
        scope.launch {
            storageMutex.withLock {
                if (synchronized(lock) { revision != reportRevision }) return@launch
                runCatching { TypingTestReportStore.write(app, reportId, json) }
                    .onFailure { synchronized(lock) {
                        if (revision == reportRevision) mutableState.value = mutableState.value.copy(
                            failure = "测试报告保存失败，可先导出当前报告")
                    } }
            }
        }
    }

    private fun predictionQueriesJson(queries: List<TypingTestPredictionQuery>) = JSONArray().apply {
        queries.forEach { query -> put(JSONObject().put("commit_token", query.commitToken)
            .put("outcome", query.outcome).put("warmth", query.warmth.name)
            .put("available", query.available ?: JSONObject.NULL)
            .put("elapsed_ns", query.elapsedNanos ?: JSONObject.NULL)
            .put("initialization_ns", query.initializationNanos ?: JSONObject.NULL)
            .put("native_query_ns", query.queryNanos ?: JSONObject.NULL)) }
    }

    private fun predictionMetricsJson(metrics: TypingTestPredictionMetrics) = JSONObject().apply {
        put("successful_commits", metrics.successfulCommitCount).put("failed_commits", metrics.failedCommitCount)
        put("published", metrics.publishedCount).put("drawn", metrics.drawnCount)
        put("selected", metrics.selectedCount).put("adopted", metrics.adoptedCount)
        fun fraction(key: String, value: TypingTestFraction) {
            put(key, JSONObject().put("numerator", value.numerator).put("denominator", value.denominator)
                .put("rate", value.value ?: JSONObject.NULL))
        }
        fraction("commit_draw_coverage", metrics.commitDrawCoverage)
        fraction("adoption_rate", metrics.adoptionRate)
        fraction("adoption_draw_coverage", metrics.adoptionDrawCoverage)
        fraction("target_hit_rate", metrics.targetHitRate)
        fraction("target_scoring_coverage", metrics.targetScoringCoverage)
        fraction("savings_coverage", metrics.savingsCoverage)
        put("estimated_pinyin_letter_keys", if (metrics.savingsCoverage.numerator > 0)
            metrics.estimatedPinyinLetterKeys else JSONObject.NULL)
        put("unscored_reasons", JSONObject(metrics.unscoredReasons))
        put("query_outcomes", JSONObject(metrics.queryOutcomes))
        put("omitted_records", metrics.omittedRecordCount)
        fun latency(value: TypingTestLatency) = JSONObject().put("samples", value.sampleCount)
            .put("p50_ns", value.p50Nanos ?: JSONObject.NULL).put("p95_ns", value.p95Nanos ?: JSONObject.NULL)
            .put("maximum_ns", value.maximumNanos ?: JSONObject.NULL)
            .put("invalid_samples", value.invalidSampleCount).put("omitted_samples", value.omittedSampleCount)
            .put("measurement", value.measurement)
        put("commit_to_draw_latency", latency(metrics.drawLatency))
        put("commit_to_draw_latency_by_warmth", JSONObject().apply {
            metrics.drawLatencyByWarmth.forEach { (warmth, value) -> put(warmth.name, latency(value)) }
        })
        put("commit_to_draw_samples", JSONArray().apply {
            metrics.drawLatencySamples.forEach { sample -> put(JSONObject()
                .put("warmth", sample.warmth.name).put("elapsed_ns", sample.elapsedNanos)) }
        })
    }

    private fun finite(value: Float): Any = if (value.isFinite()) value.toDouble() else JSONObject.NULL
}
