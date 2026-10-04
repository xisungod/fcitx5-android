/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.theme

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import splitties.dimensions.dp

/** The same actual preset swatches in the keyboard panel and the complete effects screen. */
internal class PressPaletteCards(
    private val context: Context,
    private val theme: Theme,
    tagPrefix: String,
    onSelect: (ThemePrefs.PressEffectPalette) -> Unit
) {
    private val accent = if (theme.isDark) 0xFF90C9FF.toInt() else 0xFF356FA5.toInt()
    private val cards = linkedMapOf<ThemePrefs.PressEffectPalette, Pair<LinearLayout, TextView>>()
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    val root = HorizontalScrollView(context).apply {
        tag = "${tagPrefix}_palettes"
        isHorizontalScrollBarEnabled = true
        addView(row, LinearLayout.LayoutParams(-2, -1))
    }

    init {
        PressColorPalette.recommendedPresets.forEach { (preset, colors) ->
            val label = TextView(context).apply {
                text = context.getString(titleFor(preset))
                textSize = 13f
                setTextColor(theme.keyTextColor)
                gravity = Gravity.CENTER_VERTICAL
                typeface = Typeface.DEFAULT_BOLD
            }
            val card = LinearLayout(context).apply {
                tag = "${tagPrefix}_palette_${preset.name}"
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener { onSelect(preset) }
                addView(label, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(LinearLayout(context).apply {
                    tag = "${tagPrefix}_palette_colors_${preset.name}"
                    orientation = LinearLayout.HORIZONTAL
                    colors.forEach { color ->
                        addView(View(context).apply { setBackgroundColor(color) },
                            LinearLayout.LayoutParams(0, -1, 1f))
                    }
                }, LinearLayout.LayoutParams(-1, context.dp(16)))
            }
            cards[preset] = card to label
            row.addView(card, LinearLayout.LayoutParams(context.dp(136), context.dp(78)).apply {
                rightMargin = context.dp(8)
            })
        }
        select(null)
    }

    fun select(preset: ThemePrefs.PressEffectPalette?) {
        cards.forEach { (entry, pair) ->
            val (card, label) = pair
            val selected = entry == preset
            card.isSelected = selected
            label.text = context.getString(titleFor(entry)) + if (selected) " ✓" else ""
            label.setTextColor(if (selected) accent else theme.keyTextColor)
            card.contentDescription = label.text
            card.background = GradientDrawable().apply {
                cornerRadius = context.dp(10).toFloat()
                setColor(theme.keyBackgroundColor)
                setStroke(context.dp(if (selected) 2 else 1), if (selected) accent else theme.dividerColor)
            }
        }
    }

    companion object {
        fun titleFor(preset: ThemePrefs.PressEffectPalette): Int = when (preset) {
            ThemePrefs.PressEffectPalette.Cyberpunk -> R.string.cyber_palette_full_spectrum
            else -> preset.stringRes
        }
    }
}
