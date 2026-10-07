/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.fcitx.fcitx5.android.core.RimeTouchProbe
import org.fcitx.fcitx5.android.core.RimeTouchProbeStatus
import org.fcitx.fcitx5.android.ui.main.settings.behavior.TouchProbeSelfCheckUi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TouchProbeSelfCheckUiTest {
    private fun fixture(block: (TouchProbeSelfCheckUi) -> Unit) {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_AXiang)
        val activity = controller.setup().get()
        val ui = TouchProbeSelfCheckUi(activity) {}
        activity.setContentView(ui.root)
        try { block(ui) } finally { controller.pause().stop().destroy() }
    }

    private fun field(ui: TouchProbeSelfCheckUi, tag: String) =
        ui.root.findViewWithTag<TextView>(tag).text.toString()

    @Test fun unknownAndLoadedInterfacesNeverPretendAQuerySucceeded() = fixture { ui ->
        assertEquals("尚未查询", field(ui, "touch-probe-query-outcome"))
        assertTrue(field(ui, "touch-probe-version").contains("尚未确认"))
        ui.render(RimeTouchProbe.Diagnostics(true, RimeTouchProbeStatus("Ready", "1.16.1"), null,
            readyHandle = true))
        assertEquals("尚未查询", field(ui, "touch-probe-query-outcome"))
        assertTrue(field(ui, "touch-probe-version").contains("1.16.1"))
        assertTrue(field(ui, "touch-probe-interface").contains("就绪"))
        assertEquals("本次进程尚无返回候选的原生查询", field(ui, "touch-probe-last-native-success"))
    }

    @Test fun actualSuccessEmptyTimeoutAndUnavailableStayDistinctAndCachedResultsAreLabeled() = fixture { ui ->
        val base = RimeTouchProbe.Diagnostics(true, RimeTouchProbeStatus("Ready", "1.16.1"), null)
        for ((outcome, label) in listOf("Success" to "成功 · 已返回候选", "NoCandidates" to "完成 · 无候选",
            "Timeout" to "超时 · 已丢弃结果", "Unavailable" to "查询不可用")) {
            ui.render(base.copy(lastOutcome = outcome, lastCacheHit = false,
                lastQueryElapsedNanos = 30_000_000, lastInitializationNanos = 28_000_000,
                lastNativeQueryNanos = 2_000_000))
            assertEquals(label, field(ui, "touch-probe-query-outcome"))
            assertTrue(field(ui, "touch-probe-timing").contains("28.00"))
            assertTrue(field(ui, "touch-probe-timing").contains("2.00"))
        }
        ui.render(base.copy(lastOutcome = "Success", lastCacheHit = true))
        assertTrue(descendants(ui.root).filterIsInstance<TextView>().any {
            it.text.toString() == "结果来源：临时候选缓存"
        })
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h800dp-mdpi")
    fun smallScreensAndLargeFontsWrapAllStatusDetailsWithoutHidingTheSelfCheck() {
        RuntimeEnvironment.setFontScale(1.4f)
        try {
            fixture { ui ->
                ui.render(RimeTouchProbe.Diagnostics(false,
                    RimeTouchProbeStatus("VersionMismatch", "1.12.0"), "ProbeUnavailable:VersionMismatch",
                    lastOutcome = "Unavailable", unavailableCount = 1, queryCount = 1))
                ui.root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                ui.root.layout(0, 0, 320, 800)
                for (view in descendants(ui.root).filterIsInstance<TextView>()) {
                    if (view.visibility != View.VISIBLE || view.layout == null) continue
                    assertTrue("${view.text}: no clipped final line",
                        view.layout.getLineBottom(view.lineCount - 1) <= view.height - view.totalPaddingTop - view.totalPaddingBottom)
                    assertTrue((0 until view.lineCount).all { view.layout.getEllipsisCount(it) == 0 })
                }
                assertTrue(ui.checkButton.height >= 48)
                val bitmap = Bitmap.createBitmap(ui.root.width, ui.root.height, Bitmap.Config.ARGB_8888)
                try {
                    repeat(2) { ui.root.draw(Canvas(bitmap)) }
                    File("build/outputs/candidate-checks").apply { mkdirs() }
                        .resolve("touch-self-check-small-large-text.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                } finally { bitmap.recycle() }
            }
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
