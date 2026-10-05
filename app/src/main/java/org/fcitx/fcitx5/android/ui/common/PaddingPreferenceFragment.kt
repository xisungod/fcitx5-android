/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.CallSuper
import org.fcitx.fcitx5.android.ui.main.modified.MyPreferenceFragment
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

abstract class PaddingPreferenceFragment : MyPreferenceFragment() {

    @CallSuper
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = super.onCreateView(inflater, container, savedInstanceState).apply {
        listView.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(listView) { view, insets ->
            val navigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val spacing = (24 * resources.displayMetrics.density).toInt()
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, navigation.bottom + spacing)
            insets
        }
    }
}
