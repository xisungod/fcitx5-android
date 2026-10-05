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
    @Test fun upgradeSelectsSamOnceAndLaterExplicitChoicesSurvive() {
        val storage = RuntimeEnvironment.getApplication().getSharedPreferences("sam-upgrade", Context.MODE_PRIVATE)
        storage.edit().clear().putString("ripple_shape", "IrregularFluid")
            .putInt("press_motion_amplitude", 13).putString("press_user_colors", "#0099FF,#FF0099").commit()
        val prefs = ThemePrefs(storage)
        prefs.applySamDefaultOnce()
        assertEquals(ThemePrefs.RippleShape.Sam, prefs.rippleShape.getValue())
        assertEquals(13, prefs.pressMotionAmplitude.getValue())
        assertEquals("#0099FF,#FF0099", prefs.pressUserColors.getValue())
        for (mode in ThemePrefs.RippleShape.entries) {
            prefs.rippleShape.setValue(mode)
            val restarted = ThemePrefs(storage)
            restarted.applySamDefaultOnce()
            assertEquals("An explicit mode choice is never overwritten after migration", mode, restarted.rippleShape.getValue())
        }
    }

    @Test fun newInstallUsesSamWithoutChangingTheTwoLegacyChoices() {
        val storage = RuntimeEnvironment.getApplication().getSharedPreferences("sam-fresh", Context.MODE_PRIVATE)
        storage.edit().clear().commit()
        val prefs = ThemePrefs(storage)
        assertEquals(ThemePrefs.RippleShape.Sam, prefs.rippleShape.getValue())
        assertEquals(listOf("SoftMist", "IrregularFluid", "Sam"), ThemePrefs.RippleShape.entries.map { it.name })
        assertTrue("Reading default preferences must be side-effect free", storage.all.isEmpty())
    }
}
