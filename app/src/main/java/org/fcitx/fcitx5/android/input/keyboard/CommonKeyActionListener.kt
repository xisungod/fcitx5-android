/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard

import androidx.core.content.ContextCompat
import androidx.annotation.Keep
import androidx.lifecycle.lifecycleScope
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.diagnostics.TouchDiagnosticPolicy
import org.fcitx.fcitx5.android.data.diagnostics.TouchDiagnosticStore
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
    @Volatile
    private var editorEpoch = 0L

    fun clearPinyinTapFeedback() {
        pinyinTapRuntime.nextAction()
    }

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

    override fun onScopeSetupFinished(scope: DynamicScope) {
        kbdPrefs.pinyinTouchCorrection.registerOnChangeListener(correctionPreferenceListener)
        kbdPrefs.pinyinTouchPersonalization.registerOnChangeListener(personalizationPreferenceListener)
        if (kbdPrefs.pinyinTouchCorrection.getValue()) pinyinTouchModels.preload(service.lifecycleScope)
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        editorEpoch++
        clearPinyinTapFeedback()
        if (kbdPrefs.pinyinTouchCorrection.getValue()) {
            pinyinTouchModels.preload(service.lifecycleScope)
            pinyinTouchModels.refreshProfile(service.lifecycleScope)
        }
    }

    override fun onImeUpdate(ime: InputMethodEntry) { editorEpoch++; clearPinyinTapFeedback() }

    override fun onWindowDetached(window: InputWindow) { editorEpoch++; clearPinyinTapFeedback() }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        if (empty) clearPinyinTapFeedback()
    }

    private fun FcitxAPI.currentPinyinSpelling(): String? {
        val preedit = inputPanelCached.preedit
        return PinyinTapRuntime.spellingAtEnd(preedit.toString(), preedit.cursor)
    }

    private fun FcitxAPI.allowsPinyinTap(editor: EditorInfo?, epoch: Long): Boolean =
        kbdPrefs.pinyinTouchCorrection.getValue() && epoch == editorEpoch &&
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
        sequence: Long
    ) {
        val evidence = action.pinyinTapEvidence
        if (evidence == null || !kbdPrefs.pinyinTouchCorrection.getValue()) {
            sendKey(action.act, action.states.states, action.code)
            recordPinyinDecision(evidence, action.act, bypass = "Disabled")
            return
        }
        withInputTransaction { sendPinyinTapInTransaction(action, source, editor, epoch, sequence) }
    }

    private suspend fun FcitxAPI.sendPinyinTapInTransaction(
        action: FcitxKeyAction,
        source: KeyActionListener.Source,
        editor: EditorInfo?,
        epoch: Long,
        sequence: Long
    ) {
        val evidence = action.pinyinTapEvidence
        val prefix = currentPinyinSpelling()
        val model = pinyinTouchModels.decider
        val supported = source == KeyActionListener.Source.Keyboard && evidence != null &&
            action.act.length == 1 && action.act[0] in 'a'..'z' &&
            action.act[0] == evidence.tap.original && action.states == KeyStates.Virtual &&
            allowsPinyinTap(editor, epoch) && prefix != null && model != null
        if (!supported) {
            sendKey(action.act, action.states.states, action.code)
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
        sendKey(selected, action.states.states, code)
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

    val listener by lazy {
        KeyActionListener { action, source ->
            val editor = service.currentInputEditorInfo
            val epoch = editorEpoch
            val feedbackAction = action is RestorePinyinTapAction || action is ConfirmPinyinTapAction
            val sequence = if (feedbackAction) -1L else pinyinTapRuntime.nextAction()
            if (kbdPrefs.pinyinTouchCorrection.getValue()) pinyinTouchModels.preload(service.lifecycleScope)
            when (action) {
                is FcitxKeyAction -> service.postFcitxJob {
                    sendPinyinTap(action, source, editor, epoch, sequence)
                }
                is RestorePinyinTapAction -> service.postFcitxJob {
                    resolvePinyinFeedback(action.token, true, editor, epoch)
                }
                is ConfirmPinyinTapAction -> service.postFcitxJob {
                    resolvePinyinFeedback(action.token, false, editor, epoch)
                }
                is SymAction -> service.postFcitxJob {
                    sendKey(action.sym, action.states)
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
