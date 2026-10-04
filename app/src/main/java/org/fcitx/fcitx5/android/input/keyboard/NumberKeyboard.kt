/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.popup.PopupAction
import splitties.views.imageResource

/** Large 1–9 keypad with an independent arithmetic strip and dedicated 0. */
@SuppressLint("ViewConstructor")
class NumberKeyboard(context: Context, theme: Theme) : BaseKeyboard(context, theme, Layout) {

    companion object {
        const val Name = "Number"
        private fun digit(n: Int) = NumPadKey(n.toString(), 0xffb0 + n, 30f, 0.24f)
        private fun operator(text: String, sym: Int) = NumPadKey(text, sym, 26f, 0.14f).let {
            KeyDef(KeyDef.Appearance.Text(text, 26f, percentWidth = 0.14f,
                border = KeyDef.Appearance.Border.Off, margin = false), it.behaviors)
        }

        val Layout: List<List<KeyDef>> = listOf(
            listOf(operator("+", 0xffab), operator("-", 0xffad), operator("*", 0xffaa), operator("/", 0xffaf)),
            listOf(digit(1), digit(2), digit(3), BackspaceKey(0.14f)),
            listOf(digit(4), digit(5), digit(6), KeyDef(KeyDef.Appearance.Text("@", 28f, percentWidth = 0.14f),
                setOf(KeyDef.Behavior.Press(KeyAction.CommitAction("@"))))),
            listOf(digit(7), digit(8), digit(9), NumPadKey(".", 0xffae, 28f, 0.14f)),
            listOf(LayoutSwitchKey("!#1", SymbolKeyboard.Name, 0.14f),
                LayoutSwitchKey("返回", TextKeyboard.Name, 0.24f, textSize = 22f), digit(0), SpaceKey(0.24f), ReturnKey(0.14f))
        )

        internal val Cells: List<KeyboardCell> = buildList {
            repeat(4) { add(KeyboardCell(0f, it * 0.1875f, 0.14f, 0.1875f)) }
            repeat(3) { row ->
                repeat(3) { col -> add(KeyboardCell(0.14f + col * 0.24f, row * 0.25f, 0.24f, 0.25f)) }
                add(KeyboardCell(0.86f, row * 0.25f, 0.14f, 0.25f))
            }
            add(KeyboardCell(0f, 0.75f, 0.14f, 0.25f))
            repeat(3) { col -> add(KeyboardCell(0.14f + col * 0.24f, 0.75f, 0.24f, 0.25f)) }
            add(KeyboardCell(0.86f, 0.75f, 0.14f, 0.25f))
        }
    }

    val backspace: ImageKeyView by lazy { findViewById(R.id.button_backspace) }
    val space: TextKeyView by lazy { findViewById(R.id.button_space) }
    val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }

    init {
        val keys = arrangeGrid(Cells)
        val strip = View(context).apply {
            isClickable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            background = InsetDrawable(GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = keys.first().radius
                setColor(theme.keyBackgroundColor)
            }, backspace.hMargin, backspace.vMargin, backspace.hMargin, backspace.vMargin)
        }
        (keys.first().parent as ConstraintLayout).addView(strip, 0,
            gridParams(KeyboardCell(0f, 0f, 0.14f, 0.75f)))
        space.setSpaceIcon()
        space.contentDescription = context.getString(R.string.space_key_label)
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`.img.imageResource = returnDrawable
    }

    @SuppressLint("MissingSuperCall")
    override fun onPopupAction(action: PopupAction) {
        // Number keys already have generous labels; avoid covering neighbours.
    }
}
