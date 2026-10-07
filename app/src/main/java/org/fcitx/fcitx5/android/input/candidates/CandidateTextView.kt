/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.candidates

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.widget.TextView
import androidx.core.graphics.withSave
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Single-line, span-aware candidate rendering. AutoScaleTextView draws a plain String,
 * which discards the comment's color and size spans; keyboard glyphs keep that view.
 * A constrained expanded slot still scales proportionally, without altering its hit area.
 */
@SuppressLint("AppCompatCustomView")
internal class CandidateTextView(context: Context) : TextView(context) {
    private var candidateLayout: StaticLayout? = null
    private var laidOutText: CharSequence? = null
    private var laidOutSize = 0f
    private var laidOutColor = 0
    private var laidOutTypeface: android.graphics.Typeface? = null
    private var contentWidth = 0f

    private fun prepareLayout(): StaticLayout {
        candidateLayout?.let { layout ->
            if (laidOutText === text && laidOutSize == textSize &&
                laidOutColor == currentTextColor && laidOutTypeface == typeface) return layout
        }
        val textPaint = TextPaint(paint).apply { color = currentTextColor }
        contentWidth = Layout.getDesiredWidth(text, textPaint)
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint,
            ceil(contentWidth).toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(includeFontPadding)
            .setMaxLines(1)
            .build().also {
                candidateLayout = it
                laidOutText = text
                laidOutSize = textSize
                laidOutColor = currentTextColor
                laidOutTypeface = typeface
            }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val layout = prepareLayout()
        setMeasuredDimension(
            resolveSize(maxOf(suggestedMinimumWidth, ceil(contentWidth).toInt() + paddingLeft + paddingRight),
                widthMeasureSpec),
            resolveSize(maxOf(suggestedMinimumHeight, layout.height + paddingTop + paddingBottom), heightMeasureSpec)
        )
    }

    private data class Placement(val x: Float, val y: Float, val scale: Float)

    private fun placement(viewWidth: Int, viewHeight: Int, layout: StaticLayout): Placement {
        val availableWidth = (viewWidth - paddingLeft - paddingRight).coerceAtLeast(0)
        val scale = if (contentWidth > 0f) min(1f, availableWidth / contentWidth) else 1f
        val horizontalGravity = Gravity.getAbsoluteGravity(gravity, layoutDirection) and
            Gravity.HORIZONTAL_GRAVITY_MASK
        val x = when (horizontalGravity) {
            Gravity.LEFT -> paddingLeft.toFloat()
            Gravity.RIGHT -> viewWidth - paddingRight - contentWidth * scale
            else -> paddingLeft + (availableWidth - contentWidth * scale) / 2f
        }
        val availableHeight = viewHeight - paddingTop - paddingBottom
        val y = when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.TOP -> paddingTop.toFloat()
            Gravity.BOTTOM -> viewHeight - paddingBottom - layout.height * scale
            else -> paddingTop + (availableHeight - layout.height * scale) / 2f
        }
        return Placement(x, y, scale)
    }

    override fun onDraw(canvas: Canvas) {
        val layout = prepareLayout()
        val placement = placement(width, height, layout)
        canvas.withSave {
            translate(placement.x, placement.y)
            scale(placement.scale, placement.scale)
            layout.draw(this)
        }
    }

    override fun getBaseline(): Int {
        val layout = prepareLayout()
        val placement = placement(measuredWidth, measuredHeight, layout)
        return (placement.y + layout.getLineBaseline(0) * placement.scale).roundToInt()
    }

    /** Complete primary text bounds in screen coordinates; comments are excluded. */
    fun mainTextBounds(length: Int, bounds: Rect): Boolean {
        if (length <= 0 || length > text.length || width <= 0 || height <= 0) return false
        val layout = prepareLayout()
        val placement = placement(width, height, layout)
        val location = IntArray(2)
        getLocationOnScreen(location)
        // The union of selection runs also covers mixed-direction text and
        // supplementary characters; start/end advances alone miss internal bidi runs.
        val path = Path()
        layout.getSelectionPath(0, length, path)
        val localBounds = RectF()
        path.computeBounds(localBounds, true)
        val left = location[0] + placement.x + localBounds.left * placement.scale
        val right = location[0] + placement.x + localBounds.right * placement.scale
        val top = location[1] + placement.y + localBounds.top * placement.scale
        val bottom = location[1] + placement.y + localBounds.bottom * placement.scale
        bounds.set(floor(left).toInt(), floor(top).toInt(), ceil(right).toInt(), ceil(bottom).toInt())
        return !bounds.isEmpty
    }
}
