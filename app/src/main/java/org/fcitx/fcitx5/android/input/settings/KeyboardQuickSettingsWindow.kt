/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.settings

import android.content.Context
import android.content.res.ColorStateList
import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
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
import org.fcitx.fcitx5.android.data.theme.KeyMotionSettings
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
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
    override fun onDetached() {
        // The title-bar Back action intentionally discards the draft and ends its preview.
        ui.dispose()
    }
}

/** Uses the production key and depth engine, but never routes gestures to an input action. */
private class KeyMotionPreview(context: Context, theme: Theme) : TextKeyView(
    context, theme, KeyDef.Appearance.Text("A", 30f, border = KeyDef.Appearance.Border.On)
) {
    private var configuration = KeyMotionSettings()
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private val releaseClick = Runnable {
        if (isAttachedToWindow && isShown && isEnabled) {
            isPressed = false
            setDepthPressed(false)
        } else {
            stopPreview()
        }
    }

    init {
        contentDescription = context.getString(R.string.key_motion_preview_label)
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun configure(settings: KeyMotionSettings) {
        configuration = settings.normalized()
        setDepthConfiguration(configuration)
    }

    fun stopPreview() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        removeCallbacks(releaseClick)
        isPressed = false
        cancelGestures()
        resetPressDepth()
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun releaseTouch() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        isPressed = false
        setDepthPressed(false)
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || !isAttachedToWindow || !isShown) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(releaseClick)
                activePointerId = event.getPointerId(event.actionIndex)
                isPressed = true
                setDepthPressed(true)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index >= 0 && (event.getX(index) !in 0f..width.toFloat() ||
                            event.getY(index) !in 0f..height.toFloat())) releaseTouch()
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    releaseTouch()
                    // Announce the click without starting the accessibility-only pulse again.
                    super.performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> stopPreview()
        }
        return true
    }

    /** Accessibility activation runs one bounded press/release, never a repeating demo. */
    override fun performClick(): Boolean {
        if (!isEnabled || !isAttachedToWindow || !isShown) return false
        removeCallbacks(releaseClick)
        activePointerId = MotionEvent.INVALID_POINTER_ID
        isPressed = true
        setDepthPressed(true)
        postDelayed(releaseClick, configuration.pressDuration.toLong())
        super.performClick()
        return true
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE) stopPreview()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != View.VISIBLE) stopPreview()
    }

    override fun onDetachedFromWindow() {
        stopPreview()
        super.onDetachedFromWindow()
    }
}

internal class KeyboardQuickSettingsUi(
    private val context: Context,
    private val theme: Theme,
    private val draft: KeyboardQuickSettingsDraft,
    private val disableAnimation: Boolean,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onHeight: () -> Unit,
    onMore: () -> Unit
) {
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val accentColor = if (theme.isDark) 0xFF90C9FF.toInt() else 0xFF356FA5.toInt()
    private val accent = ColorStateList.valueOf(accentColor)
    private var disposed = false
    private val motionPreview = KeyMotionPreview(context, theme).apply { tag = "quick_motion_preview" }
    private val motionControls = mutableListOf<View>()
    private val refreshMotionSliders = mutableListOf<() -> Unit>()
    private val motionAvailability = label("", 12f).apply {
        setTextColor(theme.keyTextColor.alpha(0.65f))
    }
    private val widthEditor by lazy { KeyWidthEditor(context, theme, draft) }
    private var breathingToggle: SwitchCompat? = null
    private val breathingAvailability = label(context.getString(R.string.sam_idle_hint), 12f).apply {
        setTextColor(theme.keyTextColor.alpha(0.65f))
    }

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
        addView(button(R.string.done) { leave(onDone) }.apply { tag = "quick_done" },
            FrameLayout.LayoutParams(context.dp(68), match, Gravity.END))
    }

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(16))
    }

    val root = object : ScrollView(context) {
        override fun onVisibilityChanged(changedView: View, visibility: Int) {
            super.onVisibilityChanged(changedView, visibility)
            if (visibility != View.VISIBLE) motionPreview.stopPreview()
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility != View.VISIBLE) motionPreview.stopPreview()
        }

        override fun onDetachedFromWindow() {
            motionPreview.stopPreview()
            super.onDetachedFromWindow()
        }
    }.apply {
        isFillViewport = true
        setBackgroundColor(theme.keyboardColor)
        addView(content, ViewGroup.LayoutParams(match, wrap))
    }

    fun dispose() {
        disposed = true
        motionPreview.stopPreview()
        updateMotionAvailability()
    }

    private fun leave(action: () -> Unit) {
        // Ignore a click queued on a panel already removed by a theme or window change.
        if (disposed) return
        dispose()
        action()
    }

    private fun updateMotionAvailability() {
        val enabled = !disposed && !disableAnimation && draft.values.keyMotion == ThemePrefs.KeyMotionEffect.Press
        motionPreview.isEnabled = enabled
        motionPreview.alpha = if (enabled) 1f else 0.4f
        motionControls.forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.4f
        }
        if (!enabled) motionPreview.stopPreview()
        motionAvailability.visibility = if (enabled || disposed) View.GONE else View.VISIBLE
        motionAvailability.text = if (disableAnimation) {
            context.getString(R.string.keyboard_quick_settings_disabled_animation)
        } else {
            context.getString(R.string.key_motion_controls_requires_press,
                context.getString(ThemePrefs.KeyMotionEffect.Press.stringRes))
        }
    }

    private fun refreshMotionSettings() {
        motionPreview.configure(draft.values.motionSettings.normalized())
        refreshMotionSliders.forEach { it() }
        updateMotionAvailability()
    }

    private fun motionSlider(
        title: Int, key: String, range: IntRange, step: Int, format: Int,
        read: (KeyMotionSettings) -> Int,
        change: (KeyMotionSettings, Int) -> KeyMotionSettings
    ): View {
        val caption = label("", 12f).apply {
            minHeight = context.dp(28)
            gravity = Gravity.BOTTOM
            setPadding(0, 0, 0, context.dp(2))
        }
        val bar = SeekBar(context).apply {
            tag = key
            contentDescription = context.getString(title)
            max = (range.last - range.first) / step
            progressTintList = accent
            thumbTintList = accent
            progressBackgroundTintList = ColorStateList.valueOf(theme.keyTextColor.alpha(0.2f))
        }
        val refresh = {
            val value = read(draft.values.motionSettings.normalized())
            caption.text = "${context.getString(title)} · ${context.getString(format, value)}"
            bar.progress = (value - range.first) / step
        }
        refreshMotionSliders += refresh
        motionControls += bar
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || !bar.isEnabled || disposed) return
                val next = range.first + progress * step
                draft.values = draft.values.copy(motionSettings = change(draft.values.motionSettings, next).normalized())
                motionPreview.configure(draft.values.motionSettings)
                refresh()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(caption, LinearLayout.LayoutParams(match, wrap))
            addView(bar, LinearLayout.LayoutParams(match, context.dp(40)))
        }
    }

    private fun addMotionControls() {
        val mode = choice(R.string.key_motion_effect, "quick_key_motion",
            ThemePrefs.KeyMotionEffect.entries.toTypedArray(), draft.values.keyMotion, compact = true) {
            draft.values = draft.values.copy(keyMotion = it)
            updateMotionAvailability()
        }
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(context.getString(R.string.key_motion_controls_title), 15f).apply {
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(mode, LinearLayout.LayoutParams(context.dp(150), context.dp(44)))
        }, LinearLayout.LayoutParams(match, wrap))

        val reset = button(R.string.key_motion_controls_reset) {
            draft.resetMotionSettings()
            refreshMotionSettings()
        }.apply {
            tag = "quick_motion_reset"
            textSize = 12f
        }
        motionControls += reset
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(motionPreview, LinearLayout.LayoutParams(context.dp(72), context.dp(72)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(context.getString(R.string.key_motion_preview_hint), 12f).apply {
                    setTextColor(theme.keyTextColor.alpha(0.75f))
                }, LinearLayout.LayoutParams(match, wrap))
                addView(reset, LinearLayout.LayoutParams(wrap, context.dp(44)))
            }, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = context.dp(12) })
        }, LinearLayout.LayoutParams(match, context.dp(76)))

        fun row(left: View, right: View) {
            content.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(left, LinearLayout.LayoutParams(0, wrap, 1f).apply { rightMargin = context.dp(6) })
                addView(right, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = context.dp(6) })
            }, LinearLayout.LayoutParams(match, wrap))
        }
        row(
            motionSlider(R.string.key_motion_press_amplitude, "quick_press_amplitude",
                KeyMotionSettings.PRESS_AMPLITUDE_RANGE, 1, R.string.key_motion_value_percent,
                { it.pressAmplitude }, { motion, value -> motion.copy(pressAmplitude = value) }),
            motionSlider(R.string.key_motion_rebound_amplitude, "quick_rebound_amplitude",
                KeyMotionSettings.REBOUND_AMPLITUDE_RANGE, 1, R.string.key_motion_value_percent,
                { it.reboundAmplitude }, { motion, value -> motion.copy(reboundAmplitude = value) })
        )
        row(
            motionSlider(R.string.key_motion_press_duration, "quick_press_duration",
                KeyMotionSettings.PRESS_DURATION_RANGE, 20, R.string.key_motion_value_ms,
                { it.pressDuration }, { motion, value -> motion.copy(pressDuration = value) }),
            motionSlider(R.string.key_motion_rebound_duration, "quick_rebound_duration",
                KeyMotionSettings.REBOUND_DURATION_RANGE, 100, R.string.key_motion_value_ms,
                { it.reboundDuration }, { motion, value -> motion.copy(reboundDuration = value) })
        )
        content.addView(motionAvailability, LinearLayout.LayoutParams(match, wrap))
        note(R.string.key_motion_timing_hint)
        refreshMotionSettings()
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

    private fun toggle(title: Int, key: String, checked: Boolean, update: (Boolean) -> Unit): SwitchCompat {
        val view = SwitchCompat(context).apply {
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
        }
        content.addView(view, LinearLayout.LayoutParams(match, wrap))
        return view
    }

    private fun updateEffectAvailability() {
        val sam = draft.values.rippleShape == ThemePrefs.RippleShape.Sam
        breathingToggle?.isEnabled = !sam
        breathingToggle?.alpha = if (sam) 0.4f else 1f
        breathingAvailability.visibility = if (sam) View.VISIBLE else View.GONE
    }

    private fun addWidthControls() {
        val editor = widthEditor.root.apply { visibility = View.GONE }
        content.addView(button(R.string.key_width_title) {
            editor.visibility = if (editor.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (editor.visibility == View.VISIBLE) widthEditor.refresh()
        }.apply { tag = "quick_key_width_expand" }, LinearLayout.LayoutParams(match, context.dp(44)))
        content.addView(editor, LinearLayout.LayoutParams(match, wrap))
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
        title: Int, key: String, options: Array<T>, selected: T, compact: Boolean = false, update: (T) -> Unit
    ): Spinner where T : Enum<T>, T : ManagedPreferenceEnum {
        if (!compact) {
            content.addView(label(context.getString(title)).apply {
                setPadding(0, context.dp(10), 0, 0)
            }, LinearLayout.LayoutParams(match, wrap))
        }
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
        if (!compact) content.addView(spinner, LinearLayout.LayoutParams(match, context.dp(44)))
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
        addMotionControls()
        addWidthControls()
        note(R.string.keyboard_quick_settings_hint)
        heading(R.string.keyboard_quick_settings_effects)
        choice(R.string.keyboard_effect_mode, "quick_ripple_shape", ThemePrefs.RippleShape.entries.toTypedArray(), draft.values.rippleShape) {
            draft.values = draft.values.copy(rippleShape = it)
            updateEffectAvailability()
        }
        breathingToggle = toggle(R.string.idle_breathing, "quick_idle_breathing", draft.values.idleBreathing) {
            draft.values = draft.values.copy(idleBreathing = it)
        }
        content.addView(breathingAvailability, LinearLayout.LayoutParams(match, wrap))
        updateEffectAvailability()
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
        content.addView(button(R.string.keyboard_height_adjust) { leave(onHeight) }.apply { tag = "quick_height" },
            LinearLayout.LayoutParams(match, context.dp(44)))
        note(R.string.keyboard_quick_settings_height_summary)
        heading(R.string.keyboard_quick_settings_layout)
        toggle(R.string.keyboard_quick_settings_number_row, "quick_number_row", draft.values.numberRow) {
            draft.values = draft.values.copy(numberRow = it)
            widthEditor.refresh()
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
        content.addView(button(R.string.keyboard_quick_settings_more) { leave(onMore) }.apply { tag = "quick_more" },
            LinearLayout.LayoutParams(match, context.dp(48)))
        note(R.string.keyboard_quick_settings_more_summary)
        content.addView(button(android.R.string.cancel) { leave(onCancel) }.apply { tag = "quick_cancel" },
            LinearLayout.LayoutParams(match, context.dp(48)))
    }
}
