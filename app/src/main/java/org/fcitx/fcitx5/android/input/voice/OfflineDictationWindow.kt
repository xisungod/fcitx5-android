/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.alpha
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import kotlin.math.min

/** The session owns editor-bound live transcription; this window only controls its lifetime. */
class OfflineDictationWindow(
    private val session: OfflineDictationSession
) : InputWindow.ExtendedInputWindow<OfflineDictationWindow>() {
    private val service by manager.inputMethodService()
    private val theme by manager.theme()
    private val windows: InputWindowManager by manager.must()
    private var stateJob: Job? = null
    private var started = false
    private var closed = false

    override val title: String get() = context.getString(R.string.offline_dictation_ui_title)

    private val ui by lazy {
        OfflineDictationUi(context, theme, session) { windows.attachWindow(KeyboardWindow) }
    }

    override fun onCreateView(): View = ui.root

    override fun onAttached() {
        if (closed) return
        ui.render(session.state.value)
        stateJob?.cancel()
        stateJob = service.lifecycleScope.launch {
            session.state.collect { ui.render(it) }
        }
        if (!started) {
            started = true
            session.start()
            if (!closed) ui.render(session.state.value)
        }
    }

    override fun onDetached() {
        if (closed) return
        closed = true
        stateJob?.cancel()
        stateJob = null
        // Keep text already entered, stop capture, and invalidate callbacks from this old editor.
        session.cancel()
        session.close()
    }
}

/** The editor displays the words. The keyboard shows just the microphone and local status. */
internal class OfflineDictationUi(
    private val context: Context,
    private val theme: Theme,
    private val session: OfflineDictationSession,
    onReturn: () -> Unit
) {
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val accentColor = if (theme.isDark) 0xFF83D8EF.toInt() else 0xFF287DA0.toInt()

    private fun label(size: Float) = TextView(context).apply {
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(theme.keyTextColor)
    }

    private val status = label(17f).apply {
        tag = "dictation_status"
        typeface = Typeface.DEFAULT_BOLD
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val message = label(12f).apply {
        tag = "dictation_message"
        setTextColor(theme.keyTextColor.alpha(0.6f))
        setPadding(context.dp(4), context.dp(7), context.dp(4), 0)
    }
    private val record = DictationControl(context, accentColor).apply {
        tag = "dictation_record"
        setOnClickListener {
            when (session.state.value.phase) {
                OfflineDictationPhase.Preparing, OfflineDictationPhase.Recording -> session.stop()
                OfflineDictationPhase.Ready, OfflineDictationPhase.Finished,
                OfflineDictationPhase.Error -> session.start()
                OfflineDictationPhase.Finishing, OfflineDictationPhase.Unavailable -> Unit
            }
            render(session.state.value)
        }
    }
    private val back = Button(context).apply {
        tag = "dictation_back"
        setText(R.string.back_to_keyboard)
        isAllCaps = false
        textSize = 13f
        minHeight = 0
        minimumHeight = 0
        setTextColor(theme.keyTextColor.alpha(0.65f))
        background = RippleDrawable(ColorStateList.valueOf(accentColor.alpha(0.1f)), null, null)
        setOnClickListener {
            session.cancel()
            onReturn()
        }
    }

    val root = LinearLayout(context).apply {
        tag = "offline_dictation"
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(context.dp(20), context.dp(10), context.dp(20), context.dp(5))
        setBackgroundColor(theme.keyboardColor)
        addView(status, LinearLayout.LayoutParams(match, wrap))
        addView(message, LinearLayout.LayoutParams(match, wrap))
        addView(record, LinearLayout.LayoutParams(match, 0, 1f))
        addView(back, LinearLayout.LayoutParams(context.dp(144), context.dp(40)))
    }

    init { render(session.state.value) }

    fun render(state: OfflineDictationState) {
        status.setText(when (state.phase) {
            OfflineDictationPhase.Ready -> R.string.offline_dictation_ui_ready
            OfflineDictationPhase.Preparing -> R.string.offline_dictation_ui_preparing
            OfflineDictationPhase.Recording -> R.string.offline_dictation_ui_recording
            OfflineDictationPhase.Finishing -> R.string.offline_dictation_ui_finishing
            OfflineDictationPhase.Finished -> R.string.offline_dictation_ui_finished
            OfflineDictationPhase.Unavailable -> R.string.offline_dictation_ui_unavailable
            OfflineDictationPhase.Error -> R.string.offline_dictation_ui_error
        })
        message.text = state.message ?: context.getString(when (state.phase) {
            OfflineDictationPhase.Ready -> R.string.offline_dictation_ui_ready_hint
            OfflineDictationPhase.Preparing -> R.string.offline_dictation_ui_preparing_hint
            OfflineDictationPhase.Recording -> R.string.offline_dictation_ui_recording_hint
            OfflineDictationPhase.Finishing -> R.string.offline_dictation_ui_finishing_hint
            OfflineDictationPhase.Finished -> R.string.offline_dictation_ui_finished_hint
            OfflineDictationPhase.Unavailable -> R.string.offline_dictation_ui_unavailable_hint
            OfflineDictationPhase.Error -> R.string.offline_dictation_ui_error_hint
        })
        val running = state.phase == OfflineDictationPhase.Preparing || state.phase == OfflineDictationPhase.Recording
        record.contentDescription = context.getString(if (running)
            R.string.offline_dictation_ui_stop else R.string.offline_dictation_ui_start)
        record.isEnabled = state.phase != OfflineDictationPhase.Unavailable &&
            state.phase != OfflineDictationPhase.Finishing
        record.running = running
        record.invalidate()
    }
}

/** A real microphone/stop affordance, with a quiet halo instead of a simulated waveform. */
private class DictationControl(context: Context, private val accentColor: Int) : View(context) {
    var running = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val microphone = AppCompatResources.getDrawable(context, R.drawable.ic_offline_mic_24)!!.mutate()

    init {
        isClickable = true
        isFocusable = true
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.ImageButton::class.java.name

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = width / 2f
        val y = height / 2f
        val radius = min(context.dp(48).toFloat(), min(width, height) * 0.36f)
        if (radius <= 0f) return
        val opacity = if (isEnabled) 1f else 0.35f
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(x, y, radius * 1.36f,
            intArrayOf(accentColor.alpha(0.2f * opacity), accentColor.alpha(0f)),
            floatArrayOf(0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, radius * 1.36f, paint)
        paint.shader = null
        paint.color = accentColor.alpha((if (isPressed) 0.27f else 0.14f) * opacity)
        canvas.drawCircle(x, y, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(1.5f).toFloat()
        paint.color = accentColor.alpha(0.65f * opacity)
        canvas.drawCircle(x, y, radius, paint)
        paint.style = Paint.Style.FILL
        if (running) {
            val half = radius * 0.24f
            paint.color = accentColor.alpha(opacity)
            canvas.drawRoundRect(x - half, y - half, x + half, y + half,
                context.dp(4).toFloat(), context.dp(4).toFloat(), paint)
        } else {
            val half = (radius * 0.43f).toInt()
            microphone.setBounds(x.toInt() - half, y.toInt() - half, x.toInt() + half, y.toInt() + half)
            microphone.setTint(accentColor.alpha(opacity))
            microphone.draw(canvas)
        }
    }
}
