/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import kotlin.math.roundToInt

/** A temporary touch shield over the keyboard; the owner previews and persists its height. */
internal class KeyboardHeightEditor(
    context: Context,
    theme: Theme,
    private val onPreview: (Int) -> Unit,
    private val onDone: () -> Unit,
    private val onCancel: () -> Unit,
    private val onReset: () -> Unit
) : FrameLayout(context) {

    private var currentHeight = 1
    private var minimumAllowedHeight = 1
    private var maximumAllowedHeight = 1
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var dragStartHeight = 1
    private var dragStartRawY = 0f
    private val accessibilityStep = context.dp(8).coerceAtLeast(1)

    private val dragHandle = object : TextView(context) {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean = handleDrag(event)

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = SeekBar::class.java.name
            info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                minimumAllowedHeight.toFloat(), maximumAllowedHeight.toFloat(), currentHeight.toFloat()
            )
            info.isScrollable = minimumAllowedHeight < maximumAllowedHeight
            if (currentHeight < maximumAllowedHeight) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            }
            if (currentHeight > minimumAllowedHeight) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            }
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            if (this@KeyboardHeightEditor.visibility != View.VISIBLE) return false
            val next = when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> currentHeight.toLong() + accessibilityStep
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> currentHeight.toLong() - accessibilityStep
                else -> return super.performAccessibilityAction(action, arguments)
            }.coerceIn(minimumAllowedHeight.toLong(), maximumAllowedHeight.toLong()).toInt()
            if (next == currentHeight) return false
            clearDrag()
            previewHeight(next)
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
            return true
        }
    }.apply {
        setText(R.string.keyboard_height_drag_hint)
        setTextColor(theme.keyTextColor)
        textSize = 14f
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setPadding(context.dp(12), 0, context.dp(12), 0)
        setBackgroundColor(ColorUtils.setAlphaComponent(theme.backgroundColor, 232))
    }

    init {
        visibility = View.GONE
        isFocusable = true
        isFocusableInTouchMode = true
        // Keep additional fingers in the current gesture instead of activating another control.
        isMotionEventSplittingEnabled = false
        background = GradientDrawable().apply {
            setColor(0x33000000)
            setStroke(context.dp(1).coerceAtLeast(1), ColorUtils.setAlphaComponent(theme.keyTextColor, 120))
        }
        addView(dragHandle, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(48), Gravity.TOP))

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ColorUtils.setAlphaComponent(theme.backgroundColor, 232))
        }
        fun button(label: Int, action: () -> Unit) = Button(context).apply {
            setText(label)
            setTextColor(theme.keyTextColor)
            textSize = 14f
            isAllCaps = false
            minimumWidth = 0
            minimumHeight = 0
            setPadding(context.dp(8), 0, context.dp(8), 0)
            background = RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(theme.keyTextColor, 40)),
                null, GradientDrawable().apply { setColor(Color.WHITE) }
            )
            setOnClickListener {
                clearDrag()
                action()
            }
        }
        for ((label, action) in listOf(
            android.R.string.cancel to onCancel,
            R.string.keyboard_height_reset to onReset,
            R.string.done to onDone
        )) {
            buttons.addView(button(label, action), LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(48), Gravity.BOTTOM))
    }

    fun show(heightPx: Int, minHeightPx: Int, maxHeightPx: Int) {
        clearDrag()
        minimumAllowedHeight = minHeightPx.coerceAtLeast(1)
        maximumAllowedHeight = maxHeightPx.coerceAtLeast(minimumAllowedHeight)
        updateHeight(heightPx)
        visibility = View.VISIBLE
        bringToFront()
        dragHandle.requestFocus()
    }

    /** The owner can report its actual clamped/rounded preview without restarting a drag. */
    fun updateHeight(heightPx: Int) {
        currentHeight = heightPx.coerceIn(minimumAllowedHeight, maximumAllowedHeight)
    }

    fun hide() {
        clearDrag()
        visibility = View.GONE
        clearFocus()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = visibility == View.VISIBLE

    override fun onDetachedFromWindow() {
        clearDrag()
        super.onDetachedFromWindow()
    }

    private fun handleDrag(event: MotionEvent): Boolean {
        if (visibility != View.VISIBLE) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearDrag()
                activePointerId = event.getPointerId(event.actionIndex)
                dragStartRawY = rawY(event, event.actionIndex)
                dragStartHeight = currentHeight
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (activePointerId != MotionEvent.INVALID_POINTER_ID) updateDrag(event)
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    updateDrag(event)
                    clearDrag()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                val restoreHeight = dragStartHeight
                val wasDragging = activePointerId != MotionEvent.INVALID_POINTER_ID
                clearDrag()
                if (wasDragging) previewHeight(restoreHeight)
            }
        }
        return true
    }

    private fun updateDrag(event: MotionEvent) {
        val index = event.findPointerIndex(activePointerId)
        if (index < 0) return
        // Screen coordinates stay stable while the IME's top edge moves with the preview.
        val next = dragStartHeight + dragStartRawY - rawY(event, index)
        previewHeight(next.coerceIn(minimumAllowedHeight.toFloat(), maximumAllowedHeight.toFloat()).roundToInt())
    }

    private fun rawY(event: MotionEvent, index: Int): Float =
        event.getY(index) + event.rawY - event.y

    private fun previewHeight(heightPx: Int) {
        val next = heightPx.coerceIn(minimumAllowedHeight, maximumAllowedHeight)
        if (next == currentHeight) return
        currentHeight = next
        onPreview(next)
    }

    private fun clearDrag() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
    }
}
