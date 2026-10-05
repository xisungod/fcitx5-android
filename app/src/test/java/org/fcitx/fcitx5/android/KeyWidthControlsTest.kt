/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Spinner
import androidx.activity.ComponentActivity
import androidx.appcompat.widget.SwitchCompat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.*
import org.fcitx.fcitx5.android.input.settings.KeyboardQuickSettingsDraft
import org.fcitx.fcitx5.android.input.settings.KeyboardQuickSettingsUi
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class KeyWidthControlsTest {
    private var oldApplication: Any? = null
    private val restore = mutableListOf<() -> Unit>()

    @Before fun prepare() {
        val application = RuntimeEnvironment.getApplication()
        val field = FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }
        oldApplication = field.get(null)
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        field.set(null, app)
        AppPrefs.init(application.getSharedPreferences("key-width-test-host", Context.MODE_PRIVATE))
        setting(ThemeManager.prefs.keyWidthOverrides, "")
        setting(ThemeManager.prefs.pressEffect, false)
        setting(ThemeManager.prefs.idleBreathing, false)
        setting(ThemeManager.prefs.keyMotionEffect, ThemePrefs.KeyMotionEffect.Off)
        setting(ThemeManager.prefs.portraitNumberRow, true)
        setting(AppPrefs.getInstance().keyboard.expandKeypressArea, true)
        setting(AppPrefs.getInstance().keyboard.showLangSwitchKey, true)
        setting(AppPrefs.getInstance().keyboard.hapticStrength, 0)
        setting(AppPrefs.getInstance().keyboard.popupOnKeyPress, false)
    }

    @After fun restore() {
        restore.asReversed().forEach { it() }
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true; set(null, oldApplication)
        }
    }

    private fun <T : Any> setting(pref: ManagedPreference<T>, value: T) {
        val exists = pref.sharedPreferences.contains(pref.key)
        val old = pref.getValue()
        restore += { if (exists) pref.setValue(old) else pref.sharedPreferences.edit().remove(pref.key).commit(); Unit }
        pref.setValue(value)
    }

    private fun keys(view: View): List<KeyView> = when (view) {
        is KeyView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { keys(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun touch(view: View, action: Int, x: Float, y: Float, time: Long) {
        val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
        try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
    }

    @Test fun boundedWeightsReflowWithoutCollapsingNeighboursAndResetOnlyTheChosenLayout() {
        val settings = KeyWidthSettings.parse("Text:51=150;Text:57=70;T9:5=999;broken;bad/id=80;Text:45=oops")
        assertEquals(150, settings["Text:51"])
        assertEquals(150, settings["T9:5"])
        assertEquals(100, settings["Text:45"])
        assertEquals(settings, KeyWidthSettings.parse(settings.encode()))
        assertEquals(100, settings.reset(KeyWidthProfile.Text)["Text:51"])
        assertEquals(150, settings.reset(KeyWidthProfile.Text)["T9:5"])
        val base = List(10) { 0.1f }
        for (selected in base.indices) {
            val widths = KeyWidthSettings.distribute(base, base.indices.map { if (it == selected) 70 else 150 })
            assertEquals(1f, widths.sum(), 0.00001f)
            assertTrue("Even adverse ratios leave a useful touch width", widths.all { it >= 0.065f - 0.00001f })
        }
    }

    @Test fun fourRealLayoutsMoveTheirTouchBoundariesAndRemainATiledKeyboard() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).also {
            it.get().setTheme(R.style.Theme_InputViewTheme)
        }.setup()
        val activity = controller.get()
        val history = SymbolHistory(activity.getSharedPreferences("width-symbol-history", Context.MODE_PRIVATE))
        val fixtures = listOf(
            Triple(KeyWidthProfile.Text, "Text:51", "Q"),
            Triple(KeyWidthProfile.T9, "T9:5", "ABC"),
            Triple(KeyWidthProfile.Number, "Number:4", "1"),
            Triple(KeyWidthProfile.Symbols, "Symbols:5", "？")
        )
        try {
            fixtures.forEach { (profile, id, label) ->
                ThemeManager.prefs.keyWidthOverrides.setValue(KeyWidthSettings().withWidth(id, 150).encode())
                val keyboard: BaseKeyboard = when (profile) {
                    KeyWidthProfile.Text -> TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
                    KeyWidthProfile.T9 -> PinyinT9Keyboard(activity, ThemePreset.XuancaiBlackV09)
                    KeyWidthProfile.Number -> NumberKeyboard(activity, ThemePreset.XuancaiBlackV09)
                    KeyWidthProfile.Symbols -> SymbolKeyboard(activity, ThemePreset.XuancaiBlackV09, SymbolKeyboardState(), history)
                }
                val actions = mutableListOf<KeyAction>()
                keyboard.keyActionListener = KeyActionListener { action, _ -> actions += action }
                activity.setContentView(keyboard)
                controller.visible()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                layout(keyboard, 421, 283)
                val all = keys(keyboard).filter { it.visibility != View.GONE }
                val bounds = all.associateWith { key -> Rect().also {
                    key.getDrawingRect(it); keyboard.offsetDescendantRectToMyCoords(key, it)
                } }
                val selected = all.filterIsInstance<TextKeyView>().first {
                    (it.def as KeyDef.Appearance.Text).displayText == label
                }
                val selectedBounds = bounds.getValue(selected)
                val originalFraction = when (profile) {
                    KeyWidthProfile.Text -> .1f
                    KeyWidthProfile.Symbols -> .215f
                    else -> .24f
                }
                assertTrue("$profile must change the actual view, not just its painted face",
                    selectedBounds.width() > 421 * originalFraction + 8)
                for (y in 0 until keyboard.height) for (x in 0 until keyboard.width) {
                    assertEquals("$profile has an overlap or gap at $x,$y", 1, bounds.values.count { it.contains(x, y) })
                }
                val time = SystemClock.uptimeMillis()
                touch(keyboard, MotionEvent.ACTION_DOWN, selectedBounds.right - 2f, selectedBounds.exactCenterY(), time)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
                touch(keyboard, MotionEvent.ACTION_UP, selectedBounds.right - 2f, selectedBounds.exactCenterY(), time)
                assertEquals("The newly widened edge must route to the selected key", 1, actions.size)
                when (profile) {
                    // Alphabet keys retain their physical uppercase scancode when case is transformed.
                    KeyWidthProfile.Text -> assertEquals(KeyAction.FcitxKeyAction("q",
                        code = org.fcitx.fcitx5.android.core.ScancodeMapping.KEY_Q), actions.single())
                    KeyWidthProfile.T9 -> assertEquals(KeyAction.FcitxKeyAction("2"), actions.single())
                    KeyWidthProfile.Number -> assertEquals(KeyAction.SymAction(
                        org.fcitx.fcitx5.android.core.KeySym(org.fcitx.fcitx5.android.core.FcitxKeyMapping.FcitxKey_KP_1),
                        NumLockState), actions.single())
                    KeyWidthProfile.Symbols -> assertEquals(KeyAction.CommitAction("？"), actions.single())
                }
                keyboard.onDetach()
            }
            setting(AppPrefs.getInstance().keyboard.showLangSwitchKey, false)
            ThemeManager.prefs.keyWidthOverrides.setValue(KeyWidthSettings().withWidth("Text:space", 150).encode())
            val text = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
            layout(text, 421, 283)
            assertEquals(View.GONE, text.lang.visibility)
            assertTrue(text.space.width > 421 * .32f)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun draftCancellationAndDonePreserveOtherLayoutsAndConcurrentEdits() {
        val storage = RuntimeEnvironment.getApplication().getSharedPreferences("key-width-draft", Context.MODE_PRIVATE)
        storage.edit().clear().commit()
        val prefs = ThemePrefs(storage)
        val keyboard = AppPrefs(storage).keyboard
        val draft = KeyboardQuickSettingsDraft(prefs, keyboard)
        draft.values = draft.values.copy(keyWidths = KeyWidthSettings().withWidth("Text:51", 150))
        assertEquals(KeyWidthSettings(), KeyboardQuickSettingsDraft(prefs, keyboard).values.keyWidths)
        prefs.keyWidthOverrides.setValue(KeyWidthSettings().withWidth("T9:5", 125).encode())
        draft.values = draft.values.copy(popup = false)
        assertTrue(draft.apply())
        val saved = KeyWidthSettings.parse(prefs.keyWidthOverrides.getValue())
        assertEquals(150, saved["Text:51"])
        assertEquals(125, saved["T9:5"])
        assertFalse(keyboard.popupOnKeyPress.getValue())
        val reopened = KeyboardQuickSettingsDraft(prefs, keyboard)
        reopened.values = reopened.values.copy(keyWidths = reopened.values.keyWidths.reset(KeyWidthProfile.Text))
        reopened.apply()
        assertEquals(100, KeyWidthSettings.parse(prefs.keyWidthOverrides.getValue())["Text:51"])
        assertEquals(125, KeyWidthSettings.parse(prefs.keyWidthOverrides.getValue())["T9:5"])
    }

    @Test fun editorSelectsIndividualKeysStagesWidthsAndSwitchesBreathingWithTheEffectMode() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).also {
            it.get().setTheme(R.style.Theme_InputViewTheme)
        }.setup()
        val activity = controller.get()
        val storage = activity.getSharedPreferences("key-width-ui", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val draft = KeyboardQuickSettingsDraft(ThemePrefs(storage), AppPrefs(storage).keyboard)
        val ui = KeyboardQuickSettingsUi(activity, ThemePreset.XuancaiBlackV09, draft, false,
            onDone = {}, onCancel = {}, onHeight = {}, onMore = {})
        val container = FrameLayout(activity).apply { addView(ui.root) }
        try {
            activity.setContentView(container)
            controller.visible()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            layout(container, 360, 650)
            val breathing = ui.root.findViewWithTag<SwitchCompat>("quick_idle_breathing")
            assertFalse("Default sam idle must remain black", breathing.isEnabled)
            ui.root.findViewWithTag<Spinner>("quick_ripple_shape").setSelection(ThemePrefs.RippleShape.SoftMist.ordinal)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            assertTrue(breathing.isEnabled)
            ui.root.findViewWithTag<View>("quick_key_width_expand").performClick()
            layout(container, 360, 650)
            val preview = ui.root.findViewWithTag<ViewGroup>("quick_key_width_preview")
            val selected = preview.findViewWithTag<View>("quick_key_width_key_10")
            assertTrue(selected.performClick())
            val bar = ui.root.findViewWithTag<SeekBar>("quick_key_width_slider")
            layout(container, 360, 650)
            val time = SystemClock.uptimeMillis()
            touch(bar, MotionEvent.ACTION_DOWN, bar.width - 1f, bar.height / 2f, time)
            touch(bar, MotionEvent.ACTION_MOVE, bar.width - 1f, bar.height / 2f, time)
            touch(bar, MotionEvent.ACTION_UP, bar.width - 1f, bar.height / 2f, time)
            assertEquals(150, draft.values.keyWidths["Text:51"])
            assertTrue("Selecting and dragging remain local until Done", storage.all.isEmpty())
            val editor = ui.root.findViewWithTag<View>("quick_key_width_editor")
            layout(container, 360, 650)
            assertTrue(editor.width > 0 && editor.height > 0)
            val image = Bitmap.createBitmap(editor.width, editor.height, Bitmap.Config.ARGB_8888)
            editor.draw(Canvas(image).apply { drawColor(ThemePreset.XuancaiBlackV09.keyboardColor) })
            val output = File("build/outputs/effect-checks/key-width-editor.png")
            output.parentFile.mkdirs()
            output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            ui.root.findViewWithTag<View>("quick_key_width_reset_key").performClick()
            assertEquals(100, draft.values.keyWidths["Text:51"])
            val picker = ui.root.findViewWithTag<Spinner>("quick_key_width_layout")
            for (profile in KeyWidthProfile.entries) {
                picker.setSelection(profile.ordinal)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                layout(container, 360, 650)
                assertTrue("Every supported layout must render selectable real slots", preview.childCount >= 20)
            }
            assertTrue(storage.all.isEmpty())
        } finally { ui.dispose(); controller.pause().stop().destroy() }
    }
}
