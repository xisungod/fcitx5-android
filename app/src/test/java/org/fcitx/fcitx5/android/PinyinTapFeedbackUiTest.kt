/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.bar.ui.CandidateUi
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinTapFeedback
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w600dp-h900dp-mdpi")
class PinyinTapFeedbackUiTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun prepare() {
        AppPrefs.init(context.getSharedPreferences("pinyin-feedback-ui", Context.MODE_PRIVATE))
    }

    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(52, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 600, 52)
    }

    private fun buttons(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun correctionControlsKeepBarHeightAndGiveCandidateWidthBackWhenDismissed() {
        val candidates = View(context).apply { id = View.generateViewId() }
        val ui = CandidateUi(context, ThemePreset.XuancaiBlackV09, candidates)
        layout(ui.root)
        val originalWidth = candidates.width
        ui.setPinyinFeedback(PinyinTapFeedback(17L, 'b', 'n'), {}, {})
        layout(ui.root)
        assertEquals(52, ui.root.height)
        assertTrue(candidates.width > 0)
        assertTrue(candidates.width < originalWidth)
        assertTrue(candidates.right <= 600)
        ui.setPinyinFeedback(null, {}, {})
        layout(ui.root)
        assertEquals(originalWidth, candidates.width)
        assertEquals(52, ui.root.height)
    }

    @Test fun eachExplicitButtonCarriesTheCurrentCorrectionTokenAndDismissalRemovesHandlers() {
        val ui = CandidateUi(context, ThemePreset.XuancaiBlackV09, View(context))
        var restored: Long? = null
        var confirmed: Long? = null
        ui.setPinyinFeedback(PinyinTapFeedback(83L, 'b', 'n'), { restored = it }, { confirmed = it })
        val buttons = buttons(ui.root)
        assertEquals(2, buttons.size)
        buttons.single { it.text.contains("b") }.performClick()
        buttons.single { it.text.contains("n") }.performClick()
        assertEquals(83L, restored)
        assertEquals(83L, confirmed)
        assertTrue(buttons.all { !it.contentDescription.isNullOrEmpty() })
        restored = null
        confirmed = null
        ui.setPinyinFeedback(null, { restored = it }, { confirmed = it })
        buttons.forEach { it.performClick() }
        assertNull(restored)
        assertNull(confirmed)
    }
}
