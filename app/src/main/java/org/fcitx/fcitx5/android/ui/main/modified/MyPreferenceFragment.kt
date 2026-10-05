/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.modified

import android.os.Bundle
import android.view.View
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.ui.common.AXiangPreferenceGroupAdapter
import org.fcitx.fcitx5.android.ui.common.AXiangSettingsPalette

abstract class MyPreferenceFragment : PreferenceFragmentCompat() {
    private var cardDecoration: RecyclerView.ItemDecoration? = null

    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<PreferenceViewHolder> =
        AXiangPreferenceGroupAdapter(preferenceScreen)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setBackgroundColor(AXiangSettingsPalette.from(requireContext()).background)
        setDivider(null)
        val gutter = (16 * resources.displayMetrics.density).toInt()
        listView.setPadding(gutter, (8 * resources.displayMetrics.density).toInt(), gutter, gutter)
        listView.clipToPadding = false
    }

    override fun onBindPreferences() {
        super.onBindPreferences()
        cardDecoration?.let(listView::removeItemDecoration)
        cardDecoration = (listView.adapter as? AXiangPreferenceGroupAdapter)?.decoration()?.also(listView::addItemDecoration)
    }

    override fun onDestroyView() {
        cardDecoration = null
        super.onDestroyView()
    }

    @Suppress("DEPRECATION")
    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (parentFragmentManager.findFragmentByTag(javaClass.name) != null) return
        val f = when (preference) {
            is EditTextPreference -> MyEditTextPreferenceDialogFragment.newInstance(preference.key)
            is ListPreference -> MyListPreferenceDialogFragment.newInstance(preference.key)
            else -> return super.onDisplayPreferenceDialog(preference)
        }
        f.setTargetFragment(this, 0)
        f.show(parentFragmentManager, javaClass.name)
    }
}