/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim

class FeedbackSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {
    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        KeyboardPreferenceSections.arrange(screen, AppPrefs.getInstance().keyboard,
            KeyboardPreferenceSections.Page.Feedback)
        KeyboardPreferenceSections.addRelated(screen,
            R.string.axiang_keyboard_layout_entry to SettingsRoute.VirtualKeyboard) {
            navigateWithAnim(it)
        }
    }
}
