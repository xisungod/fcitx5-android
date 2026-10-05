/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.annotation.CallSuper
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.InputFeedbacks
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
import kotlin.math.roundToInt

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

    private class TouchContact(
        var target: TouchTarget,
        val downX: Float,
        val downY: Float,
        val slideThreshold: Float,
        var sliding: Boolean = false
    )

    /** Each pointer keeps its own original touch and deliberate-slide state. */
    private val touchTargets = hashMapOf<Int, TouchContact>()
    private val slideTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private fun beginTouch(event: MotionEvent, index: Int, target: TouchTarget): TouchContact =
        TouchContact(target, event.getX(index), event.getY(index),
            maxOf(slideTouchSlop * 1.5f, minOf(target.hitRect.width(), target.hitRect.height()) * 0.45f))

    private fun releaseAllTouchTargets() {
        touchTargets.forEach {
            val keyView = it.value.target.view
            keyView.cancelGestures()
            onPopupAction(PopupAction.DismissAction(keyView.id))
        }
        touchTargets.clear()
        popupSelectionKeys.clear()
    }

    private fun findTouchTarget(event: MotionEvent, pointerIndex: Int): TouchTarget? {
        val x0 = event.getX(pointerIndex).roundToInt()
        val y0 = event.getY(pointerIndex).roundToInt()
        val rowHitRect = Rect()
        val row = keyRows.find {
            it.getHitRect(rowHitRect)
            rowHitRect.contains(x0, y0)
        } ?: return null
        val x1 = x0 - rowHitRect.left
        val y1 = y0 - rowHitRect.top
        val keyHitRect = Rect()
        val key = row.children.filterIsInstance<KeyView>().find {
            it.getHitRect(keyHitRect)
            keyHitRect.contains(x1, y1)
        } ?: return null
        keyHitRect.offset(rowHitRect.left, rowHitRect.top)
        return TouchTarget(key, keyHitRect)
    }

    private fun dispatchMotionEventToTarget(
        event: MotionEvent,
        action: Int,
        pointerIndex: Int,
        target: TouchTarget
    ) {
        val childX = event.getX(pointerIndex) - target.hitRect.left
        val childY = event.getY(pointerIndex) - target.hitRect.top
        val e = MotionEvent.obtain(
            event.downTime, event.eventTime, action,
            childX, childY, event.getPressure(pointerIndex), event.getSize(pointerIndex),
            event.metaState, event.xPrecision, event.yPrecision,
            event.deviceId, event.edgeFlags
        )
        target.view.dispatchTouchEvent(e)
        e.recycle()
    }

    private val effectKeyBounds = Rect()
    private val effectHostLocation = IntArray(2)

    private fun withinSlideTolerance(event: MotionEvent, index: Int, target: TouchTarget): Boolean {
        val margin = minOf(dp(4f).toFloat(), target.hitRect.width() * 0.15f)
        val x = event.getX(index)
        val y = event.getY(index)
        return x >= target.hitRect.left - margin && x < target.hitRect.right + margin &&
            y >= target.hitRect.top - margin && y < target.hitRect.bottom + margin
    }

    private fun startsDeliberateSlide(event: MotionEvent, index: Int, contact: TouchContact, next: TouchTarget): Boolean {
        val dx = event.getX(index) - contact.downX
        val dy = event.getY(index) - contact.downY
        if (dx * dx + dy * dy < contact.slideThreshold * contact.slideThreshold) return false
        // Require entry into the neighbour's interior, not just a crossed border.
        // This remains proportional after editing individual key widths.
        val inset = minOf(dp(8f).toFloat(), minOf(next.hitRect.width(), next.hitRect.height()) * 0.22f)
        return event.getX(index) >= next.hitRect.left + inset && event.getX(index) < next.hitRect.right - inset &&
            event.getY(index) >= next.hitRect.top + inset && event.getY(index) < next.hitRect.bottom - inset
    }

    private fun switchSlideTarget(event: MotionEvent, index: Int, contact: TouchContact, next: TouchTarget) {
        val old = contact.target
        old.view.cancelGestures()
        onPopupAction(PopupAction.DismissAction(old.view.id))
        contact.target = next
        contact.sliding = true
        illuminateKey(event, index, next)
        dispatchMotionEventToTarget(event, MotionEvent.ACTION_DOWN, index, next)
    }

    private fun releaseTouch(event: MotionEvent, index: Int, contact: TouchContact) {
        val target = contact.target
        // UP never starts a slide: lift-off drift cannot replace a fast tap with
        // its neighbour. Only an established MOVE gesture follows a final UP.
        if (slideSelectionEnabled && canRetargetPendingTap(target.view) &&
            !withinSlideTolerance(event, index, target)) {
            val next = findTouchTarget(event, index)
            if (next != null && canSlideSelect(next.view)) {
                if (contact.sliding && next.view !== target.view) switchSlideTarget(event, index, contact, next)
                dispatchMotionEventToTarget(event, MotionEvent.ACTION_UP, index, contact.target)
            } else {
                target.view.cancelGestures()
                onPopupAction(PopupAction.DismissAction(target.view.id))
            }
        } else dispatchMotionEventToTarget(event, MotionEvent.ACTION_UP, index, target)
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

    private fun illuminateKey(event: MotionEvent, index: Int, target: TouchTarget?) {
        motionPress(event.getPointerId(index), target?.view)
        pressEffectLayer?.let { effect ->
            val bounds = target?.view?.let { key ->
                getLocationInWindow(effectHostLocation)
                effectKeyBounds.set(key.bounds)
                effectKeyBounds.offset(-effectHostLocation[0], -effectHostLocation[1])
                effectKeyBounds.inset(key.hMargin, key.vMargin)
                effectKeyBounds
            }
            effect.onPress(event.getX(index), event.getY(index), bounds,
                target?.view?.let { key ->
                    key.def.variant == KeyDef.Appearance.Variant.Normal &&
                        (key.def as? KeyDef.Appearance.Text)?.displayText?.isNotBlank() == true
                } == true, target?.view?.id ?: View.NO_ID,
                pointerId = event.getPointerId(index))
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (pressEffectLayer != null || keyMotion != ThemePrefs.KeyMotionEffect.Off) {
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
            pressEffectLayer?.onRelease()
            motionReleaseAll()
        } else if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            val pointerId = ev.getPointerId(ev.actionIndex)
            pressEffectLayer?.onRelease(pointerId)
            motionReleasePointer(pointerId)
        }
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
                            val next = findTouchTarget(event, i)
                            // Do not send a tiny excursion to the child: its gesture
                            // detector would permanently mark this tap as cancelled.
                            if (next?.view !== target.view && withinSlideTolerance(event, i, target)) continue
                            if (next != null && next.view !== target.view && canSlideSelect(next.view)) {
                                // Suppress an unintentional excursion before forwarding to
                                // the child, which otherwise permanently cancels the tap.
                                if (!contact.sliding && !startsDeliberateSlide(event, i, contact, next)) continue
                                // Cancel without an UP: intermediate letters must never be committed.
                                switchSlideTarget(event, i, contact, next)
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
                    releaseTouch(event, 0, target)
                    touchTargets.remove(pid)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
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
    }

    /** IME windows can hide without detaching their cached keyboard views. */
    private fun resetHiddenFloat(hidden: Boolean) {
        // Visibility callbacks may arrive during View construction, before the
        // keyboard's children and gesture storage have been initialised.
        if (!motionLifecycleReady || keyMotion != ThemePrefs.KeyMotionEffect.Press) return
        if (!hidden && isShown && windowVisibility == View.VISIBLE) return
        releaseAllTouchTargets() // Cancel; hiding must never commit a pending letter.
        motionViews.clear()
        for (i in depthKeys.indices) depthKeys[i].resetPressDepth()
    }

    open fun onAttach() {
        pressEffectLayer?.setActive(true)
    }

    open fun onReturnDrawableUpdate(@DrawableRes returnDrawable: Int) {
        // do nothing by default
    }

    open fun onPunctuationUpdate(mapping: Map<String, String>) {
        // do nothing by default
    }

    open fun onInputMethodUpdate(ime: InputMethodEntry) {
        // do nothing by default
    }

    open fun onDetach() {
        releaseAllTouchTargets()
        pressEffectLayer?.setActive(false)
        motionReleaseAll()
        for (i in depthKeys.indices) depthKeys[i].resetPressDepth()
    }

}
