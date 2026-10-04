/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.PressColorPalette

/** Native HSV editor with an exact RGB value; every RGB colour is available. */
internal class EffectColorEditor(context: Context, initialColor: Int, private val onSave: (Int) -> Unit) {
    private val ctx = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
    private fun dp(value: Int) = (value * ctx.resources.displayMetrics.density).toInt()
    private val hsv = FloatArray(3).also { Color.colorToHSV(initialColor, it) }
    private var updating = false
    private var color = initialColor or Color.BLACK
    private val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(12), dp(24), dp(8))
    }
    private val swatch = View(ctx).apply {
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(color) }
    }
    val hex = EditText(ctx).apply {
        tag = "effect-color-hex"
        hint = "#RRGGBB"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSingleLine = true
        setText(hexFor(color))
        setSelection(text.length)
        contentDescription = ctx.getString(R.string.light_effect_color_hex)
    }
    private val sliders = ArrayList<SeekBar>()
    private val labels = ArrayList<TextView>()

    val dialog: AlertDialog

    init {
        body.addView(swatch, LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(12) })
        body.addView(hex, LinearLayout.LayoutParams(-1, dp(52)))
        listOf(R.string.light_effect_hue, R.string.light_effect_saturation, R.string.light_effect_value).forEachIndexed { i, title ->
            labels += TextView(ctx).apply { textSize = 14f; setPadding(0, dp(12), 0, 0) }.also { body.addView(it) }
            sliders += SeekBar(ctx).apply {
                tag = "effect-color-hsv-$i"
                max = if (i == 0) 359 else 100
                progress = if (i == 0) hsv[i].toInt() else (hsv[i] * 100).toInt()
                progressTintList = ColorStateList.valueOf(0xFF85C9FF.toInt())
                thumbTintList = progressTintList
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                        if (updating || !fromUser) return
                        hsv[i] = if (i == 0) value.toFloat() else value / 100f
                        color = Color.HSVToColor(hsv)
                        updateViews(updateHex = true)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    override fun onStopTrackingTouch(bar: SeekBar) {}
                })
            }.also { body.addView(it, LinearLayout.LayoutParams(-1, dp(40))) }
        }
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                parseHex(s.toString())?.let {
                    color = it
                    Color.colorToHSV(color, hsv)
                    hex.error = null
                    updateViews(updateHex = false)
                }
            }
        })
        updateViews(updateHex = false)
        dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.light_effect_edit_color)
            .setView(body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = parseHex(hex.text.toString())
                if (parsed == null) hex.error = ctx.getString(R.string.light_effect_hex_error)
                else { onSave(parsed); dialog.dismiss() }
            }
        }
    }

    private fun updateViews(updateHex: Boolean) {
        updating = true
        (swatch.background as GradientDrawable).setColor(color)
        if (updateHex) hex.setText(hexFor(color))
        sliders.forEachIndexed { i, bar -> bar.progress = if (i == 0) hsv[i].toInt() else (hsv[i] * 100).toInt() }
        listOf(R.string.light_effect_hue, R.string.light_effect_saturation, R.string.light_effect_value).forEachIndexed { i, title ->
            labels[i].text = "${ctx.getString(title)}   ${if (i == 0) hsv[i].toInt() else (hsv[i] * 100).toInt()}${if (i == 0) "°" else "%"}"
        }
        updating = false
    }

    companion object {
        fun parseHex(raw: String): Int? {
            return PressColorPalette.parseColor(raw)
        }
        fun hexFor(color: Int) = PressColorPalette.formatColor(color)
    }
}
