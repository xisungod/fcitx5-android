/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import splitties.views.imageResource

/** Numeric pinyin codes go to Rime's T9 schema; the keypad never commits its letter groups. */
@SuppressLint("ViewConstructor")
class PinyinT9Keyboard(context: Context, theme: Theme) : BaseKeyboard(context, theme, Layout) {
    companion object {
        const val Name = "PinyinT9"
        const val Text26Route = "Text26"
        internal val LetterGroups = linkedMapOf(2 to "ABC", 3 to "DEF", 4 to "GHI", 5 to "JKL",
            6 to "MNO", 7 to "PQRS", 8 to "TUV", 9 to "WXYZ")

        private fun group(digit: Int) = KeyDef(
            KeyDef.Appearance.Text(LetterGroups.getValue(digit), 22f, percentWidth = 0.24f),
            setOf(KeyDef.Behavior.Press(KeyAction.FcitxKeyAction(digit.toString())),
                KeyDef.Behavior.LongPress(KeyAction.CommitAction(digit.toString())))
        )

        private fun punctuation(text: String) = KeyDef(
            KeyDef.Appearance.Text(text, 25f, percentWidth = 0.14f,
                border = KeyDef.Appearance.Border.Off, margin = false),
            setOf(KeyDef.Behavior.Press(KeyAction.CommitAction(text)))
        )

        val Layout: List<List<KeyDef>> = listOf(
            listOf(punctuation("，"), punctuation("。"), punctuation("？"), punctuation("！")),
            listOf(KeyDef(KeyDef.Appearance.Text("分词", 20f, percentWidth = 0.24f),
                setOf(KeyDef.Behavior.Press(KeyAction.FcitxKeyAction("'")),
                    KeyDef.Behavior.LongPress(KeyAction.CommitAction("1")))), group(2), group(3), BackspaceKey(0.14f)),
            listOf(group(4), group(5), group(6), LayoutSwitchKey("26键", Text26Route, 0.14f)),
            listOf(group(7), group(8), group(9), LanguageKey(0.14f)),
            listOf(LayoutSwitchKey("!#1", SymbolKeyboard.Name, 0.14f),
                LayoutSwitchKey("123", NumberKeyboard.Name, 0.24f, textSize = 20f),
                SpaceKey(0.48f), ReturnKey(0.14f))
        )

        internal val Cells: List<KeyboardCell> = buildList {
            repeat(4) { add(KeyboardCell(0f, it * 0.1875f, 0.14f, 0.1875f)) }
            repeat(3) { row ->
                repeat(3) { col -> add(KeyboardCell(0.14f + col * 0.24f, row * 0.25f, 0.24f, 0.25f)) }
                add(KeyboardCell(0.86f, row * 0.25f, 0.14f, 0.25f))
            }
            add(KeyboardCell(0f, 0.75f, 0.14f, 0.25f))
            add(KeyboardCell(0.14f, 0.75f, 0.24f, 0.25f))
            add(KeyboardCell(0.38f, 0.75f, 0.48f, 0.25f))
            add(KeyboardCell(0.86f, 0.75f, 0.14f, 0.25f))
        }
    }

    val backspace: ImageKeyView by lazy { findViewById(R.id.button_backspace) }
    val space: TextKeyView by lazy { findViewById(R.id.button_space) }
    val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }
    val lang: TextKeyView by lazy { findViewById(R.id.button_lang) }

    init {
        val keys = arrangeGrid(Cells, KeyWidthProfile.T9)
        val strip = View(context).apply {
            isClickable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            background = InsetDrawable(GradientDrawable().apply {
                cornerRadius = keys.first().radius
                setColor(this@PinyinT9Keyboard.theme.keyBackgroundColor)
            }, backspace.hMargin, backspace.vMargin, backspace.hMargin, backspace.vMargin)
        }
        (keys.first().parent as ConstraintLayout).addView(strip, 0,
            gridParams(KeyboardCell(0f, 0f,
                (keys.first().layoutParams as ConstraintLayout.LayoutParams).matchConstraintPercentWidth, 0.75f)))

        // These numeric hints belong to the T9 layout, independently of the alphabet
        // keyboard's punctuation-position and top-number-row preferences.
        val labels = mapOf(1 to "分词") + LetterGroups
        labels.forEach { (digit, letters) ->
            val key = keys.filterIsInstance<TextKeyView>().first {
                (it.def as KeyDef.Appearance.Text).displayText == letters
            }
            key.tag = "t9-key-$digit"
            key.contentDescription = if (digit == 1) context.getString(R.string.pinyin_t9_separator_description)
                else context.getString(R.string.pinyin_t9_letters_description, letters, digit)
            (key.getChildAt(0) as ConstraintLayout).addView(TextView(context).apply {
                tag = "t9-corner-digit"
                text = digit.toString()
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(this@PinyinT9Keyboard.theme.altKeyTextColor)
                isClickable = false
                isFocusable = false
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
                rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                rightMargin = key.hMargin + context.dp(6)
                topMargin = key.vMargin + context.dp(4)
            })
        }
        keys.filterIsInstance<TextKeyView>().first {
            (it.def as KeyDef.Appearance.Text).displayText == "26键"
        }.contentDescription = context.getString(R.string.pinyin_t9_switch_26_description)
        space.setSpaceIcon()
        space.contentDescription = context.getString(R.string.space_key_label)
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        val english = TextKeyboard.isEnglish(ime)
        lang.mainText.text = if (english) "英" else "中"
        lang.contentDescription = if (english) "English" else "中文"
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`.img.imageResource = returnDrawable
    }
}
