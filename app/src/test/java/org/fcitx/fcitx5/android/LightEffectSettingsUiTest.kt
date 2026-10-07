package org.fcitx.fcitx5.android

import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ContextThemeWrapper
import android.view.inputmethod.EditorInfo
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.PopupMenu
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.PressColorPalette
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.idle.KeyboardLayoutChoice
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.settings.KeyboardQuickSettingsWindow
import org.fcitx.fcitx5.android.input.status.StatusAreaAdapter
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry
import org.fcitx.fcitx5.android.input.status.StatusAreaWindow
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.plusAssign
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.fcitx.fcitx5.android.input.keyboard.PressEffect
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyboardSizePolicy
import org.fcitx.fcitx5.android.ui.main.settings.theme.LightEffectSettingsFragment
import org.fcitx.fcitx5.android.ui.main.settings.theme.LightEffectSettingsUi
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
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w400dp-h900dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class LightEffectSettingsUiTest {
    @Before fun prepare() {
        val application = RuntimeEnvironment.getApplication()
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, app) }
        AppPrefs.init(application.getSharedPreferences("light-settings-test", Context.MODE_PRIVATE))
        val prefs = ThemeManager.prefs
        prefs.pressColorMode.setValue(ThemePrefs.PressColorMode.Random)
        prefs.pressEffectPalette.setValue(ThemePrefs.PressEffectPalette.Cyberpunk)
        prefs.portraitNumberRow.setValue(true)
        prefs.pressEffect.setValue(true)
        prefs.pressSingleColor.setValue(0xFF00E8FF.toInt())
        prefs.pressUserColors.setValue("#18FFC1,#D96EFF,#FF8A32")
        prefs.pressIgnitionTime.setValue(100)
        prefs.pressExpansionTime.setValue(1400)
        prefs.pressWaveHoldTime.setValue(350)
        prefs.pressFadeOutTime.setValue(900)
        prefs.pressKeyHoldTime.setValue(120)
        prefs.pressKeyRetreatTime.setValue(1800)
    }

    private fun activity() = Robolectric.buildActivity(AppCompatActivity::class.java).also {
        it.get().setTheme(R.style.Theme_AXiang)
    }.setup()

    private fun layout(view: View, width: Int = 600, height: Int = 1260) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun save(view: View, filename: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        save(bitmap, filename)
    }

    private fun save(bitmap: Bitmap, filename: String) {
        val target = File("build/outputs/effect-checks/$filename.png")
        target.parentFile.mkdirs()
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Render the actual native child at the actual scroll offset, bypassing Robolectric's stale ScrollView display list. */
    private fun captureScrollViewport(scroll: ScrollView): Bitmap {
        val body = scroll.getChildAt(0)
        fun draw(): Bitmap = Bitmap.createBitmap(scroll.width, scroll.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            scroll.background?.draw(canvas)
            canvas.clipRect(scroll.paddingLeft, scroll.paddingTop, scroll.width - scroll.paddingRight, scroll.height - scroll.paddingBottom)
            canvas.translate((body.left - scroll.scrollX).toFloat(), (body.top - scroll.scrollY).toFloat())
            body.draw(canvas)
        }
        // Native TextViews/SeekBars build their display lists lazily; use real draws to warm them.
        repeat(2) { draw().recycle() }
        return draw()
    }

    private fun assertSliderPixelsAreVisible(bitmap: Bitmap, viewport: View, slider: View) {
        val origin = IntArray(2).also { viewport.getLocationOnScreen(it) }
        val position = IntArray(2).also { slider.getLocationOnScreen(it) }
        val area = Rect(position[0] - origin[0], position[1] - origin[1],
            position[0] - origin[0] + slider.width, position[1] - origin[1] + slider.height)
        assertTrue("Slider must lie within the captured viewport", area.intersect(0, 0, bitmap.width, bitmap.height))
        val accent = ContextCompat.getColor(viewport.context, R.color.ax_settings_accent)
        var accentPixels = 0
        for (y in area.top until area.bottom) for (x in area.left until area.right) {
            val color = bitmap.getPixel(x, y)
            if (Color.alpha(color) > 240 && abs(Color.red(color) - Color.red(accent)) < 20 &&
                abs(Color.green(color) - Color.green(accent)) < 20 &&
                abs(Color.blue(color) - Color.blue(accent)) < 20) accentPixels++
        }
        assertTrue("${slider.contentDescription} must be drawn as real visible slider pixels", accentPixels > 10)
    }


    @Test fun commonControlsStayVisibleAndExpandingDetailsDoesNotChangeSavedSettings() {
        val controller = activity()
        val prefs = ThemeManager.prefs
        prefs.rippleShape.setValue(ThemePrefs.RippleShape.Sam)
        val stored = prefs.pressEffect.sharedPreferences
        val before = stored.all.toMap()
        val ui = LightEffectSettingsUi(controller.get())
        try {
            controller.get().setContentView(ui.root)
            shadowOf(Looper.getMainLooper()).idle()
            layout(ui.root)
            assertEquals(ContextCompat.getColor(ui.context, R.color.ax_settings_background),
                (ui.root.background as ColorDrawable).color)
            assertEquals(ContextCompat.getColor(ui.context, R.color.ax_settings_surface),
                (ui.root.findViewWithTag<View>("effect-basics").background as GradientDrawable).color!!.defaultColor)
            assertTrue(ui.root.findViewWithTag<View>(prefs.pressEffect.key).isShown)
            assertTrue(ui.root.findViewWithTag<View>("effect-ripple-shape").isShown)
            assertTrue(ui.modeButtons.getValue(ThemePrefs.PressColorMode.Random).isShown)
            val toggle = ui.root.findViewWithTag<View>("effect-advanced-toggle")
            val timing = ui.durationControls.getValue(prefs.samKeyRetreatTime.key)
            assertFalse(timing.root.isShown)
            assertEquals(ui.context.getString(R.string.ax_light_collapsed), ViewCompat.getStateDescription(toggle))
            toggle.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            layout(ui.root)
            assertTrue(timing.root.isShown)
            assertEquals(ui.context.getString(R.string.ax_light_expanded), ViewCompat.getStateDescription(toggle))
            toggle.performClick()
            assertFalse(timing.root.isShown)
            assertEquals("Opening or expanding controls never rewrites preferences", before, stored.all)
        } finally {
            ui.dismissDialogs()
            controller.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w400dp-h900dp-night-hdpi")
    fun lightSettingsAndColourEditorFollowTheNightPalette() {
        val controller = activity()
        val activity = controller.get()
        val ui = LightEffectSettingsUi(activity)
        try {
            activity.setContentView(ui.root)
            layout(ui.root)
            val surface = ContextCompat.getColor(activity, R.color.ax_settings_surface)
            val ink = ContextCompat.getColor(activity, R.color.ax_settings_text)
            val accent = ContextCompat.getColor(activity, R.color.ax_settings_accent)
            assertTrue("Night surface must be dark", androidx.core.graphics.ColorUtils.calculateLuminance(surface) < 0.15)
            assertTrue("Text must remain readable on cards", androidx.core.graphics.ColorUtils.calculateContrast(ink, surface) >= 4.5)
            val toggle = ui.root.findViewWithTag<Switch>(ThemeManager.prefs.pressEffect.key)
            assertEquals(ink, toggle.currentTextColor)
            val control = ui.durationControls.getValue(ThemeManager.prefs.samKeyRetreatTime.key)
            assertEquals(accent, control.slider.progressTintList!!.defaultColor)
            save(ui.root, "light-settings-night")
            ui.modeButtons.getValue(ThemePrefs.PressColorMode.Single).performClick()
            ui.root.findViewWithTag<Button>("effect-single-color").performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ui.activeColorDialog!!
            assertEquals(accent, dialog.getButton(AlertDialog.BUTTON_POSITIVE).currentTextColor)
            assertEquals(ink, dialog.window!!.decorView.findViewWithTag<EditText>("effect-color-hex").currentTextColor)
        } finally {
            ui.dismissDialogs()
            controller.pause().stop().destroy()
        }
    }

    @Test fun lightSettingsExposeTheKeyboardSizeAndFeedbackShortcut() {
        val controller = activity()
        var opened = 0
        val ui = LightEffectSettingsUi(controller.get(), openKeyboardSettings = { opened++ })
        val button = ui.root.findViewWithTag<Button>("keyboard-size-feedback-settings")
        assertNotNull(button)
        assertEquals(controller.get().getString(R.string.keyboard_size_feedback), button.text)
        button.performClick()
        assertEquals(1, opened)
        ui.dismissDialogs()
        controller.pause().stop().destroy()
    }

    /** The native input engine is outside this test; the toolbar, window manager and settings are real. */
    private class ToolbarKeyboardPlaceholder : InputWindow.SimpleInputWindow<ToolbarKeyboardPlaceholder>(), EssentialWindow {
        override val key: EssentialWindow.Key get() = KeyboardWindow
        override fun onCreateView(): View = View(context)
        override fun onAttached() {}
        override fun onDetached() {}
    }

    @Test fun toolsMenuOpensKeyboardSettingsAndBackDiscardsWhileDoneApplies() {
        val controller = activity()
        val activity = controller.get()
        val scope = DynamicScope()
        val themedContext: ContextThemeWrapper = ContextThemeWrapper(activity, R.style.Theme_InputViewTheme)
        val theme: Theme = ThemePreset.XuancaiBlackV09
        val broadcaster = InputBroadcaster()
        val windows = InputWindowManager()
        val bar = KawaiiBarComponent()
        // Supply engine status as a normal broadcast below. No native engine work may run here.
        val connection: FcitxConnection = object : FcitxConnection {
            override val lifecycleScope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Main)
            override fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T = error("Native engine is outside this UI test")
            override suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T = error("Native engine is outside this UI test")
            override fun runIfReady(block: suspend FcitxAPI.() -> Unit) {}
        }
        // Complete the listener's service dependency without starting the native daemon.
        val queuedService = Robolectric.buildService(FcitxInputMethodService::class.java).get()
        ReflectionHelpers.setField(queuedService, "fcitx", connection)
        assertTrue(connection.lifecycleScope.coroutineContext[Job]!!.isCancelled)
        scope += themedContext.wrapToUniqueComponent()
        scope += theme.wrapToUniqueComponent()
        scope += connection.wrapToUniqueComponent()
        scope += queuedService.wrapToUniqueComponent()
        scope += broadcaster
        scope += windows
        scope += CommonKeyActionListener()
        scope += PopupComponent()
        scope += HorizontalCandidateComponent()
        scope += bar
        windows.onScopeSetupFinished(scope)
        val keyboard = ToolbarKeyboardPlaceholder()
        windows.addEssentialWindow(keyboard)
        val rowPref = ThemeManager.prefs.portraitNumberRow
        val hadRow = rowPref.sharedPreferences.contains(rowPref.key)
        val oldRow = rowPref.getValue()
        val shapePref = ThemeManager.prefs.rippleShape
        val hadShape = shapePref.sharedPreferences.contains(shapePref.key)
        val oldShape = shapePref.getValue()
        val breathingPref = ThemeManager.prefs.idleBreathing
        val hadBreathing = breathingPref.sharedPreferences.contains(breathingPref.key)
        val oldBreathing = breathingPref.getValue()
        val animationPref = AppPrefs.getInstance().advanced.disableAnimation
        val oldAnimation = animationPref.getValue()
        animationPref.setValue(true)
        try {
            windows.attachWindow(keyboard)
            val shell = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(bar.view, LinearLayout.LayoutParams(-1, 60))
                addView(windows.view, LinearLayout.LayoutParams(-1, 405))
            }
            activity.setContentView(shell)
            controller.visible()
            bar.onStartInput(EditorInfo(), CapabilityFlags(0uL))
            layout(shell, 600, 465)
            fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            val toolbarItems = descendants(bar.view)
            assertTrue("Offline dictation is available from the permanent toolbar", toolbarItems.any {
                it.contentDescription == activity.getString(R.string.offline_dictation_ui_title)
            })
            assertFalse("Color settings must not remain in the permanent toolbar", toolbarItems.any {
                it.contentDescription == activity.getString(R.string.keyboard_quick_settings)
            })
            assertFalse("Text editing must not remain in the permanent toolbar", toolbarItems.any {
                it.contentDescription == activity.getString(R.string.text_editing)
            })
            assertFalse("Height moves to the tools menu", toolbarItems.any {
                it.contentDescription == activity.getString(R.string.keyboard_height_adjust)
            })
            val layoutButton = toolbarItems.first {
                it.contentDescription == activity.getString(R.string.keyboard_layout_menu_title)
            }
            assertTrue(layoutButton.performClick())
            val layoutMenu = ReflectionHelpers.getField<PopupMenu>(bar, "keyboardLayoutPopup")
            assertEquals(KeyboardLayoutChoice.entries.size, layoutMenu.menu.size())
            assertEquals(KeyboardLayoutChoice.entries.map { activity.getString(it.title) },
                (0 until layoutMenu.menu.size()).map { layoutMenu.menu.getItem(it).title.toString() })
            assertTrue("Opening the layout chooser does not switch the current keyboard", windows.isAttached(keyboard))
            layoutMenu.dismiss()
            val entry = toolbarItems.first {
                it.contentDescription == activity.getString(R.string.keyboard_tools)
            }
            assertTrue(entry.width > 0)
            fun openSettingsFromToolsMenu() {
                assertTrue(entry.performClick())
                val menu = ReflectionHelpers.getField<InputWindow>(windows, "currentWindow") as StatusAreaWindow
                broadcaster.onStatusAreaUpdate(emptyArray())
                layout(shell, 600, 465)
                val adapter = menu.view.adapter as StatusAreaAdapter
                assertTrue("Text editing remains available from the tools menu", adapter.entries.any {
                    it is StatusAreaEntry.Android && it.type == StatusAreaEntry.Android.Type.TextEditing
                })
                assertTrue("Height remains available from the tools menu", adapter.entries.any {
                    it is StatusAreaEntry.Android && it.type == StatusAreaEntry.Android.Type.KeyboardHeight
                })
                val position = adapter.entries.indexOfFirst {
                    it is StatusAreaEntry.Android && it.type == StatusAreaEntry.Android.Type.KeyboardSettings
                }
                assertTrue("Tools menu must contain keyboard settings", position >= 0)
                val holder = menu.view.findViewHolderForAdapterPosition(position)
                assertNotNull("The real menu entry must be visible and clickable", holder)
                assertTrue(holder!!.itemView.performClick())
            }
            openSettingsFromToolsMenu()
            assertTrue(ReflectionHelpers.getField<InputWindow>(windows, "currentWindow") is KeyboardQuickSettingsWindow)
            val pending = windows.view.findViewWithTag<SwitchCompat>("quick_number_row")
            assertNotNull("The actual menu entry must attach the real keyboard settings panel", pending)
            pending.isChecked = !oldRow
            assertEquals("Editing a control must only change the draft", oldRow, rowPref.getValue())
            val back = descendants(bar.view).first {
                it.contentDescription == activity.getString(R.string.back_to_keyboard)
            }
            assertTrue(back.performClick())
            assertTrue(windows.isAttached(keyboard))
            assertEquals(oldRow, rowPref.getValue())
            openSettingsFromToolsMenu()
            val reopened = windows.view.findViewWithTag<SwitchCompat>("quick_number_row")
            assertEquals("A canceled draft must not return when reopening", oldRow, reopened.isChecked)
            reopened.isChecked = !oldRow
            assertTrue(bar.view.findViewWithTag<View>("quick_done").performClick())
            assertTrue("Done returns to the keyboard before preference callbacks", windows.isAttached(keyboard))
            assertEquals(!oldRow, rowPref.getValue())

            fun openDirectTool(type: StatusAreaEntry.Android.Type): StatusAreaWindow {
                assertTrue(entry.performClick())
                val menu = ReflectionHelpers.getField<InputWindow>(windows, "currentWindow") as StatusAreaWindow
                broadcaster.onStatusAreaUpdate(emptyArray())
                layout(shell, 600, 465)
                val adapter = menu.view.adapter as StatusAreaAdapter
                assertEquals("The common tools have six concise entries", 6, adapter.entries.size)
                assertFalse(adapter.entries.any { it is StatusAreaEntry.Android && it.type in setOf(
                    StatusAreaEntry.Android.Type.ReloadConfig, StatusAreaEntry.Android.Type.Keyboard,
                    StatusAreaEntry.Android.Type.InputMethod, StatusAreaEntry.Android.Type.ThemeList) })
                val position = adapter.entries.indexOfFirst { it is StatusAreaEntry.Android && it.type == type }
                assertTrue(position >= 0)
                val holder = menu.view.findViewHolderForAdapterPosition(position)
                assertNotNull(holder)
                holder!!.itemView.performClick()
                return menu
            }
            val shapeMenu = openDirectTool(StatusAreaEntry.Android.Type.RippleShape)
            val picker = shapeMenu.popupMenu!!
            val newShape = if (oldShape == ThemePrefs.RippleShape.SoftMist)
                ThemePrefs.RippleShape.IrregularFluid else ThemePrefs.RippleShape.SoftMist
            assertEquals(ThemePrefs.RippleShape.entries.size, picker.menu.size())
            assertTrue(picker.menu.performIdentifierAction(newShape.ordinal + 1, 0))
            assertTrue("Choosing a shape returns to the keyboard for immediate feedback", windows.isAttached(keyboard))
            assertEquals(newShape, shapePref.getValue())
            openDirectTool(StatusAreaEntry.Android.Type.IdleBreathing)
            assertTrue(windows.isAttached(keyboard))
            assertEquals("The breathing tile applies with one tap", !oldBreathing, breathingPref.getValue())
            assertNull("Common keyboard settings must never start the controller Activity",
                shadowOf(activity).nextStartedActivity)
        } finally {
            if (hadRow) rowPref.setValue(oldRow) else rowPref.sharedPreferences.edit().remove(rowPref.key).apply()
            if (hadShape) shapePref.setValue(oldShape) else shapePref.sharedPreferences.edit().remove(shapePref.key).apply()
            if (hadBreathing) breathingPref.setValue(oldBreathing) else breathingPref.sharedPreferences.edit().remove(breathingPref.key).apply()
            animationPref.setValue(oldAnimation)
            controller.pause().stop().destroy()
        }
    }

    @Test fun singleColourCanBeAnyRgbValueAndInvalidHexCannotSave() {
        val controller = activity()
        val activity = controller.get()
        val prefs = ThemeManager.prefs
        val ui = LightEffectSettingsUi(activity)
        activity.setContentView(ui.root)
        ui.modeButtons.getValue(ThemePrefs.PressColorMode.Single).performClick()
        ui.root.findViewWithTag<Button>("effect-single-color").performClick()
        shadowOf(Looper.getMainLooper()).idle() // Dialog dispatches OnShow through the main handler.
        val dialog = ui.activeColorDialog!!
        val hex = dialog.findViewById<EditText>(android.R.id.edit) ?: dialog.window!!.decorView.findViewWithTag<EditText>("effect-color-hex")
        hex.setText("#12345Z")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(dialog.isShowing)
        assertEquals(0xFF00E8FF.toInt(), prefs.pressSingleColor.getValue())
        hex.setText("#376BAD")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertEquals(0xFF376BAD.toInt(), prefs.pressSingleColor.getValue())
        assertEquals(ThemePrefs.PressColorMode.Single, prefs.pressColorMode.getValue())
        ui.dismissDialogs()
        controller.pause().stop().destroy()
    }

    @Test fun customPaletteEditsAddsAndRemovesRealStoredColoursUpToEight() {
        val controller = activity()
        val activity = controller.get()
        val ui = LightEffectSettingsUi(activity)
        activity.setContentView(ui.root)
        ui.modeButtons.getValue(ThemePrefs.PressColorMode.Custom).performClick()
        repeat(5) { ui.root.findViewWithTag<Button>("effect-color-add").performClick() }
        assertEquals(8, ThemeManager.prefs.userPressColors().size)
        assertFalse(ui.root.findViewWithTag<Button>("effect-color-add").isEnabled)
        ui.root.findViewWithTag<Button>("effect-custom-color-7").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ui.activeColorDialog!!
        dialog.window!!.decorView.findViewWithTag<EditText>("effect-color-hex").setText("#A1B2C3")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0xFFA1B2C3.toInt(), ThemeManager.prefs.userPressColors().last())
        repeat(8) { ui.root.findViewWithTag<Button>("effect-custom-color-0").performLongClick() }
        assertEquals(1, ThemeManager.prefs.userPressColors().size)
        ui.dismissDialogs()
        controller.pause().stop().destroy()
    }

    @Test fun openingThePageKeepsExistingTimingsAndSliderReleasePersistsTheChosenDuration() {
        val prefs = ThemeManager.prefs
        prefs.pressExpansionTime.setValue(1410)
        prefs.pressFadeOutTime.setValue(450)
        val ui = LightEffectSettingsUi(RuntimeEnvironment.getApplication())
        assertEquals(1410, prefs.pressExpansionTime.getValue())
        assertEquals(450, prefs.pressFadeOutTime.getValue())
        val control = ui.durationControls.getValue(prefs.pressExpansionTime.key)
        control.slider.progress = 95 // 100 + 95*20 = 2000 ms.
        assertEquals(1410, prefs.pressExpansionTime.getValue())
        control.commit()
        assertEquals(2000, prefs.pressExpansionTime.getValue())
        assertEquals(350, prefs.pressWaveHoldTime.getValue())
    }

    @Test fun quickerDefaultIgnitionIsAdjustableWithoutOverwritingASavedTiming() {
        val prefs = ThemeManager.prefs
        prefs.rippleShape.setValue(ThemePrefs.RippleShape.SoftMist)
        assertTrue(prefs.pressIgnitionTime.sharedPreferences.edit().remove(prefs.pressIgnitionTime.key).commit())
        assertEquals(40, prefs.pressIgnitionTime.getValue())
        val ui = LightEffectSettingsUi(RuntimeEnvironment.getApplication())
        val control = ui.durationControls.getValue(prefs.pressIgnitionTime.key)
        assertEquals(1, control.slider.progress) // 30 + 1 * 10 = 40ms.
        assertEquals(ui.context.getString(R.string.light_effect_total, 2690),
            ui.root.findViewWithTag<TextView>("effect-total-time").text.toString())
        control.slider.progress = 7
        assertEquals("Dragging does not save before release", 40, prefs.pressIgnitionTime.getValue())
        control.commit()
        assertEquals(100, prefs.pressIgnitionTime.getValue())
        assertEquals(ui.context.getString(R.string.light_effect_total, 2750),
            ui.root.findViewWithTag<TextView>("effect-total-time").text.toString())
        assertEquals(ui.context.getString(R.string.light_effect_total_detail, 100),
            ui.root.findViewWithTag<TextView>("effect-total-detail").text.toString())
        val reopened = LightEffectSettingsUi(RuntimeEnvironment.getApplication())
        assertEquals("Opening the new page preserves the user's saved 100ms", 100, prefs.pressIgnitionTime.getValue())
        assertEquals(7, reopened.durationControls.getValue(prefs.pressIgnitionTime.key).slider.progress)
    }

    @Test fun samTimingControlsUseIndependentColoursAndImmediateWaveTimingAcrossModeChanges() {
        val controller = activity()
        val prefs = ThemeManager.prefs
        prefs.rippleShape.setValue(ThemePrefs.RippleShape.Sam)
        prefs.samKeyHoldTime.setValue(80)
        prefs.samKeyRetreatTime.setValue(800)
        prefs.pressKeyHoldTime.setValue(37)
        prefs.pressKeyRetreatTime.setValue(1783)
        val stored = prefs.pressEffect.sharedPreferences
        val before = stored.all.toMap()
        val ui = LightEffectSettingsUi(controller.get())
        try {
            controller.get().setContentView(ui.root)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Opening timing settings cannot normalize saved values", before, stored.all)
            fun visible(pref: org.fcitx.fcitx5.android.data.prefs.ManagedPreference.PInt) =
                ui.durationControls.getValue(pref.key).root.visibility == View.VISIBLE
            assertTrue(visible(prefs.samKeyHoldTime))
            assertTrue(visible(prefs.samKeyRetreatTime))
            assertFalse(visible(prefs.pressKeyHoldTime))
            assertFalse(visible(prefs.pressKeyRetreatTime))
            assertFalse(visible(prefs.pressIgnitionTime))
            assertEquals(ui.context.getString(R.string.light_effect_total, 2650),
                ui.root.findViewWithTag<TextView>("effect-total-time").text.toString())
            assertEquals(ui.context.getString(R.string.sam_wave_total_detail),
                ui.root.findViewWithTag<TextView>("effect-total-detail").text.toString())
            val hold = ui.durationControls.getValue(prefs.samKeyHoldTime.key)
            val fade = ui.durationControls.getValue(prefs.samKeyRetreatTime.key)
            assertEquals(8, hold.slider.progress)
            assertEquals(70, fade.slider.progress)
            hold.slider.progress = 16
            hold.commit()
            fade.slider.progress = 130
            fade.commit()
            assertEquals(160, prefs.samKeyHoldTime.getValue())
            assertEquals(1400, prefs.samKeyRetreatTime.getValue())
            assertEquals(37, prefs.pressKeyHoldTime.getValue())
            assertEquals(1783, prefs.pressKeyRetreatTime.getValue())
            val selector = ui.root.findViewWithTag<Spinner>("effect-ripple-shape")
            selector.setSelection(ThemePrefs.RippleShape.IrregularFluid.ordinal)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(visible(prefs.pressKeyHoldTime))
            assertTrue(visible(prefs.pressKeyRetreatTime))
            assertTrue(visible(prefs.pressIgnitionTime))
            assertFalse(visible(prefs.samKeyHoldTime))
            assertFalse(visible(prefs.samKeyRetreatTime))
            assertEquals(ui.context.getString(R.string.light_effect_total, 2750),
                ui.root.findViewWithTag<TextView>("effect-total-time").text.toString())
            val legacy = ui.durationControls.getValue(prefs.pressKeyRetreatTime.key)
            legacy.slider.progress = 21
            legacy.commit()
            assertEquals(230, prefs.pressKeyRetreatTime.getValue())
            assertEquals(1400, prefs.samKeyRetreatTime.getValue())
            selector.setSelection(ThemePrefs.RippleShape.Sam.ordinal)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(visible(prefs.samKeyRetreatTime))
            assertEquals(130, fade.slider.progress)
        } finally {
            ui.dismissDialogs()
            controller.pause().stop().destroy()
        }
    }

    @Test fun openingRandomModeDisplaysItsActualLegacyPaletteWithoutChangingPreferences() {
        val prefs = ThemeManager.prefs
        prefs.pressCustomColorCount.setValue(2)
        prefs.pressCustomColor1.setValue(ThemePrefs.NeonColor.Magenta)
        prefs.pressCustomColor2.setValue(ThemePrefs.NeonColor.Yellow)
        val theme = runCatching { ThemeManager.activeTheme }.getOrDefault(ThemeManager.DefaultTheme)
        val expected = linkedMapOf(
            ThemePrefs.PressEffectPalette.SamsungCool to PressEffect.SAMSUNG_COOL,
            ThemePrefs.PressEffectPalette.Cool to PressEffect.COOL,
            ThemePrefs.PressEffectPalette.Rainbow to PressEffect.RAINBOW,
            ThemePrefs.PressEffectPalette.Berry to PressEffect.BERRY,
            ThemePrefs.PressEffectPalette.Cyberpunk to PressEffect.CYBERPUNK,
            ThemePrefs.PressEffectPalette.Accent to intArrayOf(theme.accentKeyBackgroundColor.takeIf { it ushr 24 != 0 } ?: 0xFF76B5FF.toInt()),
            ThemePrefs.PressEffectPalette.Custom to intArrayOf(ThemePrefs.NeonColor.Magenta.argb, ThemePrefs.NeonColor.Yellow.argb)
        )
        val stored = ReflectionHelpers.getField<SharedPreferences>(prefs, "sharedPreferences")
        expected.forEach { (palette, colors) ->
            prefs.pressEffectPalette.setValue(palette)
            val before = stored.all.toMap()
            val ui = LightEffectSettingsUi(RuntimeEnvironment.getApplication())
            val preview = ui.root.findViewWithTag<LinearLayout>("effect-palette-preview")
            assertEquals(palette.name, colors.size, preview.childCount)
            colors.forEachIndexed { i, color ->
                assertEquals(palette.name, color, (preview.getChildAt(i).background as GradientDrawable).color!!.defaultColor)
            }
            assertEquals(before, stored.all)
            assertEquals(palette, prefs.pressEffectPalette.getValue())
        }
    }

    @Test fun shapeSelectorPersistsIndependentlyOfTheUsersColorMode() {
        val controller = activity()
        val prefs = ThemeManager.prefs
        val beforeShape = prefs.rippleShape.getValue()
        val beforeMode = prefs.pressColorMode.getValue()
        val beforeColors = prefs.pressUserColors.getValue()
        try {
            prefs.rippleShape.setValue(ThemePrefs.RippleShape.SoftMist)
            prefs.pressColorMode.setValue(ThemePrefs.PressColorMode.Custom)
            prefs.pressUserColors.setValue("#18FFC1,#D96EFF")
            val ui = LightEffectSettingsUi(controller.get())
            controller.get().setContentView(ui.root)
            shadowOf(Looper.getMainLooper()).idle()
            val selector = ui.root.findViewWithTag<Spinner>("effect-ripple-shape")
            assertEquals(ThemePrefs.RippleShape.SoftMist.ordinal, selector.selectedItemPosition)
            selector.setSelection(ThemePrefs.RippleShape.IrregularFluid.ordinal)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(ThemePrefs.RippleShape.IrregularFluid, prefs.rippleShape.getValue())
            assertEquals(ThemePrefs.PressColorMode.Custom, prefs.pressColorMode.getValue())
            assertEquals("#18FFC1,#D96EFF", prefs.pressUserColors.getValue())
            val reopened = LightEffectSettingsUi(controller.get())
            assertEquals(ThemePrefs.RippleShape.IrregularFluid.ordinal,
                reopened.root.findViewWithTag<Spinner>("effect-ripple-shape").selectedItemPosition)
        } finally {
            prefs.rippleShape.setValue(beforeShape)
            prefs.pressColorMode.setValue(beforeMode)
            prefs.pressUserColors.setValue(beforeColors)
            controller.pause().stop().destroy()
        }
    }

    @Test fun nativeFragmentOpensWithoutStartingTheInputEngine() {
        val controller = activity()
        val activity = controller.get()
        val fragment = LightEffectSettingsFragment()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        assertEquals("light-effect-settings", fragment.requireView().tag)
        assertNotNull(fragment.requireView().findViewWithTag<View>("effect-mode-Single"))
        controller.pause().stop().destroy()
    }

    @Test fun numberRowSwitchChangesTheRealKeyboardAndItsTouchGeometry() {
        val controller = activity()
        val activity = controller.get()
        val prefs = ThemeManager.prefs
        assertTrue("Keep the existing default of a visible number row", prefs.portraitNumberRow.defaultValue)
        ThemeManager.init(activity.resources.configuration)
        var themeNotifications = 0
        val listener = ThemeManager.OnThemeChangeListener { themeNotifications++ }
        ThemeManager.addOnChangedListener(listener)
        val keyboards = mutableListOf<TextKeyboard>()
        try {
            val ui = LightEffectSettingsUi(activity)
            activity.setContentView(ui.root)
            val toggle = ui.root.findViewWithTag<Switch>(prefs.portraitNumberRow.key)
            assertTrue(toggle.isChecked)
            val withNumbers = TextKeyboard(activity, ThemePreset.XuancaiBlackV09).also {
                keyboards.add(it); layout(it, 600, 450)
            }
            assertEquals(5, withNumbers.childCount)
            val digits = withNumbers.getChildAt(0) as ViewGroup
            assertEquals("1234567890", (0 until digits.childCount).joinToString("") {
                (digits.getChildAt(it) as TextKeyView).mainText.text.toString()
            })
            val previousLetterHeight = withNumbers.getChildAt(1).height
            save(withNumbers, "keyboard-number-row-on")

            toggle.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(prefs.portraitNumberRow.getValue())
            assertTrue("The service's theme listener must be notified to rebuild the keyboard", themeNotifications > 0)
            val withoutNumbers = TextKeyboard(activity, ThemePreset.XuancaiBlackV09).also {
                keyboards.add(it); layout(it, 600, KeyboardSizePolicy.heightForLayout(450, true, true, false))
            }
            assertEquals(4, withoutNumbers.childCount)
            val letters = withoutNumbers.getChildAt(0) as ViewGroup
            assertEquals(0, letters.top)
            assertEquals("Removing the number row must preserve actual letter key height", previousLetterHeight.toDouble(), letters.height.toDouble(), 1.0)
            assertEquals("Q", (letters.getChildAt(0) as TextKeyView).mainText.text.toString())
            for (row in 0 until withoutNumbers.childCount - 1) {
                assertEquals("No blank strip between remaining rows", withoutNumbers.getChildAt(row).bottom,
                    withoutNumbers.getChildAt(row + 1).top)
            }
            assertEquals(withoutNumbers.height, withoutNumbers.getChildAt(3).bottom)
            save(withoutNumbers, "keyboard-number-row-off")
            // A returning keyboard lives below the activity's real ViewTreeLifecycleOwner.
            activity.setContentView(withoutNumbers)
            controller.visible()
            shadowOf(Looper.getMainLooper()).idle()
            layout(withoutNumbers, 600, KeyboardSizePolicy.heightForLayout(450, true, true, false))
            var typed: KeyAction? = null
            withoutNumbers.keyActionListener = KeyActionListener { action, _ -> typed = action }
            val q = letters.getChildAt(0)
            val now = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(now, now, action, q.left + q.width / 2f,
                    letters.top + letters.height / 2f, 0)
                try { assertTrue(withoutNumbers.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
            assertTrue(typed is KeyAction.FcitxKeyAction)
            assertEquals("q", (typed as KeyAction.FcitxKeyAction).act)

            activity.setContentView(ui.root)
            toggle.performClick()
            assertTrue(prefs.portraitNumberRow.getValue())
            val restored = TextKeyboard(activity, ThemePreset.XuancaiBlackV09).also {
                keyboards.add(it); layout(it, 600, 450)
            }
            assertEquals(5, restored.childCount)
        } finally {
            keyboards.forEach { it.onDetach() }
            ThemeManager.removeOnChangedListener(listener)
            prefs.portraitNumberRow.setValue(true)
            controller.pause().stop().destroy()
        }
    }

    @Test fun renderTheActualSettingsScreenAndNativeColourEditor() {
        val controller = activity()
        val activity = controller.get()
        val prefs = ThemeManager.prefs
        prefs.rippleShape.setValue(ThemePrefs.RippleShape.SoftMist)
        prefs.pressColorMode.setValue(ThemePrefs.PressColorMode.Custom)
        prefs.pressUserColors.setValue(PressColorPalette.encode(intArrayOf(0xFF18FFC1.toInt(), 0xFFD96EFF.toInt(),
            0xFFFF8A32.toInt(), 0xFF00DAFF.toInt(), 0xFFFF3FA5.toInt(), 0xFFB1FF42.toInt())))
        val ui = LightEffectSettingsUi(activity)
        activity.setContentView(ui.root)
        shadowOf(Looper.getMainLooper()).idle()
        layout(ui.root)
        save(ui.root, "light-settings-colours")
        val advanced = ui.root.findViewWithTag<View>("effect-advanced-content")
        assertEquals("Detailed timing stays collapsed until requested", View.GONE, advanced.visibility)
        ui.root.findViewWithTag<View>("effect-advanced-toggle").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        layout(ui.root)
        val body = ui.root.getChildAt(0) as ViewGroup
        val firstTiming = ui.durationControls.getValue(prefs.pressFadeOutTime.key).root
        val timingBounds = Rect(0, 0, firstTiming.width, firstTiming.height)
        body.offsetDescendantRectToMyCoords(firstTiming, timingBounds)
        val timingScroll = (timingBounds.top - 12).coerceAtLeast(0)
        assertTrue("Animation controls must extend beyond the palette viewport", timingScroll > 0)
        ui.root.scrollTo(0, timingScroll)
        ui.root.requestLayout()
        shadowOf(Looper.getMainLooper()).idle()
        layout(ui.root)
        ui.root.invalidate()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Scroll must finish before capturing the timing screen", ui.root.scrollY > 0)
        listOf(prefs.pressFadeOutTime, prefs.pressKeyHoldTime, prefs.pressKeyRetreatTime).forEach { pref ->
            val control = ui.durationControls.getValue(pref.key).root
            val visible = Rect()
            assertTrue("${pref.key} must be visible in the timing screenshot", control.getGlobalVisibleRect(visible))
            assertTrue("${pref.key} must show its label and slider", visible.height() >= control.height)
        }
        val timing = captureScrollViewport(ui.root)
        listOf(prefs.pressFadeOutTime, prefs.pressKeyHoldTime, prefs.pressKeyRetreatTime).forEach { pref ->
            assertSliderPixelsAreVisible(timing, ui.root, ui.durationControls.getValue(pref.key).slider)
        }
        save(timing, "light-settings-timing")
        ui.root.findViewWithTag<Button>("effect-custom-color-0").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val decor = ui.activeColorDialog!!.window!!.decorView
        layout(decor, 540, 860)
        save(decor, "light-settings-colour-editor")
        ui.dismissDialogs()
        controller.pause().stop().destroy()
    }
}
