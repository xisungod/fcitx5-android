/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.DialogSeekBarPreference
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.TwinSeekBarPreference
import org.fcitx.fcitx5.android.utils.navigateWithAnim

/** Layout and gestures, separated from typing rules and sound/vibration. */
class KeyboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {
    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        KeyboardPreferenceSections.arrange(screen, AppPrefs.getInstance().keyboard,
            KeyboardPreferenceSections.Page.Keyboard)
        KeyboardPreferenceSections.addRelated(screen,
            R.string.axiang_keyboard_feedback_entry to SettingsRoute.Feedback,
            R.string.axiang_keyboard_typing_entry to SettingsRoute.Typing) {
            navigateWithAnim(it)
        }
    }
}

/** Reuse the existing saved percentages, margins and vibration controls in one easy-to-find group. */
internal object KeyboardQuickControls {
    fun attach(screen: PreferenceScreen, prefs: AppPrefs.Keyboard) {
        val category = PreferenceCategory(screen.context).apply {
            key = "keyboard_size_feedback"
            setTitle(R.string.keyboard_size_feedback)
            order = -100
        }
        screen.addPreference(category)
        listOf(prefs.keyboardHeightPercent.key, prefs.keyboardSidePadding.key,
            prefs.hapticStrength.key, prefs.hapticOnKeyPress.key).forEachIndexed { order, key ->
            screen.findPreference<Preference>(key)?.let { preference ->
                screen.removePreference(preference)
                preference.order = order
                category.addPreference(preference)
                when (key) {
                    prefs.keyboardHeightPercent.key -> (preference as? TwinSeekBarPreference)?.apply {
                        setTitle(R.string.keyboard_height_size)
                        setDialogTitle(R.string.keyboard_height_size)
                        setDialogMessage(R.string.keyboard_height_size_summary)
                    }
                    prefs.keyboardSidePadding.key -> (preference as? TwinSeekBarPreference)?.apply {
                        setTitle(R.string.keyboard_width_size)
                        setDialogTitle(R.string.keyboard_width_size)
                        setDialogMessage(R.string.keyboard_width_size_summary)
                    }
                    prefs.hapticStrength.key -> (preference as? DialogSeekBarPreference)?.apply {
                        setDialogMessage(R.string.haptic_strength_summary)
                    }
                }
            }
        }
        category.addPreference(Preference(screen.context).apply {
            setSummary(R.string.haptic_strength_summary)
            isSelectable = false
            isIconSpaceReserved = false
            order = 4
        })
    }
}
