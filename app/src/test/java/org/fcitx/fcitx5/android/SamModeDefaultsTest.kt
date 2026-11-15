/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SamModeDefaultsTest {
    @Test fun legacyShapeIsStillReadableInBackupsButIsNotAUserControl() {
        val storage = RuntimeEnvironment.getApplication().getSharedPreferences("sam-upgrade", Context.MODE_PRIVATE)
        storage.edit().clear().putString("ripple_shape", "IrregularFluid")
            .putInt("press_motion_amplitude", 13).putString("press_user_colors", "#0099FF,#FF0099").commit()
        val before = storage.all.toMap()
        val prefs = ThemePrefs(storage)
        assertEquals(ThemePrefs.RippleShape.IrregularFluid, prefs.rippleShape.getValue())
        assertEquals(13, prefs.pressMotionAmplitude.getValue())
        assertEquals("#0099FF,#FF0099", prefs.pressUserColors.getValue())
        assertFalse(prefs.managedPreferencesUi.any { it.key == "ripple_shape" })
        assertEquals(before, storage.all)
    }

    @Test fun newInstallLeavesTheIdlePanelDark() {
        val storage = RuntimeEnvironment.getApplication().getSharedPreferences("sam-fresh", Context.MODE_PRIVATE)
        storage.edit().clear().commit()
        val prefs = ThemePrefs(storage)
        assertEquals(ThemePrefs.RippleShape.Sam, prefs.rippleShape.getValue())
        assertTrue(prefs.pressEffect.getValue())
        assertFalse(prefs.idleBreathing.getValue())
        assertTrue("Reading default preferences must be side-effect free", storage.all.isEmpty())
    }
}
