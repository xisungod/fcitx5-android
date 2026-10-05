/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.SystemClock
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
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
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
import kotlin.math.sin

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
    private val accentColor = 0xFF79E8F8.toInt()

    private fun label(size: Float) = TextView(context).apply {
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(0xFFEAF4FC.toInt())
        includeFontPadding = false
    }

    private val status = label(18f).apply {
        tag = "dictation_status"
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.035f
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val message = label(12f).apply {
        tag = "dictation_message"
        setTextColor(0xFF8492A9.toInt())
        maxLines = 4
        setPadding(context.dp(8), context.dp(8), context.dp(8), 0)
    }
    private val record = DictationControl(context).apply {
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
        textSize = 12f
        minHeight = 0
        minimumHeight = 0
        setTextColor(0xFF8B9AB1.toInt())
        setPadding(context.dp(16), 0, context.dp(16), 0)
        compoundDrawablePadding = context.dp(7)
        val icon = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_keyboard_24)!!.mutate()
        icon.setTint(0xFF8B9AB1.toInt())
        icon.setBounds(0, 0, context.dp(17), context.dp(17))
        setCompoundDrawables(icon, null, null, null)
        background = RippleDrawable(ColorStateList.valueOf(accentColor.alpha(0.09f)), null, null)
        setOnClickListener {
            session.cancel()
            onReturn()
        }
    }

    val root = LinearLayout(context).apply {
        tag = "offline_dictation"
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(context.dp(24), context.dp(6), context.dp(24), context.dp(5))
        // A focused listening surface stays dark even when the typing theme is light.
        setBackgroundColor(if (theme.isDark) 0xFF070B13.toInt() else 0xFF0A0F19.toInt())
        addView(record, LinearLayout.LayoutParams(context.dp(192), 0, 1f))
        addView(status, LinearLayout.LayoutParams(match, wrap))
        addView(message, LinearLayout.LayoutParams(match, wrap))
        addView(back, LinearLayout.LayoutParams(wrap, context.dp(44)).apply {
            topMargin = context.dp(5)
        })
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
        record.contentDescription = when (state.phase) {
            OfflineDictationPhase.Finishing, OfflineDictationPhase.Unavailable -> status.text
            else -> context.getString(if (running) R.string.offline_dictation_ui_stop
                else R.string.offline_dictation_ui_start)
        }
        record.isEnabled = state.phase != OfflineDictationPhase.Unavailable &&
            state.phase != OfflineDictationPhase.Finishing
        record.phase = state.phase
        record.invalidate()
    }
}

/** The quiet breathing rim communicates recording state, never a simulated microphone level. */
private class DictationControl(context: Context) : View(context) {
    var phase = OfflineDictationPhase.Ready
        set(value) {
            if (field == value) return
            field = value
            syncAnimation()
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val microphone = AppCompatResources.getDrawable(context, R.drawable.ic_offline_mic_24)!!.mutate()
    private val cyan = 0xFF79E8F8.toInt()
    private val violet = 0xFFB399FF.toInt()
    private var radius = 0f
    private var halo: Shader? = null
    private var surface: Shader? = null
    private var rim: Shader? = null
    private var shine: Shader? = null
    private val born = SystemClock.uptimeMillis()
    private fun appMotionEnabled() = runCatching {
        !AppPrefs.getInstance().advanced.disableAnimation.getValue()
    }.getOrDefault(true)
    private var loopReady = false
    private val frameTick = object : Runnable {
        override fun run() {
            if (!shouldAnimate()) return
            invalidate()
            postDelayed(this, 50L)
        }
    }

    init {
        isClickable = true
        isFocusable = true
        loopReady = true
    }

    private fun shouldAnimate(): Boolean =
        (phase == OfflineDictationPhase.Preparing || phase == OfflineDictationPhase.Recording ||
            phase == OfflineDictationPhase.Finishing) &&
        isShown && isAttachedToWindow && windowVisibility == VISIBLE &&
        appMotionEnabled() &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled())

    private fun syncAnimation() {
        if (!loopReady) return
        removeCallbacks(frameTick)
        if (shouldAnimate()) postDelayed(frameTick, 50L)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimation()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameTick)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncAnimation()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.ImageButton::class.java.name

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val x = w / 2f
        val y = h / 2f
        radius = min(context.dp(54).toFloat(), min(w, h) * 0.32f)
        if (radius <= 0f) return
        halo = RadialGradient(x, y, radius * 1.53f,
            intArrayOf(0x006ECDF1, 0x206ACDEC, 0x08795FE6, Color.TRANSPARENT),
            floatArrayOf(0f, 0.65f, 0.84f, 1f), Shader.TileMode.CLAMP)
        surface = RadialGradient(x - radius * 0.4f, y - radius * 0.48f, radius * 1.85f,
            intArrayOf(0xFF203D51.toInt(), 0xFF101D32.toInt(), 0xFF171426.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        rim = SweepGradient(x, y,
            intArrayOf(0xFF6DC0F5.toInt(), violet, 0xFF6172C6.toInt(), cyan, 0xFF6DC0F5.toInt()),
            floatArrayOf(0f, 0.24f, 0.48f, 0.76f, 1f))
        shine = LinearGradient(x - radius, y - radius, x + radius, y + radius,
            intArrayOf(0xFFE0FCFF.toInt(), 0x80A5D8F4.toInt(), 0xFFBBA5FF.toInt()),
            floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return
        val x = width / 2f
        val y = height / 2f
        val running = phase == OfflineDictationPhase.Preparing || phase == OfflineDictationPhase.Recording
        val busy = phase == OfflineDictationPhase.Finishing
        val animate = shouldAnimate()
        val pulse = if (animate) ((sin((SystemClock.uptimeMillis() - born) / 2400.0 * Math.PI * 2) + 1) / 2).toFloat() else 0.35f
        val opacity = if (phase == OfflineDictationPhase.Unavailable) 0.4f else 1f
        val press = if (isPressed && isEnabled) 0.97f else 1f
        val checkpoint = canvas.save()
        canvas.scale(press, press, x, y)

        paint.style = Paint.Style.FILL
        paint.shader = halo
        paint.alpha = ((0.75f + pulse * 0.25f) * opacity * 255).toInt()
        canvas.drawCircle(x, y, radius * 1.53f, paint)
        paint.shader = surface
        paint.alpha = (opacity * 255).toInt()
        canvas.drawCircle(x, y, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.shader = rim
        paint.strokeWidth = context.dp(1.25f).toFloat()
        paint.alpha = (opacity * (0.66f + pulse * 0.18f) * 255).toInt()
        canvas.drawCircle(x, y, radius, paint)
        paint.shader = null
        paint.color = 0xFF89C4E4.toInt().alpha(0.085f * opacity)
        paint.strokeWidth = context.dp(0.7f).toFloat()
        canvas.drawCircle(x, y, radius * 0.86f, paint)

        // Two small light accents give the orb direction without a busy radar or fake waveform.
        val orbit = radius * 1.14f
        bounds.set(x - orbit, y - orbit, x + orbit, y + orbit)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = context.dp(1.4f).toFloat()
        paint.color = cyan.alpha(0.62f * opacity)
        canvas.drawArc(bounds, 215f, 24f, false, paint)
        paint.color = violet.alpha(0.54f * opacity)
        canvas.drawArc(bounds, 38f, 17f, false, paint)
        paint.strokeCap = Paint.Cap.BUTT

        paint.style = Paint.Style.FILL
        paint.shader = shine
        paint.alpha = (opacity * 255).toInt()
        if (running) {
            val half = radius * 0.19f
            val rounding = radius * 0.065f
            canvas.drawRoundRect(x - half, y - half, x + half, y + half, rounding, rounding, paint)
        } else if (busy) {
            val dot = radius * 0.038f
            for (i in -1..1) canvas.drawCircle(x + radius * 0.18f * i, y, dot, paint)
        } else {
            paint.shader = null
            val half = (radius * 0.32f).toInt()
            microphone.setBounds(x.toInt() - half, y.toInt() - half, x.toInt() + half, y.toInt() + half)
            microphone.setTint(0xFFD8F6FF.toInt().alpha(opacity))
            microphone.draw(canvas)
        }
        paint.shader = null
        paint.alpha = 255
        canvas.restoreToCount(checkpoint)
        // Only the small control redraws; the backdrop and labels stay still.
    }
}
