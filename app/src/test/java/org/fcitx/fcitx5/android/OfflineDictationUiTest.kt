/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.animation.ValueAnimator
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.voice.OfflineDictationPhase
import org.fcitx.fcitx5.android.input.voice.OfflineDictationSession
import org.fcitx.fcitx5.android.input.voice.OfflineDictationState
import org.fcitx.fcitx5.android.input.voice.OfflineDictationUi
import org.fcitx.fcitx5.android.input.voice.OfflineDictationWindow
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
import splitties.dimensions.dp
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class OfflineDictationUiTest {
    private var previousAnimationPreference = false

    @Before fun enableAnimations() {
        AppPrefs.init(RuntimeEnvironment.getApplication().getSharedPreferences("dictation-ui", Context.MODE_PRIVATE))
        previousAnimationPreference = AppPrefs.getInstance().advanced.disableAnimation.getValue()
        AppPrefs.getInstance().advanced.disableAnimation.setValue(false)
        systemAnimationScale(1f)
    }

    @After fun restoreAnimations() {
        AppPrefs.getInstance().advanced.disableAnimation.setValue(previousAnimationPreference)
        systemAnimationScale(1f)
    }

    private fun systemAnimationScale(scale: Float) {
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", java.lang.Float.TYPE).invoke(null, scale)
    }

    private class Session(initial: OfflineDictationState = OfflineDictationState()) : OfflineDictationSession {
        override val state = MutableStateFlow(initial)
        var starts = 0
        var stops = 0
        var cancels = 0
        var closes = 0
        override fun start() { starts++; state.value = OfflineDictationState(OfflineDictationPhase.Preparing) }
        override fun stop() { stops++; state.value = state.value.copy(phase = OfflineDictationPhase.Finishing) }
        override fun cancel() { cancels++ }
        override fun close() { closes++ }
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun save(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/outputs/effect-checks/offline-dictation/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test fun microphoneStopsLiveRecognitionAndCanRestartAfterTheSentenceFinishes() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get().apply { setTheme(R.style.Theme_InputViewTheme) }
        val session = Session(OfflineDictationState(OfflineDictationPhase.Recording, "你好，今天的天气不错"))
        var returns = 0
        val ui = OfflineDictationUi(activity, ThemePreset.XuancaiBlackV09, session) { returns++ }
        activity.setContentView(ui.root)
        controller.visible()
        try {
            layout(ui.root, activity.dp(360), activity.dp(270))
            val microphone = ui.root.findViewWithTag<View>("dictation_record")
            assertEquals(0, session.starts) // The window starts once; re-rendering the UI must not.
            assertTrue(microphone.isEnabled)
            assertEquals(activity.getString(R.string.offline_dictation_ui_stop), microphone.contentDescription)
            assertNull("Words belong in the editor, not a duplicate confirmation panel",
                ui.root.findViewWithTag<View>("dictation_transcript"))
            assertNull(ui.root.findViewWithTag<View>("dictation_insert"))
            save(ui.root, "listening")
            layout(ui.root, activity.dp(360), activity.dp(320))
            save(ui.root, "listening-tall")
            assertTrue(microphone.performClick())
            assertEquals(1, session.stops)
            assertFalse(microphone.isEnabled)
            save(ui.root, "finishing")
            assertEquals(0, returns)
            session.state.value = OfflineDictationState(OfflineDictationPhase.Finished, "你好，今天的天气不错。")
            ui.render(session.state.value)
            assertTrue(microphone.isEnabled)
            save(ui.root, "finished")
            microphone.performClick()
            assertEquals(1, session.starts)
            assertEquals(OfflineDictationPhase.Preparing, session.state.value.phase)
            save(ui.root, "preparing")
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun orbRedrawsOnlyWhileActiveVisibleAndAnimationsAreEnabled() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get().apply { setTheme(R.style.Theme_InputViewTheme) }
        val session = Session(OfflineDictationState(OfflineDictationPhase.Recording))
        val ui = OfflineDictationUi(activity, ThemePreset.PixelLight, session) {}
        activity.setContentView(ui.root)
        controller.visible()
        try {
            layout(ui.root, activity.dp(360), activity.dp(270))
            val microphone = ui.root.findViewWithTag<View>("dictation_record")
            val tick = ReflectionHelpers.getField<Runnable>(microphone, "frameTick")
            val handler = requireNotNull(microphone.handler)
            fun show(phase: OfflineDictationPhase) {
                session.state.value = OfflineDictationState(phase)
                ui.render(session.state.value)
            }
            assertTrue("Listening schedules a restrained status glow", handler.hasCallbacks(tick))
            save(ui.root, "light-keyboard-listening")
            show(OfflineDictationPhase.Finished)
            assertFalse("Finished must not keep an idle redraw loop", handler.hasCallbacks(tick))
            show(OfflineDictationPhase.Recording)
            assertTrue(handler.hasCallbacks(tick))
            ui.root.visibility = View.GONE
            assertFalse("Hiding the keyboard removes the pending frame", handler.hasCallbacks(tick))
            ui.root.visibility = View.VISIBLE
            assertTrue(handler.hasCallbacks(tick))
            AppPrefs.getInstance().advanced.disableAnimation.setValue(true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
            assertFalse("The app setting stops an already active animation", handler.hasCallbacks(tick))
            AppPrefs.getInstance().advanced.disableAnimation.setValue(false)
            show(OfflineDictationPhase.Ready)
            show(OfflineDictationPhase.Recording)
            assertTrue(handler.hasCallbacks(tick))
            systemAnimationScale(0f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
            assertFalse("System animation disable also stops the loop", handler.hasCallbacks(tick))
            systemAnimationScale(1f)
            show(OfflineDictationPhase.Ready)
            show(OfflineDictationPhase.Recording)
            assertTrue(handler.hasCallbacks(tick))
            activity.setContentView(View(activity))
            assertFalse("Detached controls must release their pending frames", handler.hasCallbacks(tick))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun unavailableReasonRemainsVisibleAndReturnWorksAtShortKeyboardHeight() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get().apply { setTheme(R.style.Theme_InputViewTheme) }
        val reason = "请先完成当前拼音选词，再开始离线听写。"
        val session = Session(OfflineDictationState(OfflineDictationPhase.Unavailable, message = reason))
        var returns = 0
        val ui = OfflineDictationUi(activity, ThemePreset.XuancaiBlackV09, session) { returns++ }
        activity.setContentView(ui.root)
        controller.visible()
        try {
            layout(ui.root, activity.dp(360), activity.dp(180))
            assertEquals(reason, ui.root.findViewWithTag<TextView>("dictation_message").text.toString())
            val microphone = ui.root.findViewWithTag<View>("dictation_record")
            assertFalse(microphone.isEnabled)
            microphone.performClick()
            assertEquals(0, session.starts)
            val back = ui.root.findViewWithTag<View>("dictation_back")
            val bounds = Rect().also { back.getDrawingRect(it); ui.root.offsetDescendantRectToMyCoords(back, it) }
            assertTrue(bounds.top >= 0 && bounds.bottom <= ui.root.height)
            assertTrue(back.width > 0 && back.height >= activity.dp(40))
            save(ui.root, "unavailable-short-keyboard")
            back.performClick()
            assertEquals(1, session.cancels)
            assertEquals(1, returns)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun returningOrHidingClosesTheSessionExactlyOnce() {
        val session = Session(OfflineDictationState(OfflineDictationPhase.Recording, "已经进入输入框"))
        val window = OfflineDictationWindow(session)
        window.onDetached()
        window.onDetached()
        assertEquals(1, session.cancels)
        assertEquals(1, session.closes)
        assertEquals("Leaving only manages session lifetime; it does not clear entered text",
            "已经进入输入框", session.state.value.transcript)
    }
}
