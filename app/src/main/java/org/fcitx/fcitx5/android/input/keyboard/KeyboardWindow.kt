/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.transition.Slide
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import org.mechdancer.dependency.manager.must
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent

class KeyboardWindow : InputWindow.SimpleInputWindow<KeyboardWindow>(), EssentialWindow,
    InputBroadcastReceiver {

    private val service by manager.inputMethodService()
    private val inputView by manager.inputView()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val popup: PopupComponent by manager.must()
    private val bar: KawaiiBarComponent by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    companion object : EssentialWindow.Key

    override val key: EssentialWindow.Key
        get() = KeyboardWindow

    override fun enterAnimation(lastWindow: InputWindow) = Slide().apply {
        slideEdge = Gravity.BOTTOM
    }.takeIf {
        // disable animation switching between picker
        lastWindow !is PickerWindow
    }

    override fun exitAnimation(nextWindow: InputWindow) =
        super.exitAnimation(nextWindow).takeIf {
            // disable animation switching between picker
            nextWindow !is PickerWindow
        }

    private lateinit var keyboardView: FrameLayout

    private val keyboards: HashMap<String, BaseKeyboard> by lazy {
        hashMapOf(
            TextKeyboard.Name to TextKeyboard(context, theme),
            PinyinT9Keyboard.Name to PinyinT9Keyboard(context, theme),
            NumberKeyboard.Name to NumberKeyboard(context, theme)
        )
    }
    private val symbolHistory by lazy {
        SymbolHistory(context.getSharedPreferences("symbol-keyboard", android.content.Context.MODE_PRIVATE))
    }
    private var symbolState = SymbolKeyboardState()
    private var currentKeyboardName = ""
    private var currentInputType = InputType.TYPE_CLASS_TEXT
    private var latestIme: InputMethodEntry? = null
    private val engineSwitch = KeyboardEngineSwitch()
    private val schemaSwitchGuard by lazy {
        TextView(context).apply {
            text = context.getString(R.string.pinyin_layout_switching)
            setTextColor(theme.keyTextColor)
            setBackgroundColor(theme.keyboardColor)
            textSize = 16f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
        }
    }
    private var lastSymbolType: String by AppPrefs.getInstance().internal.lastSymbolLayout

    private val currentKeyboard: BaseKeyboard? get() = keyboards[currentKeyboardName]

    private val keyActionListener = KeyActionListener { it, source ->
        if (it is KeyAction.LayoutSwitchAction) {
            switchLayout(it.act)
        } else {
            commonKeyActionListener.listener.onKeyAction(it, source)
        }
    }

    private val popupActionListener: PopupActionListener by lazy {
        popup.listener
    }

    // This will be called EXACTLY ONCE
    override fun onCreateView(): View {
        keyboardView = context.frameLayout(R.id.keyboard_view)
        latestIme = fcitx.runImmediately { inputMethodEntryCached }
        attachLayout(PinyinLayoutPolicy.textLayout(latestIme!!, currentInputType))
        return keyboardView
    }

    private fun detachCurrentLayout() {
        currentKeyboard?.also {
            bar.setEffectKeyboard(null)
            it.onDetach()
            keyboardView.removeView(it)
            it.keyActionListener = null
            it.popupActionListener = null
        }
    }

    private fun attachLayout(target: String) {
        currentKeyboardName = target
        currentKeyboard?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            keyboardView.apply { add(it, lParams(matchParent, matchParent)) }
            if (schemaSwitchGuard.parent === keyboardView) schemaSwitchGuard.bringToFront()
            it.onAttach()
            bar.setEffectKeyboard(it)
            it.onReturnDrawableUpdate(returnKeyDrawable.resourceId)
            it.onInputMethodUpdate(fcitx.runImmediately { inputMethodEntryCached })
        }
    }

    fun switchLayout(to: String, remember: Boolean = true) {
        if (to == PinyinT9Keyboard.Name || to == PinyinLayoutPolicy.FullRoute) {
            selectPinyinLayout(to == PinyinT9Keyboard.Name)
            return
        }
        val ime = latestIme ?: fcitx.runImmediately { inputMethodEntryCached }
        val target = when (to) {
            TextKeyboard.Name -> PinyinLayoutPolicy.textLayout(ime, currentInputType)
            "" -> lastSymbolType
            else -> to
        }
        ContextCompat.getMainExecutor(service).execute {
            // Category, paging and lock commands stay in the keyboard window.
            // Rebuild from state and stored history so recent symbols also refresh on re-entry.
            val isSymbols = SymbolKeyboard.isRoute(target) || target == "SamsungSymbol1" || target == "SamsungSymbol2"
            val layoutName = if (isSymbols) SymbolKeyboard.Name else target
            if (isSymbols || keyboards.containsKey(layoutName)) {
                if (remember && layoutName != TextKeyboard.Name && layoutName != PinyinT9Keyboard.Name) {
                    lastSymbolType = layoutName
                }
                if (!isSymbols && layoutName == currentKeyboardName) return@execute
                detachCurrentLayout()
                if (isSymbols) {
                    symbolState = symbolState.copy(locked = symbolHistory.locked)
                        .navigate(if (target.startsWith("SamsungSymbol")) SymbolKeyboard.Name else target, symbolHistory.items)
                    symbolHistory.locked = symbolState.locked
                    keyboards[SymbolKeyboard.Name] = SymbolKeyboard(context, theme, symbolState, symbolHistory)
                }
                attachLayout(layoutName)
                if (windowManager.isAttached(this)) {
                    notifyBarLayoutChanged()
                }
            } else {
                if (remember) {
                    lastSymbolType = PickerWindow.Key.Symbol.name
                }
                windowManager.attachWindow(PickerWindow.Key.Symbol)
            }
        }
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        commonKeyActionListener.clearPinyinTapFeedback()
        cancelPendingEngineSwitch()
        currentInputType = info.inputType
        latestIme = fcitx.runImmediately { inputMethodEntryCached }
        val targetLayout = if (PinyinLayoutPolicy.isNumber(info.inputType)) NumberKeyboard.Name else TextKeyboard.Name
        switchLayout(targetLayout, remember = false)
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        latestIme = ime
        if (currentKeyboardName == TextKeyboard.Name || currentKeyboardName == PinyinT9Keyboard.Name) {
            switchLayout(TextKeyboard.Name, remember = false)
        }
        currentKeyboard?.onInputMethodUpdate(ime)
    }

    /** Select the real bundled schema before exposing the matching keypad. */
    fun selectPinyinLayout(nineKey: Boolean) {
        if (!PinyinLayoutPolicy.allowsPinyin(currentInputType)) {
            Toast.makeText(context, R.string.pinyin_layout_text_only, Toast.LENGTH_SHORT).show()
            return
        }
        switchInputEngine { request ->
            if (request.call { availableIme() }.none { it.uniqueName == "rime" }) return@switchInputEngine null
            ensureEnabled("rime", request)
            request.call { activateIme("rime") }
            val action = RimeActions.pinyinSchema(request.call { statusArea() }, nineKey)
                ?: return@switchInputEngine null
            // Pinned fcitx5-rime selects the schema and clears ascii_mode together.
            request.call { activateAction(action.id) }
            request.call { currentIme() }.takeIf { RimeActions.isPinyinSchema(it, nineKey) }
        }
    }

    fun selectEnglishKeyboard() {
        if (PinyinLayoutPolicy.isNumber(currentInputType)) {
            switchLayout(NumberKeyboard.Name, remember = false)
            return
        }
        switchInputEngine { request ->
            if (request.call { availableIme() }.none { it.uniqueName == "keyboard-us" }) return@switchInputEngine null
            ensureEnabled("keyboard-us", request)
            request.call { activateIme("keyboard-us") }
            request.call { currentIme() }.takeIf { it.uniqueName == "keyboard-us" }
        }
    }

    private suspend fun FcitxAPI.ensureEnabled(name: String, request: KeyboardEngineSwitch.Request) {
        val enabled = request.call { enabledIme() }.map { it.uniqueName }
        if (name !in enabled) {
            request.call { setEnabledIme((enabled + name).toTypedArray()) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val entries = request.call { enabledIme() }
                request.call { SubtypeManager.syncWith(entries) }
            }
        }
    }

    internal fun cancelPendingEngineSwitch() {
        engineSwitch.cancel()
        if (::keyboardView.isInitialized) keyboardView.removeView(schemaSwitchGuard)
    }

    private fun switchInputEngine(selectEngine: suspend FcitxAPI.(KeyboardEngineSwitch.Request) -> InputMethodEntry?) {
        // Capture dependencies while this window still belongs to its scope. The native job can
        // finish after a theme change has replaced the InputView or the user has hidden the IME.
        val targetService = service
        val windows = windowManager
        val targetContext = context
        val targetView = keyboardView
        val inputConnection = targetService.currentInputConnection ?: return
        val editorInfo = targetService.currentInputEditorInfo
        val executor = ContextCompat.getMainExecutor(targetService)
        val request = engineSwitch.begin {
            withContext(Dispatchers.Main.immediate) {
                targetView.isAttachedToWindow && windows.isAttached(this@KeyboardWindow) &&
                    targetService.currentInputConnection === inputConnection &&
                    targetService.currentInputEditorInfo === editorInfo
            }
        }
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).let {
            currentKeyboard?.dispatchTouchEvent(it)
            it.recycle()
        }
        popup.dismissAll()
        if (schemaSwitchGuard.parent == null) {
            targetView.addView(schemaSwitchGuard, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        schemaSwitchGuard.bringToFront()
        val job = targetService.postFcitxJob {
            var selected: InputMethodEntry? = null
            var failed = false
            var pendingInput = false
            try {
                val api = this
                val result = engineSwitch.execute(request, hasPreedit = {
                    // This read also waits for earlier native work to update both preedit caches.
                    currentIme()
                    clientPreeditCached.isNotEmpty() || inputPanelCached.preedit.isNotEmpty()
                }) {
                    api.selectEngine(request)
                }
                pendingInput = result.pendingInput
                if (!pendingInput) {
                    selected = result.ime ?: request.call { currentIme() }
                    failed = result.ime == null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                Timber.w(e, "Unable to select bundled pinyin layout")
            } finally {
                val actualIme = selected
                val showFailure = failed
                val showPendingInput = pendingInput
                executor.execute {
                    if (engineSwitch.isCurrent(request)) {
                        targetView.removeView(schemaSwitchGuard)
                        if (targetView.isAttachedToWindow && windows.isAttached(this@KeyboardWindow) &&
                            targetService.currentInputConnection === inputConnection &&
                            targetService.currentInputEditorInfo === editorInfo) {
                            actualIme?.let { onImeUpdate(it) }
                            if (!showPendingInput) switchLayout(if (PinyinLayoutPolicy.isNumber(currentInputType))
                                NumberKeyboard.Name else TextKeyboard.Name, remember = false)
                            if (showPendingInput) Toast.makeText(targetContext,
                                R.string.keyboard_switch_finish_composing, Toast.LENGTH_LONG).show()
                            else if (showFailure) Toast.makeText(targetContext,
                                R.string.pinyin_layout_unavailable, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        engineSwitch.track(request, job)
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        currentKeyboard?.onPunctuationUpdate(mapping)
    }

    override fun onReturnKeyDrawableUpdate(resourceId: Int) {
        currentKeyboard?.onReturnDrawableUpdate(resourceId)
    }

    override fun onAttached() {
        latestIme = fcitx.runImmediately { inputMethodEntryCached }
        if (currentKeyboardName == TextKeyboard.Name || currentKeyboardName == PinyinT9Keyboard.Name) {
            switchLayout(TextKeyboard.Name, remember = false)
        }
        currentKeyboard?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            it.onAttach()
            bar.setEffectKeyboard(it)
        }
        notifyBarLayoutChanged()
    }

    override fun onDetached() {
        commonKeyActionListener.clearPinyinTapFeedback()
        cancelPendingEngineSwitch()
        inputView.onKeyboardLayoutChanged(false)
        currentKeyboard?.let {
            bar.setEffectKeyboard(null)
            it.onDetach()
            it.keyActionListener = null
            it.popupActionListener = null
        }
        popup.dismissAll()
    }

    // Call this when
    // 1) the keyboard window was newly attached
    // 2) currently keyboard window is attached and switchLayout was used
    private fun notifyBarLayoutChanged() {
        inputView.onKeyboardLayoutChanged(currentKeyboardName == TextKeyboard.Name)
        bar.onKeyboardLayoutSwitched(currentKeyboardName == NumberKeyboard.Name)
    }
}
