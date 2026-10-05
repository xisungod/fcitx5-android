/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import org.fcitx.fcitx5.android.ui.common.AXiangPreferenceGroupAdapter
import org.fcitx.fcitx5.android.ui.common.AXiangSettingsPalette
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Exercise real Preference bindings after replacing their layouts and grouped surfaces. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AXiangPreferenceUiTest {
    class SettingsFixture : PaddingPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = "axiang-preference-visual-test"
            preferenceManager.sharedPreferences!!.edit().clear().commit()
            val context = preferenceManager.context
            preferenceScreen = preferenceManager.createPreferenceScreen(context)
            val category = PreferenceCategory(context).apply { title = "按键与反馈" }
            preferenceScreen.addPreference(category)
            category.addPreference(SwitchPreferenceCompat(context).apply {
                key = "feedback"
                title = "按键振动"
                summary = "轻触按键时提供振动反馈"
            })
            category.addPreference(ListPreference(context).apply {
                key = "strength"
                title = "振动强度"
                entries = arrayOf("轻柔", "标准", "清晰")
                entryValues = arrayOf("light", "normal", "strong")
                setDefaultValue("normal")
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            })
            category.addPreference(Preference(context).apply {
                key = "long_title"
                title = "在大号字体下也能完整阅读的键盘设置标题"
                summary = "说明文字自然换行，开关和点击入口始终保持足够空间。"
                isSelectable = false
            })
            val second = PreferenceCategory(context).apply { title = "输入体验" }
            preferenceScreen.addPreference(second)
            second.addPreference(Preference(context).apply {
                key = "typing"
                title = "输入习惯"
                summary = "双拼、自动空格与标点"
            })
        }
    }

    private fun fixture(block: (AppCompatActivity, SettingsFixture) -> Unit) {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_FcitxAppTheme)
        val activity = controller.setup().get()
        try {
            val fragment = SettingsFixture()
            activity.supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, fragment).commitNow()
            shadowOf(Looper.getMainLooper()).idle()
            layout(activity.window.decorView)
            block(activity, fragment)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun layout(view: View) {
        val metrics = view.resources.displayMetrics
        view.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun row(fragment: SettingsFixture, key: String): View {
        val adapter = fragment.listView.adapter as AXiangPreferenceGroupAdapter
        val position = adapter.getPreferenceAdapterPosition(key)
        return checkNotNull(fragment.listView.findViewHolderForAdapterPosition(position)).itemView
    }

    @Test fun switchesStillPersistAndNavigationRowsKeepDistinctAffordances() = fixture { _, fragment ->
        val switchRow = row(fragment, "feedback")
        assertEquals(View.GONE, switchRow.findViewById<View>(R.id.ax_settings_chevron).visibility)
        assertTrue(switchRow.performClick())
        assertTrue(fragment.findPreference<SwitchPreferenceCompat>("feedback")!!.isChecked)
        assertTrue(fragment.preferenceManager.sharedPreferences!!.getBoolean("feedback", false))
        assertEquals(View.VISIBLE, row(fragment, "strength").findViewById<View>(R.id.ax_settings_chevron).visibility)
        assertEquals(View.GONE, row(fragment, "long_title").findViewById<View>(R.id.ax_settings_chevron).visibility)
        assertTrue("Every interactive row remains at least a 48dp target", switchRow.height >= 48)
    }

    @Test fun styledListRowsOpenAndSaveTheExistingPreferenceDialog() = fixture { activity, fragment ->
        row(fragment, "strength").performClick()
        activity.supportFragmentManager.executePendingTransactions()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        val list = dialog.listView
        list.performItemClick(list.adapter.getView(2, null, list), 2, list.adapter.getItemId(2))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("strong", fragment.findPreference<ListPreference>("strength")!!.value)
        assertEquals("strong", fragment.preferenceManager.sharedPreferences!!.getString("strength", null))
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h900dp-mdpi")
    fun narrowScreensAndLargeFontsWrapInsteadOfTruncating() {
        RuntimeEnvironment.setFontScale(1.4f)
        fixture { activity, fragment ->
            val row = row(fragment, "long_title")
            val title = row.findViewById<TextView>(android.R.id.title)
            assertTrue(title.lineCount > 1)
            assertTrue((0 until title.lineCount).all { title.layout.getEllipsisCount(it) == 0 })
            val summary = row.findViewById<TextView>(android.R.id.summary)
            assertTrue(summary.bottom <= (summary.parent as View).height)
            assertTrue(row.height >= title.height + summary.height)
            savePreview(activity.window.decorView, "secondary-large-font.png")
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h900dp-night-mdpi")
    fun nightPaletteRetainsAccessibleTextAndRendersGroupedPreferences() = fixture { activity, fragment ->
        val palette = AXiangSettingsPalette.from(activity)
        assertTrue(ColorUtils.calculateContrast(palette.text, palette.surface) >= 7.0)
        assertTrue(ColorUtils.calculateContrast(palette.secondary, palette.surface) >= 4.5)
        assertEquals(palette.text, row(fragment, "feedback").findViewById<TextView>(android.R.id.title).currentTextColor)
        savePreview(activity.window.decorView, "secondary-night.png")
    }

    @Test fun lightPaletteAndGroupedPreferencePreview() = fixture { activity, _ ->
        val palette = AXiangSettingsPalette.from(activity)
        assertTrue(ColorUtils.calculateContrast(palette.text, palette.surface) >= 7.0)
        assertTrue(ColorUtils.calculateContrast(palette.secondary, palette.surface) >= 4.5)
        savePreview(activity.window.decorView, "secondary-light.png")
    }

    private fun savePreview(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val directory = File("build/outputs/settings-checks").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
