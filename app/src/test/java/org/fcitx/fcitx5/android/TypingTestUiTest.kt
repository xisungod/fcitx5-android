/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.fcitx.fcitx5.android.ui.main.TypingTestUi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TypingTestUiTest {
    private val typing = TypingTestUi.State(TypingTestUi.Phase.Typing, promptId = 1,
        target = "你好啊", pinyin = "nihaoa", index = 0, total = 5)

    private fun fixture(block: (TypingTestUi, MutableList<String>) -> Unit) {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_AXiang)
        val activity = controller.setup().get()
        val actions = mutableListOf<String>()
        val ui = TypingTestUi(activity, TypingTestUi.Actions(
            start = { actions += "start:$it" }, complete = { actions += "complete" },
            next = { actions += "next" }, abort = { actions += "abort" },
            export = { actions += "export" }, clear = { actions += "clear" },
            focusChanged = {}, retry = { actions += "retry" }
        ))
        activity.setContentView(ui.root)
        try { block(ui, actions) } finally { controller.pause().stop().destroy() }
    }

    private fun layout(root: View) {
        val metrics = root.resources.displayMetrics
        root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    @Test fun measurementNeedsAnExplicitStartAndOffersShortAndFullRuns() = fixture { ui, actions ->
        ui.render(TypingTestUi.State(TypingTestUi.Phase.Intro))
        assertTrue(actions.isEmpty())
        assertNull(ui.input)
        ui.root.findViewWithTag<View>("typing-test-start-quick").performClick()
        ui.root.findViewWithTag<View>("typing-test-start-full").performClick()
        assertEquals(listOf("start:5", "start:20"), actions)
        assertEquals(View.GONE, ui.root.findViewWithTag<View>("typing-test-export").parent.let { it as View }.visibility)
        layout(ui.root)
        save(ui.root, "intro.png")
    }

    @Test fun statusUpdatesKeepTheSameEditorAndItsComposingInputAlive() = fixture { ui, _ ->
        ui.render(typing)
        val edit = ui.input!!
        edit.setText("niha")
        edit.setSelection(4)
        ui.render(typing.copy(failure = "等待按键处理"))
        assertSame(edit, ui.input)
        assertEquals("niha", edit.text.toString())
        assertEquals(4, edit.selectionStart)
        assertEquals(R.id.typing_test_input, edit.id)
        assertEquals(0, edit.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertEquals("等待按键处理", ui.root.findViewWithTag<TextView>("typing-test-status").text)
        ui.render(typing.copy(failure = null))
        assertEquals(View.GONE, ui.root.findViewWithTag<View>("typing-test-status").visibility)
    }

    @Test fun targetAndPinyinHelpStaySeparateFromTheRealInputField() = fixture { ui, actions ->
        ui.render(typing)
        assertEquals("你好啊", ui.root.findViewWithTag<TextView>("typing-test-target").text.toString())
        val pinyin = ui.root.findViewWithTag<TextView>("typing-test-pinyin")
        assertEquals(View.VISIBLE, pinyin.visibility)
        ui.root.findViewWithTag<View>("typing-test-pinyin-toggle").performClick()
        assertEquals(View.GONE, pinyin.visibility)
        ui.root.findViewWithTag<View>("typing-test-pinyin-toggle").performClick()
        assertEquals(View.VISIBLE, pinyin.visibility)
        assertEquals("nihaoa", pinyin.text.toString())
        assertEquals("", ui.input!!.text.toString())
        ui.root.findViewWithTag<View>("typing-test-complete").performClick()
        assertEquals(listOf("complete"), actions)
        layout(ui.root)
        save(ui.root, "typing.png")
    }

    @Test fun completedPhraseCannotContinueMeasuringAndNextIsExplicit() = fixture { ui, actions ->
        ui.render(typing)
        ui.render(typing.copy(phase = TypingTestUi.Phase.Completed, lastCommittedText = "怒号啊"))
        assertNull(ui.input)
        assertEquals("怒号啊", ui.root.findViewWithTag<TextView>("typing-test-completed-text").text.toString())
        assertTrue(actions.isEmpty())
        ui.root.findViewWithTag<View>("typing-test-next").performClick()
        assertEquals(listOf("next"), actions)
    }

    @Test fun unavailableResultsRemainUnavailableAndLateReportUpdatesRender() = fixture { ui, _ ->
        val state = TypingTestUi.State(TypingTestUi.Phase.Report, completed = 1,
            reportSummary = "首选覆盖目标：--\n按键排队与处理：--", reportAvailable = false)
        ui.render(state)
        val report = ui.root.findViewWithTag<TextView>("typing-test-results")
        assertTrue(report.text.contains("--"))
        ui.render(state.copy(reportSummary = "首选覆盖目标：--\n按键排队与处理：4.0 毫秒", reportAvailable = true))
        assertSame(report, ui.root.findViewWithTag<View>("typing-test-results"))
        assertTrue(report.text.contains("4.0 毫秒"))
        assertEquals(View.VISIBLE, (ui.root.findViewWithTag<View>("typing-test-export").parent as View).visibility)
        layout(ui.root)
        save(ui.root, "results.png")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h800dp-night-mdpi")
    fun primaryActionKeepsReadableContrastInNightMode() = fixture { ui, _ ->
        ui.render(TypingTestUi.State(TypingTestUi.Phase.Intro))
        val start = ui.root.findViewWithTag<TextView>("typing-test-start-quick")
        assertEquals(start.context.getColor(R.color.ax_settings_background), start.currentTextColor)
        layout(ui.root)
        save(ui.root, "intro-night.png")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h520dp-mdpi")
    fun narrowPhoneWithLargeTextKeepsTheInputAndButtonsUsable() {
        RuntimeEnvironment.setFontScale(1.4f)
        fixture { ui, _ ->
            listOf(TypingTestUi.State(TypingTestUi.Phase.Intro), typing,
                typing.copy(phase = TypingTestUi.Phase.Completed, lastCommittedText = "你好啊")).forEach { state ->
                ui.render(state)
                layout(ui.root)
                descendants(ui.root).filterIsInstance<TextView>().forEach { view ->
                    if (view.visibility != View.VISIBLE || view.layout == null || view is EditText) return@forEach
                    assertTrue("${view.text} has no clipped final line", view.layout.getLineBottom(view.lineCount - 1) <=
                        view.height - view.totalPaddingTop - view.totalPaddingBottom)
                    assertTrue("${view.text} wraps", (0 until view.lineCount).all { view.layout.getEllipsisCount(it) == 0 })
                }
                descendants(ui.root).filter { it is Button && it.isShown }
                    .forEach { assertTrue("${it.tag} meets the touch target", it.height >= 48) }
            }
            save(ui.root, "small-large-text.png")
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun save(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        repeat(2) { view.draw(Canvas(bitmap)) }
        File("build/outputs/typing-test-checks").apply { mkdirs() }.resolve(name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
