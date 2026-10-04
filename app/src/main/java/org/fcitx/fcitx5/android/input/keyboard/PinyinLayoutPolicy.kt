/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import org.fcitx.fcitx5.android.core.InputMethodEntry

/** The active Rime schema owns the layout, so digits never reach a full-pinyin schema. */
internal object PinyinLayoutPolicy {
    const val FullRoute = "Text26"

    fun isNumber(inputType: Int): Boolean = when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE -> true
        else -> false
    }

    fun allowsPinyin(inputType: Int): Boolean {
        if ((inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        return (inputType and InputType.TYPE_MASK_VARIATION) !in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI
        )
    }

    fun textLayout(ime: InputMethodEntry, inputType: Int): String =
        if (allowsPinyin(inputType) && RimeActions.isPinyinSchema(ime, nineKey = true))
            PinyinT9Keyboard.Name else TextKeyboard.Name
}
