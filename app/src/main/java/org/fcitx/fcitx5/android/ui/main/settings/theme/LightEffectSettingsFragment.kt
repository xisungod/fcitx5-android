/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim

/** Directly reachable from the keyboard's palette tool button. */
class LightEffectSettingsFragment : Fragment() {
    private var ui: LightEffectSettingsUi? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) =
        LightEffectSettingsUi(requireContext(), openKeyboardSettings = {
            navigateWithAnim(SettingsRoute.VirtualKeyboard)
        }).also { ui = it }.root

    override fun onDestroyView() {
        ui?.dismissDialogs()
        ui = null
        super.onDestroyView()
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            AppPrefs.getInstance().syncToDeviceEncryptedStorage()
            ThemeManager.syncToDeviceEncryptedStorage()
        }
        super.onStop()
    }
}
