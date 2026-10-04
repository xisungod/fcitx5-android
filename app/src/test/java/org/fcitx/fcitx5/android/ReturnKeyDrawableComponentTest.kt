/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Application
import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.plusAssign
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ReturnKeyDrawableComponentTest {
    private class Recorder : UniqueComponent<Recorder>(), InputBroadcastReceiver {
        val resources = mutableListOf<Int>()

        override fun onReturnKeyDrawableUpdate(resourceId: Int) {
            resources += resourceId
        }
    }

    private fun component(): Pair<ReturnKeyDrawableComponent, Recorder> {
        val scope = DynamicScope()
        val component = ReturnKeyDrawableComponent()
        val recorder = Recorder()
        scope += InputBroadcaster()
        scope += component
        scope += recorder
        return component to recorder
    }

    @Test
    fun onlySendUsesTheSwooshAndNoEnterActionKeepsTheReturnArrow() {
        val (component, _) = component()
        for ((action, drawable) in listOf(
            EditorInfo.IME_ACTION_SEND to R.drawable.ic_send_swoosh_24,
            EditorInfo.IME_ACTION_GO to R.drawable.ic_baseline_arrow_forward_24,
            EditorInfo.IME_ACTION_SEARCH to R.drawable.ic_baseline_search_24,
            EditorInfo.IME_ACTION_NEXT to R.drawable.ic_baseline_keyboard_tab_24,
            EditorInfo.IME_ACTION_DONE to R.drawable.ic_baseline_done_24,
            EditorInfo.IME_ACTION_PREVIOUS to R.drawable.ic_baseline_keyboard_tab_reverse_24,
            EditorInfo.IME_ACTION_NONE to R.drawable.ic_baseline_keyboard_return_24,
            EditorInfo.IME_ACTION_UNSPECIFIED to R.drawable.ic_baseline_keyboard_return_24
        )) {
            component.updateDrawableOnEditorInfo(EditorInfo().apply { imeOptions = action })
            assertEquals("Wrong icon for editor action $action", drawable, component.resourceId)
        }
        component.updateDrawableOnEditorInfo(EditorInfo().apply {
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        })
        assertEquals(R.drawable.ic_baseline_keyboard_return_24, component.resourceId)
    }

    @Test
    fun compositionKeepsReturnAndClearingItRestoresSendWithoutDuplicateBroadcasts() {
        val (component, recorder) = component()
        component.updateDrawableOnEditorInfo(EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_SEND })
        component.updateDrawableOnPreedit(false)
        component.updateDrawableOnPreedit(false)
        component.updateDrawableOnPreedit(true)
        component.updateDrawableOnPreedit(true)
        assertEquals(listOf(
            R.drawable.ic_send_swoosh_24,
            R.drawable.ic_baseline_keyboard_return_24,
            R.drawable.ic_send_swoosh_24
        ), recorder.resources)
        assertEquals(R.drawable.ic_send_swoosh_24, component.resourceId)
    }
}
