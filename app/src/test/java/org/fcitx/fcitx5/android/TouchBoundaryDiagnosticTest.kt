/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.diagnostics.TouchDiagnosticStore
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.TouchTraceRecorder
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/** Single-variable boundary experiment, using the production keyboard routing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w600dp-h900dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TouchBoundaryDiagnosticTest {
    private var previousApplication: Any? = null
    private var previousDiagnosticStore: Any? = null
    private var previousAppPrefs: Any? = null

    @Before fun prepare() {
        val context = RuntimeEnvironment.getApplication()
        val field = FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }
        previousApplication = field.get(null)
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, context))
        field.set(null, app)
        val preferences = context.getSharedPreferences("touch-boundary-diagnostics", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        AppPrefs::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            previousAppPrefs = get(null)
            set(null, null)
        }
        AppPrefs.init(preferences)
        TouchDiagnosticStore::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            previousDiagnosticStore = get(null)
            set(null, null)
        }
    }

    @After fun restore() {
        TouchDiagnosticStore::class.java.getDeclaredField("instance").apply {
            isAccessible = true; set(null, previousDiagnosticStore)
        }
        AppPrefs::class.java.getDeclaredField("instance").apply {
            isAccessible = true; set(null, previousAppPrefs)
        }
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true; set(null, previousApplication)
        }
    }

    private data class Finger(val id: Int, val x: Float, val y: Float)

    private class Harness(boundary: Boolean, observe: Boolean = true) : AutoCloseable {
        private val restore = mutableListOf<() -> Unit>()
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        private val activity = controller.get()
        val keyboard: TextKeyboard
        val typed = mutableListOf<String>()
        val traces = mutableListOf<JSONObject>()
        private var downTime = 0L

        init {
            setting(ThemeManager.prefs.pressEffect, false)
            setting(ThemeManager.prefs.idleBreathing, false)
            setting(ThemeManager.prefs.keyMotionEffect, ThemePrefs.KeyMotionEffect.Off)
            setting(ThemeManager.prefs.portraitNumberRow, false)
            setting(ThemeManager.prefs.keyWidthOverrides, "")
            setting(AppPrefs.getInstance().keyboard.popupOnKeyPress, false)
            setting(AppPrefs.getInstance().keyboard.expandKeypressArea, true)
            setting(AppPrefs.getInstance().keyboard.longPressDelay, 300)
            setting(AppPrefs.getInstance().keyboard.touchBoundarySettling, boundary)
            activity.setTheme(R.style.Theme_InputViewTheme)
            keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
            activity.setContentView(FrameLayout(activity).apply {
                addView(keyboard, FrameLayout.LayoutParams(360, 260))
            })
            controller.visible()
            waitFor(32)
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(260, View.MeasureSpec.EXACTLY))
            keyboard.layout(0, 0, 360, 260)
            keyboard.keyActionListener = KeyActionListener { action, _ ->
                if (action is KeyAction.FcitxKeyAction) typed += action.act
            }
            if (observe) keyboard.touchDiagnosticObserver = { traces += it }
        }

        private fun <T : Any> setting(pref: ManagedPreference<T>, value: T) {
            val existed = pref.sharedPreferences.contains(pref.key)
            val old = pref.getValue()
            restore += { if (existed) pref.setValue(old) else pref.sharedPreferences.edit().remove(pref.key).commit(); Unit }
            pref.setValue(value)
        }

        private fun keys(view: View = keyboard): List<KeyView> = when (view) {
            is KeyView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { keys(view.getChildAt(it)) }
            else -> emptyList()
        }

        fun bounds(label: String): Rect {
            var child: View = keys().first { (it.def as? KeyDef.Appearance.Text)?.displayText == label }
            return Rect(0, 0, child.width, child.height).apply {
                while (child !== keyboard) {
                    offset(child.left, child.top)
                    child = child.parent as View
                }
            }
        }

        fun center(label: String, id: Int = 0) = bounds(label).let { Finger(id, it.exactCenterX(), it.exactCenterY()) }
        fun boundaryPoints(): Pair<Finger, Finger> {
            val f = bounds("F")
            return Finger(0, f.right - 1f, f.exactCenterY()) to Finger(0, f.right + 8f, f.exactCenterY())
        }

        private fun coords(finger: Finger) = MotionEvent.PointerCoords().apply {
            x = finger.x; y = finger.y; pressure = .65f; size = .3f; touchMajor = 14f; touchMinor = 8f
        }

        fun event(action: Int, vararg fingers: Finger) {
            if ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            val properties = fingers.map { MotionEvent.PointerProperties().apply {
                id = it.id; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }.toTypedArray()
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, fingers.size,
                properties, fingers.map(::coords).toTypedArray(), 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(keyboard.dispatchTouchEvent(event)) } finally { event.recycle() }
            waitFor(8)
        }

        fun historicalMove(first: Finger, last: Finger, gap: Long) {
            val start = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(downTime, start, MotionEvent.ACTION_MOVE, 1,
                arrayOf(MotionEvent.PointerProperties().apply { id = first.id; toolType = MotionEvent.TOOL_TYPE_FINGER }),
                arrayOf(coords(first)), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            event.addBatch(start + gap, arrayOf(coords(last)), 0)
            waitFor(gap)
            try { assertTrue(keyboard.dispatchTouchEvent(event)) } finally { event.recycle() }
            waitFor(8)
        }

        fun waitFor(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
        override fun close() {
            keyboard.touchDiagnosticObserver = null
            keyboard.onDetach()
            controller.pause().stop().destroy()
            restore.asReversed().forEach { it() }
        }
    }

    @Test fun boundarySettlingDefaultPreservesTheCurrentReleasePolicy() {
        assertTrue(AppPrefs.getInstance().keyboard.touchBoundarySettling.getValue())
        for (enabled in listOf(true, false)) Harness(enabled).use { h ->
            val (down, interior) = h.boundaryPoints()
            h.event(MotionEvent.ACTION_DOWN, down)
            h.event(MotionEvent.ACTION_MOVE, interior)
            h.waitFor(48)
            h.event(MotionEvent.ACTION_MOVE, interior)
            assertTrue("Both modes still commit on UP", h.typed.isEmpty())
            h.event(MotionEvent.ACTION_UP, interior)
            assertEquals(listOf(if (enabled) "g" else "f"), h.typed)
            val trace = h.traces.single()
            assertEquals(enabled, trace.getBoolean("boundary_settling"))
            assertEquals(if (enabled) 1 else 0, trace.getJSONArray("decisions").length())
            if (enabled) assertEquals("settle", trace.getJSONArray("decisions").getJSONObject(0).getString("mode"))
            val contact = trace.getJSONArray("contacts").getJSONObject(0)
            val keys = trace.getJSONObject("layout").getJSONArray("keys")
            fun id(label: String) = (0 until keys.length()).map { keys.getJSONObject(it) }
                .first { it.getString("label") == label }.getInt("id")
            assertEquals(id("F"), contact.getInt("down_key"))
            assertEquals(id("G"), contact.getInt("up_hit_key"))
            assertEquals(id(if (enabled) "G" else "F"), contact.getInt("up_key"))
            assertEquals(if (enabled) "g" else "f", trace.getJSONArray("actions").getJSONObject(0).getString("act"))
        }
    }

    @Test fun changingTheSwitchMidContactAffectsOnlyTheNextGesture() {
        Harness(true).use { h ->
            val (down, interior) = h.boundaryPoints()
            h.event(MotionEvent.ACTION_DOWN, down)
            AppPrefs.getInstance().keyboard.touchBoundarySettling.setValue(false)
            h.event(MotionEvent.ACTION_MOVE, interior)
            h.waitFor(48)
            h.event(MotionEvent.ACTION_MOVE, interior)
            h.event(MotionEvent.ACTION_UP, interior)
            h.event(MotionEvent.ACTION_DOWN, down)
            h.event(MotionEvent.ACTION_MOVE, interior)
            h.waitFor(48)
            h.event(MotionEvent.ACTION_MOVE, interior)
            h.event(MotionEvent.ACTION_UP, interior)
            assertEquals(listOf("g", "f"), h.typed)
            assertEquals(listOf(true, false), h.traces.map { it.getBoolean("boundary_settling") })
        }
    }

    @Test fun deliberateSlidingIsPreservedWhenBoundarySettlingIsOff() {
        for (enabled in listOf(true, false)) Harness(enabled).use { h ->
            h.event(MotionEvent.ACTION_DOWN, h.center("G"))
            h.event(MotionEvent.ACTION_MOVE, h.center("H"))
            h.waitFor(64)
            h.event(MotionEvent.ACTION_MOVE, h.center("H"))
            assertTrue(h.typed.isEmpty())
            h.event(MotionEvent.ACTION_UP, h.center("J"))
            assertEquals(listOf("j"), h.typed)
            val decisions = h.traces.single().getJSONArray("decisions")
            assertEquals("slide", decisions.getJSONObject(0).getString("mode"))
        }
    }

    @Test fun historyAndContactGeometryAreRecordedWithoutChangingBatchDecisions() {
        for (enabled in listOf(true, false)) Harness(enabled).use { h ->
            val (down, interior) = h.boundaryPoints()
            h.event(MotionEvent.ACTION_DOWN, down)
            h.historicalMove(interior, interior, 48)
            h.event(MotionEvent.ACTION_UP, interior)
            assertEquals(listOf(if (enabled) "g" else "f"), h.typed)
            val events = h.traces.single().getJSONArray("events")
            val move = events.getJSONObject(1)
            val historical = move.getJSONArray("history").getJSONObject(0)
            assertEquals(48L, move.getLong("t") - historical.getLong("t"))
            val pointer = historical.getJSONArray("pointers").getJSONObject(0)
            assertEquals(.65, pointer.getDouble("pressure"), .00001)
            assertEquals(.3, pointer.getDouble("size"), .00001)
            assertEquals(14.0, pointer.getDouble("touch_major"), .00001)
            assertEquals(8.0, pointer.getDouble("touch_minor"), .00001)
        }
    }

    @Test fun reversedReleaseOrderRemainsUnchangedAndStablePointerIdsAreLogged() {
        for (enabled in listOf(true, false)) Harness(enabled).use { h ->
            val n = h.center("N", 3)
            val l = h.center("L", 9)
            h.event(MotionEvent.ACTION_DOWN, n)
            h.event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), n, l)
            h.event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), n, l)
            h.event(MotionEvent.ACTION_UP, n)
            assertEquals("A/B must not silently change the commit ordering", listOf("l", "n"), h.typed)
            val trace = h.traces.single()
            val actions = trace.getJSONArray("actions")
            assertEquals(9, actions.getJSONObject(0).getInt("pointer_id"))
            assertEquals(3, actions.getJSONObject(1).getInt("pointer_id"))
            val contacts = trace.getJSONArray("contacts")
            assertEquals(3, contacts.getJSONObject(0).getInt("pointer_id"))
            assertEquals(9, contacts.getJSONObject(1).getInt("pointer_id"))
        }
    }

    @Test fun cancellationRecordsNoCommittedCharacter() {
        Harness(false).use { h ->
            h.event(MotionEvent.ACTION_DOWN, h.center("N"))
            h.event(MotionEvent.ACTION_CANCEL, h.center("B"))
            assertTrue(h.typed.isEmpty())
            val trace = h.traces.single()
            assertTrue(trace.getBoolean("cancelled"))
            assertEquals(0, trace.getJSONArray("actions").length())
            assertTrue(trace.getJSONArray("contacts").getJSONObject(0).getBoolean("cancelled"))
        }
    }

    @Test fun cancellationDoesNotRetroactivelyCancelAnAlreadyReleasedPointer() {
        Harness(false).use { h ->
            val n = h.center("N", 3)
            val l = h.center("L", 9)
            h.event(MotionEvent.ACTION_DOWN, n)
            h.event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), n, l)
            h.event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), n, l)
            h.event(MotionEvent.ACTION_CANCEL, n)
            assertEquals(listOf("l"), h.typed)
            val contacts = h.traces.single().getJSONArray("contacts")
            assertTrue(contacts.getJSONObject(0).getBoolean("cancelled"))
            assertFalse(contacts.getJSONObject(1).optBoolean("cancelled", false))
        }
    }

    @Test fun observationCanBeRemovedMidGestureWithoutEmittingAPartialTrace() {
        Harness(false).use { h ->
            h.event(MotionEvent.ACTION_DOWN, h.center("N"))
            h.keyboard.touchDiagnosticObserver = null
            h.event(MotionEvent.ACTION_UP, h.center("N"))
            assertEquals(listOf("n"), h.typed)
            assertTrue(h.traces.isEmpty())
        }
    }

    @Test fun editorTokenChangeDropsTheGestureEvenIfRecordingResumesBeforeUp() {
        Harness(false, observe = false).use { h ->
            val store = TouchDiagnosticStore.get(RuntimeEnvironment.getApplication())
            val logging = AppPrefs.getInstance().keyboard.touchDiagnosticLogging
            logging.setValue(true)
            val plain = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
            try {
                store.updateEditor(plain)
                runBlocking { store.clear() }
                h.event(MotionEvent.ACTION_DOWN, h.center("N"))
                store.updateEditor(EditorInfo().apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                })
                store.updateEditor(plain)
                h.event(MotionEvent.ACTION_UP, h.center("N"))
                assertEquals(0, runBlocking { store.snapshot() }.recordCount)
                h.event(MotionEvent.ACTION_DOWN, h.center("N"))
                h.event(MotionEvent.ACTION_UP, h.center("N"))
                assertEquals(1, runBlocking { store.snapshot() }.recordCount)
                assertEquals(listOf("n", "n"), h.typed)
            } finally {
                logging.setValue(false)
                store.updateEditor(null)
                runBlocking { store.clear() }
            }
        }
    }

    @Test fun disablingAndReenablingLoggingDropsAnAlreadyStartedGesture() {
        Harness(false, observe = false).use { h ->
            val store = TouchDiagnosticStore.get(RuntimeEnvironment.getApplication())
            val logging = AppPrefs.getInstance().keyboard.touchDiagnosticLogging
            logging.setValue(true)
            try {
                store.updateEditor(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT })
                runBlocking { store.clear() }
                h.event(MotionEvent.ACTION_DOWN, h.center("N"))
                logging.setValue(false)
                logging.setValue(true)
                h.event(MotionEvent.ACTION_UP, h.center("N"))
                assertEquals(0, runBlocking { store.snapshot() }.recordCount)
                assertEquals(listOf("n"), h.typed)
            } finally {
                logging.setValue(false)
                store.updateEditor(null)
                runBlocking { store.clear() }
            }
        }
    }

    @Test fun brokenDiagnosticPayloadCannotEscapeIntoTouchDispatch() {
        val recorder = TouchTraceRecorder(RuntimeEnvironment.getApplication())
        val traces = mutableListOf<JSONObject>()
        recorder.observer = { traces += it }
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        val up = MotionEvent.obtain(time, time + 1, MotionEvent.ACTION_UP, 10f, 10f, 0)
        try {
            recorder.beforeEvent(down, true, { error("Injected diagnostic layout failure") }) { _, _ -> 1 }
            recorder.decision(time, 0, 1, 2, slide = false)
            recorder.action(KeyAction.FcitxKeyAction("n"), KeyActionListener.Source.Keyboard)
            recorder.released(0, 1)
            recorder.afterEvent(up)
            assertTrue(traces.isEmpty())
            // Failure discards the affected trace; it cannot poison the next one.
            recorder.beforeEvent(down, true, { JSONObject() }) { _, _ -> 1 }
            recorder.beforeEvent(up, true, { JSONObject() }) { _, _ -> 1 }
            recorder.afterEvent(up)
            assertEquals(1, traces.size)
        } finally { down.recycle(); up.recycle() }
    }
}
