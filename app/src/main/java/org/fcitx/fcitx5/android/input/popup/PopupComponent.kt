/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import java.util.LinkedList

class PopupComponent :
    UniqueComponent<PopupComponent>(), Dependent, ManagedHandler by managedHandler() {

    private val context by manager.context()
    private val theme by manager.theme()
    private val punctuation: PunctuationComponent by manager.must()

    private val showingEntryUi = HashMap<Int, PopupEntryUi>()
    private val freeEntryUi = LinkedList<PopupEntryUi>()

    private val showingContainerUi = HashMap<Int, PopupContainerUi>()

    private val keyBottomMargin by lazy {
        context.dp(ThemeManager.prefs.keyVerticalMargin.getValue())
    }
    private val popupWidth by lazy {
        context.dp(44)
    }
    private val popupHeight by lazy {
        context.dp(100)
    }
    private val popupKeyHeight by lazy {
        context.dp(52)
    }
    private val popupRadius by lazy {
        context.dp(12f)
    }

    private val rootLocation = intArrayOf(0, 0)
    private val rootBounds: Rect = Rect()

    val root by lazy {
        context.frameLayout {
            // we want (0, 0) at top left
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            isClickable = false
            isFocusable = false

            // Keep previews attached and measured; reuse them without add/remove layout churn.
            repeat(3) {
                val preview = PopupEntryUi(context, theme, popupKeyHeight, popupRadius)
                preview.root.visibility = View.INVISIBLE
                addView(preview.root, FrameLayout.LayoutParams(popupWidth, popupHeight))
                freeEntryUi.add(preview)
            }

            addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                val (x, y) = rootLocation.also { v.getLocationInWindow(it) }
                val width = right - left
                val height = bottom - top
                rootBounds.set(x, y, x + width, y + height)
            }
        }
    }

    private var lastPreviewColor = -1

    /** Use this key's current colour, or independent colours without consecutive repeats. */
    private fun nextPreviewColor(viewId: Int): Int? {
        val prefs = org.fcitx.fcitx5.android.data.theme.ThemeManager.prefs
        if (!prefs.coloredPreview.getValue()) return null
        val currentPressHasColor = PopupAnimationPolicy.canMatchPressColor(
            prefs.pressEffect.getValue(),
            AppPrefs.getInstance().advanced.disableAnimation.getValue(),
            prefs.effectsFollowSystemAnimation.getValue(),
            PopupAnimationPolicy.systemAnimationsEnabled()
        )
        if (prefs.previewSameColor.getValue() && currentPressHasColor) {
            // Bind to this key, including overlapping fingers and delayed preview callbacks.
            org.fcitx.fcitx5.android.input.keyboard.PressEffect.colorForKey(viewId)?.let { return it }
        }
        val colors = org.fcitx.fcitx5.android.input.keyboard.PressEffect.CYBERPUNK
        var i = kotlin.random.Random.nextInt(colors.size)
        if (i == lastPreviewColor) i = (i + 1 + kotlin.random.Random.nextInt(colors.size - 1)) % colors.size
        lastPreviewColor = i
        return colors[i]
    }

    private fun showPopup(viewId: Int, content: String, bounds: Rect) {
        val container = root
        val popup = (showingEntryUi[viewId] ?: freeEntryUi.poll()
            ?: PopupEntryUi(context, theme, popupKeyHeight, popupRadius)).apply {
            setText(content)
            tint(nextPreviewColor(viewId))
        }
        // make sure that popup.root does not have parent view before adding it under root container
        // it's wired that on some devices it would have a parent view despite it was newly created
        // or just polled from freeEntryUi
        if (popup.root.parent == null) {
            container.addView(popup.root, FrameLayout.LayoutParams(popupWidth, popupHeight))
        } else if (popup.root.parent !== container) {
            (popup.root.parent as? ViewGroup)?.removeView(popup.root)
            container.addView(popup.root, FrameLayout.LayoutParams(popupWidth, popupHeight))
        }
        // Align popup bottom with key border bottom [^1]. Translations avoid a layout pass.
        popup.root.translationY = (bounds.bottom - popupHeight - keyBottomMargin - rootBounds.top).toFloat()
        popup.root.translationX = ((bounds.left + bounds.right - popupWidth) / 2 - rootBounds.left)
            .coerceIn(0, (container.width - popupWidth).coerceAtLeast(0)).toFloat()
        showingEntryUi[viewId] = popup
        popup.previewLifecycle.show()
    }

    private fun updatePopup(viewId: Int, content: String) {
        showingEntryUi[viewId]?.setText(content)
    }

    private fun showKeyboard(viewId: Int, keyboard: KeyDef.Popup.Keyboard, bounds: Rect) {
        var keys: Array<String>
        var labels: Array<String>
        when (keyboard) {
            is KeyDef.Popup.Keyboard.Preset -> {
                val preset = PopupPreset[keyboard.label] ?: return
                keys = preset
                labels = if (keyboard.transformPunctuation && punctuation.enabled) {
                    Array(keys.size) { punctuation.transform(keys[it]) }
                } else keys
            }
            is KeyDef.Popup.Keyboard.Explicit -> {
                keys = keyboard.items
                labels = keyboard.items
            }
        }
        // clear popup preview text         OR create empty popup preview
        showingEntryUi[viewId]?.setText("") ?: showPopup(viewId, "", bounds)
        val keyboardUi = PopupKeyboardUi(
            context,
            theme,
            rootBounds,
            bounds,
            { dismissPopup(viewId) },
            popupRadius,
            popupWidth,
            popupKeyHeight,
            // position popup keyboard higher, because of [^1]
            popupHeight + keyBottomMargin,
            keys,
            labels
        )
        showPopupContainer(viewId, keyboardUi)
    }

    private fun showMenu(viewId: Int, menu: KeyDef.Popup.Menu, bounds: Rect) {
        showingEntryUi[viewId]?.let {
            dismissPopupEntry(viewId, it)
        }
        val menuUi = PopupMenuUi(
            context,
            theme,
            rootBounds,
            bounds,
            { dismissPopup(viewId) },
            menu.items,
        )
        showPopupContainer(viewId, menuUi)
    }

    private fun showPopupContainer(viewId: Int, ui: PopupContainerUi) {
        root.apply {
            add(ui.root, lParams {
                leftMargin = ui.triggerBounds.left + ui.offsetX - rootBounds.left
                topMargin = ui.triggerBounds.top + ui.offsetY - rootBounds.top
            })
        }
        showingContainerUi[viewId] = ui
    }

    private fun changeFocus(viewId: Int, x: Float, y: Float): Boolean {
        return showingContainerUi[viewId]?.changeFocus(x, y) ?: false
    }

    private fun triggerFocused(viewId: Int): KeyAction? {
        return showingContainerUi[viewId]?.onTrigger()
    }

    private fun dismissPopup(viewId: Int) {
        dismissPopupContainer(viewId)
        showingEntryUi[viewId]?.also { popup ->
            popup.previewLifecycle.release { dismissPopupEntry(viewId, popup) }
        }
    }

    private fun dismissPopupContainer(viewId: Int) {
        showingContainerUi[viewId]?.also {
            showingContainerUi.remove(viewId)
            root.removeView(it.root)
        }
    }

    private fun dismissPopupEntry(viewId: Int, popup: PopupEntryUi) {
        // A delayed dismissal must never recycle a newer preview for the same key.
        if (showingEntryUi[viewId] !== popup) return
        showingEntryUi.remove(viewId)
        popup.previewLifecycle.hideImmediately()
        freeEntryUi.add(popup)
    }

    fun dismissAll() {
        // too
        showingContainerUi.forEach { (_, container) ->
            root.removeView(container.root)
        }
        showingContainerUi.clear()
        // too too
        showingEntryUi.forEach { (_, entry) ->
            entry.previewLifecycle.hideImmediately()
            freeEntryUi.add(entry)
        }
        showingEntryUi.clear()
    }

    val listener = PopupActionListener { action ->
        with(action) {
            when (this) {
                is PopupAction.ChangeFocusAction -> outResult = changeFocus(viewId, x, y)
                is PopupAction.DismissAction -> dismissPopup(viewId)
                is PopupAction.PreviewAction -> showPopup(viewId, content, bounds)
                is PopupAction.PreviewUpdateAction -> updatePopup(viewId, content)
                is PopupAction.ShowKeyboardAction -> showKeyboard(viewId, keyboard, bounds)
                is PopupAction.ShowMenuAction -> showMenu(viewId, menu, bounds)
                is PopupAction.TriggerAction -> outAction = triggerFocused(viewId)
            }
        }
    }
}
