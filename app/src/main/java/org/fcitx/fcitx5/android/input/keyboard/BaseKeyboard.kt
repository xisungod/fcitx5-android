/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.animation.ValueAnimator
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import androidx.annotation.CallSuper
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import org.json.JSONArray
import org.json.JSONObject
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.diagnostics.TouchDiagnosticPolicy
import org.fcitx.fcitx5.android.data.typingtest.TypingTestSession
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.PressColorPalette
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.GestureType
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.OnGestureListener
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.keyboard.typing.KeyCell
import org.fcitx.fcitx5.android.input.keyboard.typing.TapEvidence
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinTapEvidence
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.leftToRightOf
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.rightToLeftOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import kotlin.math.absoluteValue
import java.util.concurrent.atomic.AtomicLong

abstract class BaseKeyboard(
    context: Context,
    sourceTheme: Theme,
    private val keyLayout: List<List<KeyDef>>,
    private val useEffectTheme: Boolean = true
) : ConstraintLayout(context) {

    protected val theme = if (useEffectTheme) ThemeManager.keyboardTheme(sourceTheme) else sourceTheme
    private val samMode = useEffectTheme && ThemeManager.prefs.rippleShape.getValue() == ThemePrefs.RippleShape.Sam

    var keyActionListener: KeyActionListener? = null
    internal var effectInvalidator: (() -> Unit)? = null
        set(value) {
            field = value
            pressEffectLayer?.invalidateExtension = value
        }
    internal fun drawPressExtension(canvas: Canvas) { pressEffectLayer?.drawExtension(canvas) }

    private val prefs = AppPrefs.getInstance()
    private val touchTrace = TouchTraceRecorder(context)
    private var boundarySettlingForGesture = true
    private var downOrderForGesture = false
    private var downOrderEditorForGesture: EditorInfo? = null
    private var downOrderGestureCancelled = false
    private var touchDownSequence = 0L
    private var touchDispatchSequence = 0L
    private var touchGestureGeneration = 0L
    /** Only early-confirmed contacts are retained, until their original physical UP. */
    private val physicalUpsPending = hashMapOf<Int, Long>()

    /** Memory-only editor source for canonical keyboard tests without an IME service. */
    internal var downOrderEditorInfoProvider: (() -> EditorInfo?)? = null
    private val editorService: InputMethodService? by lazy {
        generateSequence(context) { current ->
            (current as? ContextWrapper)?.baseContext?.takeIf { it !== current }
        }.filterIsInstance<InputMethodService>().firstOrNull()
    }
    private fun downOrderEditorInfo(): EditorInfo? =
        downOrderEditorInfoProvider?.invoke() ?: editorService?.currentInputEditorInfo

    /** The test editor alone may retain evidence when both correction experiments are off. */
    protected fun typingTestEditorEligible(): Boolean =
        TypingTestSession.isEligibleEditor(downOrderEditorInfo())

    /** Memory-only observation for canonical MotionEvent replay tests. */
    internal var touchDiagnosticObserver: ((JSONObject) -> Unit)?
        get() = touchTrace.observer
        set(value) { touchTrace.discard(); touchTrace.observer = value }

    protected open fun diagnosticState(): JSONObject = JSONObject()
    /** Original per-pointer DOWN evidence, available only during ordinary UP dispatch. */
    protected var currentPinyinTapEvidence: PinyinTapEvidence? = null
        private set
    private val keyMotion = ThemeManager.prefs.keyMotionEffect.getValue()
    private val floatingKeys = android.util.SparseArray<KeyView>()
    private var motionLifecycleReady = false

    /** colourful press effect (Keys Cafe style), drawn under and over the keys */
    private val pressEffectLayer: PressEffect? = ThemeManager.prefs.let { p ->
        if (!p.pressEffect.getValue() && (!p.idleBreathing.getValue() || samMode)) null
        else PressEffect(
            this,
            PressColorPalette.colorsFor(p, theme.accentKeyBackgroundColor),
            theme.isDark,
            p.pressEffectSize.getValue(),
            p.pressExpansionTime.getValue(),
            p.pressFadeOutTime.getValue(),
            p.pressEffectOverKeys.getValue(),
            p.pressEffect.getValue(),
            p.idleBreathing.getValue() && !samMode,
            IdleBreathing(
                p.idleDelay.getValue().toLong(),
                p.idleCycle.getValue().toLong(),
                p.idleFadeIn.getValue().toLong(),
                p.idleFadeOut.getValue().toLong(),
                p.idleBrightness.getValue() / 100f,
                p.idleTimeout.getValue() * 1000L
            ),
            if (p.idleRandomColors.getValue()) PressEffect.CYBERPUNK
            else intArrayOf(p.idleColorPrimary.getValue().argb, p.idleColorSecondary.getValue().argb),
            randomIdleColors = p.idleRandomColors.getValue(),
            ignitionTimeMs = p.pressIgnitionTime.getValue(),
            keyHoldTimeMs = p.pressKeyHoldTime.getValue(),
            keyRetreatTimeMs = p.pressKeyRetreatTime.getValue(),
            samKeyHoldTimeMs = p.samKeyHoldTime.getValue(),
            samKeyRetreatTimeMs = p.samKeyRetreatTime.getValue(),
            keySurfaceEffects = true,
            keyColorStyle = p.keyColorStyle.getValue().ordinal,
            keyCornerRadius = p.keyRadius.getValue() * resources.displayMetrics.density,
            glowReachPercent = p.pressGlowReach.getValue(),
            glowOnCandidates = p.pressGlowOnCandidates.getValue(),
            holdWhilePressed = true,
            dimExit = p.keyExitStyle.getValue() == ThemePrefs.KeyExitStyle.Dim,
            ringWave = false,
            sequentialColors = false,
            exactKeyShape = true,
            normalizeOldLight = true,
            extensionStrength = 1f,
            glowBrightnessPercent = p.pressGlowBrightness.getValue(),
            waveHoldTimeMs = p.pressWaveHoldTime.getValue(),
            coordinatePalette = p.pressColorMode.getValue() == ThemePrefs.PressColorMode.Random,
            rippleShape = if (useEffectTheme) p.rippleShape.getValue() else ThemePrefs.RippleShape.SoftMist,
            keyFloatOpacity = if (keyMotion == ThemePrefs.KeyMotionEffect.Press)
                { keyId -> floatingKeys.get(keyId)?.floatingFaceOpacity() ?: 0f } else null
        )
    }

    init {
        if (pressEffectLayer != null) setWillNotDraw(false)
        if (samMode) setBackgroundColor(android.graphics.Color.BLACK)
    }

    private val popupOnKeyPress by prefs.keyboard.popupOnKeyPress
    private val expandKeypressArea by prefs.keyboard.expandKeypressArea
    private val swipeSymbolDirection by prefs.keyboard.swipeSymbolDirection

    private val spaceSwipeMoveCursor = prefs.keyboard.spaceSwipeMoveCursor
    private val spaceLongPressBehavior = prefs.keyboard.spaceKeyLongPressBehavior
    private val spaceKeys = mutableListOf<KeyView>()
    private val alphabetKeyIds = hashSetOf<Int>()
    private val surfaceKeys = mutableListOf<KeyView>()
    private val depthKeys = mutableListOf<KeyView>()
    private fun updateSpaceGestures() {
        val trackpad = spaceLongPressBehavior.getValue() == SpaceLongPressBehavior.MoveCursor
        spaceKeys.forEach {
            it.swipeRequiresLongPress = trackpad
            it.swipeEnabled = trackpad || spaceSwipeMoveCursor.getValue()
            it.swipeDominantAxisOnly = trackpad
            it.swipeThresholdX = selectionSwipeThreshold
            it.swipeThresholdY = if (trackpad) selectionSwipeThreshold else disabledSwipeThreshold
        }
    }
    private val spaceSwipeChangeListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        updateSpaceGestures()
    }
    private val spaceLongPressChangeListener = ManagedPreference.OnChangeListener<SpaceLongPressBehavior> { _, _ ->
        updateSpaceGestures()
    }

    private val vivoKeypressWorkaround by prefs.advanced.vivoKeypressWorkaround

    protected open val slideSelectionEnabled = false
    /** Compact QWERTY cells favour stable taps; large numeric grids keep direct sliding. */
    protected open val guardTapRetargeting = false
    private val popupSelectionKeys = hashSetOf<Int>()
    protected open fun canSlideSelect(key: KeyView): Boolean {
        val text = (key.def as? KeyDef.Appearance.Text)?.displayText ?: return false
        return text.length == 1 && (text[0] in 'a'..'z' || text[0] in 'A'..'Z' || text[0] in '0'..'9')
    }

    private fun canRetargetPendingTap(key: KeyView): Boolean =
        canSlideSelect(key) && !key.hasConsumedTouchAction && key.id !in popupSelectionKeys

    private val hapticOnRepeat by prefs.keyboard.hapticOnRepeat

    var popupActionListener: PopupActionListener? = null

    private val selectionSwipeThreshold = dp(10f)
    private val inputSwipeThreshold = dp(36f)

    // a rather large threshold effectively disables swipe of the direction
    private val disabledSwipeThreshold = dp(800f)

    private val keyRows: List<ConstraintLayout>

    init {
        isMotionEventSplittingEnabled = true
        keyRows = keyLayout.map { row ->
            val keyViews = row.map(::createKeyView)
            constraintLayout Row@{
                isMotionEventSplittingEnabled = true
                var totalWidth = 0f
                keyViews.forEachIndexed { index, view ->
                    add(view, lParams {
                        centerVertically()
                        if (index == 0) {
                            leftOfParent()
                            horizontalChainStyle = LayoutParams.CHAIN_PACKED
                        } else {
                            leftToRightOf(keyViews[index - 1])
                        }
                        if (index == keyViews.size - 1) {
                            rightOfParent()
                            // for RTL
                            horizontalChainStyle = LayoutParams.CHAIN_PACKED
                        } else {
                            rightToLeftOf(keyViews[index + 1])
                        }
                        val def = row[index]
                        matchConstraintPercentWidth = def.appearance.percentWidth
                    })
                    row[index].appearance.percentWidth.let {
                        // 0f means fill remaining space, thus does not need expanding
                        totalWidth += if (it != 0f) it else 1f
                    }
                }
                if (expandKeypressArea && totalWidth < 1f) {
                    val free = (1f - totalWidth) / 2f
                    keyViews.first().apply {
                        updateLayoutParams<LayoutParams> {
                            matchConstraintPercentWidth += free
                        }
                        layoutMarginLeft = free / (row.first().appearance.percentWidth + free)
                    }
                    keyViews.last().apply {
                        updateLayoutParams<LayoutParams> {
                            matchConstraintPercentWidth += free
                        }
                        layoutMarginRight = free / (row.last().appearance.percentWidth + free)
                    }
                }
            }
        }
        keyRows.forEachIndexed { index, row ->
            add(row, lParams {
                if (index == 0) topOfParent()
                else below(keyRows[index - 1])
                if (index == keyRows.size - 1) bottomOfParent()
                else above(keyRows[index + 1])
                centerHorizontally()
            })
        }
        spaceSwipeMoveCursor.registerOnChangeListener(spaceSwipeChangeListener)
        spaceLongPressBehavior.registerOnChangeListener(spaceLongPressChangeListener)
        pressEffectLayer?.invalidateKeySurfaces = {
            // Invalidate child display lists as well as the parent light layer.
            for (i in surfaceKeys.indices) surfaceKeys[i].invalidateKeySurface()
        }
        motionLifecycleReady = true
    }

    private fun createKeyView(def: KeyDef): KeyView {
        return when (def.appearance) {
            is KeyDef.Appearance.AltText -> AltTextKeyView(context, theme, def.appearance)
            is KeyDef.Appearance.ImageText -> ImageTextKeyView(context, theme, def.appearance)
            is KeyDef.Appearance.Text -> TextKeyView(context, theme, def.appearance)
            is KeyDef.Appearance.Image -> ImageKeyView(context, theme, def.appearance)
        }.apply {
            if (id == View.NO_ID) id = View.generateViewId()
            if (def is AlphabetKey) alphabetKeyIds.add(id)
            if (keyMotion == ThemePrefs.KeyMotionEffect.Press) {
                depthKeys.add(this)
                floatingKeys.put(id, this)
            }
            pressEffectLayer?.let { effect ->
                surfaceKeys.add(this)
                keySurfacePainter = KeyView.KeySurfacePainter { canvas, width, height ->
                    // Keep the theme's legend colour steady throughout press and release.
                    // The cap luminance is bounded; glyphs need no black contour.
                    effect.drawKeySurface(canvas, id, width, height, hMargin, vMargin)
                }
            }
            soundEffect = when (def) {
                is SpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
                is MiniSpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
                is BackspaceKey -> InputFeedbacks.SoundEffect.Delete
                is ReturnKey -> InputFeedbacks.SoundEffect.Return
                else -> InputFeedbacks.SoundEffect.Standard
            }
            if (def is SpaceKey) {
                spaceKeys.add(this)
                updateSpaceGestures()
                swipeRepeatEnabled = true
                onGestureListener = OnGestureListener { view, event ->
                    when (event.type) {
                        GestureType.Move -> when (val count = if (swipeRequiresLongPress && event.countY != 0)
                            event.countY else event.countX) {
                            0 -> false
                            else -> {
                                val sym = if (swipeRequiresLongPress && event.countY != 0) {
                                    if (count > 0) FcitxKeyMapping.FcitxKey_Down else FcitxKeyMapping.FcitxKey_Up
                                } else {
                                    if (count > 0) FcitxKeyMapping.FcitxKey_Right else FcitxKeyMapping.FcitxKey_Left
                                }
                                val action = KeyAction.SymAction(KeySym(sym), KeyStates.Virtual)
                                repeat(count.absoluteValue) {
                                    onAction(action)
                                    if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                                }
                                true
                            }
                        }
                        else -> false
                    }
                }
            } else if (def is BackspaceKey) {
                swipeEnabled = true
                swipeRepeatEnabled = true
                swipeThresholdX = selectionSwipeThreshold
                swipeThresholdY = disabledSwipeThreshold
                onGestureListener = OnGestureListener { view, event ->
                    when (event.type) {
                        GestureType.Move -> {
                            val count = event.countX
                            if (count != 0) {
                                onAction(KeyAction.MoveSelectionAction(count))
                                if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                                true
                            } else false
                        }
                        GestureType.Up -> {
                            onAction(KeyAction.DeleteSelectionAction(event.totalX))
                            false
                        }
                        else -> false
                    }
                }
            }
            def.behaviors.forEach {
                when (it) {
                    is KeyDef.Behavior.Press -> {
                        setOnClickListener { _ ->
                            onAction(it.action)
                        }
                    }
                    is KeyDef.Behavior.LongPress -> {
                        setOnLongClickListener { _ ->
                            onAction(it.action)
                            true
                        }
                    }
                    is KeyDef.Behavior.Repeat -> {
                        repeatEnabled = true
                        onRepeatListener = { view ->
                            onAction(it.action)
                            if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                        }
                    }
                    is KeyDef.Behavior.Swipe -> {
                        swipeEnabled = true
                        swipeThresholdX = disabledSwipeThreshold
                        swipeThresholdY = inputSwipeThreshold
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        onGestureListener = OnGestureListener { view, event ->
                            when (event.type) {
                                GestureType.Up -> {
                                    if (!event.consumed && swipeSymbolDirection.checkY(event.totalY)) {
                                        onAction(it.action)
                                        true
                                    } else {
                                        false
                                    }
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Behavior.DoubleTap -> {
                        doubleTapEnabled = true
                        onDoubleTapListener = { _ ->
                            onAction(it.action)
                        }
                    }
                }
            }
            def.popup?.forEach {
                when (it) {
                    // TODO: gesture processing middleware
                    is KeyDef.Popup.Menu -> {
                        setOnLongClickListener { view ->
                            view as KeyView
                            onPopupAction(PopupAction.ShowMenuAction(view.id, it, view.bounds))
                            // do not consume this LongClick gesture
                            false
                        }
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        swipeEnabled = true
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            when (event.type) {
                                GestureType.Move -> {
                                    onPopupChangeFocus(view.id, event.x, event.y)
                                }
                                GestureType.Up -> {
                                    onPopupTrigger(view.id)
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Popup.Keyboard -> {
                        setOnLongClickListener { view ->
                            view as KeyView
                            onPopupAction(PopupAction.ShowKeyboardAction(view.id, it, view.bounds))
                            // do not consume this LongClick gesture
                            false
                        }
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        swipeEnabled = true
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            when (event.type) {
                                GestureType.Move -> {
                                    onPopupChangeFocus(view.id, event.x, event.y)
                                }
                                GestureType.Up -> {
                                    onPopupTrigger(view.id)
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Popup.AltPreview -> {
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            if (popupOnKeyPress) {
                                when (event.type) {
                                    GestureType.Down -> onPopupAction(
                                        PopupAction.PreviewAction(view.id, it.content, view.bounds)
                                    )
                                    GestureType.Move -> {
                                        val triggered = swipeSymbolDirection.checkY(event.totalY)
                                        val text = if (triggered) it.alternative else it.content
                                        onPopupAction(
                                            PopupAction.PreviewUpdateAction(view.id, text)
                                        )
                                    }
                                    GestureType.Up -> {
                                        onPopupAction(PopupAction.DismissAction(view.id))
                                    }
                                }
                            }
                            // never consume gesture in preview popup
                            oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Popup.Preview -> {
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            if (popupOnKeyPress) {
                                when (event.type) {
                                    GestureType.Down -> onPopupAction(
                                        PopupAction.PreviewAction(view.id, it.content, view.bounds)
                                    )
                                    GestureType.Up -> {
                                        onPopupAction(PopupAction.DismissAction(view.id))
                                    }
                                    else -> {}
                                }
                            }
                            // never consume gesture in preview popup
                            oldOnGestureListener.onGesture(view, event)
                        }
                    }
                }
            }
        }
    }

    private class TouchTarget(val view: KeyView, val hitRect: Rect)
    private data class TouchSample(val x: Float, val y: Float, val time: Long)

    private class TouchContact(
        var target: TouchTarget,
        val downX: Float,
        val downY: Float,
        val downAt: Long,
        val slideThreshold: Float,
        var sliding: Boolean = false,
        var settled: Boolean = false,
        val retargetGuard: TapRetargetGuard = TapRetargetGuard(),
        val tapCells: List<KeyCell> = emptyList(),
        val contactId: Long = 0L,
        val downSequence: Long = 0L,
        val downOrderState: Long? = null
    )

    /** Each pointer keeps its own original touch and deliberate-slide state. */
    private val touchTargets = hashMapOf<Int, TouchContact>()
    private val slideTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private fun beginTouch(event: MotionEvent, index: Int, target: TouchTarget): TouchContact =
        TouchContact(target, event.getX(index), event.getY(index), event.eventTime,
            maxOf(slideTouchSlop * 1.5f, minOf(target.hitRect.width(), target.hitRect.height()) * 0.45f),
            tapCells = if (this is TextKeyboard && (prefs.keyboard.pinyinTouchCorrection.getValue() ||
                prefs.keyboard.pinyinTouchAlternatives.getValue() || typingTestEditorEligible()))
                alphabetCells() else emptyList(),
            contactId = contactIds.incrementAndGet(), downSequence = touchDownSequence++,
            downOrderState = if (downOrderForGesture && isDownOrderLetter(target.view))
                (this as TextKeyboard).downOrderStateGeneration else null).also {
                    touchTrace.contact(event.getPointerId(index), it.contactId, it.downSequence)
                }

    private fun isDownOrderLetter(key: KeyView): Boolean {
        if ((this as? TextKeyboard)?.acceptsDownOrderedLetterTaps != true) return false
        return key.id in alphabetKeyIds
    }

    private fun isOrdinaryDownOrderTap(contact: TouchContact): Boolean {
        val key = contact.target.view
        if (contact.downOrderState == null ||
            contact.downOrderState != (this as? TextKeyboard)?.downOrderStateGeneration ||
            contact.sliding || contact.settled || !canRetargetPendingTap(key) ||
            !key.isEnabled || !isDownOrderLetter(key)) return false
        // Gesture state belongs to the KeyView. Keep simultaneous contacts on
        // that same key on their existing path rather than ending another hold.
        return touchTargets.values.count { it.target.view === key } == 1
    }

    private fun downOrderGeometryUnchanged(contact: TouchContact): Boolean {
        val key = contact.target.view
        val row = key.parent as? View ?: return false
        val bounds = contact.target.hitRect
        return row.parent === this && row.visibility == View.VISIBLE && key.visibility == View.VISIBLE &&
            bounds.left == key.left + row.left && bounds.top == key.top + row.top &&
            bounds.right == key.right + row.left && bounds.bottom == key.bottom + row.top
    }

    private fun releaseOlderOrdinaryTaps(event: MotionEvent, releasingIndex: Int) {
        if (!downOrderForGesture || downOrderGestureCancelled) return
        val gestureGeneration = touchGestureGeneration
        val pointerIds = TapDownOrder.olderPointersToRelease(event.getPointerId(releasingIndex),
            touchTargets.map { (pid, contact) ->
                val index = event.findPointerIndex(pid)
                TapDownOrder.Contact(pid, contact.downSequence,
                    index >= 0 && isOrdinaryDownOrderTap(contact) &&
                        withinSlideTolerance(event, index, contact.target))
            })
        for (pid in pointerIds) {
            if (gestureGeneration != touchGestureGeneration) return
            if (!ensureDownOrderContext()) return
            val contact = touchTargets[pid] ?: continue
            val index = event.findPointerIndex(pid)
            if (index < 0 || !isOrdinaryDownOrderTap(contact)) continue
            // Finalize before callbacks: a layout/editor change or its later
            // physical UP must never dispatch this contact a second time.
            touchTargets.remove(pid)
            if (contact.tapCells.isNotEmpty()) physicalUpsPending[pid] = contact.contactId
            pressEffectLayer?.onRelease(pid)
            motionReleasePointer(pid)
            releaseTouch(event, index, contact, phantom = true)
            // The child's UP clears timers before its click callback. Never
            // cancel that view afterwards: the callback may start a fresh DOWN.
            if (gestureGeneration != touchGestureGeneration) return
            if (!ensureDownOrderContext()) return
        }
    }

    private fun ensureDownOrderContext(): Boolean {
        if (!downOrderForGesture) return true
        if (downOrderGestureCancelled) return false
        if (downOrderEditorForGesture !== downOrderEditorInfo() ||
            !TouchDiagnosticPolicy.allows(downOrderEditorForGesture) ||
            touchTargets.values.any { !downOrderGeometryUnchanged(it) }) {
            cancelDownOrderGesture()
            return false
        }
        return true
    }

    private fun alphabetCells(): List<KeyCell> = keyRows.flatMap { row ->
        if (row.visibility != View.VISIBLE) emptyList() else row.children.filterIsInstance<KeyView>()
            .filter { it.visibility == View.VISIBLE }.mapNotNull { key ->
                val text = (key.def as? KeyDef.Appearance.Text)?.displayText?.lowercase()
                text?.singleOrNull()?.takeIf { it in 'a'..'z' }?.let { letter ->
                    KeyCell(letter, (key.left + row.left).toFloat(), (key.top + row.top).toFloat(),
                        (key.right + row.left).toFloat(), (key.bottom + row.top).toFloat())
                }
            }.toList()
    }

    private fun releaseAllTouchTargets() {
        touchTargets.forEach {
            val keyView = it.value.target.view
            keyView.cancelGestures()
            onPopupAction(PopupAction.DismissAction(keyView.id))
        }
        touchTargets.clear()
        popupSelectionKeys.clear()
    }

    private fun findTouchTarget(event: MotionEvent, pointerIndex: Int): TouchTarget? =
        findTouchTarget(event.getX(pointerIndex), event.getY(pointerIndex))

    private fun findTouchTarget(x: Float, y: Float): TouchTarget? {
        // View.getHitRect includes the view's animation matrix. Keycap shrink,
        // bounce and tilt must never shrink, move or overlap the input cells.
        val row = keyRows.find {
            it.visibility == View.VISIBLE && x >= it.left && x < it.right &&
                y >= it.top && y < it.bottom
        } ?: return null
        val x1 = x - row.left
        val y1 = y - row.top
        val key = row.children.filterIsInstance<KeyView>().find {
            it.visibility == View.VISIBLE && x1 >= it.left && x1 < it.right &&
                y1 >= it.top && y1 < it.bottom
        } ?: return null
        return TouchTarget(key, Rect(key.left + row.left, key.top + row.top,
            key.right + row.left, key.bottom + row.top))
    }

    private fun dispatchMotionEventToTarget(
        event: MotionEvent,
        action: Int,
        pointerIndex: Int,
        target: TouchTarget,
        sample: TouchSample? = null,
        contact: TouchContact? = touchTargets[event.getPointerId(pointerIndex)],
        earlyConfirmation: Boolean = false
    ) {
        val childX = (sample?.x ?: event.getX(pointerIndex)) - target.hitRect.left
        val childY = (sample?.y ?: event.getY(pointerIndex)) - target.hitRect.top
        val e = MotionEvent.obtain(
            event.downTime, sample?.time ?: event.eventTime, action,
            childX, childY, event.getPressure(pointerIndex), event.getSize(pointerIndex),
            event.metaState, event.xPrecision, event.yPrecision,
            event.deviceId, event.edgeFlags
        )
        val previousEvidence = currentPinyinTapEvidence
        val rawLetter = (target.view.def as? KeyDef.Appearance.Text)?.displayText?.lowercase()?.singleOrNull()
        val physicalUp = !earlyConfirmation && pointerIndex == event.actionIndex &&
            (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_POINTER_UP)
        currentPinyinTapEvidence = if (action == MotionEvent.ACTION_UP && contact != null &&
            !contact.sliding && !contact.settled && contact.tapCells.isNotEmpty() &&
            rawLetter != null && rawLetter in 'a'..'z') {
            PinyinTapEvidence(TapEvidence(rawLetter, contact.downX, contact.downY,
                resources.displayMetrics.density), contact.tapCells,
                diagnosticTraceId = touchTrace.currentTraceId,
                diagnosticToken = touchTrace.currentRecordingToken,
                pointerId = event.getPointerId(pointerIndex), downTime = contact.downAt,
                contactId = contact.contactId, downSequence = contact.downSequence,
                dispatchSequence = touchDispatchSequence++, dispatchTime = SystemClock.uptimeMillis(),
                physicalUpTime = event.eventTime.takeIf { physicalUp },
                physicalUpX = event.getX(pointerIndex).takeIf { physicalUp },
                physicalUpY = event.getY(pointerIndex).takeIf { physicalUp })
        } else null
        try {
            touchTrace.dispatch(event.getPointerId(pointerIndex), target.view.id, currentPinyinTapEvidence) {
                target.view.dispatchTouchEvent(e)
            }
        } finally { currentPinyinTapEvidence = previousEvidence; e.recycle() }
    }

    private val effectKeyBounds = Rect()
    private val effectHostLocation = IntArray(2)

    private fun withinSlideTolerance(event: MotionEvent, index: Int, target: TouchTarget): Boolean =
        withinSlideTolerance(event.getX(index), event.getY(index), target)

    private fun withinSlideTolerance(x: Float, y: Float, target: TouchTarget): Boolean {
        val margin = minOf(dp(4f).toFloat(), target.hitRect.width() * 0.15f)
        return x >= target.hitRect.left - margin && x < target.hitRect.right + margin &&
            y >= target.hitRect.top - margin && y < target.hitRect.bottom + margin
    }

    private fun insideTarget(x: Float, y: Float, target: TouchTarget, inset: Float): Boolean =
        x >= target.hitRect.left + inset && x < target.hitRect.right - inset &&
            y >= target.hitRect.top + inset && y < target.hitRect.bottom - inset

    private fun mayStartDeliberateSlide(x: Float, y: Float, contact: TouchContact, next: TouchTarget): Boolean {
        val dx = x - contact.downX
        val dy = y - contact.downY
        if (dx * dx + dy * dy < contact.slideThreshold * contact.slideThreshold) return false
        // Require entry into the neighbour's interior, not just a crossed border.
        // This remains proportional after editing individual key widths.
        val inset = minOf(dp(8f).toFloat(), minOf(next.hitRect.width(), next.hitRect.height()) * 0.22f)
        return insideTarget(x, y, next, inset)
    }

    private fun maySettleBoundaryTap(
        x: Float, y: Float, sampleTime: Long, contact: TouchContact, next: TouchTarget
    ): Boolean {
        // The experiment changes only automatic boundary settling. Intentional
        // slide selection and the original MOVE/UP sampling remain unchanged.
        if (!boundarySettlingForGesture) return false
        if (contact.settled || sampleTime - contact.downAt !in
            TapRetargetGuard.SETTLE_MIN_HOLD_MS..TapRetargetGuard.SETTLE_WINDOW_MS) return false
        val original = contact.target.hitRect
        val edgeBand = minOf(dp(6f).toFloat(), minOf(original.width(), original.height()) * 0.20f)
        // The original contact must be close to this neighbour, not merely close
        // to some unrelated edge. This works for every row and custom key width.
        val dx = maxOf(next.hitRect.left - contact.downX, contact.downX - next.hitRect.right, 0f)
        val dy = maxOf(next.hitRect.top - contact.downY, contact.downY - next.hitRect.bottom, 0f)
        if (dx * dx + dy * dy > edgeBand * edgeBand) return false
        val inset = minOf(dp(4f).toFloat(), minOf(next.hitRect.width(), next.hitRect.height()) * 0.12f)
        return insideTarget(x, y, next, inset)
    }

    private fun moveGuardedTap(event: MotionEvent, index: Int, contact: TouchContact) {
        // Neighbour confirmations persist across batched MOVE samples.
        // Advance a logical contact first, then dispatch at most one real DOWN
        // so hidden intermediate samples cannot produce extra feedback.
        val selection = TouchContact(contact.target, contact.downX, contact.downY,
            contact.downAt, contact.slideThreshold, contact.sliding, contact.settled,
            contact.retargetGuard, contact.tapCells)
        var handover: TouchSample? = null
        var pendingHoldCancelled = false

        fun consumeSample(x: Float, y: Float, time: Long): Boolean {
            val target = selection.target
            val next = findTouchTarget(x, y)
            if (next?.view !== target.view && withinSlideTolerance(x, y, target)) {
                selection.retargetGuard.reset()
                return true
            }
            if (next != null && next.view !== target.view && canSlideSelect(next.view)) {
                val maySettle = maySettleBoundaryTap(x, y, time, selection, next)
                val maySlide = mayStartDeliberateSlide(x, y, selection, next)
                if (maySettle || maySlide) pendingHoldCancelled = true
                val decision = if (selection.sliding) TapRetargetGuard.Decision.Slide
                    else selection.retargetGuard.observe(next.view.id, time, maySettle, maySlide)
                if (decision != TapRetargetGuard.Decision.Keep) {
                    touchTrace.decision(time, event.getPointerId(index), target.view.id, next.view.id,
                        decision == TapRetargetGuard.Decision.Slide)
                    selection.target = next
                    selection.sliding = decision == TapRetargetGuard.Decision.Slide
                    if (decision == TapRetargetGuard.Decision.Settle) selection.settled = true
                    selection.retargetGuard.reset()
                    handover = TouchSample(x, y, time)
                    pendingHoldCancelled = false
                }
                // Pending neighbour samples stay away from the old child: its
                // touchMovedOutside flag would permanently cancel the tap.
                return true
            }
            selection.retargetGuard.reset()
            return false
        }

        for (history in 0 until event.historySize) {
            consumeSample(event.getHistoricalX(index, history), event.getHistoricalY(index, history),
                event.getHistoricalEventTime(history))
        }
        val consumed = consumeSample(event.getX(index), event.getY(index), event.eventTime)
        handover?.let { sample ->
            // Use the point that actually selected the final key. A later,
            // unconfirmed excursion may already be over another neighbour.
            switchSlideTarget(event, index, contact, selection.target, selection.sliding,
                sample = sample, resetRetargetGuard = false, recordDecision = false)
        }
        contact.sliding = selection.sliding
        contact.settled = selection.settled
        if (pendingHoldCancelled) contact.target.view.cancelPendingHoldActions()
        if (!consumed) dispatchMotionEventToTarget(event, MotionEvent.ACTION_MOVE, index, contact.target)
    }

    private fun switchSlideTarget(
        event: MotionEvent, index: Int, contact: TouchContact, next: TouchTarget,
        deliberateSlide: Boolean = true,
        sample: TouchSample? = null,
        resetRetargetGuard: Boolean = true,
        recordDecision: Boolean = true
    ) {
        val old = contact.target
        if (recordDecision) touchTrace.decision(sample?.time ?: event.eventTime,
            event.getPointerId(index), old.view.id, next.view.id, deliberateSlide)
        old.view.cancelGestures()
        onPopupAction(PopupAction.DismissAction(old.view.id))
        contact.target = next
        contact.sliding = deliberateSlide
        if (!deliberateSlide) contact.settled = true
        if (resetRetargetGuard) contact.retargetGuard.reset()
        illuminateKey(event, index, next, sample)
        dispatchMotionEventToTarget(event, MotionEvent.ACTION_DOWN, index, next, sample)
    }

    private fun releaseTouch(event: MotionEvent, index: Int, contact: TouchContact, phantom: Boolean = false) {
        val target = contact.target
        if (phantom) touchTrace.released(event.getPointerId(index), target.view.id,
            reason = "down_order", time = event.eventTime)
        // UP never starts a slide: lift-off drift cannot replace a fast tap with
        // its neighbour. Only an established MOVE gesture follows a final UP.
        if (slideSelectionEnabled && canRetargetPendingTap(target.view) &&
            !withinSlideTolerance(event, index, target)) {
            val next = findTouchTarget(event, index)
            if (next != null && canSlideSelect(next.view)) {
                if (contact.sliding && next.view !== target.view) switchSlideTarget(event, index, contact, next)
                dispatchMotionEventToTarget(event, MotionEvent.ACTION_UP, index, contact.target,
                    contact = contact, earlyConfirmation = phantom)
            } else {
                target.view.cancelGestures()
                onPopupAction(PopupAction.DismissAction(target.view.id))
                touchTrace.released(event.getPointerId(index), target.view.id, cancelled = true)
                return
            }
        } else dispatchMotionEventToTarget(event, MotionEvent.ACTION_UP, index, target,
            contact = contact, earlyConfirmation = phantom)
        if (!phantom) touchTrace.released(event.getPointerId(index), contact.target.view.id,
            reason = "physical_up", time = event.eventTime)
    }

    private val motionViews = android.util.SparseArray<View>()
    private val motionRandom = java.util.Random()

    private fun motionAnimationsAllowed(): Boolean =
        !prefs.advanced.disableAnimation.getValue() &&
            (!ThemeManager.prefs.effectsFollowSystemAnimation.getValue() ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled())

    private fun resetMotion(view: View) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.rotation = 0f
        view.translationY = 0f
        (view as? KeyView)?.resetPressDepth()
    }

    private fun motionPress(pointerId: Int, view: View?) {
        if (keyMotion == ThemePrefs.KeyMotionEffect.Off) return
        if (!motionAnimationsAllowed()) {
            motionReleaseAll()
            view?.let(::resetMotion)
            return
        }
        val old = motionViews.get(pointerId)
        if (old === view) return
        motionViews.remove(pointerId)
        if (old != null && (keyMotion != ThemePrefs.KeyMotionEffect.Press || !motionViewIsHeld(old))) motionRelease(old)
        if (view == null) {
            return
        }
        motionViews.put(pointerId, view)
        if (keyMotion == ThemePrefs.KeyMotionEffect.Press) {
            (view as? KeyView)?.setDepthPressed(true)
            return
        }
        val a = view.animate().setDuration(70L).setInterpolator(android.view.animation.DecelerateInterpolator())
        when (keyMotion) {
            ThemePrefs.KeyMotionEffect.Shrink -> a.scaleX(0.86f).scaleY(0.86f)
            ThemePrefs.KeyMotionEffect.Bounce -> a.scaleX(0.9f).scaleY(0.9f)
            ThemePrefs.KeyMotionEffect.Tilt ->
                a.rotation(if (motionRandom.nextBoolean()) 7f else -7f).scaleX(0.95f).scaleY(0.95f)
            ThemePrefs.KeyMotionEffect.Press -> {}
            ThemePrefs.KeyMotionEffect.Off -> {}
        }
        a.start()
    }

    private fun motionRelease(view: View) {
        if (!motionAnimationsAllowed()) {
            resetMotion(view)
            return
        }
        if (keyMotion == ThemePrefs.KeyMotionEffect.Press) {
            (view as? KeyView)?.setDepthPressed(false)
            return
        }
        val a = view.animate().scaleX(1f).scaleY(1f).rotation(0f).translationY(0f)
        when (keyMotion) {
            ThemePrefs.KeyMotionEffect.Bounce ->
                a.setDuration(320L).setInterpolator(android.view.animation.OvershootInterpolator(3.2f))
            ThemePrefs.KeyMotionEffect.Tilt ->
                a.setDuration(260L).setInterpolator(android.view.animation.OvershootInterpolator(2f))
            else -> a.setDuration(140L).setInterpolator(android.view.animation.DecelerateInterpolator())
        }
        a.start()
    }

    private fun motionReleasePointer(pointerId: Int) {
        val view = motionViews.get(pointerId) ?: return
        motionViews.remove(pointerId)
        if (keyMotion != ThemePrefs.KeyMotionEffect.Press || !motionViewIsHeld(view)) motionRelease(view)
    }

    private fun motionViewIsHeld(view: View): Boolean {
        for (i in 0 until motionViews.size()) if (motionViews.valueAt(i) === view) return true
        return false
    }

    private fun motionReleaseAll() {
        for (i in 0 until motionViews.size()) motionRelease(motionViews.valueAt(i))
        motionViews.clear()
    }

    private fun illuminateKey(event: MotionEvent, index: Int, target: TouchTarget?, sample: TouchSample? = null) {
        motionPress(event.getPointerId(index), target?.view)
        pressEffectLayer?.let { effect ->
            val bounds = target?.view?.let { key ->
                getLocationInWindow(effectHostLocation)
                effectKeyBounds.set(key.bounds)
                effectKeyBounds.offset(-effectHostLocation[0], -effectHostLocation[1])
                effectKeyBounds.inset(key.hMargin, key.vMargin)
                effectKeyBounds
            }
            effect.onPress(sample?.x ?: event.getX(index), sample?.y ?: event.getY(index), bounds,
                target?.view?.let { key ->
                    key.def.variant == KeyDef.Appearance.Variant.Normal &&
                        (key.def as? KeyDef.Appearance.Text)?.displayText?.isNotBlank() == true
                } == true, target?.view?.id ?: View.NO_ID,
                pointerId = event.getPointerId(index))
        }
    }

    private fun diagnosticLayout(): JSONObject {
        val keys = JSONArray()
        for (row in keyRows) if (row.visibility == View.VISIBLE) {
            for (key in row.children.filterIsInstance<KeyView>()) if (key.visibility == View.VISIBLE) {
                keys.put(JSONObject().put("index", keys.length()).put("id", key.id)
                    .put("label", (key.def as? KeyDef.Appearance.Text)?.displayText
                        ?: key.def.javaClass.simpleName)
                    .put("rect", JSONArray().put(key.left + row.left).put(key.top + row.top)
                        .put(key.right + row.left).put(key.bottom + row.top)))
            }
        }
        val keyboardPrefs = prefs.keyboard
        val settings = JSONObject()
            .put("number_row", this is TextKeyboard && keyRows.size == TextKeyboard.Layout.size + 1)
            .put("portrait_number_row", ThemeManager.prefs.portraitNumberRow.getValue())
            .put("key_width_overrides", ThemeManager.prefs.keyWidthOverrides.getValue())
            .put("expand_keypress_area", expandKeypressArea)
            .put("long_press_delay", keyboardPrefs.longPressDelay.getValue())
            .put("popup_on_key_press", popupOnKeyPress)
            .put("space_swipe_move_cursor", spaceSwipeMoveCursor.getValue())
            .put("space_long_press_behavior", spaceLongPressBehavior.getValue().name)
            .put("swipe_symbol_direction", swipeSymbolDirection.name)
            .put("vivo_keypress_workaround", vivoKeypressWorkaround)
            .put("slide_selection_enabled", slideSelectionEnabled)
            .put("guard_tap_retargeting", guardTapRetargeting)
            .put("touch_slop_px", slideTouchSlop)
            .put("settle_window_ms", TapRetargetGuard.SETTLE_WINDOW_MS)
            .put("settle_min_hold_ms", TapRetargetGuard.SETTLE_MIN_HOLD_MS)
            .put("settle_dwell_ms", TapRetargetGuard.SETTLE_DWELL_MS)
            .put("slide_dwell_ms", TapRetargetGuard.SLIDE_DWELL_MS)
            .put("retarget_min_samples", TapRetargetGuard.MIN_SAMPLES)
            .put("keep_letters_uppercase", keyboardPrefs.keepLettersUppercase.getValue())
            .put("show_lang_switch_key", keyboardPrefs.showLangSwitchKey.getValue())
            .put("pinyin_touch_correction", keyboardPrefs.pinyinTouchCorrection.getValue())
            .put("pinyin_touch_alternatives", keyboardPrefs.pinyinTouchAlternatives.getValue())
            .put("pinyin_down_order", keyboardPrefs.pinyinDownOrder.getValue())
        return JSONObject().put("name", javaClass.simpleName.removeSuffix("Keyboard"))
            .put("width", width).put("height", height).put("density", resources.displayMetrics.density)
            .put("orientation", resources.configuration.orientation).put("keys", keys)
            .put("settings", settings).put("state", diagnosticState())
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            physicalUpsPending.clear()
            boundarySettlingForGesture = prefs.keyboard.touchBoundarySettling.getValue()
            downOrderEditorForGesture = if (prefs.keyboard.pinyinDownOrder.getValue()) downOrderEditorInfo() else null
            downOrderForGesture = this is TextKeyboard && prefs.keyboard.pinyinDownOrder.getValue() &&
                TouchDiagnosticPolicy.allows(downOrderEditorForGesture) && acceptsDownOrderedLetterTaps
            downOrderGestureCancelled = false
            touchDownSequence = 0L
            touchDispatchSequence = 0L
            touchGestureGeneration++
        }
        touchTrace.beforeEvent(ev, boundarySettlingForGesture, ::diagnosticLayout) { x, y ->
            findTouchTarget(x, y)?.view?.id
        }
        if (!downOrderGestureCancelled && (pressEffectLayer != null || keyMotion != ThemePrefs.KeyMotionEffect.Off)) {
            val action = ev.actionMasked
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                val i = ev.actionIndex
                val target = findTouchTarget(ev, i)
                illuminateKey(ev, i, target)
            }
        }
        val handled = super.dispatchTouchEvent(ev)
        // Final-UP retargeting can illuminate a new key inside onTouchEvent.
        // Release afterwards so it cannot leave idle breathing in the held state.
        if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            physicalUpsPending.clear()
            pressEffectLayer?.onRelease()
            motionReleaseAll()
        } else if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            val pointerId = ev.getPointerId(ev.actionIndex)
            physicalUpsPending.remove(pointerId)?.let { contactId ->
                TypingTestSession.completeTouchEvidence(contactId, ev.eventTime,
                    ev.getX(ev.actionIndex), ev.getY(ev.actionIndex))
            }
            pressEffectLayer?.onRelease(pointerId)
            motionReleasePointer(pointerId)
        }
        touchTrace.afterEvent(ev)
        return handled
    }

    override fun dispatchDraw(canvas: Canvas) {
        val effect = pressEffectLayer ?: return super.dispatchDraw(canvas)
        effect.drawUnder(canvas)
        effect.drawOver(canvas)
        // Every travelling light layer stays below key faces and their legends.
        super.dispatchDraw(canvas)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // intercept ACTION_DOWN and all following events will go to parent's onTouchEvent
        return if ((vivoKeypressWorkaround || slideSelectionEnabled) && ev.actionMasked == MotionEvent.ACTION_DOWN) true
        else super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (vivoKeypressWorkaround || slideSelectionEnabled) {
            if (event.actionMasked != MotionEvent.ACTION_DOWN) ensureDownOrderContext()
            if (downOrderGestureCancelled && event.actionMasked != MotionEvent.ACTION_DOWN) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    releaseAllTouchTargets()
                    val pid = event.getPointerId(0)
                    val target = findTouchTarget(event, 0) ?: return false
                    touchTargets[pid] = beginTouch(event, 0, target)
                    dispatchMotionEventToTarget(event, MotionEvent.ACTION_DOWN, 0, target)
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = event.actionIndex
                    val pid = event.getPointerId(i)
                    val target = findTouchTarget(event, i) ?: return true
                    // Return has one long-press state. An extra finger must not reset it
                    // and turn the final release into a Send/Search/Done action.
                    if (target.view.id == R.id.button_return &&
                        touchTargets.values.any { it.target.view === target.view }) return true
                    touchTargets[pid] = beginTouch(event, i, target)
                    dispatchMotionEventToTarget(event, MotionEvent.ACTION_DOWN, i, target)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.pointerCount) {
                        val pid = event.getPointerId(i)
                        val contact = touchTargets[pid] ?: continue
                        val target = contact.target
                        if (slideSelectionEnabled && canRetargetPendingTap(target.view)) {
                            if (guardTapRetargeting) {
                                moveGuardedTap(event, i, contact)
                                continue
                            }
                            val next = findTouchTarget(event, i)
                            val decision = when {
                                contact.sliding -> TapRetargetGuard.Decision.Slide
                                next != null && mayStartDeliberateSlide(event.getX(i), event.getY(i), contact, next) ->
                                    TapRetargetGuard.Decision.Slide
                                else -> TapRetargetGuard.Decision.Keep
                            }
                            // Do not send a tiny excursion to the child: its gesture
                            // detector would permanently mark this tap as cancelled.
                            if (next?.view !== target.view && withinSlideTolerance(event, i, target)) continue
                            if (next != null && next.view !== target.view && canSlideSelect(next.view)) {
                                // Suppress an unintentional excursion before forwarding to
                                // the child, which otherwise permanently cancels the tap.
                                if (decision == TapRetargetGuard.Decision.Keep) continue
                                // Cancel without an UP: intermediate letters must never be committed.
                                switchSlideTarget(event, i, contact, next,
                                    deliberateSlide = decision == TapRetargetGuard.Decision.Slide)
                                continue
                            }
                        }
                        dispatchMotionEventToTarget(event, MotionEvent.ACTION_MOVE, i, target)
                    }
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val i = event.actionIndex
                    val pid = event.getPointerId(i)
                    val target = touchTargets[pid] ?: return true
                    releaseOlderOrdinaryTaps(event, i)
                    if (touchTargets[pid] !== target) return true
                    releaseTouch(event, i, target)
                    touchTargets.remove(pid)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val pid = event.getPointerId(0)
                    val target = touchTargets[pid]
                    if (target == null) {
                        releaseAllTouchTargets()
                        return true
                    }
                    releaseOlderOrdinaryTaps(event, 0)
                    if (touchTargets[pid] !== target) return true
                    releaseTouch(event, 0, target)
                    touchTargets.remove(pid)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (downOrderForGesture) downOrderGestureCancelled = true
                    touchTargets.forEach { (pid, contact) ->
                        touchTrace.released(pid, contact.target.view.id, cancelled = true)
                    }
                    releaseAllTouchTargets()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    @CallSuper
    protected open fun onAction(
        action: KeyAction,
        source: KeyActionListener.Source = KeyActionListener.Source.Keyboard
    ) {
        touchTrace.action(action, source)
        keyActionListener?.onKeyAction(action, source)
    }

    @CallSuper
    protected open fun onPopupAction(action: PopupAction) {
        when (action) {
            is PopupAction.ShowKeyboardAction -> popupSelectionKeys.add(action.viewId)
            is PopupAction.ShowMenuAction -> popupSelectionKeys.add(action.viewId)
            is PopupAction.DismissAction -> popupSelectionKeys.remove(action.viewId)
            else -> {}
        }
        popupActionListener?.onPopupAction(action)
    }

    private fun onPopupChangeFocus(viewId: Int, x: Float, y: Float): Boolean {
        val changeFocusAction = PopupAction.ChangeFocusAction(viewId, x, y)
        popupActionListener?.onPopupAction(changeFocusAction)
        return changeFocusAction.outResult
    }

    private fun onPopupTrigger(viewId: Int): Boolean {
        val triggerAction = PopupAction.TriggerAction(viewId)
        // ask popup keyboard whether there's a pending KeyAction
        onPopupAction(triggerAction)
        val action = triggerAction.outAction ?: return false
        onAction(action, KeyActionListener.Source.Popup)
        onPopupAction(PopupAction.DismissAction(viewId))
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pressEffectLayer?.setActive(true)
    }

    override fun onDetachedFromWindow() {
        if (downOrderForGesture) cancelDownOrderGesture()
        touchTrace.discard()
        pressEffectLayer?.setActive(false)
        if (keyMotion == ThemePrefs.KeyMotionEffect.Press) {
            // Reattaching the same keyboard must not retain an old held pointer.
            motionViews.clear()
            for (i in depthKeys.indices) depthKeys[i].resetPressDepth()
        }
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        pressEffectLayer?.syncVisibility()
        resetHiddenFloat(visibility != View.VISIBLE)
        if (visibility != View.VISIBLE) touchTrace.discard()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        pressEffectLayer?.syncVisibility()
        resetHiddenFloat(visibility != View.VISIBLE)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        pressEffectLayer?.syncVisibility()
        resetHiddenFloat(!isVisible)
        if (!isVisible) touchTrace.discard()
    }

    /** IME windows can hide without detaching their cached keyboard views. */
    private fun resetHiddenFloat(hidden: Boolean) {
        // Visibility callbacks may arrive during View construction, before the
        // keyboard's children and gesture storage have been initialised.
        if (!motionLifecycleReady) return
        if (!hidden && isShown && windowVisibility == View.VISIBLE) return
        if (downOrderForGesture) cancelDownOrderGesture()
        if (keyMotion != ThemePrefs.KeyMotionEffect.Press) return
        releaseAllTouchTargets() // Cancel; hiding must never commit a pending letter.
        motionViews.clear()
        for (i in depthKeys.indices) depthKeys[i].resetPressDepth()
    }

    open fun onAttach() {
        if (downOrderForGesture) cancelDownOrderGesture()
        pressEffectLayer?.setActive(true)
    }

    open fun onReturnDrawableUpdate(@DrawableRes returnDrawable: Int) {
        // do nothing by default
    }

    open fun onPunctuationUpdate(mapping: Map<String, String>) {
        // do nothing by default
    }

    open fun onInputMethodUpdate(ime: InputMethodEntry) {
        if (downOrderForGesture) cancelDownOrderGesture()
    }

    private fun cancelDownOrderGesture() {
        downOrderGestureCancelled = true
        physicalUpsPending.clear()
        touchTargets.forEach { (pid, contact) ->
            touchTrace.released(pid, contact.target.view.id, cancelled = true)
        }
        releaseAllTouchTargets()
        pressEffectLayer?.onRelease()
        motionReleaseAll()
    }

    open fun onDetach() {
        if (downOrderForGesture) cancelDownOrderGesture()
        releaseAllTouchTargets()
        pressEffectLayer?.setActive(false)
        motionReleaseAll()
        for (i in depthKeys.indices) depthKeys[i].resetPressDepth()
    }

    private companion object {
        val contactIds = AtomicLong(0L)
    }

}
