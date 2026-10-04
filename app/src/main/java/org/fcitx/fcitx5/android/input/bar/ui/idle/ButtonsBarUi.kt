/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import androidx.annotation.DrawableRes
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.view

class ButtonsBarUi(override val ctx: Context, private val theme: Theme) : Ui {

    override val root = view(::FlexboxLayout) {
        alignItems = AlignItems.CENTER
        justifyContent = JustifyContent.SPACE_BETWEEN
    }

    private fun toolButton(@DrawableRes icon: Int) = ToolButton(ctx, icon, theme).also {
        val size = ctx.dp(40)
        root.addView(it, FlexboxLayout.LayoutParams(size, size).apply { flexShrink = 1f })
    }

    val keyboardLayoutButton = toolButton(R.drawable.ic_baseline_keyboard_24).apply {
        contentDescription = ctx.getString(R.string.keyboard_layout_menu_title)
        if (android.os.Build.VERSION.SDK_INT >= 26) tooltipText = contentDescription
    }

    val microphoneButton = toolButton(R.drawable.ic_offline_mic_24).apply {
        contentDescription = ctx.getString(R.string.offline_dictation_ui_title)
        if (android.os.Build.VERSION.SDK_INT >= 26) tooltipText = contentDescription
    }

    val undoButton = toolButton(R.drawable.ic_baseline_undo_24).apply {
        contentDescription = ctx.getString(R.string.undo)
    }
    val redoButton = toolButton(R.drawable.ic_baseline_redo_24).apply {
        contentDescription = ctx.getString(R.string.redo)
    }

    val clipboardButton = toolButton(R.drawable.ic_clipboard).apply {
        contentDescription = ctx.getString(R.string.clipboard)
    }

    val emojiButton = toolButton(R.drawable.ic_baseline_tag_faces_24).apply {
        contentDescription = ctx.getString(R.string.emoji_and_symbols)
    }
}
