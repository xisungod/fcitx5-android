/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.appcompat.widget.SwitchCompat
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodSubMode
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.bar.ui.TitleUi
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.bar.ui.idle.ButtonsBarUi
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightEditor
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
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
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import splitties.dimensions.dp
import java.io.File
import java.time.Duration

/** Native Android view renders for review; these are not screenshots from a physical phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class KeyboardControlsPreviewTest {
    private var previousApplication: FcitxApplication? = null
    private val restorePreferences = mutableListOf<() -> Unit>()

    @Before
    fun prepareRealViewsWithoutStartingTheNativeInputEngine() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val field = FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }
        previousApplication = field.get(null) as? FcitxApplication
        val application = RuntimeEnvironment.getApplication()
        val uiApplication = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(uiApplication, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        field.set(null, uiApplication)
        AppPrefs.init(application.getSharedPreferences("keyboard-controls-preview", Context.MODE_PRIVATE))
        val theme = ThemeManager.prefs
        setting(theme.idleBreathing, false)
        setting(theme.portraitNumberRow, true)
        setting(theme.keyBorder, true)
        setting(theme.keyBorderStroke, false)
        setting(theme.keyHorizontalMargin, 3)
        setting(theme.keyVerticalMargin, 4)
        setting(theme.keyRadius, 5)
        setting(AppPrefs.getInstance().keyboard.showLangSwitchKey, true)
    }

    @After
    fun restorePreferencesApplicationAndVsync() {
        restorePreferences.asReversed().forEach { it() }
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, previousApplication)
        }
        ShadowChoreographer.setPaused(false)
    }

    private fun <T : Any> setting(preference: ManagedPreference<T>, value: T) {
        val existed = preference.sharedPreferences.contains(preference.key)
        val previous = preference.getValue()
        restorePreferences += {
            if (existed) preference.setValue(previous)
            else preference.sharedPreferences.edit().remove(preference.key).commit()
            Unit
        }
        preference.setValue(value)
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
    }

    private fun save(view: View, name: String) {
        assertTrue("A review image must contain a laid-out view", view.width > 0 && view.height > 0)
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image).apply { drawColor(Color.BLACK) })
        val file = File("build/outputs/effect-checks/keyboard-controls/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }

    private fun keys(view: View): List<KeyView> = when (view) {
        is KeyView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { keys(view.getChildAt(it)) }
        else -> emptyList()
    }

    /** The production toolbar's actual buttons, with its two 52 dp outer controls. */
    private fun toolbar(context: Context): FrameLayout {
        val theme = ThemePreset.XuancaiBlackV09
        val size = context.dp(52)
        val buttons = ButtonsBarUi(context, theme)
        val descriptions = (0 until buttons.root.childCount).map { buttons.root.getChildAt(it).contentDescription }
        assertEquals("Keyboard switch, offline mic, undo, redo, clipboard and emoji stay in the toolbar", 6, descriptions.size)
        assertFalse(descriptions.contains(context.getString(R.string.keyboard_quick_settings)))
        assertFalse(descriptions.contains(context.getString(R.string.text_editing)))
        assertFalse(descriptions.contains(context.getString(R.string.keyboard_height_adjust)))
        assertTrue(descriptions.contains(context.getString(R.string.keyboard_layout_menu_title)))
        assertEquals("The microphone belongs immediately after the layout chooser",
            context.getString(R.string.offline_dictation_ui_title), descriptions[1])
        return FrameLayout(context).apply {
            setBackgroundColor(theme.barColor)
            addView(buttons.root,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size).apply {
                    leftMargin = size
                    rightMargin = size
                })
            addView(ToolButton(context, R.drawable.ic_keyboard_tools_24, theme),
                FrameLayout.LayoutParams(size, size, Gravity.START))
            addView(ToolButton(context, R.drawable.ic_keyboard_hide_24, theme),
                FrameLayout.LayoutParams(size, size, Gravity.END))
        }
    }

    @Test
    fun realKeyboardAndHeightEditorRenderAtRegularAndDraggedHeights() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get().apply { setTheme(R.style.Theme_InputViewTheme) }
        val width = activity.dp(360)
        val barHeight = activity.dp(52)
        var keyboardHeight = activity.dp(270)
        val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
        val keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
        root.addView(toolbar(activity), FrameLayout.LayoutParams(width, barHeight))
        root.addView(keyboard, FrameLayout.LayoutParams(width, keyboardHeight).apply { topMargin = barHeight })
        lateinit var editor: KeyboardHeightEditor
        editor = KeyboardHeightEditor(activity, ThemePreset.XuancaiBlackV09,
            onPreview = { height ->
                keyboardHeight = height
                keyboard.layoutParams = (keyboard.layoutParams as FrameLayout.LayoutParams).apply {
                    this.height = height
                }
                editor.updateHeight(height)
                layout(root, width, barHeight + height)
            },
            onDone = {}, onCancel = {}, onReset = {}
        )
        root.addView(editor, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        try {
            activity.setContentView(root)
            controller.visible()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            layout(root, width, barHeight + keyboardHeight)
            keyboard.onInputMethodUpdate(InputMethodEntry(
                "rime", "", "", "", "", "zh", "", false, InputMethodSubMode()
            ))
            keyboard.onPunctuationUpdate(mapOf("," to "，", "." to "。"))
            assertTrue(keyboard.isAttachedToWindow && keyboard.isShown)
            assertTrue("The review must include real, measurable keyboard keys",
                keys(keyboard).size > 30 && keys(keyboard).all { it.width > 0 && it.height > 0 })
            save(root, "height-keyboard-270dp")

            editor.show(keyboardHeight, activity.dp(200), activity.dp(440))
            layout(root, width, barHeight + keyboardHeight)
            assertTrue(editor.getChildAt(0).height > 0 && editor.getChildAt(1).height > 0)
            save(root, "height-editor-270dp")

            val downTime = SystemClock.uptimeMillis()
            val startY = activity.dp(24).toFloat()
            fun touch(action: Int, y: Float, elapsed: Long) {
                val event = MotionEvent.obtain(downTime, downTime + elapsed, action, width / 2f, y, 0)
                try { assertTrue(editor.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN, startY, 0L)
            touch(MotionEvent.ACTION_MOVE, startY - activity.dp(90), 20L)
            touch(MotionEvent.ACTION_UP, startY - activity.dp(90), 40L)
            assertEquals("The review must show an actual 90 dp drag result", activity.dp(360), keyboardHeight)
            assertEquals(View.VISIBLE, editor.visibility)
            assertTrue(keyboard.isShown)
            save(root, "height-editor-360dp")
        } finally {
            keyboard.onDetach()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun quickSettingsRenderInTheKeyboardWithFixedDoneAndScrollableControls() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get().apply { setTheme(R.style.Theme_InputViewTheme) }
        val theme = ThemePreset.XuancaiBlackV09
        val storage = activity.getSharedPreferences("keyboard-controls-quick-settings", Context.MODE_PRIVATE)
        storage.edit().clear().commit()
        val draft = KeyboardQuickSettingsDraft(ThemePrefs(storage), AppPrefs(storage).keyboard)
        val ui = KeyboardQuickSettingsUi(activity, theme, draft, disableAnimation = false,
            onDone = {}, onCancel = {}, onHeight = {}, onMore = {})
        val title = TitleUi(activity, theme).apply {
            setTitle(activity.getString(R.string.keyboard_quick_settings))
            addExtension(ui.extension, showTitle = true)
        }
        val width = activity.dp(360)
        val barHeight = activity.dp(52)
        val panelHeight = activity.dp(270)
        val root = FrameLayout(activity).apply {
            setBackgroundColor(theme.barColor)
            addView(title.root, FrameLayout.LayoutParams(width, barHeight))
            addView(ui.root, FrameLayout.LayoutParams(width, panelHeight).apply { topMargin = barHeight })
        }
        try {
            activity.setContentView(root)
            controller.visible()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            layout(root, width, barHeight + panelHeight)
            val done = ui.extension.findViewWithTag<View>("quick_done")
            assertNotNull(done)
            assertTrue("The real title-bar Done action must have a visible hit area", done.width > 0 && done.height > 0)
            val doneBounds = Rect().also {
                done.getDrawingRect(it)
                root.offsetDescendantRectToMyCoords(done, it)
            }
            assertTrue("Done must stay inside the fixed title bar",
                doneBounds.left >= 0 && doneBounds.right <= width &&
                    doneBounds.top >= 0 && doneBounds.bottom <= barHeight)
            assertTrue("The settings must genuinely require vertical scrolling",
                ui.root.getChildAt(0).height > ui.root.height)
            for (tag in listOf("quick_ripple_shape", "quick_idle_breathing")) {
                val control = ui.root.findViewWithTag<View>(tag)
                val bounds = Rect().also { control.getDrawingRect(it); ui.root.offsetDescendantRectToMyCoords(control, it) }
                assertTrue("$tag must be reachable on the first panel screen", bounds.top >= 0 && bounds.bottom <= panelHeight)
            }
            assertNull("Engine maintenance is not a common keyboard control", ui.root.findViewWithTag<View>("quick_rime"))
            save(root, "settings-top")

            for ((tag, name) in listOf(
                "quick_haptic_mode" to "settings-feedback",
                "quick_palettes" to "settings-palettes",
                "quick_ripple_shape" to "settings-ripple-shape",
                "quick_key_motion" to "settings-effects"
            )) {
                val control = ui.root.findViewWithTag<View>(tag)
                assertTrue("A settings control must be laid out: $tag", control.width > 0 && control.height > 0)
                ui.root.scrollTo(0, (control.top - activity.dp(38)).coerceAtLeast(0))
                save(root, name)
            }
            ui.root.scrollTo(0, ui.root.getChildAt(0).height)
            save(root, "settings-bottom")
            val unchangedStorage = storage.all.toMap()
            val originalNumberRow = draft.values.numberRow
            ui.root.findViewWithTag<SwitchCompat>("quick_number_row").isChecked = !originalNumberRow
            assertEquals(!originalNumberRow, draft.values.numberRow)
            assertEquals("Editing the live panel must stage changes until Done", unchangedStorage, storage.all)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
