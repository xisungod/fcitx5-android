/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.view.View
import android.widget.PopupMenu
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R

internal enum class KeyboardLayoutChoice(@StringRes val title: Int) {
    PinyinNine(R.string.pinyin_layout_nine_key),
    Pinyin26(R.string.pinyin_layout_full),
    English(R.string.keyboard_layout_menu_english),
    Numbers(R.string.keyboard_layout_menu_numbers)
}

/** A local anchored chooser. Opening it neither launches settings nor changes the input engine. */
internal fun keyboardLayoutMenu(context: Context, anchor: View,
    onChoose: (KeyboardLayoutChoice) -> Unit): PopupMenu = PopupMenu(context, anchor).apply {
    KeyboardLayoutChoice.entries.forEachIndexed { index, choice ->
        menu.add(0, index + 1, index, choice.title).setOnMenuItemClickListener {
            onChoose(choice)
            true
        }
    }
}
