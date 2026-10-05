/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.KeyLegendTypeface
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.gravityCenter

class PopupEntryUi(override val ctx: Context, private val theme: Theme, keyHeight: Int, radius: Float) : Ui {

    val textView = view(::AutoScaleTextView) {
        textSize = 30f
        scaleMode = AutoScaleTextView.Mode.Proportional
        gravity = gravityCenter
        setTextColor(theme.popupTextColor)
        // Keep the lower part of the preview transparent: the real key face
        // underneath must remain visible while the finger is down.
        background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(theme.popupBackgroundColor)
            setStroke(dp(1), theme.dividerColor)
        }
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dp(6f)
    }

    override val root = constraintLayout {
        visibility = View.INVISIBLE
        add(textView, lParams(matchParent, keyHeight) {
            topOfParent()
            centerHorizontally()
        })
    }

    internal val previewLifecycle = PopupPreviewLifecycle(root)

    private val defaultTypeface = textView.typeface
    private val defaultBackground = theme.popupBackgroundColor
    private val defaultText = theme.popupTextColor
    private val defaultStroke = theme.dividerColor

    /** Samsung Keys Cafe: the character bubble itself is coloured; null restores the theme colours */
    fun tint(color: Int?) {
        val bg = textView.background as? GradientDrawable ?: return
        if (color == null) {
            bg.setColor(defaultBackground)
            bg.setStroke(textView.dp(1), defaultStroke)
            textView.setTextColor(defaultText)
        } else {
            bg.setColor(color)
            bg.setStroke(0, 0)
            val darkInk = 0xFF111111.toInt()
            val lightInk = 0xFFFFFFFF.toInt()
            val opaqueBackground = color or 0xFF000000.toInt()
            textView.setTextColor(if (ColorUtils.calculateContrast(darkInk, opaqueBackground) >=
                ColorUtils.calculateContrast(lightInk, opaqueBackground)) darkInk else lightInk)
        }
    }

    fun setText(text: String) {
        if (textView.text.toString() != text) {
            textView.typeface = KeyLegendTypeface.resolve(ctx, theme, text, defaultTypeface)
            textView.text = text
        }
    }
}
