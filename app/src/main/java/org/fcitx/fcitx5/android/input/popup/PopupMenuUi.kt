/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.imageDrawable
import kotlin.math.floor

class PopupMenuUi(
    override val ctx: Context,
    theme: Theme,
    outerBounds: Rect,
    triggerBounds: Rect,
    onDismissSelf: PopupContainerUi.() -> Unit = {},
    private val items: Array<KeyDef.Popup.Menu.Item>
) : PopupContainerUi(ctx, theme, outerBounds, triggerBounds, onDismissSelf) {

    private val keySize = ctx.dp(64)
    private val keyHeight = ctx.dp(72)

    private fun tileBackground(active: Boolean) = InsetDrawable(
        GradientDrawable().apply {
            cornerRadius = ctx.dp(12f)
            setColor(if (active) theme.genericActiveBackgroundColor else android.graphics.Color.TRANSPARENT)
        }, ctx.dp(4)
    )

    private val columnCount = items.size
    private val focusColumn =
        calcInitialFocusedColumn(columnCount, keySize, outerBounds, triggerBounds)

    override val offsetX = ((triggerBounds.width() - keySize) / 2) - (keySize * focusColumn)
    override val offsetY = -keyHeight - ctx.dp(8)

    private val columnOrder = createColumnOrder(columnCount, focusColumn)

    private var focusedIndex = columnOrder[focusColumn]

    private val keyViews = items.map { item ->
        val label = when (item.label) {
            "Emoji" -> ctx.getString(R.string.popup_emoji)
            "QuickPhrase" -> ctx.getString(R.string.popup_quick_phrase)
            "Unicode" -> ctx.getString(R.string.popup_unicode)
            else -> item.label
        }
        verticalLayout {
            gravity = Gravity.CENTER
            contentDescription = label
            background = tileBackground(false)
            add(imageView {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                imageDrawable = drawable(item.icon)!!.mutate().apply {
                    setTint(theme.popupTextColor)
                }
            }, lParams(ctx.dp(24), ctx.dp(24)))
            add(textView {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(theme.popupTextColor)
                setPadding(0, ctx.dp(5), 0, 0)
            }, lParams(keySize, ctx.dp(24)))
        }
    }

    init {
        markFocus(focusedIndex)
    }

    override val root = horizontalLayout root@{
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(16f)
            setColor(theme.popupBackgroundColor)
            setStroke(ctx.dp(1), theme.dividerColor)
        }
        elevation = ctx.dp(6f)
        for (i in 0 until columnCount) {
            val view = keyViews[columnOrder[i]]
            add(view, lParams(keySize, keyHeight))
        }
    }

    private fun markFocus(index: Int) {
        keyViews.getOrNull(index)?.apply {
            background = tileBackground(true)
        }
    }

    private fun markInactive(index: Int) {
        keyViews.getOrNull(index)?.apply {
            background = tileBackground(false)
        }
    }

    override fun onChangeFocus(x: Float, y: Float): Boolean {
        var newColumn = floor(x / keySize).toInt()
        if (newColumn < -2 || newColumn > columnCount + 1) {
            onDismissSelf(this)
            return true
        }
        newColumn = limitIndex(newColumn, columnCount)
        val newFocus = columnOrder[newColumn]
        if (newFocus < keyViews.size) {
            markInactive(focusedIndex)
            markFocus(newFocus)
            focusedIndex = newFocus
        }
        return false
    }

    override fun onTrigger() = items.getOrNull(focusedIndex)?.action
}
