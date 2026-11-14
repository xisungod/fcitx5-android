/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Dictation status shown in place of the toolbar. The keyboard stays where it is and the
 * recognised words go straight into the editor, so this strip only says that the microphone
 * is on, shows how loud the input is, and offers one tap to finish.
 */
class InlineDictationBar(
    context: Context,
    private val textColor: Int,
    private val accentColor: Int,
    private val accentTextColor: Int,
    microphone: Drawable?
) : LinearLayout(context) {

    enum class Mode { Preparing, Listening, Finishing, Notice }

    /** The app's and the system's animation switches. Checked on every frame. */
    var animationsAllowed: () -> Boolean = { true }

    var mode = Mode.Preparing
        private set

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = (value * density + 0.5f).toInt()

    private val orb = Orb(context, microphone?.mutate())
    private val wave = Wave(context)
    private val label = TextView(context).apply {
        tag = "inline_dictation_label"
        setTextColor(textColor)
        includeFontPadding = false
        ellipsize = TextUtils.TruncateAt.END
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val done = TextView(context).apply {
        tag = "inline_dictation_done"
        setTextColor(accentTextColor)
        textSize = 13f
        includeFontPadding = false
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(14f), 0, dp(14f), 0)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16f).toFloat()
            setColor(accentColor)
        }
        // The whole strip is one button; this pill only shows where the tap leads.
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        tag = "inline_dictation"
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setPadding(dp(8f), 0, dp(10f), 0)
        addView(orb, LayoutParams(dp(40f), dp(40f)))
        addView(wave, LayoutParams(0, dp(30f), 1f).apply { marginStart = dp(6f) })
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(10f)
        })
        addView(done, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32f)).apply { marginStart = dp(12f) })
        render(Mode.Preparing, "", "")
    }

    /**
     * [status] is the short state name. In [Mode.Notice] it is the whole message and the
     * level display is replaced by it; [action] names what a tap on the strip does.
     */
    fun render(mode: Mode, status: CharSequence, action: CharSequence) {
        this.mode = mode
        val notice = mode == Mode.Notice
        label.text = status
        label.textSize = if (notice) 12f else 14f
        label.maxLines = if (notice) 2 else 1
        label.alpha = if (mode == Mode.Listening || notice) 1f else 0.72f
        (label.layoutParams as LayoutParams).apply {
            width = if (notice) 0 else LayoutParams.WRAP_CONTENT
            weight = if (notice) 1f else 0f
        }
        label.requestLayout()
        wave.visibility = if (notice) View.GONE else View.VISIBLE
        done.text = action
        done.visibility = if (action.isEmpty()) View.GONE else View.VISIBLE
        done.alpha = if (mode == Mode.Finishing) 0.45f else 1f
        isEnabled = mode != Mode.Finishing
        contentDescription = if (action.isEmpty()) status else "$status, $action"
        val live = mode == Mode.Listening
        orb.live = live
        orb.dimmed = notice || mode == Mode.Finishing
        wave.live = live
        if (!live) setLevel(0f)
        orb.invalidate()
        wave.invalidate()
    }

    /** Microphone loudness, 0..1. Safe to call for every audio chunk. */
    fun setLevel(level: Float) {
        val value = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
        orb.target = value
        wave.target = value
        if (!animationsAllowed()) {
            orb.invalidate()
            wave.invalidate()
        }
    }

    private fun motion() = isShown && animationsAllowed()

    /** A filled disc that swells with the voice; the microphone glyph stays still inside it. */
    private inner class Orb(context: Context, private val icon: Drawable?) : View(context) {
        var target = 0f
        var live = false
        var dimmed = false
        private var shown = 0f
        private var last = 0L
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val now = SystemClock.uptimeMillis()
            val moving = live && motion()
            if (moving) {
                val elapsed = if (last == 0L) 16L else (now - last).coerceIn(1L, 64L)
                // Rise quickly with the voice and fall back slowly, like a level meter.
                val rate = if (target > shown) 0.022f else 0.006f
                shown += (target - shown) * min(1f, rate * elapsed)
            } else shown = if (live) target else 0f
            last = now
            val x = width / 2f
            val y = height / 2f
            val base = min(width, height) * 0.36f
            val opacity = if (dimmed) 0.4f else 1f
            paint.color = accentColor
            if (live) {
                val breath = if (moving) 0.5f + 0.5f * sin(now / 900.0 * Math.PI).toFloat() else 0.5f
                paint.alpha = ((0.16f + 0.10f * breath) * 255).toInt()
                canvas.drawCircle(x, y, base * (1.12f + 0.26f * shown), paint)
            }
            paint.alpha = ((if (live) 1f else 0.55f) * opacity * 255).toInt()
            canvas.drawCircle(x, y, base, paint)
            icon?.let {
                val half = (base * 0.62f).toInt()
                it.setBounds(x.toInt() - half, y.toInt() - half, x.toInt() + half, y.toInt() + half)
                it.setTint(accentTextColor)
                it.alpha = (opacity * 255).toInt()
                it.draw(canvas)
            }
            if (moving) postInvalidateOnAnimation()
        }

        override fun onVisibilityChanged(changedView: View, visibility: Int) {
            super.onVisibilityChanged(changedView, visibility)
            last = 0L
            if (visibility == VISIBLE) invalidate()
        }
    }

    /** Recent loudness as rounded bars, newest on the right, sliding left at a steady pace. */
    private inner class Wave(context: Context) : View(context) {
        var target = 0f
        var live = false
        private val history = FloatArray(BARS)
        private var smoothed = 0f
        private var phase = 0f
        private var last = 0L
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        private fun push(value: Float) {
            System.arraycopy(history, 1, history, 0, BARS - 1)
            history[BARS - 1] = value
        }

        override fun onDraw(canvas: Canvas) {
            val now = SystemClock.uptimeMillis()
            val moving = live && motion()
            if (!live) {
                history.fill(0f)
                smoothed = 0f
                phase = 0f
            } else if (moving) {
                val elapsed = if (last == 0L) 16L else (now - last).coerceIn(1L, 64L)
                val rate = if (target > smoothed) 0.03f else 0.008f
                smoothed += (target - smoothed) * min(1f, rate * elapsed)
                phase += elapsed / STEP_MS
                while (phase >= 1f) {
                    phase -= 1f
                    push(smoothed)
                }
            } else {
                // Without motion the bars still report the level, one step per audio chunk.
                push(target)
                phase = 0f
            }
            last = now
            val pitch = width.toFloat() / (BARS - 1)
            val thickness = max(density * 2f, pitch * 0.42f)
            val middle = height / 2f
            val reach = height / 2f - thickness / 2f
            paint.color = accentColor
            paint.strokeWidth = thickness
            paint.strokeCap = Paint.Cap.ROUND
            for (i in 0 until BARS) {
                val x = (i - phase) * pitch
                if (x < -pitch || x > width + pitch) continue
                val value = if (i == BARS - 1 && moving) smoothed else history[i]
                // Older bars fade out toward the left edge instead of ending abruptly.
                val age = (x / width).coerceIn(0f, 1f)
                paint.alpha = ((0.25f + 0.75f * age) * (if (live) 255 else 110)).toInt()
                val half = max(0f, reach * (0.06f + 0.94f * value))
                canvas.drawLine(x, middle - half, x, middle + half, paint)
            }
            if (moving) postInvalidateOnAnimation()
        }

        override fun onVisibilityChanged(changedView: View, visibility: Int) {
            super.onVisibilityChanged(changedView, visibility)
            last = 0L
            if (visibility == VISIBLE) invalidate()
        }
    }

    private companion object {
        const val BARS = 26
        const val STEP_MS = 70f
    }
}
