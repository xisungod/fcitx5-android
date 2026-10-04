/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.bar

import android.content.Context
import android.graphics.Canvas
import android.widget.ViewAnimator
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard

/** Continues keyboard ripples behind candidate text, without a second animation or touch layer. */
class RippleBarView(context: Context) : ViewAnimator(context) {
    private val keyboardPosition = IntArray(2)
    private val barPosition = IntArray(2)
    var keyboard: BaseKeyboard? = null
        set(value) {
            field?.effectInvalidator = null
            field = value
            value?.effectInvalidator = { invalidate() }
            invalidate()
        }

    override fun dispatchDraw(canvas: Canvas) {
        keyboard?.takeIf { it.isShown && it.isAttachedToWindow }?.let { source ->
            source.getLocationInWindow(keyboardPosition)
            getLocationInWindow(barPosition)
            val saved = canvas.save()
            canvas.clipRect(0, 0, width, height)
            canvas.translate((keyboardPosition[0] - barPosition[0]).toFloat(),
                (keyboardPosition[1] - barPosition[1]).toFloat())
            source.drawPressExtension(canvas)
            canvas.restoreToCount(saved)
        }
        super.dispatchDraw(canvas)
    }
}
