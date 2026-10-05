/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.*
import org.fcitx.fcitx5.android.utils.alpha
import splitties.dimensions.dp
import kotlin.math.roundToInt

/** Selects a real layout slot, then previews the same geometry used by the keyboard. */
internal class KeyWidthEditor(
    private val context: Context,
    private val theme: Theme,
    private val draft: KeyboardQuickSettingsDraft
) {
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val accent = 0xFF90C9FF.toInt()
    private var profile = KeyWidthProfile.Text
    private var selectedId: String? = null
    private var rendering = false
    private data class PreviewKey(val id: String, val label: String, val cell: KeyboardCell)
    private var keys: List<PreviewKey> = emptyList()
    private val caption = text("", 14f).apply { tag = "quick_key_width_caption" }
    private val hint = text(context.getString(R.string.key_width_hint), 12f)
    private val bar = SeekBar(context).apply {
        tag = "quick_key_width_slider"
        max = (KeyWidthSettings.RANGE.last - KeyWidthSettings.RANGE.first) / 5
        progressTintList = ColorStateList.valueOf(accent)
        thumbTintList = ColorStateList.valueOf(accent)
        progressBackgroundTintList = ColorStateList.valueOf(theme.keyTextColor.alpha(0.2f))
    }
    private val preview = object : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            for (index in 0 until childCount) {
                val cell = keys[index].cell
                val left = (cell.left * measuredWidth).roundToInt()
                val right = ((cell.left + cell.width) * measuredWidth).roundToInt()
                val top = (cell.top * measuredHeight).roundToInt()
                val bottom = ((cell.top + cell.height) * measuredHeight).roundToInt()
                getChildAt(index).measure(View.MeasureSpec.makeMeasureSpec(right - left, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(bottom - top, View.MeasureSpec.EXACTLY))
            }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            for (index in 0 until childCount) {
                val cell = keys[index].cell
                getChildAt(index).layout((cell.left * width).roundToInt(), (cell.top * height).roundToInt(),
                    ((cell.left + cell.width) * width).roundToInt(), ((cell.top + cell.height) * height).roundToInt())
            }
        }
    }.apply { tag = "quick_key_width_preview" }

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        tag = "quick_key_width_editor"
    }

    private fun text(value: CharSequence, size: Float) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(theme.keyTextColor)
    }

    private fun control(title: Int, tagName: String, action: () -> Unit) = Button(context).apply {
        tag = tagName
        setText(title)
        isAllCaps = false
        textSize = 12f
        minWidth = 0
        minHeight = 0
        setPadding(0, 0, 0, 0)
        setTextColor(theme.keyTextColor)
        backgroundTintList = ColorStateList.valueOf(theme.keyBackgroundColor)
        setOnClickListener { action() }
    }

    init {
        val picker = Spinner(context).apply {
            tag = "quick_key_width_layout"
            contentDescription = context.getString(R.string.key_width_layout)
            adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item,
                KeyWidthProfile.entries.map { context.getString(it.title) }) {
                init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getView(position, convertView, parent).also { (it as TextView).setTextColor(theme.keyTextColor) }
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getDropDownView(position, convertView, parent).also {
                        (it as TextView).setTextColor(theme.popupTextColor)
                        it.setBackgroundColor(theme.popupBackgroundColor)
                    }
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    profile = KeyWidthProfile.entries[position]
                    selectedId = null
                    refresh()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        root.addView(picker, LinearLayout.LayoutParams(match, context.dp(44)))
        root.addView(hint, LinearLayout.LayoutParams(match, wrap))
        root.addView(preview, LinearLayout.LayoutParams(match, context.dp(200)).apply {
            topMargin = context.dp(8); bottomMargin = context.dp(8)
        })
        root.addView(caption, LinearLayout.LayoutParams(match, wrap))
        root.addView(bar, LinearLayout.LayoutParams(match, context.dp(44)))
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(control(R.string.key_width_reset_key, "quick_key_width_reset_key") {
                selectedId?.let { draft.values = draft.values.copy(keyWidths = draft.values.keyWidths.withWidth(it, 100)) }
                refresh()
            }, LinearLayout.LayoutParams(0, context.dp(44), 1f))
            addView(control(R.string.key_width_reset_layout, "quick_key_width_reset_layout") {
                draft.values = draft.values.copy(keyWidths = draft.values.keyWidths.reset(profile))
                refresh()
            }, LinearLayout.LayoutParams(0, context.dp(44), 1f))
        }, LinearLayout.LayoutParams(match, wrap))
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || rendering) return
                selectedId?.let {
                    draft.values = draft.values.copy(keyWidths = draft.values.keyWidths.withWidth(it,
                        KeyWidthSettings.RANGE.first + progress * 5))
                    refresh()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { root.parent?.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { root.parent?.requestDisallowInterceptTouchEvent(false) }
        })
        refresh()
    }

    private fun label(def: KeyDef.Appearance): String = when (def.viewId) {
        R.id.button_space -> context.getString(R.string.key_width_space)
        R.id.button_return -> "↵"
        R.id.button_backspace -> "⌫"
        R.id.button_caps -> "⇧"
        R.id.button_lang -> "中/英"
        else -> (def as? KeyDef.Appearance.Text)?.displayText ?: when ((def as? KeyDef.Appearance.Image)?.src) {
            R.drawable.ic_symbol_lock, R.drawable.ic_symbol_unlock -> "▣"
            R.drawable.ic_baseline_expand_less_24 -> "↑"
            R.drawable.ic_baseline_expand_more_24 -> "↓"
            else -> "·"
        }
    }

    private fun textKeys(): List<PreviewKey> {
        val rows = (if (draft.values.numberRow && context.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE) listOf(TextKeyboard.NumberRow) else emptyList()) + TextKeyboard.Layout
        val showLanguage = AppPrefs.getInstance().keyboard.showLangSwitchKey.getValue()
        return rows.flatMapIndexed { rowIndex, original ->
            val visible = original.filter { showLanguage || it.appearance.viewId != R.id.button_lang }
            val missing = original.filterNot { it in visible }.sumOf { it.appearance.percentWidth.toDouble() }.toFloat()
            val base = visible.map { it.appearance.percentWidth + if (it is ReturnKey) missing else 0f }
            val ids = visible.map { KeyWidthGeometry.textId(it.appearance) }
            val widths = KeyWidthSettings.distribute(base, ids.map { draft.values.keyWidths[it] })
            var left = (1f - base.sum()).coerceAtLeast(0f) / 2f
            visible.mapIndexed { index, def ->
                PreviewKey(ids[index], label(def.appearance),
                    KeyboardCell(left, rowIndex.toFloat() / rows.size, widths[index], 1f / rows.size)).also {
                    left += widths[index]
                }
            }
        }
    }

    fun refresh() {
        rendering = true
        keys = if (profile == KeyWidthProfile.Text) textKeys() else {
            val (defs, originalCells) = when (profile) {
                KeyWidthProfile.T9 -> PinyinT9Keyboard.Layout.flatten() to PinyinT9Keyboard.Cells
                KeyWidthProfile.Number -> NumberKeyboard.Layout.flatten() to NumberKeyboard.Cells
                KeyWidthProfile.Symbols -> SymbolKeyboard.layoutFor(SymbolKeyboardState(), emptyList()).flatten() to SymbolKeyboard.Cells
                else -> error("Text layout uses rows")
            }
            val cells = KeyWidthGeometry.grid(profile, originalCells, draft.values.keyWidths)
            defs.mapIndexed { index, def -> PreviewKey(KeyWidthGeometry.gridId(profile, index), label(def.appearance), cells[index]) }
        }
        if (keys.none { it.id == selectedId }) selectedId = keys.firstOrNull()?.id
        preview.removeAllViews()
        keys.forEachIndexed { index, key ->
            val selected = key.id == selectedId
            preview.addView(Button(context).apply {
                tag = "quick_key_width_key_$index"
                text = key.label
                textSize = if (key.label.length > 3) 10f else 14f
                isAllCaps = false
                minHeight = 0; minWidth = 0
                minimumHeight = 0; minimumWidth = 0
                setPadding(context.dp(1), 0, context.dp(1), 0)
                gravity = Gravity.CENTER
                setTextColor(if (selected) 0xFF10151C.toInt() else theme.keyTextColor)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                background = android.graphics.drawable.InsetDrawable(GradientDrawable().apply {
                    cornerRadius = context.dp(5).toFloat()
                    setColor(if (selected) accent else theme.keyTextColor.alpha(0.1f))
                }, context.dp(1), context.dp(1), context.dp(1), context.dp(1))
                contentDescription = context.getString(R.string.key_width_value, key.label, draft.values.keyWidths[key.id])
                isSelected = selected
                setOnClickListener { selectedId = key.id; refresh() }
            }, FrameLayout.LayoutParams(0, 0))
        }
        val selected = keys.first { it.id == selectedId }
        val percent = draft.values.keyWidths[selected.id]
        caption.text = context.getString(R.string.key_width_value,
            if (selected.id.endsWith(":rail")) context.getString(R.string.key_width_side_column) else selected.label, percent)
        bar.contentDescription = caption.text
        bar.progress = (percent - KeyWidthSettings.RANGE.first) / 5
        preview.requestLayout()
        rendering = false
    }
}
