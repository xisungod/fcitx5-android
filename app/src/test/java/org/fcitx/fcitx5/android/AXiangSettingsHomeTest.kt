/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.fcitx.fcitx5.android.ui.main.SettingsHomeUi
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
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
class AXiangSettingsHomeTest {
    private fun fixture(block: (SettingsHomeUi, MutableList<SettingsRoute>) -> Unit) {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_AXiang)
        val activity = controller.setup().get()
        val routes = mutableListOf<SettingsRoute>()
        val ui = SettingsHomeUi(activity) { routes.add(it) }
        activity.setContentView(ui.root)
        try {
            layout(ui.root)
            block(ui, routes)
        } finally { controller.pause().stop().destroy() }
    }

    private fun layout(view: View) {
        val metrics = view.resources.displayMetrics
        view.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    @Test fun searchFindsTheActualControlsAndClearRestoresHome() = fixture { ui, routes ->
        ui.search.setText("震动")
        assertNotNull(ui.root.findViewWithTag<View>("settings-feedback"))
        assertNull(ui.root.findViewWithTag<View>("settings-layout"))
        ui.root.findViewWithTag<View>("settings-feedback").performClick()
        assertEquals(listOf(SettingsRoute.Feedback), routes)
        ui.search.setText("不存在的设置")
        assertNotNull(ui.root.findViewWithTag<View>("settings-no-results"))
        ui.root.findViewWithTag<View>("settings-clear-search").performClick()
        assertNotNull(ui.root.findViewWithTag<View>("settings-layout"))
        assertNotNull(ui.root.findViewWithTag<View>("settings-about"))
    }

    @Test fun everyHomeDestinationNavigatesAndRendersAtPhoneWidth() = fixture { ui, routes ->
        ui.destinations.forEach { entry ->
            val row = ui.root.findViewWithTag<View>("settings-${entry.id}")
            assertNotNull(row)
            assertTrue("${entry.id} keeps a usable touch target", row.height >= 48)
            row.performClick()
            assertEquals(entry.route, routes.last())
        }
        save(ui.root, "home-light.png")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h800dp-mdpi")
    fun largeTextOnSmallPhonesStaysInsideItsRows() {
        RuntimeEnvironment.setFontScale(1.4f)
        fixture { ui, _ ->
            for (view in descendants(ui.root).filterIsInstance<TextView>()) {
                if (view.visibility != View.VISIBLE || view.layout == null) continue
                assertTrue("${view.text}: no clipped final line", view.layout.getLineBottom(view.lineCount - 1) <= view.height - view.totalPaddingTop - view.totalPaddingBottom)
                assertTrue("${view.text}: wraps without ellipsizing", (0 until view.lineCount).all { view.layout.getEllipsisCount(it) == 0 })
            }
            save(ui.root, "home-small-large-text.png")
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h800dp-night-mdpi")
    fun homeUsesTheSameNightPaletteAsTheDetailPages() = fixture { ui, _ ->
        val text = descendants(ui.root).filterIsInstance<TextView>().first { it.text == "AXiang" }
        assertEquals(text.context.getColor(R.color.ax_settings_text), text.currentTextColor)
        save(ui.root, "home-night.png")
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun save(view: View, name: String) {
        // Draw the real content rather than a stale ScrollView display list in native Robolectric.
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        repeat(2) { view.draw(Canvas(bitmap)) }
        File("build/outputs/settings-checks").apply { mkdirs() }.resolve(name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
