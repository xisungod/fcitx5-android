/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.inputmethodservice.InputMethodService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.appcompat.widget.SwitchCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.settings.KeyboardQuickSettingsDraft
import org.fcitx.fcitx5.android.input.settings.KeyboardQuickSettingsUi
import org.fcitx.fcitx5.android.input.settings.KeyboardQuickSettingsWindow
import org.fcitx.fcitx5.android.input.status.StatusAreaAdapter
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry
import org.fcitx.fcitx5.android.input.status.StatusAreaWindow
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
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
import java.time.Duration

/** Service-context regression: an Activity supplies only a window token, never widget context/theme. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-hdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardQuickSettingsImeContextTest {
    class RecordingImeService : InputMethodService() {
        var hideRequests = 0
            private set
        override fun requestHideSelf(flags: Int) {
            hideRequests++
            super.requestHideSelf(flags)
        }
    }

    private var previousApplication: FcitxApplication? = null
    private val restorePreferences = mutableListOf<() -> Unit>()

    @Before fun prepare() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val application = RuntimeEnvironment.getApplication()
        val field = FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }
        previousApplication = field.get(null) as? FcitxApplication
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        field.set(null, app)
        AppPrefs.init(application.getSharedPreferences("ime-quick-settings-context", Context.MODE_PRIVATE))
        setting(AppPrefs.getInstance().advanced.disableAnimation, false)
        setting(ThemeManager.prefs.effectsFollowSystemAnimation, false)
        setting(ThemeManager.prefs.rippleShape, ThemePrefs.RippleShape.Sam)
        setting(ThemeManager.prefs.keyMotionEffect, ThemePrefs.KeyMotionEffect.Press)
    }

    @After fun restore() {
        restorePreferences.asReversed().forEach { it() }
        FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, previousApplication)
        ShadowChoreographer.setPaused(false)
    }

    private fun <T : Any> setting(preference: ManagedPreference<T>, value: T) {
        val existed = preference.sharedPreferences.contains(preference.key)
        val old = preference.getValue()
        restorePreferences.add {
            if (existed) preference.setValue(old)
            else preference.sharedPreferences.edit().remove(preference.key).commit()
            Unit
        }
        preference.setValue(value)
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun contextChain(context: Context): List<Context> {
        val chain = mutableListOf<Context>()
        var next: Context? = context
        while (next != null && chain.none { it === next }) {
            val current = next ?: break
            chain.add(current)
            next = if (current is ContextWrapper) current.baseContext else null
        }
        return chain
    }

    private fun assertServiceWidgets(view: View, service: RecordingImeService) {
        val widgets = descendants(view).filter { it is TextView || it is Spinner || it is SeekBar }
        assertTrue("The regression must exercise real settings widgets", widgets.isNotEmpty())
        widgets.forEach { widget ->
            val chain = contextChain(widget.context)
            assertTrue("${widget.javaClass.simpleName} must retain the IME Service context", chain.any { it === service })
            assertFalse("An Activity context must not supply styles missing from the IME", chain.any { it is Activity })
        }
    }

    /** Draw the measured widget itself, including switches below the scroll viewport. */
    private fun drawSwitch(toggle: SwitchCompat): Bitmap {
        val thumb = requireNotNull(toggle.thumbDrawable) { "A service-themed switch must have its actual thumb" }
        val track = requireNotNull(toggle.trackDrawable) { "A service-themed switch must have its actual track" }
        assertTrue(toggle.width > 0 && toggle.height > 0)
        fun draw() = Bitmap.createBitmap(toggle.width, toggle.height, Bitmap.Config.ARGB_8888).also {
            toggle.draw(Canvas(it))
        }
        draw().recycle()
        return draw().also { image ->
            for ((name, drawable) in listOf("thumb" to thumb, "track" to track)) {
                val bounds = Rect(drawable.bounds)
                assertTrue("The real $name receives nonempty bounds during draw", bounds.intersect(0, 0, image.width, image.height))
                var painted = 0
                for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right)
                    if (Color.alpha(image.getPixel(x, y)) > 0) painted++
                assertTrue("The real $name has visible drawn pixels", painted > 0)
            }
        }
    }

    private fun assertSwitchVisualStateChanges(before: Bitmap, after: Bitmap) {
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        var changed = 0
        for (y in 0 until before.height) for (x in 0 until before.width)
            if (before.getPixel(x, y) != after.getPixel(x, y)) changed++
        assertTrue("Changing checked state must visibly update the real switch, not just its Boolean", changed >= 4)
    }

    private fun advance(milliseconds: Long) {
        var left = milliseconds
        while (left > 0L) {
            val step = minOf(left, 16L)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            left -= step
        }
    }

    private inner class Host : AutoCloseable {
        private val serviceController = Robolectric.buildService(RecordingImeService::class.java).create()
        val service = serviceController.get()
        // Identical wrapper resource to BaseInputView.themedContext; no Activity in this chain.
        val imeContext = ContextThemeWrapper(service, R.style.Theme_InputViewTheme)
        private val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = activityController.get()
        val shell = LinearLayout(imeContext).apply { orientation = LinearLayout.VERTICAL }
        private val frame = FrameLayout(activity).apply {
            addView(shell, FrameLayout.LayoutParams(imeContext.dp(360), imeContext.dp(318)))
        }

        init {
            assertTrue(contextChain(imeContext).any { it === service })
            assertFalse(contextChain(imeContext).any { it is Activity })
            activity.setContentView(frame)
            activityController.visible()
            settle()
        }

        fun settle() {
            // Exercise real pending layout/transition/selection work with animations enabled.
            advance(160)
            shell.measure(View.MeasureSpec.makeMeasureSpec(imeContext.dp(360), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(imeContext.dp(318), View.MeasureSpec.EXACTLY))
            shell.layout(0, 0, shell.measuredWidth, shell.measuredHeight)
            shell.viewTreeObserver.dispatchOnPreDraw()
            advance(160)
        }

        fun assertVisible() {
            assertTrue("The input shell must remain attached and visible after the menu action", shell.isAttachedToWindow && shell.isShown)
            assertEquals(View.VISIBLE, shell.visibility)
            assertEquals("Keyboard settings must never request the IME to hide", 0, service.hideRequests)
            assertNull("In-keyboard controls must not launch a controller Activity", shadowOf(service).nextStartedActivity)
            assertNull(shadowOf(activity).nextStartedActivity)
        }

        override fun close() {
            activityController.pause().stop().destroy()
            serviceController.destroy()
        }
    }

    private class KeyboardPlaceholder : InputWindow.SimpleInputWindow<KeyboardPlaceholder>(), EssentialWindow {
        override val key: EssentialWindow.Key get() = KeyboardWindow
        override fun onCreateView(): View = View(context)
        override fun onAttached() {}
        override fun onDetached() {}
    }

    @Test fun settingsWidgetsConstructAttachAndInteractUsingOnlyTheImeServiceTheme() {
        val states = listOf(ThemePrefs.KeyMotionEffect.Press to false,
            ThemePrefs.KeyMotionEffect.Bounce to false, ThemePrefs.KeyMotionEffect.Press to true)
        for ((motion, disabled) in states) {
            setting(AppPrefs.getInstance().advanced.disableAnimation, disabled)
            Host().use { host ->
                val storage = host.service.getSharedPreferences("ime-settings-direct-draft", Context.MODE_PRIVATE)
                storage.edit().clear().commit()
                val draft = KeyboardQuickSettingsDraft(ThemePrefs(storage), AppPrefs(storage).keyboard).apply {
                    values = values.copy(keyMotion = motion)
                }
                var navigation = 0
                val ui = KeyboardQuickSettingsUi(host.imeContext, ThemePreset.Sam, draft, disabled,
                    onDone = { draft.apply(); navigation++ }, onCancel = { navigation++ },
                    onHeight = { navigation++ }, onMore = { navigation++ })
                try {
                    host.shell.addView(ui.extension, LinearLayout.LayoutParams(-1, host.imeContext.dp(48)))
                    host.shell.addView(ui.root, LinearLayout.LayoutParams(-1, host.imeContext.dp(270)))
                    host.settle()
                    assertServiceWidgets(ui.root, host.service)
                    assertServiceWidgets(ui.extension, host.service)
                    assertTrue(ui.root.isAttachedToWindow && ui.root.isShown)
                    val preview = ui.root.findViewWithTag<View>("quick_motion_preview")
                    val previewEnabled = motion == ThemePrefs.KeyMotionEffect.Press && !disabled
                    assertEquals("Preview enablement respects $motion and disableAnimation=$disabled", previewEnabled, preview.isEnabled)
                    if (previewEnabled) {
                        assertTrue("The actual animated motion preview remains interactive", preview.performClick())
                        host.settle()
                    } else assertFalse("A disabled preview cannot start an animation", preview.performClick())
                    val layout = ui.root.findViewWithTag<Spinner>("quick_key_width_layout")
                    assertNotNull(layout)
                    layout.setSelection(1)
                    host.settle()
                    layout.setSelection(0)
                    host.settle()
                    assertNotNull(ui.root.findViewWithTag<View>("quick_key_width_preview"))
                    val toggles = descendants(ui.root).filterIsInstance<SwitchCompat>()
                    assertTrue("The actual quick panel must contain its compatibility switches", toggles.size >= 3)
                    toggles.forEach { drawSwitch(it).recycle() }
                    val numberRow = ui.root.findViewWithTag<SwitchCompat>("quick_number_row")
                    val originalRow = draft.values.numberRow
                    val beforeStored = storage.all.toMap()
                    val before = drawSwitch(numberRow)
                    // CompoundButton toggles before delegating its return value to
                    // View.performClick; no separate OnClickListener is required.
                    numberRow.performClick()
                    assertEquals("The actual switch toggles on click", !originalRow, numberRow.isChecked)
                    host.settle()
                    val after = drawSwitch(numberRow)
                    try { assertSwitchVisualStateChanges(before, after) } finally { before.recycle(); after.recycle() }
                    assertEquals(!originalRow, draft.values.numberRow)
                    assertEquals("Toggling a switch edits only the draft", beforeStored, storage.all)
                    assertEquals("Editing common controls must stay inside the input view", 0, navigation)
                    assertTrue(ui.extension.findViewWithTag<View>("quick_done").performClick())
                    assertEquals(1, navigation)
                    assertEquals("Done persists the staged switch value", !originalRow, ThemePrefs(storage).portraitNumberRow.getValue())
                    host.assertVisible()
                } finally { ui.dispose() }
            }
        }
    }

    @Test fun animatedToolsMenuOpensRealSettingsAndBackOrDoneKeepsTheInputShellVisible() {
        Host().use { host ->
            val scope = DynamicScope()
            val context: ContextThemeWrapper = host.imeContext
            val theme: Theme = ThemePreset.Sam
            val broadcaster = InputBroadcaster()
            val windows = InputWindowManager()
            val bar = KawaiiBarComponent()
            val connection: FcitxConnection = object : FcitxConnection {
                override val lifecycleScope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Main)
                override fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T = error("Native engine is outside this service-context test")
                override suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T = error("Native engine is outside this service-context test")
                override fun runIfReady(block: suspend FcitxAPI.() -> Unit) {}
            }
            scope += context.wrapToUniqueComponent()
            scope += theme.wrapToUniqueComponent()
            scope += connection.wrapToUniqueComponent()
            scope += broadcaster
            scope += windows
            scope += CommonKeyActionListener()
            scope += PopupComponent()
            scope += HorizontalCandidateComponent()
            scope += bar
            windows.onScopeSetupFinished(scope)
            val keyboard = KeyboardPlaceholder()
            windows.addEssentialWindow(keyboard)
            windows.attachWindow(keyboard)
            host.shell.addView(bar.view, LinearLayout.LayoutParams(-1, host.imeContext.dp(48)))
            host.shell.addView(windows.view, LinearLayout.LayoutParams(-1, host.imeContext.dp(270)))
            bar.onStartInput(EditorInfo(), CapabilityFlags(0uL))
            host.settle()
            assertFalse("The failing route must run with normal transition animations", AppPrefs.getInstance().advanced.disableAnimation.getValue())
            val rowPreference = ThemeManager.prefs.portraitNumberRow
            val originalRow = rowPreference.getValue()
            setting(rowPreference, originalRow)
            repeat(2) { visit ->
                val tools = descendants(bar.view).first { it.contentDescription == context.getString(R.string.keyboard_tools) }
                assertTrue(tools.performClick())
                broadcaster.onStatusAreaUpdate(emptyArray())
                host.settle()
                val menu = windows.currentWindow as StatusAreaWindow
                val adapter = menu.view.adapter as StatusAreaAdapter
                val position = adapter.entries.indexOfFirst {
                    it is StatusAreaEntry.Android && it.type == StatusAreaEntry.Android.Type.KeyboardSettings
                }
                assertTrue("The actual tools grid contains Keyboard settings", position >= 0)
                val holder = menu.view.findViewHolderForAdapterPosition(position)
                assertNotNull("The visible tools entry must be clickable", holder)
                assertTrue(holder!!.itemView.performClick())
                host.settle()
                assertTrue("The selected settings Window must remain current after pending transitions", windows.currentWindow is KeyboardQuickSettingsWindow)
                val panel = windows.view.findViewWithTag<View>("quick_motion_preview")
                assertNotNull(panel)
                assertTrue(panel.isAttachedToWindow && panel.isShown)
                assertTrue(windows.view.isAttachedToWindow && windows.view.isShown)
                assertServiceWidgets(windows.view, host.service)
                host.assertVisible()
                val toggle = windows.view.findViewWithTag<SwitchCompat>("quick_number_row")
                assertEquals("A canceled draft cannot leak into the next opening", originalRow, toggle.isChecked)
                drawSwitch(toggle).recycle()
                toggle.performClick()
                assertEquals("The actual menu switch toggles on click", !originalRow, toggle.isChecked)
                assertEquals("Menu switch edits remain staged until Done", originalRow, rowPreference.getValue())
                if (visit == 0) {
                    val back = descendants(bar.view).first { it.contentDescription == context.getString(R.string.back_to_keyboard) }
                    assertTrue(back.performClick())
                } else assertTrue(bar.view.findViewWithTag<View>("quick_done").performClick())
                host.settle()
                assertTrue("Back/Done changes the in-IME panel instead of closing the input view", windows.isAttached(keyboard))
                assertEquals("Back discards, while Done applies the actual switch choice",
                    if (visit == 0) originalRow else !originalRow, rowPreference.getValue())
                host.assertVisible()
            }
        }
    }
}
