/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
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
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.KeyMotionSettings
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.KeyPressDepth
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import splitties.dimensions.dp
import java.time.Duration

/** Exercises the panel's real controls and KeyView without starting the native input engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class KeyboardMotionControlsTest {
    private var previousApplication: FcitxApplication? = null
    private val restorePreferences = mutableListOf<() -> Unit>()
    private var panelNumber = 0
    private val sliderTags = listOf("quick_press_amplitude", "quick_press_duration",
        "quick_rebound_amplitude", "quick_rebound_duration")

    @Before fun prepare() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val field = FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }
        previousApplication = field.get(null) as? FcitxApplication
        val application = RuntimeEnvironment.getApplication()
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        field.set(null, app)
        AppPrefs.init(application.getSharedPreferences("motion-controls-host", Context.MODE_PRIVATE))
        setting(ThemeManager.prefs.effectsFollowSystemAnimation, false)
        setting(ThemeManager.prefs.keyBorder, true)
        setting(ThemeManager.prefs.keyRippleEffect, false)
        setting(AppPrefs.getInstance().advanced.disableAnimation, false)
    }

    @After fun restore() {
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

    private fun advance(milliseconds: Long) {
        var remaining = milliseconds
        while (remaining > 0L) {
            val step = minOf(remaining, 16L)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            remaining -= step
        }
    }

    private fun touch(view: View, action: Int, downTime: Long, x: Float = view.width / 2f): Boolean {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
            x, view.height / 2f, 0)
        return try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private inner class Panel(
        disabled: Boolean = false,
        motion: KeyMotionSettings = KeyMotionSettings()
    ) : AutoCloseable {
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get().apply { setTheme(R.style.Theme_InputViewTheme) }
        val storage = activity.getSharedPreferences("motion-controls-${panelNumber++}", Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() }
        val draft = KeyboardQuickSettingsDraft(ThemePrefs(storage), AppPrefs(storage).keyboard).apply {
            values = values.copy(keyMotion = ThemePrefs.KeyMotionEffect.Press, motionSettings = motion)
        }
        var navigationCallbacks = 0
        val ui = KeyboardQuickSettingsUi(activity, ThemePreset.XuancaiBlackV09, draft, disabled,
            onDone = { navigationCallbacks++ }, onCancel = { navigationCallbacks++ },
            onHeight = { navigationCallbacks++ }, onMore = { navigationCallbacks++ })
        val container = FrameLayout(activity).apply {
            addView(ui.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val preview: TextKeyView get() = ui.root.findViewWithTag("quick_motion_preview")
        val depth: KeyPressDepth get() = ReflectionHelpers.getField(preview, "pressDepth")
        val sliders: List<SeekBar> get() = sliderTags.map { ui.root.findViewWithTag(it) }

        init {
            activity.setContentView(container)
            controller.visible()
            advance(32)
            val width = activity.dp(360)
            val height = activity.dp(270)
            container.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            container.layout(0, 0, width, height)
            assertTrue(preview.isAttachedToWindow)
            assertTrue(preview.width > 0 && preview.height > 0)
        }

        fun chooseMode(mode: ThemePrefs.KeyMotionEffect) {
            ui.root.findViewWithTag<Spinner>("quick_key_motion").setSelection(mode.ordinal)
            advance(32)
        }

        fun assertAtRest() {
            assertEquals(0f, depth.currentLift(), 0f)
            assertEquals(1f, depth.currentScale(), 0f)
            assertFalse(depth.isTransitioning())
            assertFalse("A stopped preview must not keep requesting display frames",
                ReflectionHelpers.getField<Boolean>(preview, "depthFrameScheduled"))
        }

        override fun close() {
            ui.dispose()
            controller.pause().stop().destroy()
        }
    }

    @Test fun fourRealSlidersStageTheirValuesAndRespectMotionAndGlobalAnimationSwitches() {
        Panel().use { panel ->
            val bounds = panel.sliders.map { slider ->
                Rect().also { slider.getDrawingRect(it); panel.ui.root.offsetDescendantRectToMyCoords(slider, it) }
            }
            assertEquals("The four controls use two columns", 2, bounds.map { it.left }.distinct().size)
            assertEquals("The four controls use two rows", 2, bounds.map { it.top }.distinct().size)
            panel.sliders.forEach { slider ->
                assertTrue(slider.isEnabled && slider.width > 0 && slider.height > 0)
                val downTime = SystemClock.uptimeMillis()
                assertTrue(touch(slider, MotionEvent.ACTION_DOWN, downTime, slider.paddingLeft.toFloat()))
                assertTrue(touch(slider, MotionEvent.ACTION_MOVE, downTime, slider.width - 1f))
                assertTrue(touch(slider, MotionEvent.ACTION_UP, downTime, slider.width - 1f))
            }
            assertEquals(KeyMotionSettings(16, 300, 6, 1800), panel.draft.values.motionSettings)
            assertTrue("Dragging every slider remains an unsaved draft", panel.storage.all.isEmpty())
            panel.draft.values = panel.draft.values.copy(glowBrightness = 35, popup = false)
            val beforeReset = panel.draft.values
            assertTrue(panel.ui.root.findViewWithTag<View>("quick_motion_reset").performClick())
            assertEquals(beforeReset.copy(motionSettings = KeyMotionSettings()), panel.draft.values)
            assertTrue(panel.storage.all.isEmpty())

            val downTime = SystemClock.uptimeMillis()
            assertTrue(touch(panel.preview, MotionEvent.ACTION_DOWN, downTime))
            advance(32)
            assertTrue(panel.depth.currentLift() < 0f)
            panel.chooseMode(ThemePrefs.KeyMotionEffect.Bounce)
            assertTrue(panel.sliders.none { it.isEnabled })
            assertFalse(panel.preview.isEnabled)
            panel.assertAtRest()
            panel.chooseMode(ThemePrefs.KeyMotionEffect.Press)
            assertTrue(panel.sliders.all { it.isEnabled })
            assertTrue(panel.preview.isEnabled)
            assertEquals(0, panel.navigationCallbacks)
            assertTrue(panel.storage.all.isEmpty())
        }
        setting(AppPrefs.getInstance().advanced.disableAnimation, true)
        Panel(disabled = true).use { panel ->
            assertTrue(panel.sliders.none { it.isEnabled })
            assertFalse(panel.preview.isEnabled)
            panel.assertAtRest()
            assertTrue(panel.storage.all.isEmpty())
        }
    }

    @Test fun theRealPreviewCompressesAndReboundsUsingStagedSettingsWithoutSavingOrNavigating() {
        Panel(motion = KeyMotionSettings(16, 60, 6, 400)).use { panel ->
            val downTime = SystemClock.uptimeMillis()
            assertTrue(touch(panel.preview, MotionEvent.ACTION_DOWN, downTime))
            advance(240)
            assertTrue("A held preview must actually compress", panel.depth.currentLift() < 0f)
            assertTrue("The preview must use the staged 16% amplitude, not the saved 8% default",
                panel.depth.currentScale() < 0.90f)
            assertTrue(touch(panel.preview, MotionEvent.ACTION_UP, downTime))
            var largestScale = 1f
            var positiveLift = false
            repeat(80) {
                advance(16)
                largestScale = maxOf(largestScale, panel.depth.currentScale())
                positiveLift = positiveLift || panel.depth.currentLift() > 0f
            }
            assertTrue("Releasing the real key must rise above rest", positiveLift)
            assertTrue("The staged 6% rebound must replace the saved 3% default", largestScale > 1.035f)
            advance(1000)
            panel.assertAtRest()
            assertEquals(0, panel.navigationCallbacks)
            assertTrue("Touching the preview cannot commit preferences", panel.storage.all.isEmpty())
        }
    }

    @Test fun disposingHidingAndDetachingCancelThePreviewAndItsDelayedAccessibilityRelease() {
        for (stop in listOf("dispose", "invisible", "gone", "detach")) {
            Panel().use { panel ->
                assertTrue("Accessibility activation must run the actual preview", panel.preview.performClick())
                advance(16)
                assertTrue("The preview must have started before $stop", panel.depth.currentLift() < 0f)
                val queuedFrame = ReflectionHelpers.getField<Runnable>(panel.preview, "depthFrame")
                when (stop) {
                    "dispose" -> panel.ui.dispose()
                    "invisible" -> panel.ui.root.visibility = View.INVISIBLE
                    "gone" -> panel.ui.root.visibility = View.GONE
                    "detach" -> panel.container.removeView(panel.ui.root)
                }
                panel.assertAtRest()
                // A callback already dequeued before dismissal must not resurrect motion either.
                queuedFrame.run()
                advance(2500)
                panel.assertAtRest()
                if (stop == "dispose") {
                    panel.ui.extension.findViewWithTag<View>("quick_done").performClick()
                    panel.ui.root.findViewWithTag<View>("quick_cancel").performClick()
                }
                assertEquals(0, panel.navigationCallbacks)
                assertTrue("Dismissal cannot save a preview-only change", panel.storage.all.isEmpty())
            }
        }
    }
}
