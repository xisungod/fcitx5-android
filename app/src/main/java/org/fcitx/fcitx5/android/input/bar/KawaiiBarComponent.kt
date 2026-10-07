/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import android.graphics.Color
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import android.os.Build
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.ViewAnimator
import android.widget.inline.InlineContentView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent.CandidateListEvent
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.State.ClickToAttachWindow
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.State.ClickToDetachWindow
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.State.Hidden
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.BooleanKey.CandidateEmpty
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.BooleanKey.PreeditEmpty
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.ExtendedWindowAttached
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.PreeditUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.WindowDetached
import org.fcitx.fcitx5.android.input.bar.ui.CandidateUi
import org.fcitx.fcitx5.android.input.bar.ui.IdleUi
import org.fcitx.fcitx5.android.input.bar.ui.TitleUi
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.bar.ui.idle.KeyboardLayoutChoice
import org.fcitx.fcitx5.android.input.bar.ui.idle.keyboardLayoutMenu
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateStyle
import org.fcitx.fcitx5.android.input.candidates.expanded.window.FlexboxExpandedCandidateWindow
import org.fcitx.fcitx5.android.input.candidates.expanded.window.GridExpandedCandidateWindow
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.clipboard.ClipboardWindow
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.NumberKeyboard
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.status.StatusAreaWindow
import org.fcitx.fcitx5.android.input.voice.OfflineDictationWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.must
import splitties.bitflags.hasFlag
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import org.fcitx.fcitx5.android.data.otp.VerificationCodes
import org.fcitx.fcitx5.android.data.otp.SmsCodeStatus
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

class KawaiiBarComponent : UniqueViewComponent<KawaiiBarComponent, FrameLayout>(),
    InputBroadcastReceiver {

    private val context by manager.context()
    private val theme by manager.theme()
    private val service by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val horizontalCandidate: HorizontalCandidateComponent by manager.must()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val popup: PopupComponent by manager.must()

    private val prefs = AppPrefs.getInstance()

    private val clipboardSuggestion = prefs.clipboard.clipboardSuggestion
    private val clipboardItemTimeout = prefs.clipboard.clipboardItemTimeout
    private val clipboardMaskSensitive by prefs.clipboard.clipboardMaskSensitive
    private val expandedCandidateStyle by prefs.keyboard.expandedCandidateStyle
    private val expandToolbarByDefault by prefs.keyboard.expandToolbarByDefault
    private val toolbarNumRowOnPassword by prefs.keyboard.toolbarNumRowOnPassword

    private var clipboardTimeoutJob: Job? = null
    private var pinyinFeedbackJob: Job? = null
    private var keyboardLayoutPopup: PopupMenu? = null
    private var nextWordPredictionVisible = false
    private var nativePreeditEmpty = true
    private var nativeCandidatesEmpty = true
    private var suggestionListenersActive = false

    private var isClipboardFresh: Boolean = false
    private var isInlineSuggestionPresent: Boolean = false
    private var isCapabilityFlagsPassword: Boolean = false
    private var isKeyboardLayoutNumber: Boolean = false
    private var isToolbarManuallyToggled: Boolean = false

    private enum class NumberRowState { Auto, ForceShow, ForceHide }

    private var numberRowState = NumberRowState.Auto

    @Keep
    private val onClipboardUpdateListener =
        ClipboardManager.OnClipboardUpdateListener {
            if (!clipboardSuggestion.getValue()) return@OnClipboardUpdateListener
            service.lifecycleScope.launch {
                if (!suggestionListenersActive) return@launch
                if (isSmsCodeSuggestionActive()) return@launch
                val token = ClipboardSuggestionDismissals.Token(
                    ClipboardSuggestionDismissals.Source.Clipboard, it.timestamp, it.id)
                // Check inside the posted callback too: closing can precede a queued update.
                if (ClipboardSuggestionDismissals.shared.isDismissed(token)) return@launch
                if (it.text.isEmpty()) {
                    clearClipboardSuggestion()
                } else {
                    val code = if (codeFromClipboard.getValue()) VerificationCodes.extract(it.text) else null
                    chipToken = token
                    chipEntry = it
                    chipCode = code
                    chipFromSms = false
                    val masked = it.sensitive && clipboardMaskSensitive
                    idleUi.clipboardUi.text.text = when {
                        code != null -> context.getString(
                            R.string.verification_code_chip,
                            if (masked) ClipboardEntry.BULLET.repeat(code.length) else code
                        )
                        masked -> ClipboardEntry.BULLET.repeat(min(42, it.text.length))
                        else -> it.text.take(42)
                    }
                    isClipboardFresh = true
                    launchClipboardTimeoutJob()
                }
                evalIdleUiState()
            }
        }

    private val codeFromClipboard = prefs.clipboard.verificationCodeFromClipboard
    private val codeFromSms = prefs.clipboard.verificationCodeFromSms

    /** verification code currently offered in the clipboard chip; tapping pastes just the code */
    private var chipCode: String? = null
    private var chipFromSms = false
    private var chipEntry: ClipboardEntry? = null
    private var chipToken: ClipboardSuggestionDismissals.Token? = null

    private fun smsCodesAllowed(): Boolean = codeFromSms.getValue() &&
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECEIVE_SMS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** A fresh SMS code takes precedence over idle next-word suggestions, never composing text. */
    internal fun isSmsCodeSuggestionActive(): Boolean {
        if (!chipFromSms || !isClipboardFresh || !smsCodesAllowed()) return false
        val fresh = VerificationCodes.fresh() ?: return false
        return fresh.source == VerificationCodes.Source.Sms && fresh.code == chipCode &&
            fresh.timestamp == chipToken?.timestamp
    }

    private fun clearClipboardSuggestion() {
        val wasSms = chipFromSms
        chipCode = null
        chipFromSms = false
        chipEntry = null
        chipToken = null
        clipboardTimeoutJob?.cancel()
        clipboardTimeoutJob = null
        isClipboardFresh = false
        if (wasSms) {
            service.refreshNextWordPrediction()
            setNextWordPredictionVisible(nextWordPredictionVisible)
        }
    }

    private fun dismissClipboardSuggestion() {
        chipToken?.let(ClipboardSuggestionDismissals.shared::dismiss)
        clearClipboardSuggestion()
        evalIdleUiState()
    }

    private fun showSmsCode(code: VerificationCodes.Code) {
        if (!smsCodesAllowed() || code.source != VerificationCodes.Source.Sms ||
            VerificationCodes.fresh() != code) return
        val token = ClipboardSuggestionDismissals.Token(ClipboardSuggestionDismissals.Source.Sms, code.timestamp)
        if (ClipboardSuggestionDismissals.shared.isDismissed(token)) return
        chipToken = token
        chipEntry = null
        chipCode = code.code
        chipFromSms = true
        idleUi.clipboardUi.text.text = context.getString(R.string.verification_code_sms_chip, code.code)
        isClipboardFresh = true
        // Invalidate an already displayed offer as well as any query still running in the worker.
        // Subsequent native idle events also see the blocked prediction surface in InputView.
        service.refreshNextWordPrediction()
        setNextWordPredictionVisible(nextWordPredictionVisible)
        // an SMS code stays offered for its whole validity window, independent of the clipboard timeout
        clipboardTimeoutJob?.cancel()
        val left = VerificationCodes.TTL_MS - (System.currentTimeMillis() - code.timestamp)
        clipboardTimeoutJob = service.lifecycleScope.launch {
            delay(left.coerceAtLeast(0L))
            if (chipFromSms) {
                clearClipboardSuggestion()
                evalIdleUiState()
            }
            clipboardTimeoutJob = null
        }
        evalIdleUiState()
        SmsCodeStatus.recordPrepared()
    }

    @Keep
    private val onVerificationCodeListener = VerificationCodes.Listener { code ->
        service.lifecycleScope.launch {
            if (suggestionListenersActive) showSmsCode(code)
        }
    }

    @Keep
    private val onSmsCodePreferenceListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        service.lifecycleScope.launch {
            if (suggestionListenersActive) reconcileSmsCodeSuggestion()
        }
    }

    private fun reconcileSmsCodeSuggestion() {
        if (!smsCodesAllowed()) {
            if (chipFromSms) clearClipboardSuggestion()
        } else {
            val fresh = VerificationCodes.fresh()
            if (fresh?.source == VerificationCodes.Source.Sms) showSmsCode(fresh)
            else if (chipFromSms) clearClipboardSuggestion()
        }
        evalIdleUiState()
    }

    @Keep
    private val onClipboardSuggestionUpdateListener =
        ManagedPreference.OnChangeListener<Boolean> { _, it ->
            if (!it) {
                clearClipboardSuggestion()
                evalIdleUiState()
            }
        }

    @Keep
    private val onClipboardTimeoutUpdateListener =
        ManagedPreference.OnChangeListener<Int> { _, _ ->
            when (idleUi.currentState) {
                IdleUi.State.Clipboard -> {
                    // SMS offers retain their own fixed validity window.
                    if (!chipFromSms) launchClipboardTimeoutJob()
                }
                else -> {}
            }
        }

    private fun launchClipboardTimeoutJob() {
        clipboardTimeoutJob?.cancel()
        clipboardTimeoutJob = null
        val timeout = clipboardItemTimeout.getValue() * 1000L
        // never transition to ClipboardTimedOut state when timeout < 0
        if (timeout < 0L) return
        clipboardTimeoutJob = service.lifecycleScope.launch {
            delay(timeout)
            clearClipboardSuggestion()
            evalIdleUiState()
        }
    }

    private fun evalIdleUiState(fromUser: Boolean = false) {
        val newState = when {
            // Temporarily cover a manually opened number row without forgetting the user's
            // choice. Dismissal or expiry restores it; ordinary clipboard suggestions don't.
            isSmsCodeSuggestionActive() -> IdleUi.State.Clipboard
            numberRowState == NumberRowState.ForceShow -> IdleUi.State.NumberRow
            isClipboardFresh -> IdleUi.State.Clipboard
            isInlineSuggestionPresent -> IdleUi.State.InlineSuggestion
            isCapabilityFlagsPassword && !isKeyboardLayoutNumber && numberRowState != NumberRowState.ForceHide -> IdleUi.State.NumberRow
            /**
             * state matrix:
             *                               expandToolbarByDefault
             *                          |   \   |    true |   false
             * isToolbarManuallyToggled |  true |   Empty | Toolbar
             *                          | false | Toolbar |   Empty
             */
            expandToolbarByDefault == isToolbarManuallyToggled -> IdleUi.State.Empty
            else -> IdleUi.State.Toolbar
        }
        if (newState == idleUi.currentState) return
        idleUi.updateState(newState, fromUser)
    }

    private val hideKeyboardCallback = View.OnClickListener {
        service.requestHideSelf(0)
    }

    private val swipeDownExpandCallback = CustomGestureView.OnGestureListener { _, e ->
        if (e.type == CustomGestureView.GestureType.Up && e.totalY > 0) {
            service.requestHideSelf(0)
            true
        } else false
    }

    // Combined gesture: determine primary direction by comparing totalX and totalY.
    // - If horizontal is dominant and left, show number row (when allowed).
    // - If vertical is dominant and down, hide keyboard.
    private val swipeHideKeyboardCallback = CustomGestureView.OnGestureListener { v, e ->
        require(v is ToolButton)
        val numberRowAvailable = isCapabilityFlagsPassword && !isKeyboardLayoutNumber
        if (numberRowAvailable) {
            val dir = if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR) 1 else -1
            // `e.x` and `e.y` are relative to the view's top-left corner
            val centerX = e.x - v.width / 2f
            val centerY = e.y - v.height / 2f

            val distance = hypot(centerX, centerY)
            // the button is ↓, so apply -90 degrees offset
            var angle = atan2(-centerX, centerY) * (180f / PI.toFloat())

            when (e.type) {
                CustomGestureView.GestureType.Move -> {
                    angle = if (angle in -45f..45f) {
                        angle.coerceIn(-10f, 10f)
                    } else abs(angle).coerceIn(90f - 10f, 90f + 10f) * dir
                    v.iconRotation = angle
                }
                CustomGestureView.GestureType.Up -> {
                    val handled = when (angle) {
                        in -45f..45f if distance > v.swipeThresholdX -> {
                            service.requestHideSelf(0)
                            true
                        }
                        !in -45f..45f if distance > v.swipeThresholdY -> {
                            v.iconRotation = 90f * dir
                            numberRowState = NumberRowState.ForceShow
                            evalIdleUiState(fromUser = true)
                            true
                        }
                        else -> false
                    }
                    v.iconRotation = 0f
                    return@OnGestureListener handled
                }
                else -> {}
            }
        }

        if (e.type == CustomGestureView.GestureType.Up && abs(e.totalY) > abs(e.totalX) && e.totalY > 0) {
            service.requestHideSelf(0)
            true
        } else false
    }

    private val idleUi: IdleUi by lazy {
        IdleUi(context, theme, popup, commonKeyActionListener).apply {
            menuButton.setOnClickListener {
                windowManager.attachWindow(StatusAreaWindow())
            }
            hideKeyboardButton.apply {
                setOnClickListener(hideKeyboardCallback)
                swipeEnabled = true
                swipeThresholdY = dp(HEIGHT.toFloat())
                swipeThresholdX = swipeThresholdY
                onGestureListener = swipeHideKeyboardCallback
            }
            buttonsUi.apply {
                keyboardLayoutButton.setOnClickListener { anchor ->
                    keyboardLayoutPopup?.dismiss()
                    val chooser = keyboardLayoutMenu(context, anchor) { choice ->
                        // Switching windows can clear dependencies and popup anchors; keep the
                        // destination before detaching anything, then act on that same keyboard.
                        val target = windowManager.getEssentialWindow(KeyboardWindow) as KeyboardWindow
                        windowManager.attachWindow(KeyboardWindow)
                        when (choice) {
                            KeyboardLayoutChoice.PinyinNine -> target.selectPinyinLayout(true)
                            KeyboardLayoutChoice.Pinyin26 -> target.selectPinyinLayout(false)
                            KeyboardLayoutChoice.English -> target.selectEnglishKeyboard()
                            KeyboardLayoutChoice.Numbers -> target.switchLayout(NumberKeyboard.Name)
                        }
                    }
                    keyboardLayoutPopup = chooser
                    chooser.setOnDismissListener {
                        if (keyboardLayoutPopup === chooser) keyboardLayoutPopup = null
                    }
                    chooser.show()
                }
                microphoneButton.setOnClickListener {
                    val session = service.createOfflineDictationSession()
                    windowManager.attachWindow(OfflineDictationWindow(session))
                }
                undoButton.setOnClickListener {
                    service.sendCombinationKeyEvents(KeyEvent.KEYCODE_Z, ctrl = true)
                }
                redoButton.setOnClickListener {
                    service.sendCombinationKeyEvents(KeyEvent.KEYCODE_Z, ctrl = true, shift = true)
                }
                emojiButton.setOnClickListener {
                    windowManager.attachWindow(PickerWindow.Key.Emoji)
                }
                clipboardButton.setOnClickListener {
                    windowManager.attachWindow(ClipboardWindow())
                }
            }
            clipboardUi.suggestionView.apply {
                setOnClickListener {
                    val code = chipCode
                    if (code != null) {
                        if (chipFromSms && !isSmsCodeSuggestionActive()) {
                            clearClipboardSuggestion()
                            evalIdleUiState()
                            return@setOnClickListener
                        }
                        if (!service.commitText(code)) return@setOnClickListener
                        if (chipFromSms) VerificationCodes.consume()
                    } else chipEntry?.let {
                        service.commitText(it.text)
                    }
                    clearClipboardSuggestion()
                    evalIdleUiState()
                }
                setOnLongClickListener {
                    if (!chipFromSms) chipEntry?.let {
                        AppUtil.launchClipboardEdit(context, it.id, true)
                    }
                    true
                }
            }
            clipboardUi.dismissButton.setOnClickListener { dismissClipboardSuggestion() }
            numberRow.apply {
                onCollapseListener = {
                    numberRowState = NumberRowState.ForceHide
                    evalIdleUiState(fromUser = true)
                }
            }
        }
    }

    private val candidateUi by lazy {
        CandidateUi(context, theme, horizontalCandidate.view).apply {
            expandButton.apply {
                swipeEnabled = true
                swipeThresholdY = dp(HEIGHT.toFloat())
                onGestureListener = swipeDownExpandCallback
            }
        }
    }

    private val titleUi by lazy {
        TitleUi(context, theme)
    }

    private val barStateMachine = KawaiiBarStateMachine.new {
        switchUiByState(it)
    }

    val expandButtonStateMachine = ExpandButtonStateMachine.new {
        when (it) {
            ClickToAttachWindow -> {
                setExpandButtonToAttach()
                setExpandButtonEnabled(true)
            }
            ClickToDetachWindow -> {
                setExpandButtonToDetach()
                setExpandButtonEnabled(true)
            }
            Hidden -> {
                setExpandButtonEnabled(false)
            }
        }
    }

    // set expand candidate button to create expand candidate
    private fun setExpandButtonToAttach() {
        candidateUi.expandButton.setOnClickListener {
            windowManager.attachWindow(
                when (expandedCandidateStyle) {
                    ExpandedCandidateStyle.Grid -> GridExpandedCandidateWindow()
                    ExpandedCandidateStyle.Flexbox -> FlexboxExpandedCandidateWindow()
                }
            )
        }
        candidateUi.expandButton.setIcon(R.drawable.ic_baseline_expand_more_24)
        candidateUi.expandButton.contentDescription = context.getString(R.string.expand_candidates_list)
    }

    // set expand candidate button to close expand candidate
    private fun setExpandButtonToDetach() {
        candidateUi.expandButton.setOnClickListener {
            windowManager.attachWindow(KeyboardWindow)
        }
        candidateUi.expandButton.setIcon(R.drawable.ic_baseline_expand_less_24)
        candidateUi.expandButton.contentDescription = context.getString(R.string.hide_candidates_list)
    }

    // should be used with setExpandButtonToAttach or setExpandButtonToDetach
    private fun setExpandButtonEnabled(enabled: Boolean) {
        candidateUi.expandButton.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    }

    private fun switchUiByState(state: KawaiiBarStateMachine.State) {
        val index = state.ordinal
        if (view.displayedChild == index) return
        val new = view.getChildAt(index)
        if (new != titleUi.root) {
            titleUi.setReturnButtonOnClickListener { }
            titleUi.setTitle("")
            titleUi.removeExtension()
        }
        view.displayedChild = index
    }

    fun setEffectKeyboard(keyboard: BaseKeyboard?) { view.keyboard = keyboard }

    /** Predictions use the idle row without pretending to be native expandable candidates. */
    fun setNextWordPredictionVisible(visible: Boolean) {
        nextWordPredictionVisible = visible
        val showPrediction = visible && !isSmsCodeSuggestionActive()
        barStateMachine.push(PreeditUpdated, PreeditEmpty to (nativePreeditEmpty && !showPrediction))
        barStateMachine.push(CandidatesUpdated, CandidateEmpty to (nativeCandidatesEmpty && !showPrediction))
    }

    override val view by lazy {
        RippleBarView(context).apply {
            backgroundColor =
                if (ThemeManager.prefs.keyBorder.getValue()) Color.TRANSPARENT
                else theme.barColor
            add(idleUi.root, lParams(matchParent, matchParent))
            add(candidateUi.root, lParams(matchParent, matchParent))
            add(titleUi.root, lParams(matchParent, matchParent))
        }
    }

    override fun onScopeSetupFinished(scope: DynamicScope) {
        suggestionListenersActive = true
        fun collectPinyinFeedback() {
            pinyinFeedbackJob?.cancel()
            pinyinFeedbackJob = service.lifecycleScope.launch {
                combine(commonKeyActionListener.pinyinTapFeedback,
                    commonKeyActionListener.touchCandidateOffer) { feedback, offer ->
                    feedback to offer
                }.collect { (feedback, offer) ->
                    horizontalCandidate.setTouchCandidate(offer)
                    // Existing top-three words may move second; novel suggestions follow the top three.
                    candidateUi.setPinyinFeedback(if (offer == null) feedback else null,
                            restore = { token -> commonKeyActionListener.listener.onKeyAction(
                                KeyAction.RestorePinyinTapAction(token), KeyActionListener.Source.Keyboard) },
                            confirm = { token -> commonKeyActionListener.listener.onKeyAction(
                                KeyAction.ConfirmPinyinTapAction(token), KeyActionListener.Source.Keyboard) })
                }
            }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { collectPinyinFeedback() }
            override fun onViewDetachedFromWindow(v: View) {
                pinyinFeedbackJob?.cancel()
                pinyinFeedbackJob = null
                horizontalCandidate.setTouchCandidate(null)
                commonKeyActionListener.clearPinyinTapFeedback()
                commonKeyActionListener.invalidateTouchCandidates()
            }
        })
        if (view.isAttachedToWindow) collectPinyinFeedback()
        ClipboardManager.lastEntry?.let {
            val now = System.currentTimeMillis()
            val clipboardTimeout = clipboardItemTimeout.getValue() * 1000L
            if (clipboardTimeout < 0 || now - it.timestamp < clipboardTimeout) {
                onClipboardUpdateListener.onUpdate(it)
            }
        }
        ClipboardManager.addOnUpdateListener(onClipboardUpdateListener)
        VerificationCodes.addListener(onVerificationCodeListener)
        VerificationCodes.fresh()?.let { showSmsCode(it) }
        codeFromSms.registerOnChangeListener(onSmsCodePreferenceListener)
        clipboardSuggestion.registerOnChangeListener(onClipboardSuggestionUpdateListener)
        clipboardItemTimeout.registerOnChangeListener(onClipboardTimeoutUpdateListener)
    }

    /** InputView destroys its dependency scope after detaching; don't retain listeners or timers. */
    internal fun dispose() {
        suggestionListenersActive = false
        codeFromSms.unregisterOnChangeListener(onSmsCodePreferenceListener)
        VerificationCodes.removeListener(onVerificationCodeListener)
        ClipboardManager.removeOnUpdateListener(onClipboardUpdateListener)
        clipboardSuggestion.unregisterOnChangeListener(onClipboardSuggestionUpdateListener)
        clipboardItemTimeout.unregisterOnChangeListener(onClipboardTimeoutUpdateListener)
        clipboardTimeoutJob?.cancel()
        clipboardTimeoutJob = null
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        keyboardLayoutPopup?.dismiss()
        nativePreeditEmpty = true
        nativeCandidatesEmpty = true
        setNextWordPredictionVisible(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            idleUi.privateMode(info.imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        }
        isCapabilityFlagsPassword = toolbarNumRowOnPassword && capFlags.has(CapabilityFlag.Password)
        isInlineSuggestionPresent = false
        numberRowState = NumberRowState.Auto
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            idleUi.inlineSuggestionsBar.clear()
        }
        reconcileSmsCodeSuggestion()
    }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        nativePreeditEmpty = empty
        barStateMachine.push(PreeditUpdated, PreeditEmpty to
            (empty && !(nextWordPredictionVisible && !isSmsCodeSuggestionActive())))
    }

    override fun onCandidateUpdate(data: CandidateListEvent.Data) {
        nativeCandidatesEmpty = data.candidates.isEmpty()
        setNextWordPredictionVisible(false)
    }

    override fun onWindowAttached(window: InputWindow) {
        when (window) {
            is InputWindow.ExtendedInputWindow<*> -> {
                titleUi.setTitle(window.title)
                window.onCreateBarExtension()?.let { titleUi.addExtension(it, window.showTitle) }
                titleUi.setReturnButtonOnClickListener {
                    windowManager.attachWindow(KeyboardWindow)
                }
                barStateMachine.push(ExtendedWindowAttached)
            }
            else -> {}
        }
    }

    override fun onWindowDetached(window: InputWindow) {
        keyboardLayoutPopup?.dismiss()
        setNextWordPredictionVisible(false)
        barStateMachine.push(WindowDetached)
    }

    private val suggestionSize by lazy {
        Size(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(HEIGHT))
    }

    private val directExecutor by lazy {
        Executor { it.run() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) {
            isInlineSuggestionPresent = false
            evalIdleUiState()
            idleUi.inlineSuggestionsBar.clear()
            return true
        }
        var pinned: InlineSuggestion? = null
        val scrollable = mutableListOf<InlineSuggestion>()
        var extraPinnedCount = 0
        suggestions.forEach {
            if (it.info.isPinned) {
                if (pinned == null) {
                    pinned = it
                } else {
                    scrollable.add(extraPinnedCount++, it)
                }
            } else {
                scrollable.add(it)
            }
        }
        service.lifecycleScope.launch {
            idleUi.inlineSuggestionsBar.setPinnedView(
                pinned?.let { inflateInlineContentView(it) }
            )
        }
        service.lifecycleScope.launch {
            val views = scrollable.map { s ->
                service.lifecycleScope.async {
                    inflateInlineContentView(s)
                }
            }.awaitAll()
            idleUi.inlineSuggestionsBar.setScrollableViews(views)
        }
        isInlineSuggestionPresent = true
        evalIdleUiState()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun inflateInlineContentView(suggestion: InlineSuggestion): InlineContentView? {
        return suspendCancellableCoroutine { c ->
            // callback view might be null
            suggestion.inflate(context, suggestionSize, directExecutor) { v ->
                c.resume(v)
            }
        }
    }

    companion object {
        const val HEIGHT = 52
    }

    fun onKeyboardLayoutSwitched(isNumber: Boolean) {
        isKeyboardLayoutNumber = isNumber
        evalIdleUiState()
    }

}
