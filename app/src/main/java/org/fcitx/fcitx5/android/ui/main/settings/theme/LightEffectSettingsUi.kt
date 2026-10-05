/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.PressColorPalette
import org.fcitx.fcitx5.android.data.theme.PressPaletteCards
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset

/** Real settings controls shared by the production fragment and its rendering tests. */
class LightEffectSettingsUi(val context: Context, private val prefs: ThemePrefs = ThemeManager.prefs,
    private val openKeyboardSettings: (() -> Unit)? = null) {
    private val ink = ContextCompat.getColor(context, R.color.ax_settings_text)
    private val subdued = ContextCompat.getColor(context, R.color.ax_settings_secondary)
    private val accent = ContextCompat.getColor(context, R.color.ax_settings_accent)
    private val surface = ContextCompat.getColor(context, R.color.ax_settings_surface)
    private val divider = ContextCompat.getColor(context, R.color.ax_settings_divider)
    private val selectedSurface = ColorUtils.blendARGB(surface, accent, 0.10f)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float = 14f, color: Int = ink) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun background(fill: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(fill)
        stroke?.let { setStroke(dp(1).coerceAtLeast(1), it) }
    }

    private fun interactiveBackground(fill: Int, stroke: Int? = null) = RippleDrawable(
        ColorStateList.valueOf(ContextCompat.getColor(context, R.color.ax_settings_ripple)),
        background(fill, stroke), background(Color.WHITE))

    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(20), dp(16), dp(32))
    }

    val root = ScrollView(context).apply {
        tag = "light-effect-settings"
        isFillViewport = true
        setBackgroundColor(ContextCompat.getColor(context, R.color.ax_settings_background))
        isVerticalScrollBarEnabled = false
        addView(body, ViewGroup.LayoutParams(-1, -2))
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(0, 0, 0, bottom)
            insets
        }
    }

    val modeButtons = linkedMapOf<ThemePrefs.PressColorMode, Button>()
    private val paletteCard = card()
    private val colorsBody = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val palettePreview = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; tag = "effect-palette-preview" }
    private val modeSummary = text("", color = subdued)
    private val totalLabel = text("", 13f, accent).apply { tag = "effect-total-time" }
    private val totalDetailLabel = text("", 12f, subdued).apply { tag = "effect-total-detail" }
    private val keyTimingHint = text("", 12f, subdued)
    val durationControls = linkedMapOf<String, EffectRangeControl>()
    var activeColorDialog: AlertDialog? = null
        private set

    init {
        body.addView(text(context.getString(R.string.light_effect_saved), 13f, subdued),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        val basics = card().apply { tag = "effect-basics" }
        addSwitch(basics, R.string.light_effect_enable, prefs.pressEffect)
        addDivider(basics)
        basics.addView(text(context.getString(R.string.ripple_shape), 15f),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        addShapeSelector(basics)
        body.addView(basics)
        section(R.string.light_effect_palette)
        val modeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(ThemePrefs.PressColorMode.Random to R.string.press_color_mode_random,
            ThemePrefs.PressColorMode.Single to R.string.press_color_mode_single,
            ThemePrefs.PressColorMode.Custom to R.string.press_color_mode_custom).forEach { (mode, title) ->
            val button = Button(context).apply {
                tag = "effect-mode-${mode.name}"
                text = context.getString(title)
                textSize = 13f
                isAllCaps = false
                stateListAnimator = null
                minWidth = 0
                minimumWidth = 0
                minHeight = dp(48)
                setPadding(dp(2), 0, dp(2), 0)
                setOnClickListener {
                    prefs.pressColorMode.setValue(mode)
                    renderColors()
                }
            }
            modeButtons[mode] = button
            modeRow.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                if (modeRow.childCount > 0) leftMargin = dp(6)
            })
        }
        paletteCard.addView(modeRow)
        paletteCard.addView(modeSummary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        paletteCard.addView(palettePreview, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(14); bottomMargin = dp(16) })
        paletteCard.addView(colorsBody)
        body.addView(paletteCard)
        renderColors()

        val advanced = LinearLayout(context).apply {
            tag = "effect-advanced-content"
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val advancedCard = card()
        val toggle = LinearLayout(context).apply {
            tag = "effect-advanced-toggle"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            isClickable = true
            isFocusable = true
            background = interactiveBackground(surface)
            ViewCompat.setScreenReaderFocusable(this, true)
        }
        val toggleText = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        toggleText.addView(text(context.getString(R.string.ax_light_advanced), 16f).apply {
            setTypeface(typeface, Typeface.BOLD)
        })
        toggleText.addView(text(context.getString(R.string.ax_light_advanced_summary), 12f, subdued),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        val chevron = text("⌄", 22f, subdued).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        toggle.addView(toggleText, LinearLayout.LayoutParams(0, -2, 1f))
        toggle.addView(chevron, LinearLayout.LayoutParams(dp(24), -2))
        fun updateExpandedState() {
            val expanded = advanced.visibility == View.VISIBLE
            chevron.text = if (expanded) "⌃" else "⌄"
            ViewCompat.setStateDescription(toggle, context.getString(
                if (expanded) R.string.ax_light_expanded else R.string.ax_light_collapsed))
        }
        toggle.setOnClickListener {
            advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            updateExpandedState()
        }
        updateExpandedState()
        advancedCard.addView(toggle)
        advancedCard.addView(advanced)
        body.addView(advancedCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        addDivider(advanced)
        val animationCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        animationCard.addView(totalLabel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        animationCard.addView(totalDetailLabel,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        listOf(
            Range(R.string.press_ignition_time, prefs.pressIgnitionTime, 30, 300, 10),
            Range(R.string.light_effect_expand, prefs.pressExpansionTime, 100, 4000, 20),
            Range(R.string.press_wave_hold_time, prefs.pressWaveHoldTime, 0, 2000, 10),
            Range(R.string.light_effect_wave_fade, prefs.pressFadeOutTime, 100, 5000, 20),
            Range(R.string.light_effect_face_hold, prefs.pressKeyHoldTime, 20, 1000, 10),
            Range(R.string.light_effect_face_exit, prefs.pressKeyRetreatTime, 20, 5000, 10),
            Range(R.string.sam_key_hold_time, prefs.samKeyHoldTime, 0, 1000, 10),
            Range(R.string.sam_key_retreat_time, prefs.samKeyRetreatTime, 100, 5000, 10)
        ).forEach { range -> addRange(animationCard, range, "ms") }
        advanced.addView(animationCard)
        advanced.addView(keyTimingHint,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(16) })
        updateTimingVisibility()
        addDivider(advanced)
        advanced.addView(text(context.getString(R.string.light_effect_light), 15f).apply {
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16); bottomMargin = dp(12) })
        val lightCard = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addRange(lightCard, Range(R.string.light_effect_reach, prefs.pressGlowReach, 20, 100, 5), "%")
        addRange(lightCard, Range(R.string.light_effect_brightness, prefs.pressGlowBrightness, 0, 100, 5), "%")
        advanced.addView(lightCard)
        addSwitch(advanced, R.string.light_effect_glow_candidates, prefs.pressGlowOnCandidates)

        section(R.string.keyboard_layout_section)
        val keyboardCard = card()
        keyboardCard.addView(Button(context).apply {
            tag = "keyboard-size-feedback-settings"
            text = context.getString(R.string.keyboard_size_feedback)
            isAllCaps = false
            textSize = 15f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            setTextColor(accent)
            background = interactiveBackground(surface)
            stateListAnimator = null
            setOnClickListener { openKeyboardSettings?.invoke() }
        }, LinearLayout.LayoutParams(-1, dp(52)))
        addDivider(keyboardCard)
        addSwitch(keyboardCard, R.string.keyboard_show_number_row, prefs.portraitNumberRow)
        keyboardCard.addView(text(context.getString(R.string.keyboard_show_number_row_summary), 12f, subdued))
        body.addView(keyboardCard)
    }

    private fun addShapeSelector(parent: LinearLayout) {
        val shapes = ThemePrefs.RippleShape.entries
        parent.addView(Spinner(context).apply {
            tag = "effect-ripple-shape"
            contentDescription = context.getString(R.string.ripple_shape)
            adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item,
                shapes.map { context.getString(it.stringRes) }) {
                init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getView(position, convertView, parent).also { (it as TextView).setTextColor(ink) }
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getDropDownView(position, convertView, parent).also {
                        (it as TextView).setTextColor(ink)
                        it.setBackgroundColor(surface)
                    }
            }
            setSelection(shapes.indexOf(prefs.rippleShape.getValue()))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = shapes[position]
                    if (prefs.rippleShape.getValue() != selected) prefs.rippleShape.setValue(selected)
                    updateTimingVisibility()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private data class Range(val title: Int, val pref: ManagedPreference.PInt, val min: Int, val max: Int, val step: Int)

    private fun card() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = background(surface)
        setPadding(dp(16), dp(12), dp(16), dp(16))
    }

    private fun section(title: Int) {
        body.addView(text(context.getString(title), 14f, subdued).apply { setTypeface(typeface, Typeface.BOLD) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24); bottomMargin = dp(10) })
    }

    private fun addDivider(parent: LinearLayout) {
        parent.addView(View(context).apply { setBackgroundColor(divider) },
            LinearLayout.LayoutParams(-1, dp(1).coerceAtLeast(1)))
    }

    private fun addSwitch(parent: LinearLayout, title: Int, pref: ManagedPreference.PBool) {
        parent.addView(Switch(context).apply {
            tag = pref.key
            text = context.getString(title)
            textSize = 15f
            minimumHeight = dp(56)
            switchPadding = dp(12)
            setTextColor(ink)
            isChecked = pref.getValue()
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accent, subdued))
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ColorUtils.setAlphaComponent(accent, 80), divider))
            setOnCheckedChangeListener { _, checked -> pref.setValue(checked) }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addRange(parent: LinearLayout, range: Range, unit: String) {
        val control = EffectRangeControl(context, context.getString(range.title), range.pref,
            range.min, range.max, range.step, unit, ::updateTotal)
        durationControls[range.pref.key] = control
        parent.addView(control.root)
    }

    private fun updateTimingVisibility() {
        val sam = prefs.rippleShape.getValue() == ThemePrefs.RippleShape.Sam
        listOf(prefs.pressIgnitionTime, prefs.pressKeyHoldTime, prefs.pressKeyRetreatTime).forEach {
            durationControls[it.key]?.root?.visibility = if (sam) View.GONE else View.VISIBLE
        }
        listOf(prefs.samKeyHoldTime, prefs.samKeyRetreatTime).forEach {
            durationControls[it.key]?.root?.visibility = if (sam) View.VISIBLE else View.GONE
        }
        keyTimingHint.setText(if (sam) R.string.sam_key_timing_hint else R.string.keyboard_quick_settings_retreat_hint)
        updateTotal()
    }

    private fun updateTotal() {
        val sam = prefs.rippleShape.getValue() == ThemePrefs.RippleShape.Sam
        val ignition = if (sam) 0 else prefs.pressIgnitionTime.getValue()
        totalLabel.text = context.getString(R.string.light_effect_total,
            ignition + prefs.pressExpansionTime.getValue() +
                prefs.pressWaveHoldTime.getValue() + prefs.pressFadeOutTime.getValue())
        totalDetailLabel.text = if (sam) context.getString(R.string.sam_wave_total_detail)
            else context.getString(R.string.light_effect_total_detail, ignition)
    }

    private fun renderColors() {
        val mode = prefs.pressColorMode.getValue()
        modeButtons.forEach { (entry, button) ->
            val selected = entry == mode
            button.isSelected = selected
            button.setTextColor(if (selected) accent else ink)
            button.background = interactiveBackground(if (selected) selectedSurface else surface,
                if (selected) accent else divider)
        }
        val colors = when (mode) {
            ThemePrefs.PressColorMode.Random -> randomPaletteColors()
            ThemePrefs.PressColorMode.Single -> intArrayOf(prefs.pressSingleColor.getValue() or 0xFF000000.toInt())
            ThemePrefs.PressColorMode.Custom -> prefs.userPressColors()
        }
        modeSummary.setText(when (mode) {
            ThemePrefs.PressColorMode.Random -> R.string.light_effect_random_summary
            ThemePrefs.PressColorMode.Single -> R.string.light_effect_single_summary
            ThemePrefs.PressColorMode.Custom -> R.string.light_effect_custom_summary
        })
        palettePreview.removeAllViews()
        colors.forEachIndexed { index, color ->
            palettePreview.addView(View(context).apply { background = background(color) },
                LinearLayout.LayoutParams(0, -1, 1f).apply { if (index > 0) leftMargin = dp(3) })
        }
        colorsBody.removeAllViews()
        when (mode) {
            ThemePrefs.PressColorMode.Random -> {
                colorsBody.addView(text(context.getString(R.string.cyber_palette_hint), 12f, subdued),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
                val cards = PressPaletteCards(context,
                    ThemePreset.MaterialLight.copy(
                        isDark = ColorUtils.calculateLuminance(surface) < 0.5,
                        keyBackgroundColor = surface,
                        keyTextColor = ink,
                        dividerColor = divider
                    ), "effect", accentColor = accent) { preset ->
                    prefs.pressEffectPalette.setValue(preset)
                    renderColors()
                }
                cards.select(prefs.pressEffectPalette.getValue())
                colorsBody.addView(cards.root, LinearLayout.LayoutParams(-1, dp(84)))
            }
            ThemePrefs.PressColorMode.Single -> colorsBody.addView(colorButton(colors[0], "effect-single-color") {
                editColor(colors[0]) { prefs.pressSingleColor.setValue(it); renderColors() }
            }, LinearLayout.LayoutParams(-1, dp(58)))
            ThemePrefs.PressColorMode.Custom -> {
                colorsBody.addView(text(context.getString(R.string.light_effect_color_guide), 12f, subdued),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
                (colors.indices).chunked(4).forEach { indices ->
                    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    indices.forEach { i ->
                        val button = colorButton(colors[i], "effect-custom-color-$i") {
                            editColor(colors[i]) { value ->
                                val updated = prefs.userPressColors().copyOf()
                                if (i < updated.size) { updated[i] = value; saveColors(updated) }
                            }
                        }.apply {
                            contentDescription = context.getString(R.string.light_effect_color_number, i + 1) + " " + EffectColorEditor.hexFor(colors[i])
                            setOnLongClickListener {
                                val current = prefs.userPressColors()
                                if (current.size <= 1) Toast.makeText(context, R.string.light_effect_color_single_min, Toast.LENGTH_SHORT).show()
                                else saveColors(current.filterIndexed { index, _ -> index != i }.toIntArray())
                                true
                            }
                        }
                        row.addView(button, LinearLayout.LayoutParams(0, dp(54), 1f).apply { rightMargin = dp(4); bottomMargin = dp(6) })
                    }
                    repeat(4 - indices.size) { row.addView(View(context), LinearLayout.LayoutParams(0, dp(54), 1f)) }
                    colorsBody.addView(row)
                }
                colorsBody.addView(Button(context).apply {
                    tag = "effect-color-add"
                    text = context.getString(R.string.light_effect_color_add)
                    isAllCaps = false
                    setTextColor(accent)
                    background = interactiveBackground(selectedSurface, divider)
                    stateListAnimator = null
                    isEnabled = colors.size < 8
                    alpha = if (isEnabled) 1f else 0.45f
                    setOnClickListener {
                        val current = prefs.userPressColors()
                        if (current.size < 8) {
                            val hsv = FloatArray(3).also { Color.colorToHSV(current.last(), it) }
                            hsv[0] = (hsv[0] + 47f) % 360f
                            hsv[1] = 0.82f; hsv[2] = 1f
                            saveColors(current + Color.HSVToColor(hsv))
                        }
                    }
                }, LinearLayout.LayoutParams(-1, dp(44)))
            }
        }
    }

    /** Keep legacy preset choices visible without silently replacing them when this page opens. */
    private fun randomPaletteColors(): IntArray = PressColorPalette.presetColors(
        prefs.pressEffectPalette.getValue(), prefs,
        runCatching { ThemeManager.activeTheme }.getOrDefault(ThemeManager.DefaultTheme).accentKeyBackgroundColor
    )

    private fun colorButton(color: Int, buttonTag: String, action: () -> Unit) = Button(context).apply {
        tag = buttonTag
        text = EffectColorEditor.hexFor(color)
        textSize = 12f
        isAllCaps = false
        minWidth = 0; minimumWidth = 0
        setPadding(dp(2), 0, dp(2), 0)
        background = interactiveBackground(color, divider)
        stateListAnimator = null
        setTextColor(if (ColorUtils.calculateLuminance(color) > 0.179) Color.BLACK else Color.WHITE)
        setOnClickListener { action() }
    }

    private fun saveColors(colors: IntArray) {
        prefs.pressUserColors.setValue(PressColorPalette.encode(colors))
        renderColors()
    }

    private fun editColor(color: Int, save: (Int) -> Unit) {
        activeColorDialog?.dismiss()
        activeColorDialog = EffectColorEditor(context, color, save).dialog.also { it.show() }
    }

    fun dismissDialogs() { activeColorDialog?.dismiss(); activeColorDialog = null }
}

/** Native slider; persist once on release rather than rebuilding the keyboard on every pixel. */
class EffectRangeControl(private val context: Context, label: String, private val pref: ManagedPreference.PInt,
    private val min: Int, private val max: Int, private val step: Int, private val unit: String, private val afterCommit: () -> Unit) {
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = pref.key }
    private val accent = ContextCompat.getColor(context, R.color.ax_settings_accent)
    private val value = TextView(context).apply { setTextColor(accent); textSize = 13f; setPadding(dp(8), 0, 0, 0) }
    val slider = SeekBar(context).apply {
        tag = "effect-slider-${pref.key}"
        this.max = (this@EffectRangeControl.max - this@EffectRangeControl.min) / step
        progress = (pref.getValue().coerceIn(this@EffectRangeControl.min, this@EffectRangeControl.max) - this@EffectRangeControl.min) / step
        progressTintList = ColorStateList.valueOf(accent)
        progressBackgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.ax_settings_divider))
        thumbTintList = progressTintList
        contentDescription = label
    }

    init {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.ax_settings_text))
        },
            LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(value)
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(slider, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        updateLabel(pref.getValue().coerceIn(min, max))
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) { updateLabel() }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) { commit() }
        })
    }

    private fun updateLabel(exact: Int = min + slider.progress * step) { value.text = "$exact $unit" }
    fun commit() { pref.setValue((min + slider.progress * step).coerceIn(min, max)); afterCommit() }
}
