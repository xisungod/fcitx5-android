/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

internal object PunctuationMode {
    fun forMode(text: String, english: Boolean): String = if (!english) text else when (text) {
        "，" -> ","
        "。" -> "."
        "？" -> "?"
        "！" -> "!"
        "…" -> "..."
        else -> text
    }
}
