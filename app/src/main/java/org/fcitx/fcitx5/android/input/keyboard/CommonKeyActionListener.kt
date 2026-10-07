/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard

import androidx.core.content.ContextCompat
import androidx.annotation.Keep
import androidx.lifecycle.lifecycleScope
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.fcitx.fcitx5.android.core.RimeTouchProbe
import org.fcitx.fcitx5.android.core.RimeTouchProbePolicy
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.FcitxEvent.InputPanelEvent
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.diagnostics.TouchDiagnosticPolicy
import org.fcitx.fcitx5.android.data.diagnostics.TouchDiagnosticStore
import org.fcitx.fcitx5.android.data.typingtest.TypingTestKeyTicket
import org.fcitx.fcitx5.android.data.typingtest.TypingTestObservationTicket
import org.fcitx.fcitx5.android.data.typingtest.TypingTestAlternativeEventKind
import org.fcitx.fcitx5.android.data.typingtest.TypingTestStage
import org.fcitx.fcitx5.android.data.typingtest.TypingTestSession
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dialog.AddMoreInputMethodsPrompt
import org.fcitx.fcitx5.android.input.dialog.InputMethodPickerDialog
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Reset
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Selection
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Stopped
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.CommitAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.DeleteSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.FcitxKeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.RestorePinyinTapAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.ConfirmPinyinTapAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SelectTouchCandidateAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.LangSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.MoveSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.PickerSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.QuickPhraseAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.ShowInputMethodPickerAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SpaceLongPressAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SymAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.UnicodeAction
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinTapRuntime
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinTapEvidence
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinSpatialKeyDecider
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinTouchModelRepository
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinMultiPathSearch
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinMultiPathTracker
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinTouchCandidateRuntime
import org.fcitx.fcitx5.android.input.keyboard.typing.resolvePinyinTouchCandidate
import org.fcitx.fcitx5.android.input.keyboard.typing.resolvePinyinTapFeedback
import org.fcitx.fcitx5.android.utils.switchToNextIME
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must

class CommonKeyActionListener :
    UniqueComponent<CommonKeyActionListener>(), Dependent, InputBroadcastReceiver,
    ManagedHandler by managedHandler() {

    enum class BackspaceSwipeState {
        Stopped, Selection, Reset
    }

    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val service by manager.inputMethodService()
    private val preeditState: PreeditEmptyStateComponent by manager.must()
    private val horizontalCandidate: HorizontalCandidateComponent by manager.must()
    private val windowManager: InputWindowManager by manager.must()

    private var lastPickerType by AppPrefs.getInstance().internal.lastPickerType

    private val kbdPrefs = AppPrefs.getInstance().keyboard

    private val spaceKeyLongPressBehavior by kbdPrefs.spaceKeyLongPressBehavior
    private val langSwitchKeyBehavior by kbdPrefs.langSwitchKeyBehavior

    private var backspaceSwipeState = Stopped

    private val pinyinTapRuntime = PinyinTapRuntime()
    private val pinyinTouchModels by lazy { PinyinTouchModelRepository(context.applicationContext) }
    val pinyinTapFeedback get() = pinyinTapRuntime.feedback
    private val touchCandidateRuntime = PinyinTouchCandidateRuntime()
    val touchCandidateOffer get() = touchCandidateRuntime.offer
    @Volatile private var touchQueryJob: Job? = null
    private data class TouchOfferObservation(val token: Long, val ticket: TypingTestObservationTicket?)
    @Volatile private var touchOfferObservation: TouchOfferObservation? = null
    @Volatile private var probeOverBudget = false
    @Volatile
    private var editorEpoch = 0L

    fun clearPinyinTapFeedback() {
        pinyinTapRuntime.nextAction()
        invalidateTouchCandidates()
    }

    /** UI calls this before selecting any ordinary candidate or changing composition. */
    fun invalidateTouchCandidates() {
        touchQueryJob?.cancel()
        touchQueryJob = null
        touchOfferObservation = null
        touchCandidateRuntime.clear()
        probeOverBudget = false
    }

    private fun nextTouchAction(): Long {
        touchQueryJob?.cancel()
        touchQueryJob = null
        touchOfferObservation = null
        return touchCandidateRuntime.nextAction()
    }

    /** Called by the visible horizontal slot, never inferred from publication alone. */
    fun onTouchCandidateDisplayed(token: Long) {
        val observation = touchOfferObservation?.takeIf { it.token == token }?.ticket ?: return
        if (!touchCandidateRuntime.isCurrent(token) || touchCandidateOffer.value?.token != token) return
        // Publication is recorded inside the earlier native transaction. Enqueue
        // this callback after it so a very fast UI collector cannot overtake it.
        service.postFcitxJob {
            if (touchCandidateRuntime.isCurrent(token) && touchCandidateOffer.value?.token == token)
                TypingTestSession.recordAlternative(observation, token,
                    TypingTestAlternativeEventKind.Displayed)
        }
    }

    private fun touchFeaturesEnabled() = kbdPrefs.pinyinTouchCorrection.getValue() ||
        kbdPrefs.pinyinTouchAlternatives.getValue()

    @Keep
    private val correctionPreferenceListener = ManagedPreference.OnChangeListener<Boolean> { _, enabled ->
        editorEpoch++
        clearPinyinTapFeedback()
        if (enabled) {
            pinyinTouchModels.preload(service.lifecycleScope)
            pinyinTouchModels.refreshProfile(service.lifecycleScope)
        }
    }

    @Keep
    private val personalizationPreferenceListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        clearPinyinTapFeedback()
    }

    @Keep
    private val alternativesPreferenceListener = ManagedPreference.OnChangeListener<Boolean> { _, enabled ->
        editorEpoch++
        clearPinyinTapFeedback()
        if (enabled) pinyinTouchModels.preload(service.lifecycleScope)
        service.postFcitxJob { withInputTransaction { RimeTouchProbe.close() } }
    }

    override fun onScopeSetupFinished(scope: DynamicScope) {
        kbdPrefs.pinyinTouchCorrection.registerOnChangeListener(correctionPreferenceListener)
        kbdPrefs.pinyinTouchPersonalization.registerOnChangeListener(personalizationPreferenceListener)
        kbdPrefs.pinyinTouchAlternatives.registerOnChangeListener(alternativesPreferenceListener)
        if (touchFeaturesEnabled()) pinyinTouchModels.preload(service.lifecycleScope)
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        editorEpoch++
        clearPinyinTapFeedback()
        if (touchFeaturesEnabled()) {
            pinyinTouchModels.preload(service.lifecycleScope)
            pinyinTouchModels.refreshProfile(service.lifecycleScope)
        }
        if (!TouchDiagnosticPolicy.allows(info))
            service.postFcitxJob { withInputTransaction { RimeTouchProbe.close() } }
    }

    override fun onImeUpdate(ime: InputMethodEntry) { editorEpoch++; clearPinyinTapFeedback() }

    override fun onWindowDetached(window: InputWindow) { editorEpoch++; clearPinyinTapFeedback() }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        // An empty UI event may arrive after the next tap was already queued.
        // Always expire chips; contact history is checked against the native cache.
        if (empty) {
            pinyinTapRuntime.nextAction()
            if (touchCandidateOffer.value != null) invalidateTouchCandidates()
        }
    }

    override fun onInputPanelUpdate(data: InputPanelEvent.Data) {
        val offer = touchCandidateOffer.value ?: return
        val preedit = data.preedit
        if (touchCandidateRuntime.pending(offer.token, service.currentInputEditorInfo,
                preedit.toString(), preedit.cursor) == null) invalidateTouchCandidates()
    }

    private fun FcitxAPI.currentPinyinSpelling(): String? {
        val preedit = inputPanelCached.preedit
        return PinyinTapRuntime.spellingAtEnd(preedit.toString(), preedit.cursor)
    }

    private fun FcitxAPI.allowsPinyinTap(editor: EditorInfo?, epoch: Long): Boolean =
        kbdPrefs.pinyinTouchCorrection.getValue() && !kbdPrefs.pinyinTouchAlternatives.getValue() &&
            epoch == editorEpoch &&
            editor === service.currentInputEditorInfo && TouchDiagnosticPolicy.allows(editor) &&
            RimeActions.isPinyinSchema(inputMethodEntryCached, false)

    private fun FcitxAPI.allowsTouchAlternatives(editor: EditorInfo?, epoch: Long): Boolean =
        kbdPrefs.pinyinTouchAlternatives.getValue() && epoch == editorEpoch &&
            editor === service.currentInputEditorInfo && TouchDiagnosticPolicy.allows(editor) &&
            RimeActions.isPinyinSchema(inputMethodEntryCached, false)

    private fun recordPinyinDecision(
        evidence: PinyinTapEvidence?, selected: String,
        decision: PinyinSpatialKeyDecider.Decision? = null, bypass: String? = null
    ) {
        if (evidence?.diagnosticTraceId == null || evidence.diagnosticToken == null) return
        val store = TouchDiagnosticStore.get(service)
        if (!store.isRecording || evidence.diagnosticToken != store.recordingToken) return
        store.record("pinyin_key_decision") {
            // record() captures its own write epoch before evaluating this closure. Recheck here
            // too so editor rotation between the outer check and record cannot relabel old evidence.
            check(store.isRecording && evidence.diagnosticToken == store.recordingToken)
            put("trace_id", evidence.diagnosticTraceId)
            put("pointer_id", evidence.pointerId)
            put("down_t", store.relativeTime(evidence.downTime))
            put("original", evidence.tap.original.toString())
            put("selected", selected)
            put("reason", decision?.reason?.name ?: bypass)
            put("correction_enabled", kbdPrefs.pinyinTouchCorrection.getValue())
            decision?.let {
                put("original_probability", it.originalProbability.toDouble())
                put("selected_probability", it.selectedProbability.toDouble())
                put("alternative", it.alternative?.toString())
                put("alternative_probability", it.alternativeProbability.toDouble())
            }
        }
    }

    private suspend fun FcitxAPI.sendPinyinTap(
        action: FcitxKeyAction,
        source: KeyActionListener.Source,
        editor: EditorInfo?,
        epoch: Long,
        sequence: Long,
        touchSequence: Long,
        observation: TypingTestObservationTicket?
    ) {
        val evidence = action.pinyinTapEvidence
        if (kbdPrefs.pinyinTouchAlternatives.getValue()) {
            withInputTransaction { sendTouchAlternativeTap(action, source, editor, epoch, touchSequence, observation) }
            return
        }
        if (evidence == null || !kbdPrefs.pinyinTouchCorrection.getValue()) {
            sendMeasuredLetter(action.act, action.states.states, action.code, observation)
            recordPinyinDecision(evidence, action.act, bypass = "Disabled")
            return
        }
        withInputTransaction { sendPinyinTapInTransaction(action, source, editor, epoch, sequence, observation) }
    }

    private suspend fun FcitxAPI.sendMeasuredLetter(
        letter: String, states: UInt, code: Int, observation: TypingTestObservationTicket?
    ) {
        val started = if (observation != null) System.nanoTime() else 0L
        sendKey(letter, states, code)
        if (observation != null) TypingTestSession.recordStage(observation,
            TypingTestStage.SendKey, System.nanoTime() - started)
    }

    private suspend fun FcitxAPI.sendTouchAlternativeTap(
        action: FcitxKeyAction, source: KeyActionListener.Source, editor: EditorInfo?,
        epoch: Long, sequence: Long, observation: TypingTestObservationTicket?
    ) {
        val evidence = action.pinyinTapEvidence
        val model = pinyinTouchModels.languageModel
        val before = inputPanelCached.preedit
        if (before.isEmpty()) probeOverBudget = false
        val supported = source == KeyActionListener.Source.Keyboard && evidence != null &&
            action.act.length == 1 && action.act[0] in 'a'..'z' &&
            action.act[0] == evidence.tap.original && action.states == KeyStates.Virtual &&
            allowsTouchAlternatives(editor, epoch) && model != null
        // The literal letter reaches live Rime even when the old inline mode is enabled.
        sendMeasuredLetter(action.act, action.states.states, action.code, observation)
        recordPinyinDecision(evidence, action.act, bypass = "LiteralWithTouchAlternative")
        if (!supported || !allowsTouchAlternatives(editor, epoch)) {
            TypingTestSession.recordAlternative(observation, sequence,
                TypingTestAlternativeEventKind.Rejected,
                reason = if (model == null) "ModelNotReady" else "UnsupportedTouchContext")
            return
        }
        val after = inputPanelCached.preedit
        val offsets = if (kbdPrefs.pinyinTouchPersonalization.getValue())
            pinyinTouchModels.profileStore?.offsets(evidence!!.cells, evidence.tap.density).orEmpty()
        else emptyMap()
        val tracker = touchCandidateRuntime.tracker(model!!)
        tracker.recordTap(sequence, editor!!, before.toString(), before.cursor,
            after.toString(), after.cursor, evidence!!, offsets, searchImmediately = false)
        tracker.captureSearch(sequence)?.let { scheduleTouchSearch(it, tracker, editor, epoch, observation) }
    }

    /** Only immutable Kotlin evidence is searched off-thread; native Rime stays on its owner queue. */
    private fun scheduleTouchSearch(search: PinyinMultiPathSearch, tracker: PinyinMultiPathTracker,
                                   editor: EditorInfo, epoch: Long,
                                   observation: TypingTestObservationTicket?) {
        if (!touchCandidateRuntime.isCurrent(search.editorSequence)) return
        touchQueryJob = service.lifecycleScope.launch(Dispatchers.Default) {
            // Coalesce fast input before searching an already obsolete prefix.
            delay(35)
            if (!touchCandidateRuntime.isCurrent(search.editorSequence)) return@launch
            val workerContext = currentCoroutineContext()
            val started = if (observation != null) System.nanoTime() else 0L
            val searchResult = try {
                search.evaluate { workerContext.ensureActive() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Optional suggestions must never turn a successful literal key into a failure.
                TypingTestSession.recordAlternative(observation, search.editorSequence,
                    TypingTestAlternativeEventKind.Rejected, reason = "SearchFailed")
                return@launch
            } finally {
                if (observation != null) TypingTestSession.recordStage(observation,
                    TypingTestStage.TouchSearch, System.nanoTime() - started)
            }
            workerContext.ensureActive()
            if (!touchCandidateRuntime.isCurrent(search.editorSequence)) return@launch
            service.postFcitxJob {
                withInputTransaction {
                    val before = inputPanelCached.preedit
                    if (!allowsTouchAlternatives(editor, epoch) ||
                        !touchCandidateRuntime.isCurrent(search.editorSequence) ||
                        PinyinMultiPathTracker.spellingAtEnd(before.toString(), before.cursor) != search.originalSpelling ||
                        !tracker.acceptSearch(search, searchResult))
                        return@withInputTransaction
                    val proposal = searchResult.proposal
                    if (proposal == null) {
                        TypingTestSession.recordAlternative(observation, search.editorSequence,
                            TypingTestAlternativeEventKind.Rejected, originalPinyin = search.originalSpelling,
                            reason = searchResult.evaluation.reason.name,
                            searchPathCount = searchResult.evaluation.attemptedPaths)
                        return@withInputTransaction
                    }
                    TypingTestSession.recordAlternative(observation, proposal.editorSequence,
                        TypingTestAlternativeEventKind.Generated, proposal.originalSpelling,
                        proposal.alternativeSpelling, searchPathCount = proposal.enumeratedPathCount)
                    fun reject(reason: String) = TypingTestSession.recordAlternative(observation,
                        proposal.editorSequence, TypingTestAlternativeEventKind.Rejected,
                        proposal.originalSpelling, proposal.alternativeSpelling, reason = reason,
                        searchPathCount = proposal.enumeratedPathCount)
                    if (probeOverBudget) { reject("ProbeDisabledAfterBudget"); return@withInputTransaction }
                    if (proposal.alternativeSpelling.length > 32) {
                        reject("ProbeLengthLimit"); return@withInputTransaction
                    }
                    if (!touchCandidateRuntime.matches(proposal, editor, before.toString(), before.cursor))
                        return@withInputTransaction
                    if (!RimeTouchProbePolicy.allowsQuery(getAddonConfig("rime"))) {
                        RimeTouchProbe.close()
                        reject("ProbePolicy")
                        return@withInputTransaction
                    }
                    val result = RimeTouchProbe.query("rime_ice", proposal.alternativeSpelling)
                    TypingTestSession.recordStage(observation, TypingTestStage.AlternativeQuery, result.elapsedNanos)
                    // Native queries cannot be preempted. A slow one yields no chip and
                    // disables further probes for this composition, never queued retries.
                    if (!result.withinBudget) {
                        if (touchCandidateRuntime.isCurrent(proposal.editorSequence)) probeOverBudget = true
                        reject("ProbeOverBudget")
                        return@withInputTransaction
                    }
                    if (!result.available) { reject("ProbeUnavailable"); return@withInputTransaction }
                    if (!allowsTouchAlternatives(editor, epoch)) return@withInputTransaction
                    val existing = getCandidates(0, 24).map { it.text }
                    // Agreement with the literal first word needs no extra suggestion.
                    // A matching second/third word is still useful spatial evidence.
                    val candidate = result.candidates.firstOrNull()
                    if (candidate == null) { reject("NoFullSpanCandidate"); return@withInputTransaction }
                    if (candidate.text == existing.firstOrNull()) {
                        reject("AgreesWithOriginalFirst"); return@withInputTransaction
                    }
                    val current = inputPanelCached.preedit
                    touchOfferObservation = TouchOfferObservation(proposal.editorSequence, observation)
                    if (touchCandidateRuntime.publish(proposal, editor, candidate.text,
                            current.toString(), current.cursor)) {
                        TypingTestSession.recordAlternative(observation, proposal.editorSequence,
                            TypingTestAlternativeEventKind.Published, proposal.originalSpelling,
                            proposal.alternativeSpelling, candidate.text,
                            originalRank = existing.indexOf(candidate.text).takeIf { it >= 0 },
                            searchPathCount = proposal.enumeratedPathCount)
                        TypingTestSession.recordOfferReady(observation, proposal.editorSequence)
                    } else {
                        if (touchOfferObservation?.token == proposal.editorSequence) touchOfferObservation = null
                        reject("StaleBeforePublication")
                    }
                }
            }.join()
        }
    }

    private suspend fun FcitxAPI.sendTouchBackspace(
        action: SymAction, editor: EditorInfo?, epoch: Long, sequence: Long,
        observation: TypingTestObservationTicket?
    ) = withInputTransaction {
        val before = inputPanelCached.preedit
        sendKey(action.sym, action.states)
        val model = pinyinTouchModels.languageModel
        if (model == null || !allowsTouchAlternatives(editor, epoch)) return@withInputTransaction
        val after = inputPanelCached.preedit
        if (after.isEmpty()) { invalidateTouchCandidates(); return@withInputTransaction }
        val tracker = touchCandidateRuntime.tracker(model)
        tracker.recordBackspace(sequence, editor!!, before.toString(), before.cursor,
            after.toString(), after.cursor, searchImmediately = false)
        tracker.captureSearch(sequence)?.let { scheduleTouchSearch(it, tracker, editor, epoch, observation) }
    }

    private suspend fun FcitxAPI.selectTouchCandidate(token: Long, editor: EditorInfo?, epoch: Long) =
        withInputTransaction {
            val before = inputPanelCached.preedit
            if (!allowsTouchAlternatives(editor, epoch)) return@withInputTransaction
            val pending = touchCandidateRuntime.claim(token, editor, before.toString(), before.cursor)
                ?: return@withInputTransaction
            val observation = touchOfferObservation?.takeIf { it.token == token }?.ticket
            TypingTestSession.recordAlternative(observation, token, TypingTestAlternativeEventKind.Selected)
            val resolved = resolvePinyinTouchCandidate(pending.offer) {
                allowsTouchAlternatives(editor, epoch) && touchCandidateRuntime.isCurrent(token)
            }
            TypingTestSession.recordAlternative(observation, token,
                TypingTestAlternativeEventKind.Resolved, success = resolved)
            if (touchCandidateRuntime.isCurrent(token)) invalidateTouchCandidates()
        }

    private suspend fun FcitxAPI.sendPinyinTapInTransaction(
        action: FcitxKeyAction,
        source: KeyActionListener.Source,
        editor: EditorInfo?,
        epoch: Long,
        sequence: Long,
        observation: TypingTestObservationTicket?
    ) {
        val evidence = action.pinyinTapEvidence
        val prefix = currentPinyinSpelling()
        val model = pinyinTouchModels.decider
        val supported = source == KeyActionListener.Source.Keyboard && evidence != null &&
            action.act.length == 1 && action.act[0] in 'a'..'z' &&
            action.act[0] == evidence.tap.original && action.states == KeyStates.Virtual &&
            allowsPinyinTap(editor, epoch) && prefix != null && model != null
        if (!supported) {
            sendMeasuredLetter(action.act, action.states.states, action.code, observation)
            recordPinyinDecision(evidence, action.act, bypass = when {
                !kbdPrefs.pinyinTouchCorrection.getValue() -> "Disabled"
                !allowsPinyinTap(editor, epoch) -> "UnsupportedContext"
                prefix == null -> "ProtectedPreedit"
                model == null -> "ModelNotReady"
                else -> "UnsupportedAction"
            })
            return
        }
        val offsets = if (kbdPrefs.pinyinTouchPersonalization.getValue())
            pinyinTouchModels.profileStore?.offsets(evidence!!.cells, evidence.tap.density).orEmpty()
        else emptyMap()
        val profileGeneration = pinyinTouchModels.profileStore?.generation
        val decision = model!!.decide(evidence!!.tap, evidence.cells, prefix!!, offsets)
        val selected = if (decision.corrected) decision.selected.toString() else action.act
        val code = if (decision.corrected) ScancodeMapping.charToScancode(decision.selected) else action.code
        sendMeasuredLetter(selected, action.states.states, code, observation)
        recordPinyinDecision(evidence, selected, decision)
        if (decision.corrected && allowsPinyinTap(editor, epoch)) {
            pinyinTapRuntime.recordCorrection(sequence, editor!!, prefix,
                currentPinyinSpelling(), decision, action.states.states, action.code, evidence, profileGeneration)
        }
    }

    private suspend fun FcitxAPI.resolvePinyinFeedback(
        token: Long, restore: Boolean, editor: EditorInfo?, epoch: Long
    ) {
        val pending = resolvePinyinTapFeedback(pinyinTapRuntime, token, editor, restore) {
            allowsPinyinTap(editor, epoch)
        } ?: return
        if (kbdPrefs.pinyinTouchPersonalization.getValue()) {
            val intended = if (restore) pending.feedback.original else pending.feedback.selected
            val generation = pending.profileGeneration
            service.lifecycleScope.launch(Dispatchers.IO) {
                // Explicit choice is the only label. Ordinary continued typing never trains this store.
                if (kbdPrefs.pinyinTouchPersonalization.getValue() && generation != null &&
                    epoch == editorEpoch && editor === service.currentInputEditorInfo &&
                    TouchDiagnosticPolicy.allows(editor)) {
                    pinyinTouchModels.profileStore?.observe(
                        pending.evidence.cells, pending.evidence.tap, intended, generation)
                }
            }
        }
    }

    // there should be a new fcitx API for this
    private suspend fun FcitxAPI.commitAndReset() {
        if (inputMethodEntryCached.languageCode.startsWith("zh")) {
            // Chinese: select 1st candidate, except prediction candidates
            if (clientPreeditCached.isNotEmpty() || inputPanelCached.preedit.isNotEmpty()) {
                // preedit not empty, maybe there are candidates to select ...
                select(0)
            }
        } else {
            // Other languages: commit preedit as-is
            service.finishComposing()
        }
        reset()
    }

    private fun showInputMethodPicker() {
        fcitx.launchOnReady {
            service.lifecycleScope.launch {
                service.showDialog(InputMethodPickerDialog.build(it, service, context))
            }
        }
    }

    /** Capture only the explicitly active test field; ordinary input keeps its existing path. */
    private suspend fun FcitxAPI.withTypingTestKey(
        ticket: TypingTestKeyTicket?, editor: EditorInfo?,
        block: suspend FcitxAPI.() -> Unit
    ) {
        if (ticket == null || !TypingTestSession.isEligibleEditor(editor)) {
            block()
            return
        }
        withInputTransaction {
            TypingTestSession.startKey(ticket)
            block()
            // Read-only measurement work comes after this timestamp, and is reported separately.
            val finishedAtNanos = System.nanoTime()
            if (!TypingTestSession.isEligibleEditor(editor)) return@withInputTransaction
            val schemaSupported = RimeActions.isPinyinSchema(inputMethodEntryCached, false)
            val snapshotStarted = System.nanoTime()
            val raw = if (schemaSupported) currentPinyinSpelling()?.takeIf { it.isNotEmpty() } else null
            val candidates = if (raw != null) try {
                getCandidates(0, 3).map { it.text }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // An optional measurement must never turn a successful key into an input failure.
                TypingTestSession.markUnsupported(editor, "candidate_snapshot_failed")
                null
            } else null
            TypingTestSession.finishKey(ticket, finishedAtNanos, raw, candidates,
                schemaSupported, System.nanoTime() - snapshotStarted)
        }
    }

    val listener by lazy {
        KeyActionListener { action, source ->
            val editor = service.currentInputEditorInfo
            // An old queued action must never attach to a later test.
            val observation = TypingTestSession.beginObservation(editor)
            val typingTestTicket = when (action) {
                is FcitxKeyAction -> {
                    val letter = action.act.singleOrNull()?.takeIf { it in 'a'..'z' }
                    TypingTestSession.beginKey(editor, letter, action.pinyinTapEvidence,
                        supported = source == KeyActionListener.Source.Keyboard &&
                            letter != null && action.states == KeyStates.Virtual,
                        orientation = context.resources.configuration.orientation)
                }
                is SymAction -> if (action.sym.sym == FcitxKeyMapping.FcitxKey_BackSpace ||
                        action.sym.sym == FcitxKeyMapping.FcitxKey_space ||
                        action.sym.sym == FcitxKeyMapping.FcitxKey_Return) {
                    TypingTestSession.beginKey(editor, null, null,
                        backspace = action.sym.sym == FcitxKeyMapping.FcitxKey_BackSpace,
                        supported = source == KeyActionListener.Source.Keyboard &&
                            action.states == KeyStates.Virtual,
                        orientation = context.resources.configuration.orientation)
                } else {
                    TypingTestSession.markUnsupported(editor, "non_typing_key")
                    null
                }
                is SelectTouchCandidateAction -> null
                else -> {
                    TypingTestSession.markUnsupported(editor, "non_typing_action")
                    null
                }
            }
            val epoch = editorEpoch
            val feedbackAction = action is RestorePinyinTapAction || action is ConfirmPinyinTapAction
            val sequence = if (feedbackAction) -1L else pinyinTapRuntime.nextAction()
            val trackedLetter = action is FcitxKeyAction && source == KeyActionListener.Source.Keyboard &&
                action.pinyinTapEvidence != null && action.act.length == 1 &&
                action.act[0] in 'a'..'z' && action.act[0] == action.pinyinTapEvidence.tap.original &&
                action.states == KeyStates.Virtual
            val trackedBackspace = action is SymAction &&
                action.sym.sym == FcitxKeyMapping.FcitxKey_BackSpace && action.states == KeyStates.Virtual
            val touchSequence = when {
                action is SelectTouchCandidateAction -> -1L
                trackedLetter || trackedBackspace -> nextTouchAction()
                else -> { invalidateTouchCandidates(); -1L }
            }
            if (touchFeaturesEnabled()) pinyinTouchModels.preload(service.lifecycleScope)
            when (action) {
                is FcitxKeyAction -> service.postFcitxJob {
                    withTypingTestKey(typingTestTicket, editor) {
                        sendPinyinTap(action, source, editor, epoch, sequence, touchSequence, observation)
                    }
                }
                is SelectTouchCandidateAction -> service.postFcitxJob {
                    selectTouchCandidate(action.token, editor, epoch)
                }
                is RestorePinyinTapAction -> service.postFcitxJob {
                    resolvePinyinFeedback(action.token, true, editor, epoch)
                }
                is ConfirmPinyinTapAction -> service.postFcitxJob {
                    resolvePinyinFeedback(action.token, false, editor, epoch)
                }
                is SymAction -> service.postFcitxJob {
                    withTypingTestKey(typingTestTicket, editor) {
                        if (trackedBackspace && kbdPrefs.pinyinTouchAlternatives.getValue())
                            sendTouchBackspace(action, editor, epoch, touchSequence, observation)
                        else sendKey(action.sym, action.states)
                    }
                }
                is CommitAction -> service.postFcitxJob {
                    commitAndReset()
                    service.lifecycleScope.launch { service.commitText(action.text) }
                }
                is QuickPhraseAction -> service.postFcitxJob {
                    commitAndReset()
                    triggerQuickPhrase()
                }
                is UnicodeAction -> service.postFcitxJob {
                    commitAndReset()
                    triggerUnicode()
                }
                is LangSwitchAction -> service.postFcitxJob {
                    if (inputMethodEntryCached.uniqueName == "rime") {
                        // Use Rime's native option action without resetting or committing pinyin.
                        RimeActions.asciiToggle(statusArea())?.let { activateAction(it.id) }
                    } else when (langSwitchKeyBehavior) {
                        LangSwitchBehavior.Enumerate -> {
                            if (enabledIme().size < 2) {
                                service.lifecycleScope.launch {
                                    service.showDialog(AddMoreInputMethodsPrompt.build(context))
                                }
                            } else enumerateIme()
                        }
                        LangSwitchBehavior.ToggleActivate -> toggleIme()
                        LangSwitchBehavior.NextInputMethodApp -> service.lifecycleScope.launch {
                            service.switchToNextIME()
                        }
                    }
                }
                is ShowInputMethodPickerAction -> showInputMethodPicker()
                is MoveSelectionAction -> {
                    when (backspaceSwipeState) {
                        Stopped -> {
                            backspaceSwipeState = if (
                                preeditState.isEmpty &&
                                horizontalCandidate.adapter.total <= 0 // total is -1 on initialization
                            ) {
                                service.applySelectionOffset(action.start, action.end)
                                Selection
                            } else {
                                Reset
                            }
                        }
                        Selection -> {
                            service.applySelectionOffset(action.start, action.end)
                        }
                        Reset -> {}
                    }
                }
                is DeleteSelectionAction -> {
                    when (backspaceSwipeState) {
                        Stopped -> {}
                        Selection -> service.deleteSelection()
                        Reset -> if (action.totalCnt < 0) { // swipe left
                            service.postFcitxJob { reset() }
                        }
                    }
                    backspaceSwipeState = Stopped
                }
                is PickerSwitchAction -> {
                    // update lastSymbolType only when specified explicitly
                    val key = action.key?.also { k -> lastPickerType = k.name }
                        ?: runCatching { PickerWindow.Key.valueOf(lastPickerType) }.getOrNull()
                        ?: PickerWindow.Key.Emoji
                    ContextCompat.getMainExecutor(service).execute {
                        windowManager.attachWindow(key)
                    }
                }
                is SpaceLongPressAction -> {
                    when (spaceKeyLongPressBehavior) {
                        SpaceLongPressBehavior.None, SpaceLongPressBehavior.MoveCursor -> {}
                        SpaceLongPressBehavior.Enumerate -> service.postFcitxJob {
                            enumerateIme()
                        }
                        SpaceLongPressBehavior.ToggleActivate -> service.postFcitxJob {
                            toggleIme()
                        }
                        SpaceLongPressBehavior.ShowPicker -> showInputMethodPicker()
                    }
                }
                else -> {}
            }
        }
    }
}
