/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.view.Gravity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.popup.PopupAction

/** Category rail, sixteen generous symbol keys, and a fixed control strip. */
@SuppressLint("ViewConstructor")
class SymbolKeyboard(
    context: Context,
    theme: Theme,
    val state: SymbolKeyboardState,
    private val history: SymbolHistory
) : BaseKeyboard(context, theme, layoutFor(state, history.items)) {

    companion object {
        const val Name = "Symbols"
        const val CategoryPrefix = "$Name:category:"
        const val Previous = "$Name:previous"
        const val Next = "$Name:next"
        const val Lock = "$Name:lock"

        fun isRoute(route: String) = route == Name || route.startsWith("$Name:")
        fun categoryRoute(category: SymbolCategory) = CategoryPrefix + category.name

        private fun symbol(text: String) = KeyDef(
            KeyDef.Appearance.Text(text, when (text.codePointCount(0, text.length)) {
                0, 1 -> 29f
                2 -> 26f
                3, 4 -> 22f
                5, 6 -> 18f
                7, 8 -> 15f
                else -> 13f
            }, percentWidth = 0.215f),
            if (text.isNotEmpty()) setOf(KeyDef.Behavior.Press(KeyAction.CommitAction(text))) else emptySet()
        )

        private fun control(icon: Int, route: String) = KeyDef(
            KeyDef.Appearance.Image(icon, 0.2f, KeyDef.Appearance.Variant.AltForeground, KeyDef.Appearance.Border.Off),
            setOf(KeyDef.Behavior.Press(KeyAction.LayoutSwitchAction(route)))
        )

        fun layoutFor(state: SymbolKeyboardState, recent: List<String>): List<List<KeyDef>> {
            val visible = state.visibleItems(recent)
            return listOf(SymbolCategory.entries.map { category ->
                LayoutSwitchKey(category.label, categoryRoute(category), 0.14f,
                    if (state.category == category) KeyDef.Appearance.Variant.Accent else KeyDef.Appearance.Variant.Alternative)
            }) + List(4) { row -> List(4) { col -> symbol(visible.getOrElse(row * 4 + col) { "" }) } } + listOf(listOf(
                control(if (state.locked) R.drawable.ic_symbol_lock else R.drawable.ic_symbol_unlock, Lock),
                control(R.drawable.ic_baseline_expand_less_24, Previous),
                control(R.drawable.ic_baseline_expand_more_24, Next),
                BackspaceKey(0.2f, KeyDef.Appearance.Variant.AltForeground, KeyDef.Appearance.Border.Off),
                KeyDef(KeyDef.Appearance.Text("返回", 18f, percentWidth = 0.2f,
                    variant = KeyDef.Appearance.Variant.AltForeground, border = KeyDef.Appearance.Border.Off),
                    setOf(KeyDef.Behavior.Press(KeyAction.LayoutSwitchAction(TextKeyboard.Name))))
            ))
        }

        internal val Cells: List<KeyboardCell> = buildList {
            repeat(5) { add(KeyboardCell(0f, it * 0.172f, 0.14f, 0.172f)) }
            repeat(4) { row -> repeat(4) { col -> add(KeyboardCell(0.14f + col * 0.215f, row * 0.215f, 0.215f, 0.215f)) } }
            repeat(5) { add(KeyboardCell(it * 0.2f, 0.86f, 0.2f, 0.14f)) }
        }
    }

    init {
        val keys = arrangeGrid(Cells)
        keys.take(5).forEachIndexed { index, key ->
            key.contentDescription = SymbolCategory.entries[index].label
            if (SymbolCategory.entries[index] == state.category) {
                // A distinct selected tab remains readable even with a black accent theme.
                key.getChildAt(0).background = InsetDrawable(GradientDrawable().apply {
                    cornerRadius = key.radius
                    setColor(0xFF9974F5.toInt())
                }, key.hMargin, key.vMargin, key.hMargin, key.vMargin)
            }
        }
        keys.drop(5).take(16).forEach { key ->
            (key as TextKeyView).mainText.apply {
                scaleMode = AutoScaleTextView.Mode.Proportional
                gravity = Gravity.CENTER
                // Bound the text to the interior of its own key, including on
                // narrow or high-density screens; long suffixes never overlap.
                updateLayoutParams<ConstraintLayout.LayoutParams> {
                    width = 0
                    leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
                    rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                    val inset = key.hMargin + (8 * resources.displayMetrics.density).toInt()
                    leftMargin = inset
                    rightMargin = inset
                }
            }
            if ((key.def as KeyDef.Appearance.Text).displayText.isEmpty()) {
                key.isEnabled = false
                key.visibility = View.INVISIBLE
            }
        }
        val controls = keys.takeLast(5)
        controls[0].contentDescription = if (state.locked) "连续输入符号，点按解锁" else "输入符号后返回字母，点按锁定"
        controls[1].apply { contentDescription = "上一页"; isEnabled = state.page > 0 }
        controls[2].apply { contentDescription = "下一页"; isEnabled = state.page + 1 < state.pageCount(history.items) }
    }

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        if (action is KeyAction.CommitAction) history.record(action.text)
        super.onAction(action, source)
        if (action is KeyAction.CommitAction && !state.locked) {
            super.onAction(KeyAction.LayoutSwitchAction(TextKeyboard.Name), source)
        }
    }

    @SuppressLint("MissingSuperCall")
    override fun onPopupAction(action: PopupAction) {
        // The 4×4 cells already expose the complete symbol, including long web suffixes.
    }
}
