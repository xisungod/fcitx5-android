/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Bundle
import androidx.preference.Preference
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.navigateWithAnim

class EngineToolsFragment : PaddingPreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(Preference(context).apply {
                setSummary(R.string.ax_settings_engine_hint)
                isSelectable = false
                isIconSpaceReserved = false
            })
            addCategory(R.string.ax_settings_engines) {
                addPreference(R.string.input_methods) { navigateWithAnim(SettingsRoute.InputMethodList) }
                addPreference(R.string.global_options) { navigateWithAnim(SettingsRoute.GlobalConfig) }
                addPreference(R.string.addons) { navigateWithAnim(SettingsRoute.AddonList) }
            }
            addCategory(R.string.ax_settings_utilities) {
                addPreference(R.string.candidates_window) { navigateWithAnim(SettingsRoute.CandidatesWindow) }
                addPreference(R.string.plugins) { navigateWithAnim(SettingsRoute.Plugin) }
                addPreference(R.string.developer) { navigateWithAnim(SettingsRoute.Developer) }
            }
        }
    }
}
