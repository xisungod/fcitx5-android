/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos

/** Cached feathered fields grow from the side edges towards the centre. */
internal class IdleGlowPainter(firstColor: Int, secondColor: Int) {
    private fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f,
            intArrayOf(color, color, color and 0x00ffffff),
            floatArrayOf(0f, 0.18f, 1f), Shader.TileMode.CLAMP)
    }
    private val left = paint(firstColor)
    private val right = paint(secondColor)

    fun draw(canvas: Canvas, width: Int, height: Int, alpha: Float, phase: Float) {
        if (width <= 0 || height <= 0 || alpha <= 0f) return
        // Expand inwards, then keep the wide field while it fades away.
        val t = (phase * 2f).coerceIn(0f, 1f)
        val spread = (0.5 - 0.5 * cos(PI * t)).toFloat()
        val radiusX = width * (0.10f + 0.78f * spread)
        val radiusY = height * 0.49f
        val opacity = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        fun side(x: Float, paint: Paint) {
            paint.alpha = opacity
            val save = canvas.save()
            canvas.translate(x, height * 0.5f)
            canvas.scale(radiusX, radiusY)
            canvas.drawCircle(0f, 0f, 1f, paint)
            canvas.restoreToCount(save)
        }
        side(0f, left)
        side(width.toFloat(), right)
    }
}
