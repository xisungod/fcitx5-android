/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.children
import org.fcitx.fcitx5.android.R
import kotlin.math.roundToInt

/** A key's touch rectangle, in fractions of the entire keyboard. */
internal data class KeyboardCell(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * Keep BaseKeyboard's generated KeyViews and gesture handlers, but give independent
 * columns their own row heights. All keys remain direct children of its first row,
 * so BaseKeyboard's hit testing and press-light coordinates use the real geometry.
 */
internal fun BaseKeyboard.arrangeGrid(cells: List<KeyboardCell>): List<KeyView> {
    val rows = children.filterIsInstance<ConstraintLayout>().toList()
    val keys = rows.flatMap { it.children.filterIsInstance<KeyView>().toList() }
    require(cells.size == keys.size)
    val host = rows.first()
    keys.forEach { (it.parent as ConstraintLayout).removeView(it) }
    rows.drop(1).forEach(::removeView)
    host.layoutParams = ConstraintLayout.LayoutParams(0, 0).apply {
        leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
        rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
    }
    keys.zip(cells).forEach { (key, cell) ->
        key.layoutMarginLeft = 0f
        key.layoutMarginRight = 0f
        host.addView(key, gridParams(cell))
    }
    // Round shared edges, rather than each cell's size independently. This avoids
    // a one-pixel dead strip when (for example) four rows divide a 421px keyboard.
    host.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
        val width = right - left
        val height = bottom - top
        keys.zip(cells).forEach { (key, cell) ->
            val x1 = (cell.left * width).roundToInt()
            val y1 = (cell.top * height).roundToInt()
            val x2 = ((cell.left + cell.width) * width).roundToInt()
            val y2 = ((cell.top + cell.height) * height).roundToInt()
            key.measure(View.MeasureSpec.makeMeasureSpec(x2 - x1, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(y2 - y1, View.MeasureSpec.EXACTLY))
            key.layout(x1, y1, x2, y2)
        }
    }
    return keys
}

internal fun gridParams(cell: KeyboardCell) = ConstraintLayout.LayoutParams(0, 0).apply {
    leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
    rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
    matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
    matchConstraintDefaultHeight = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
    matchConstraintPercentWidth = cell.width
    matchConstraintPercentHeight = cell.height
    horizontalBias = if (cell.width < 1f) cell.left / (1f - cell.width) else 0f
    verticalBias = if (cell.height < 1f) cell.top / (1f - cell.height) else 0f
}

internal fun TextKeyView.setSpaceIcon() {
    mainText.text = ""
    val icon = ContextCompat.getDrawable(context, R.drawable.ic_keyboard_space_outline)!!.mutate()
    val size = (32 * resources.displayMetrics.density).toInt()
    icon.setTint(theme.keyTextColor)
    // AutoScaleTextView draws its text directly and does not draw TextView's
    // compound drawables. Use a real sibling ImageView for the space legend.
    val appearance = getChildAt(0) as ConstraintLayout
    val image = ImageView(context).apply {
        tag = "space-key-icon"
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setImageDrawable(icon)
    }
    appearance.addView(image, ConstraintLayout.LayoutParams(size, size).apply {
        leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
        rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
    })
}
