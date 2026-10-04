/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import org.fcitx.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.bar.ui.idle.ClipboardSuggestionUi
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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ClipboardSuggestionUiTest {
    private var priorApplication: FcitxApplication? = null
    private val restorePreferences = mutableListOf<() -> Unit>()

    @Before
    fun prepareWithoutStartingTheNativeEngine() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val instance = FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }
        priorApplication = instance.get(null) as? FcitxApplication
        val application = RuntimeEnvironment.getApplication()
        val uiApplication = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(uiApplication, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        instance.set(null, uiApplication)
        AppPrefs.init(application.getSharedPreferences("clipboard-suggestion-ui", Context.MODE_PRIVATE))
        val keyboard = AppPrefs.getInstance().keyboard
        setting(keyboard.longPressDelay, 300)
        setting(keyboard.hapticStrength, 0)
        setting(keyboard.soundOnKeyPress, InputFeedbackMode.Disabled)
    }

    @After
    fun restoreApplicationPreferencesAndVsync() {
        restorePreferences.asReversed().forEach { it() }
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, priorApplication)
        }
        ShadowChoreographer.setPaused(false)
    }

    private fun <T : Any> setting(preference: ManagedPreference<T>, value: T) {
        val existed = preference.sharedPreferences.contains(preference.key)
        val oldValue = preference.getValue()
        restorePreferences += {
            if (existed) preference.setValue(oldValue)
            else preference.sharedPreferences.edit().remove(preference.key).commit()
            Unit
        }
        preference.setValue(value)
    }

    private class Harness(private val width: Int = 256) {
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        private val activity = controller.get().apply { setTheme(R.style.Theme_InputViewTheme) }
        val ui = ClipboardSuggestionUi(activity, ThemePreset.XuancaiBlackV09)
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        private val originalClip = clipboard.primaryClip
        private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { clipboardChanges++ }
        var closeCount = 0
        var pasteCount = 0
        var longClickCount = 0
        var clipboardChanges = 0
        private var downTime = 0L

        init {
            ui.text.text = "一段足够长的剪贴板内容，用于验证关闭按钮与粘贴区域没有重叠"
            ui.suggestionView.setOnClickListener { pasteCount++ }
            ui.suggestionView.setOnLongClickListener { longClickCount++; true }
            ui.dismissButton.setOnClickListener { closeCount++ }
            activity.setContentView(ui.root)
            controller.visible()
            advance(32)
            // A 360 dp keyboard leaves 256 dp between its two 52 dp outer toolbar buttons.
            ui.root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(52, View.MeasureSpec.EXACTLY)
            )
            ui.root.layout(0, 0, width, 52)
            clipboard.setPrimaryClip(ClipData.newPlainText("keep-this-clip", "保留系统剪贴板 123456"))
            advance(16)
            clipboard.addPrimaryClipChangedListener(clipboardListener)
        }

        fun bounds(view: View) = Rect().also {
            view.getDrawingRect(it)
            ui.root.offsetDescendantRectToMyCoords(view, it)
        }

        fun advance(milliseconds: Long) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
        }

        fun touch(action: Int, x: Float, y: Float) {
            if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                assertTrue("The real toolbar must route the complete gesture", ui.root.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }

        fun tap(x: Float, y: Float) {
            touch(MotionEvent.ACTION_DOWN, x, y)
            advance(40)
            touch(MotionEvent.ACTION_UP, x, y)
            advance(16)
        }

        fun assertClipboardPreserved() {
            assertEquals("Closing a suggestion cannot clear or replace the clipboard", 0, clipboardChanges)
            val clip = clipboard.primaryClip
            assertNotNull(clip)
            assertEquals("keep-this-clip", clip!!.description.label.toString())
            assertEquals(1, clip.itemCount)
            assertEquals("保留系统剪贴板 123456", clip.getItemAt(0).text.toString())
        }

        fun finish() {
            ui.suggestionView.cancelGestures()
            clipboard.removePrimaryClipChangedListener(clipboardListener)
            if (originalClip == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(originalClip)
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun tappingTheCloseTargetIncludingItsEdgeOnlyDismissesAndPreservesTheSystemClipboard() {
        val h = Harness()
        try {
            val close = h.bounds(h.ui.dismissButton)
            val chip = h.bounds(h.ui.suggestionView)
            assertTrue(h.ui.root.isAttachedToWindow)
            assertTrue("The close action needs a full 44 dp touch target", close.width() >= 44 && close.height() >= 44)
            assertTrue("The chip must remain usable alongside Close", chip.width() > 0 && chip.height() > 0)
            assertFalse("Closing and pasting must have separate hit areas", Rect.intersects(close, chip))
            assertTrue(close.left >= 0 && close.right <= h.ui.root.width && close.top >= 0 && close.bottom <= h.ui.root.height)
            assertEquals(h.ui.root.context.getString(R.string.dismiss_clipboard_suggestion),
                h.ui.dismissButton.contentDescription.toString())

            h.tap(close.exactCenterX(), close.exactCenterY())
            h.tap(close.left + 1f, close.exactCenterY())
            assertEquals(2, h.closeCount)
            assertEquals(0, h.pasteCount)
            assertEquals(0, h.longClickCount)
            h.assertClipboardPreserved()
        } finally { h.finish() }
    }

    @Test
    fun theOriginalSuggestionStillSupportsTapAndConsumedLongPressWithoutClosing() {
        val h = Harness()
        try {
            val chip = h.bounds(h.ui.suggestionView)
            h.tap(chip.exactCenterX(), chip.exactCenterY())
            assertEquals(1, h.pasteCount)
            assertEquals(0, h.longClickCount)
            assertEquals(0, h.closeCount)

            h.touch(MotionEvent.ACTION_DOWN, chip.exactCenterX(), chip.exactCenterY())
            h.advance(320)
            assertEquals("Hold must invoke the attached view's actual long-press coroutine", 1, h.longClickCount)
            h.touch(MotionEvent.ACTION_UP, chip.exactCenterX(), chip.exactCenterY())
            h.advance(16)
            assertEquals("A consumed long press must not also paste", 1, h.pasteCount)
            assertEquals(0, h.closeCount)
            h.assertClipboardPreserved()
        } finally { h.finish() }
    }

    @Test
    fun cancelingACloseGestureCannotDismissOrFallThroughToTheSuggestion() {
        val h = Harness()
        try {
            val close = h.bounds(h.ui.dismissButton)
            val chip = h.bounds(h.ui.suggestionView)
            h.touch(MotionEvent.ACTION_DOWN, close.exactCenterX(), close.exactCenterY())
            h.touch(MotionEvent.ACTION_MOVE, chip.exactCenterX(), chip.exactCenterY())
            h.touch(MotionEvent.ACTION_CANCEL, chip.exactCenterX(), chip.exactCenterY())
            h.advance(350)
            assertEquals(0, h.closeCount)
            assertEquals(0, h.pasteCount)
            assertEquals(0, h.longClickCount)
            h.assertClipboardPreserved()
        } finally { h.finish() }
    }

    @Test
    fun aNarrowToolbarKeepsTheWholeCloseTargetInsideItsBoundsAndTappable() {
        val h = Harness(width = 128)
        try {
            val close = h.bounds(h.ui.dismissButton)
            val chip = h.bounds(h.ui.suggestionView)
            assertTrue("A narrow toolbar must keep the full Close touch target", close.width() >= 44 && close.height() >= 44)
            assertTrue("Close must not extend beyond the narrow toolbar",
                close.left >= 0 && close.right <= 128 && close.top >= 0 && close.bottom <= 52)
            assertTrue("The suggestion must retain a separate visible target", chip.width() > 0 && chip.height() > 0)
            assertFalse(Rect.intersects(close, chip))
            h.tap(close.right - 1f, close.exactCenterY())
            assertEquals(1, h.closeCount)
            assertEquals(0, h.pasteCount)
            assertEquals(0, h.longClickCount)
            h.assertClipboardPreserved()
        } finally { h.finish() }
    }
}
