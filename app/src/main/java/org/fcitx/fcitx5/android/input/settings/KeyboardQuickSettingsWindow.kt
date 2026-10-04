/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.data.theme.PressPaletteCards
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.alpha
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp

/** Local, staged controls in the IME itself; no Activity or dialog is needed for common settings. */
class KeyboardQuickSettingsWindow : InputWindow.ExtendedInputWindow<KeyboardQuickSettingsWindow>() {
    private val inputView by manager.inputView()
    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()
    private val draft by lazy { KeyboardQuickSettingsDraft(ThemeManager.prefs, AppPrefs.getInstance().keyboard) }

    override val title: String get() = context.getString(R.string.keyboard_quick_settings)

    private val ui by lazy {
        KeyboardQuickSettingsUi(
            context, theme, draft,
            disableAnimation = AppPrefs.getInstance().advanced.disableAnimation.getValue(),
            onDone = {
                // Preference callbacks can immediately replace this entire InputView. Capture the
                // draft and leave the panel first, then never touch its manager after applying.
                val pending = draft
                windowManager.attachWindow(KeyboardWindow)
                pending.apply()
            },
            onCancel = { windowManager.attachWindow(KeyboardWindow) },
            onHeight = {
                val target = inputView
                windowManager.attachWindow(KeyboardWindow)
                target.showKeyboardHeightEditor()
            },
            onMore = {
                val target = context
                windowManager.attachWindow(KeyboardWindow)
                AppUtil.launchMain(target)
            }
        )
    }

    override fun onCreateView(): View = ui.root
    override fun onCreateBarExtension(): View = ui.extension
    override fun onAttached() {}
    override fun onDetached() {} // The title-bar Back action intentionally discards the draft.
}

internal class KeyboardQuickSettingsUi(
    private val context: Context,
    private val theme: Theme,
    private val draft: KeyboardQuickSettingsDraft,
    disableAnimation: Boolean,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onHeight: () -> Unit,
    onMore: () -> Unit
) {
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val accentColor = if (theme.isDark) 0xFF90C9FF.toInt() else 0xFF356FA5.toInt()
    private val accent = ColorStateList.valueOf(accentColor)

    private fun label(text: CharSequence, size: Float = 14f) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(theme.keyTextColor)
    }

    private fun button(text: Int, action: () -> Unit) = Button(context).apply {
        setText(text)
        isAllCaps = false
        textSize = 14f
        setTextColor(theme.keyTextColor)
        background = RippleDrawable(ColorStateList.valueOf(theme.keyTextColor.alpha(0.12f)),
            GradientDrawable().apply {
                cornerRadius = context.dp(8).toFloat()
                setColor(theme.keyBackgroundColor)
            }, null)
        minHeight = context.dp(44)
        setOnClickListener { action() }
    }

    val extension: View = FrameLayout(context).apply {
        addView(button(R.string.done, onDone).apply { tag = "quick_done" },
            FrameLayout.LayoutParams(context.dp(68), match, Gravity.END))
    }

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(16))
    }

    val root = ScrollView(context).apply {
        isFillViewport = true
        setBackgroundColor(theme.keyboardColor)
        addView(content, ViewGroup.LayoutParams(match, wrap))
    }

    private fun heading(title: Int) {
        content.addView(label(context.getString(title), 15f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, context.dp(16), 0, context.dp(6))
        }, LinearLayout.LayoutParams(match, wrap))
    }

    private fun note(text: Int) {
        content.addView(label(context.getString(text), 12f).apply {
            setTextColor(theme.keyTextColor.alpha(0.65f))
            setPadding(0, 0, 0, context.dp(6))
        }, LinearLayout.LayoutParams(match, wrap))
    }

    private fun toggle(title: Int, key: String, checked: Boolean, update: (Boolean) -> Unit) {
        content.addView(SwitchCompat(context).apply {
            tag = key
            setText(title)
            textSize = 14f
            setTextColor(theme.keyTextColor)
            setPadding(0, context.dp(4), 0, context.dp(4))
            minHeight = context.dp(48)
            isChecked = checked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accentColor, theme.keyTextColor.alpha(0.6f)))
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accentColor.alpha(0.4f), theme.keyTextColor.alpha(0.2f)))
            setOnCheckedChangeListener { _, value -> update(value) }
        }, LinearLayout.LayoutParams(match, wrap))
    }

    private fun slider(
        title: Int, key: String, value: Int, min: Int, max: Int, step: Int,
        format: Int = R.string.keyboard_quick_settings_value_percent, update: (Int) -> Unit
    ) {
        val caption = label("${context.getString(title)} · ${context.getString(format, value)}")
        caption.setPadding(0, context.dp(8), 0, 0)
        content.addView(caption, LinearLayout.LayoutParams(match, wrap))
        content.addView(SeekBar(context).apply {
            tag = key
            contentDescription = context.getString(title)
            this.max = (max - min) / step
            progress = (value.coerceIn(min, max) - min) / step
            progressTintList = accent
            thumbTintList = accent
            progressBackgroundTintList = ColorStateList.valueOf(theme.keyTextColor.alpha(0.2f))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val next = min + progress * step
                    caption.text = "${context.getString(title)} · ${context.getString(format, next)}"
                    update(next)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(match, context.dp(40)))
    }

    private fun <T> choice(
        title: Int, key: String, options: Array<T>, selected: T, update: (T) -> Unit
    ): Spinner where T : Enum<T>, T : ManagedPreferenceEnum {
        content.addView(label(context.getString(title)).apply {
            setPadding(0, context.dp(10), 0, 0)
        }, LinearLayout.LayoutParams(match, wrap))
        val spinner = Spinner(context).apply {
            tag = key
            contentDescription = context.getString(title)
            adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item,
                options.map { context.getString(it.stringRes) }) {
                init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getView(position, convertView, parent).also { (it as TextView).setTextColor(theme.keyTextColor) }
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getDropDownView(position, convertView, parent).also {
                        (it as TextView).setTextColor(theme.popupTextColor)
                        it.setBackgroundColor(theme.popupBackgroundColor)
                    }
            }
            setSelection(options.indexOf(selected))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    update(options[position])
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        content.addView(spinner, LinearLayout.LayoutParams(match, context.dp(44)))
        return spinner
    }

    private var colorModeSpinner: Spinner? = null
    private val paletteSummary = label("", 12f)
    private val currentPalettePreview = LinearLayout(context).apply {
        tag = "quick_current_palette"
        orientation = LinearLayout.HORIZONTAL
    }
    private val paletteCards by lazy {
        PressPaletteCards(context, theme, "quick") { preset ->
            draft.values = draft.values.copy(colorMode = ThemePrefs.PressColorMode.Random, palette = preset)
            colorModeSpinner?.setSelection(ThemePrefs.PressColorMode.Random.ordinal)
            updatePalettePreview()
        }
    }

    private fun updatePalettePreview() {
        val mode = draft.values.colorMode
        val preset = draft.values.palette
        paletteCards.select(preset.takeIf { mode == ThemePrefs.PressColorMode.Random })
        val title = if (mode == ThemePrefs.PressColorMode.Random) PressPaletteCards.titleFor(preset) else mode.stringRes
        paletteSummary.text = context.getString(R.string.cyber_palette_current, context.getString(title))
        currentPalettePreview.removeAllViews()
        draft.currentColors(theme.accentKeyBackgroundColor).forEach { color ->
            currentPalettePreview.addView(View(context).apply { setBackgroundColor(color) },
                LinearLayout.LayoutParams(0, match, 1f))
        }
    }

    init {
        note(R.string.keyboard_quick_settings_hint)
        heading(R.string.keyboard_quick_settings_effects)
        choice(R.string.ripple_shape, "quick_ripple_shape", ThemePrefs.RippleShape.entries.toTypedArray(), draft.values.rippleShape) {
            draft.values = draft.values.copy(rippleShape = it)
        }
        toggle(R.string.idle_breathing, "quick_idle_breathing", draft.values.idleBreathing) {
            draft.values = draft.values.copy(idleBreathing = it)
        }
        colorModeSpinner = choice(R.string.press_color_mode, "quick_color_mode",
            ThemePrefs.PressColorMode.entries.toTypedArray(), draft.values.colorMode) {
            draft.values = draft.values.copy(colorMode = it)
            updatePalettePreview()
        }
        note(R.string.cyber_palette_hint)
        content.addView(paletteCards.root, LinearLayout.LayoutParams(match, context.dp(84)))
        content.addView(paletteSummary, LinearLayout.LayoutParams(match, wrap).apply { topMargin = context.dp(8) })
        content.addView(currentPalettePreview, LinearLayout.LayoutParams(match, context.dp(8)).apply {
            topMargin = context.dp(4); bottomMargin = context.dp(8)
        })
        updatePalettePreview()
        choice(R.string.key_motion_effect, "quick_key_motion", ThemePrefs.KeyMotionEffect.entries.toTypedArray(), draft.values.keyMotion) {
            draft.values = draft.values.copy(keyMotion = it)
        }
        if (disableAnimation) note(R.string.keyboard_quick_settings_disabled_animation)
        toggle(R.string.press_effect, "quick_press_effect", draft.values.pressEffect) {
            draft.values = draft.values.copy(pressEffect = it)
        }
        slider(R.string.press_key_retreat_time, "quick_key_retreat", draft.values.keyRetreatTime,
            20, maxOf(500, draft.values.keyRetreatTime).coerceAtMost(5000), 10,
            R.string.keyboard_quick_settings_value_ms) {
            draft.values = draft.values.copy(keyRetreatTime = it)
        }
        note(R.string.keyboard_quick_settings_retreat_hint)
        slider(R.string.press_glow_brightness, "quick_glow_brightness", draft.values.glowBrightness, 0, 100, 5) {
            draft.values = draft.values.copy(glowBrightness = it)
        }
        slider(R.string.press_glow_reach, "quick_glow_reach", draft.values.glowReach, 20, 100, 5) {
            draft.values = draft.values.copy(glowReach = it)
        }
        toggle(R.string.press_glow_on_candidates, "quick_candidate_glow", draft.values.candidateGlow) {
            draft.values = draft.values.copy(candidateGlow = it)
        }
        content.addView(button(R.string.keyboard_height_adjust, onHeight).apply { tag = "quick_height" },
            LinearLayout.LayoutParams(match, context.dp(44)))
        note(R.string.keyboard_quick_settings_height_summary)
        heading(R.string.keyboard_quick_settings_layout)
        toggle(R.string.keyboard_quick_settings_number_row, "quick_number_row", draft.values.numberRow) {
            draft.values = draft.values.copy(numberRow = it)
        }
        toggle(R.string.popup_on_key_press, "quick_popup", draft.values.popup) {
            draft.values = draft.values.copy(popup = it)
        }
        choice(R.string.button_haptic_feedback, "quick_haptic_mode", InputFeedbackMode.entries.toTypedArray(), draft.values.hapticMode) {
            draft.values = draft.values.copy(hapticMode = it)
        }
        slider(R.string.haptic_strength, "quick_haptic_strength", draft.values.hapticStrength, 0, 100, 5) {
            draft.values = draft.values.copy(hapticStrength = it)
        }
        note(R.string.keyboard_quick_settings_haptic_hint)
        content.addView(button(R.string.keyboard_quick_settings_more, onMore).apply { tag = "quick_more" },
            LinearLayout.LayoutParams(match, context.dp(48)))
        note(R.string.keyboard_quick_settings_more_summary)
        content.addView(button(android.R.string.cancel, onCancel).apply { tag = "quick_cancel" },
            LinearLayout.LayoutParams(match, context.dp(48)))
    }
}
