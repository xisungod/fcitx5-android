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
    val lastCommittedText: String? = null
)

/** Identity is checked again inside the queued input job; stale work cannot enter a new trial. */
class TypingTestKeyTicket internal constructor(
    internal val generation: Long,
    internal val editor: EditorInfo,
    internal val letter: Char?,
    internal val letterOrdinal: Int,
    internal val enqueueNanos: Long,
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
    private var reportRevision = 0L
    private var readyGeneration = -1L
    private val nativeReceipts = IdentityHashMap<FcitxEvent.CommitStringEvent, CommitReceipt>()
    private var discardedTrials = 0
    private data class CommitReceipt(val generation: Long, val editor: EditorInfo)
    private data class PublishedOfferReceipt(val ticket: TypingTestObservationTicket, val publishedNanos: Long)

    private class Trial(val prompt: TypingTestPrompt, val settings: JSONObject) {
        val firstLetters = StringBuilder()
        val firstTouches = mutableListOf<TypingTestTouch>()
        var firstFrozen = false
        var firstComplete = false
        var letters = 0
        var backspaces = 0
        var commits = 0
        var nativeCommits = 0
        val committed = StringBuilder()
        var latestRaw: String? = null
        var latestSnapshot: TypingTestCandidateSnapshot? = null
        var initialRaw: String? = null
        var initialSnapshot: TypingTestCandidateSnapshot? = null
        var initialCaptured = false
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
        val timedOfferTokens = mutableSetOf<Long>()
        val publishedOfferReceipts = mutableMapOf<Long, PublishedOfferReceipt>()
        val omittedStageSamples = mutableMapOf<TypingTestStage, Int>()
        val alternativeEvents = mutableListOf<TypingTestAlternativeEvent>()
        var omittedAlternativeEvents = 0
        val unsupported = linkedSetOf<String>()
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
        discardedTrials = 0
        generation++
        nativeReceipts.clear()
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
        if (pending > 0) trial?.unsupported?.add(reason)
        generation++
        pending = 0
        nativeReceipts.clear()
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
            // This duration must be bound to an actually published token, never fabricated.
            TypingTestStage.OfferReady -> return
        }
        if (samples.size < TypingTestMetrics.MAX_TIMING_SAMPLES) samples.add(elapsedNanos)
        else current.omittedStageSamples[stage] = (current.omittedStageSamples[stage] ?: 0) + 1
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
            searchPathCount?.takeIf { it >= 0 } ?: prior?.searchPathCount))
        if (event == TypingTestAlternativeEventKind.Published)
            current.publishedOfferReceipts[offerToken] = PublishedOfferReceipt(ticket!!, System.nanoTime())
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
        if (current.letters + current.backspaces >= MAX_KEYS) {
            current.unsupported.add("key_limit_reached")
            return null
        }
        if (!supported) current.unsupported.add("unsupported_key")
        if (current.settings.toString() != currentSettings().toString())
            current.unsupported.add("settings_changed_during_trial")
        if (backspace) {
            current.backspaces++
            current.firstFrozen = true
        }
        if (letter != null) {
            current.letters++
            if (letter !in 'a'..'z') current.unsupported.add("non_lowercase_letter")
            if (!current.firstFrozen && current.firstLetters.length < 64) {
                current.firstLetters.append(letter)
                if (evidence != null && evidence.tap.original == letter) {
                    current.firstTouches.add(TypingTestTouch(letter, evidence.tap.downX,
                        evidence.tap.downY, evidence.tap.density, evidence.cells.toList(),
                        when (orientation) {
                            Configuration.ORIENTATION_PORTRAIT -> "portrait"
                            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
                            else -> "unknown"
                        }))
                }
                if (current.firstLetters.length == current.prompt.pinyin.length) {
                    current.firstFrozen = true
                    current.firstComplete = true
                }
            }
        }
        pending++
        TypingTestKeyTicket(generation, info!!, letter, current.letters, System.nanoTime())
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
        if (!schemaSupported) current.unsupported.add("not_full_chinese_rime")
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
                coherent = schemaSupported) }
            if (!current.initialCaptured && current.firstComplete &&
                ticket.letter != null && ticket.letterOrdinal == current.prompt.pinyin.length) {
                current.initialCaptured = true
                current.initialRaw = raw
                current.initialSnapshot = current.latestSnapshot
            }
        }
    }

    fun markUnsupported(info: EditorInfo?, reason: String) = synchronized(lock) {
        if (eligible(info)) trial?.unsupported?.add(reason.take(64))
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
        current.firstFrozen = true
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
                current.offerReadyNanos.toList()),
            alternativeEvents = current.alternativeEvents.toList())
        inputs.add(input)
        trialMetadata.add(JSONObject().put("settings", current.settings)
            .put("session_exclusions", JSONArray(current.unsupported.toList()))
            .put("queue_wait_ns", JSONArray(current.queueNanos))
            .put("engine_processing_ns", JSONArray(current.engineNanos))
            .put("test_snapshot_ns", JSONArray(current.snapshotNanos))
            .put("stage_omitted_counts", JSONObject().apply {
                current.omittedStageSamples.forEach { (stage, count) -> put(stage.name, count) }
            })
            .put("alternative_event_omitted_count", current.omittedAlternativeEvents))
        val result = TypingTestMetrics.evaluate(input)
        val results = mutableState.value.results + result
        active = false
        generation++
        trial = null
        mutableState.value = mutableState.value.copy(phase = TypingTestPhase.Completed,
            completed = results.size, results = results, failure = null,
            lastCommittedText = final, reportSummary = summary(results))
        saveReport(false)
    }

    fun nextPhrase() = synchronized(lock) {
        val old = mutableState.value
        if (old.phase != TypingTestPhase.Completed) return
        if (old.completed >= prompts.size) {
            mutableState.value = old.copy(phase = TypingTestPhase.Report, prompt = null,
                reportSummary = summary(old.results))
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
                prompt = null, reportSummary = summary(old.results), failure = null)
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
        val alternatives = results.map { it.alternativeMetrics }
        val limited = trialMetadata.any { metadata ->
            metadata.optInt("alternative_event_omitted_count", 0) > 0 ||
                metadata.optJSONObject("stage_omitted_counts")?.length()?.let { it > 0 } == true
        }
        val counts = "生成 ${alternatives.sumOf { it.generatedCount }} · " +
            "提供 ${alternatives.sumOf { it.publishedCount }} · " +
            "显示 ${alternatives.sumOf { it.displayedCount }}\n" +
            "拒绝 ${alternatives.sumOf { it.rejectedCount }} · " +
            "选中 ${alternatives.sumOf { it.selectedCount }} · " +
            "执行成功 ${alternatives.sumOf { it.resolvedSuccessCount }}"
        return "完成 ${results.size} 句 · 最终文字正确 ${results.count { it.targetCompleted }} 句\n\n" +
            "原 Rime 第一候选命中：${fraction { it.top1HitRate }}\n" +
            "原 Rime 前三候选命中：${fraction { it.top3HitRate }}\n" +
            "首轮拼音编辑率：${fraction { it.rawEditRate }}\n" +
            "首轮邻键替换率：${fraction { it.adjacentSubstitutionRate }}\n" +
            "退格 / 字母按键：${fraction { it.backspaceRate }}\n" +
            "\n* 建议：$counts\n" +
            "* 全句建议命中：${fraction { it.alternativeMetrics.publishedHitRate }}\n" +
            "* 选择执行成功：${fraction { it.alternativeMetrics.selectionSuccessRate }}\n" +
            "* 已选建议匹配目标：${fraction { it.alternativeMetrics.targetSelectionRate }}\n" +
            "\n按键入队到处理完成 P95：${p95(inputs.flatMap { it.processingNanos })}\n" +
            "原引擎按键调用 P95：${p95(inputs.flatMap { it.stageTimings.sendKeyNanos })}\n" +
            "触点备选搜索 P95：${p95(inputs.flatMap { it.stageTimings.touchSearchNanos })}\n" +
            "只读备选查询 P95：${p95(inputs.flatMap { it.stageTimings.alternativeQueryNanos })}\n\n" +
            "按键入队到建议提供 P95：${p95(inputs.flatMap { it.stageTimings.offerReadyNanos })}\n\n" +
            "原 Rime 指标仍取首次完整输入；* 建议独立统计，不算进前三候选。\n" +
            "提供表示已发布到候选数据流；显示需可见 UI 回调。执行成功不等于目标正确。\n" +
            "各段时延分别计样，不相加；按键完成不等待独立后台搜索。\n" +
            "建议提供时延仅取成功提供的建议，含等待与排队；它与分段耗时重叠，不是绘帧时间。\n" +
            "排队与快照耗时另存报告，不含手指按住和屏幕绘制。\n" +
            (if (limited) "部分记录达到上限，以上统计仅使用保留样本。\n" else "") +
            "简拼、分段上屏、外部输入和无法确认的片段不参与对应评分。\n" +
            "本版只测量，不自动训练或调整落点模型。"
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
            .put("candidate_measurement", "original_rime_first_complete_attempt_top3")
            .put("alternative_measurement", "explicit_touch_offer_lifecycle_full_prompt_target")
            .put("alternative_display_measurement", "displayed_requires_visible_ui_callback")
            .put("stage_measurements", JSONObject()
                .put("SendKey", "native_send_key_call_ns")
                .put("TouchSearch", "touch_alternative_search_ns")
                .put("AlternativeQuery", "native_read_only_rime_probe_ns"))
            .put("offer_ready_measurement", "enqueue_to_offer_publish_ns")
            .put("touch_probe_runtime", JSONObject()
                .put("measurement", "cached_bridge_status_at_report_save")
                .put("expected_version", RimeTouchProbeStatus.EXPECTED_VERSION)
                .put("library_available", probe.libraryAvailable ?: JSONObject.NULL)
                .put("status", probe.runtimeStatus?.code ?: JSONObject.NULL)
                .put("runtime_version", probe.runtimeStatus?.runtimeVersion ?: JSONObject.NULL)
                .put("last_failure", probe.lastFailure ?: JSONObject.NULL))
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
                .put("target_pinyin", input.prompt.pinyin).put("input_kind", input.inputKind.name)
                .put("first_attempt_pinyin", input.firstAttemptPinyin ?: JSONObject.NULL)
                .put("first_attempt_complete", input.firstAttemptComplete)
                .put("final_pinyin", input.finalInputPinyin ?: JSONObject.NULL)
                .put("initial_candidate_pinyin", input.candidateInputPinyin ?: JSONObject.NULL)
                .put("committed_text", input.committedText).put("target_completed", result.targetCompleted)
                .put("letters", input.letterKeyCount).put("backspaces", input.backspaceCount)
                .put("first_attempt_touches", JSONArray().apply {
                    input.firstAttemptTouches.forEach { touch -> put(JSONObject()
                        .put("original", touch.originalKey.toString()).put("down_x", finite(touch.downX))
                        .put("down_y", finite(touch.downY)).put("density", finite(touch.density))
                        .put("orientation", touch.orientation).put("hand", touch.hand)
                        .put("layout", layoutOf(touch))) }
                }).put("unscored_reasons", JSONArray(result.unscoredReasons))
                .put("candidate_snapshot", input.finalCandidateSnapshot?.let {
                    JSONObject().put("raw", it.rawPinyin).put("candidates", JSONArray(it.candidates))
                        .put("complete_prompt", it.completePromptComposition).put("coherent", it.coherent)
                } ?: JSONObject.NULL)
                .put("turnaround_ns", JSONArray(input.processingNanos))
                .put("send_key_ns", JSONArray(input.stageTimings.sendKeyNanos))
                .put("touch_search_ns", JSONArray(input.stageTimings.touchSearchNanos))
                .put("alternative_query_ns", JSONArray(input.stageTimings.alternativeQueryNanos))
                .put("enqueue_to_offer_publish_ns", JSONArray(input.stageTimings.offerReadyNanos))
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
                        .put("search_path_count", event.searchPathCount ?: JSONObject.NULL)) }
                })
                .put("calibration_labels", JSONArray().apply {
                    result.calibrationSamples.forEach { sample -> put(JSONObject()
                        .put("index", sample.inputIndex).put("original", sample.originalKey.toString())
                        .put("intended", sample.intendedKey.toString()).put("layout", sample.layoutSignature)
                        .put("orientation", sample.orientation).put("hand", sample.hand)
                        .put("offset_x", sample.normalizedOffsetX).put("offset_y", sample.normalizedOffsetY)) }
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

    private fun finite(value: Float): Any = if (value.isFinite()) value.toDouble() else JSONObject.NULL
}
