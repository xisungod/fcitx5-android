/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.os.Bundle
import androidx.preference.SwitchPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim

class ThemeSettingsFragment : ManagedPreferenceFragment(ThemeManager.prefs) {

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.findPreference<SwitchPreference>(ThemeManager.prefs.portraitNumberRow.key)?.apply {
            setTitle(R.string.keyboard_show_number_row)
            setSummary(R.string.keyboard_show_number_row_summary)
            order = 0
        }
        screen.addPreference(Preference(requireContext()).apply {
            key = "detailed_light_effect_settings"
            setTitle(R.string.light_effect_settings)
            setSummary(R.string.light_effect_settings_summary)
            setIcon(R.drawable.ic_baseline_palette_24)
            order = -1
            setOnPreferenceClickListener { navigateWithAnim(SettingsRoute.LightEffects); true }
        })
    }

    private val followSystemDayNightTheme = ThemeManager.prefs.followSystemDayNightTheme

    private var resumed = false

    private lateinit var switchPreference: SwitchPreference

    // sync SwitchPreference's state when `followSystemDayNightTheme` changed in ThemeListFragment
    private val listener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        if (resumed) return@OnChangeListener
        switchPreference.isChecked = v
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        followSystemDayNightTheme.registerOnChangeListener(listener)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        switchPreference = findPreference(followSystemDayNightTheme.key)!!
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    override fun onDestroy() {
        followSystemDayNightTheme.unregisterOnChangeListener(listener)
        super.onDestroy()
    }
}
