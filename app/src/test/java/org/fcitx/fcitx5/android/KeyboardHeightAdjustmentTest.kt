/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightAdjustment
import org.fcitx.fcitx5.android.input.keyboard.KeyboardSizePolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class KeyboardHeightAdjustmentTest {
    private fun preference(key: String = "portrait", default: Int = 30): ManagedPreference.PInt {
        val stored = RuntimeEnvironment.getApplication().getSharedPreferences("height-draft", Context.MODE_PRIVATE)
        return ManagedPreference.PInt(stored, key, default)
    }

    @Test fun noNumberRowPreviewAndSavedLayoutHaveExactlyTheSameHeight() {
        val pref = preference().apply { setValue(30) }
        val draft = KeyboardHeightAdjustment(pref, 2400, true, true, false, 300, 1800)
        assertEquals(576, draft.heightPx)
        val preview = draft.preview(768)
        assertEquals(768, preview)
        assertEquals("Dragging must not write preferences", 30, pref.getValue())
        draft.save()
        assertEquals(40, pref.getValue())
        assertEquals(preview, KeyboardSizePolicy.heightForLayout(2400 * pref.getValue() / 100, true, true, false))
    }

    @Test fun landscapeAndPortraitSaveIndependentlyAndOnlyOnDone() {
        val portrait = preference().apply { setValue(34) }
        val landscape = preference("landscape", 49).apply { setValue(49) }
        val draft = KeyboardHeightAdjustment(landscape, 1080, false, true, false, 280, 850)
        draft.preview(648)
        assertEquals(49, landscape.getValue())
        draft.save()
        assertEquals(60, landscape.getValue())
        assertEquals(34, portrait.getValue())
        val reopened = KeyboardHeightAdjustment(landscape, 1080, false, true, false, 280, 850)
        assertEquals(648, reopened.heightPx)
    }

    @Test fun resetIsADraftAndCancelKeepsTheOriginalHeight() {
        val pref = preference().apply { setValue(45) }
        val draft = KeyboardHeightAdjustment(pref, 1000, true, true, true, 160, 800)
        draft.preview(520)
        draft.reset()
        assertEquals(300, draft.heightPx)
        assertEquals(45, pref.getValue())
        // Discard the draft as Cancel/hiding/rotation does; a new session reads the saved value.
        assertEquals(450, KeyboardHeightAdjustment(pref, 1000, true, true, true, 160, 800).heightPx)
        draft.save()
        assertEquals(30, pref.getValue())
    }

    @Test fun boundsPreserveUsableRowsAndLeaveSpaceAboveTheKeyboard() {
        val draft = KeyboardHeightAdjustment(preference(), 2400, true, true, false, 384, 1500)
        val minimum = draft.preview(Int.MIN_VALUE)
        assertTrue(minimum >= 384)
        val maximum = draft.preview(Int.MAX_VALUE)
        assertTrue(maximum <= 1500)
        assertTrue(maximum > minimum)
        assertTrue(draft.percent in 10..90)
        val tiny = KeyboardHeightAdjustment(preference(), 360, false, true, true, 140, 100)
        assertTrue(tiny.minimumHeightPx <= tiny.maximumHeightPx)
        assertTrue(tiny.preview(400) <= 100)
    }

    @Test fun unchangedEditorPreservesExistingSizesOutsideNewDragBounds() {
        val pref = preference().apply { setValue(80) }
        KeyboardHeightAdjustment(pref, 1000, true, true, true, 160, 600).save()
        assertEquals(80, pref.getValue())
    }
}
