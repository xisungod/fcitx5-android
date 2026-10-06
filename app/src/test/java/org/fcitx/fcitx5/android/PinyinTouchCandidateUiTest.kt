/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.bar.ui.CandidateUi
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinTapFeedback
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinTouchCandidateOffer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w600dp-h900dp-mdpi")
class PinyinTouchCandidateUiTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun prepare() {
        AppPrefs.init(context.getSharedPreferences("pinyin-touch-candidate-ui", Context.MODE_PRIVATE))
    }

    private fun layout(view: View, width: Int) {
        repeat(2) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(52, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, 52)
        }
    }

    private fun buttons(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun offer(token: Long, text: String = "经常会") =
        PinyinTouchCandidateOffer(token, text, "jibgchsnghui", "jingchanghui")

    @Test fun longCorrectionKeepsLiteralCandidatesScrollableAtNarrowAndNormalWidths() {
        val literalCandidates = View(context).apply { id = View.generateViewId() }
        val ui = CandidateUi(context, ThemePreset.XuancaiBlackV09, literalCandidates)
        val text = "这是一条很长的纠错候选用于检查窄屏显示"
        ui.setTouchCandidate(offer(3, text)) {}
        val correction = buttons(ui.root).single { it.text.endsWith("*") }
        for (width in listOf(192, 240, 360, 600)) {
            layout(ui.root, width)
            assertEquals(52, ui.root.height)
            assertTrue("Literal candidates should remain visible at $width px", literalCandidates.width >= 96)
            assertTrue(correction.width <= 144)
            assertEquals(TextUtils.TruncateAt.END, correction.ellipsize)
            assertEquals("$text*", correction.text.toString())
            assertEquals(context.getString(R.string.pinyin_touch_alternative_description, text),
                correction.contentDescription.toString())
        }
    }

    @Test fun replacementAndDismissalCannotSelectAnOldOffer() {
        val ui = CandidateUi(context, ThemePreset.XuancaiBlackV09,
            View(context).apply { id = View.generateViewId() })
        var selected: Long? = null
        ui.setTouchCandidate(offer(17)) { selected = it }
        val correction = buttons(ui.root).single { it.text.endsWith("*") }
        ui.setTouchCandidate(offer(83, "你好啊")) { selected = it }
        correction.performClick()
        assertEquals(83L, selected)
        selected = null
        ui.setTouchCandidate(null) { selected = it }
        correction.performClick()
        assertNull(selected)
        assertEquals(View.GONE, correction.visibility)
        assertTrue(correction.text.isEmpty())
        assertNull(correction.contentDescription)
    }

    @Test fun switchingFeedbackModesClearsHiddenCallbacksAndReleasesCandidateWidth() {
        val literalCandidates = View(context).apply { id = View.generateViewId() }
        val ui = CandidateUi(context, ThemePreset.XuancaiBlackV09, literalCandidates)
        layout(ui.root, 360)
        val fullWidth = literalCandidates.width
        var restored: Long? = null
        var selected: Long? = null
        ui.setPinyinFeedback(PinyinTapFeedback(4, 'b', 'n'), { restored = it }, {})
        val restore = buttons(ui.root).single { it.text.contains("b") }
        ui.setTouchCandidate(offer(5)) { selected = it }
        restore.performClick()
        assertNull(restored)
        assertEquals(View.GONE, restore.visibility)
        val correction = buttons(ui.root).single { it.text.endsWith("*") }
        ui.setPinyinFeedback(PinyinTapFeedback(6, 'b', 'n'), { restored = it }, {})
        correction.performClick()
        assertNull(selected)
        assertEquals(View.GONE, correction.visibility)
        ui.setPinyinFeedback(null, {}, {})
        layout(ui.root, 360)
        assertEquals(fullWidth, literalCandidates.width)
    }
}
