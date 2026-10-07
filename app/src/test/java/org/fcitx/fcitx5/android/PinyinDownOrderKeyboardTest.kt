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
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodSubMode
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
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
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/** Replays overlapping contacts through the production keyboard and its gesture timers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w600dp-h900dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PinyinDownOrderKeyboardTest {
    private var previousApplication: Any? = null
    private var previousDiagnosticStore: Any? = null
    private var previousAppPrefs: Any? = null

    @Before fun prepare() {
        // Animation frames must not advance the touch clock beyond our explicit holds.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val context = RuntimeEnvironment.getApplication()
        val field = FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }
        previousApplication = field.get(null)
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, context))
        field.set(null, app)
        val preferences = context.getSharedPreferences("pinyin-down-order-keyboard", Context.MODE_PRIVATE)
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
        ShadowChoreographer.setPaused(false)
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

    companion object {
        private fun chinese() = InputMethodEntry("rime", "Rime", "", "中州韵", "中", "zh_CN",
            "rime", true, InputMethodSubMode("rime_ice", "中", "fcitx_rime"))
        private fun english() = InputMethodEntry("keyboard-us", "English", "", "English", "英",
            "en", "keyboard", false)
        private fun down(index: Int) = MotionEvent.ACTION_POINTER_DOWN or
            (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        private fun up(index: Int) = MotionEvent.ACTION_POINTER_UP or
            (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    }

    private class Harness(ordered: Boolean = true, correction: Boolean = false, alternatives: Boolean = false,
                          motion: ThemePrefs.KeyMotionEffect = ThemePrefs.KeyMotionEffect.Off) : AutoCloseable {
        private val restore = mutableListOf<() -> Unit>()
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        private val activity = controller.get()
        val keyboard: TextKeyboard
        val typed = mutableListOf<String>()
        val actions = mutableListOf<KeyAction>()
        var afterAction: ((KeyAction) -> Unit)? = null
        val popups = mutableListOf<PopupAction>()
        var editor: EditorInfo? = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        val traces = mutableListOf<JSONObject>()
        private var downTime = 0L

        init {
            setting(ThemeManager.prefs.pressEffect, false)
            setting(ThemeManager.prefs.idleBreathing, false)
            setting(ThemeManager.prefs.keyMotionEffect, motion)
            setting(ThemeManager.prefs.portraitNumberRow, false)
            setting(ThemeManager.prefs.keyWidthOverrides, "")
            setting(AppPrefs.getInstance().keyboard.popupOnKeyPress, false)
            setting(AppPrefs.getInstance().keyboard.expandKeypressArea, true)
            setting(AppPrefs.getInstance().keyboard.longPressDelay, 300)
            setting(AppPrefs.getInstance().keyboard.touchBoundarySettling, false)
            setting(AppPrefs.getInstance().keyboard.pinyinDownOrder, ordered)
            setting(AppPrefs.getInstance().keyboard.pinyinTouchCorrection, correction)
            setting(AppPrefs.getInstance().keyboard.pinyinTouchAlternatives, alternatives)
            activity.setTheme(R.style.Theme_InputViewTheme)
            keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
            keyboard.downOrderEditorInfoProvider = { editor }
            keyboard.onInputMethodUpdate(chinese())
            activity.setContentView(FrameLayout(activity).apply {
                addView(keyboard, FrameLayout.LayoutParams(360, 260))
            })
            controller.visible()
            waitFor(32)
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(260, View.MeasureSpec.EXACTLY))
            keyboard.layout(0, 0, 360, 260)
            keyboard.keyActionListener = KeyActionListener { action, _ ->
                actions += action
                if (action is KeyAction.FcitxKeyAction) typed += action.act
                afterAction?.invoke(action)
            }
            keyboard.touchDiagnosticObserver = { traces += it }
            keyboard.popupActionListener = PopupActionListener { popups += it }
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

        fun key(label: String): KeyView = keys().first { (it.def as? KeyDef.Appearance.Text)?.displayText == label }
        private fun bounds(view: View): Rect {
            var child: View = view
            return Rect(0, 0, child.width, child.height).apply {
                while (child !== keyboard) {
                    offset(child.left, child.top)
                    child = child.parent as View
                }
            }
        }

        fun bounds(label: String): Rect = bounds(key(label))
        fun center(label: String, id: Int = 0) = bounds(label).let { Finger(id, it.exactCenterX(), it.exactCenterY()) }
        fun center(view: View, id: Int) = bounds(view).let { Finger(id, it.exactCenterX(), it.exactCenterY()) }
        private fun coords(finger: Finger) = MotionEvent.PointerCoords().apply {
            x = finger.x; y = finger.y; pressure = .65f; size = .3f; touchMajor = 14f; touchMinor = 8f
        }

        fun event(action: Int, vararg fingers: Finger, advance: Long = 8, reportedTime: Long? = null) {
            if ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            val properties = fingers.map { MotionEvent.PointerProperties().apply {
                id = it.id; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }.toTypedArray()
            val event = MotionEvent.obtain(downTime, reportedTime ?: SystemClock.uptimeMillis(), action, fingers.size,
                properties, fingers.map(::coords).toTypedArray(), 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(keyboard.dispatchTouchEvent(event)) } finally { event.recycle() }
            waitFor(advance)
        }

        fun waitFor(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
        override fun close() {
            keyboard.touchDiagnosticObserver = null
            keyboard.downOrderEditorInfoProvider = null
            keyboard.onDetach()
            controller.pause().stop().destroy()
            restore.asReversed().forEach { it() }
        }
    }

    @Test fun orderAndCorrectionCanBeSwitchedIndependently() {
        val prefs = AppPrefs.getInstance().keyboard
        prefs.pinyinTouchCorrection.setValue(false)
        prefs.pinyinDownOrder.setValue(true)
        assertFalse(prefs.pinyinTouchCorrection.getValue())
        prefs.pinyinDownOrder.setValue(false)
        prefs.pinyinTouchCorrection.setValue(true)
        assertFalse(prefs.pinyinDownOrder.getValue())
    }

    @Test fun reverseReleaseOrdersOnlyEnabledOrdinaryChineseContacts() {
        for (ordered in listOf(false, true)) Harness(ordered).use { h ->
            val n = h.center("N", 3)
            val l = h.center("L", 9)
            h.event(MotionEvent.ACTION_DOWN, n)
            h.event(down(1), n, l)
            assertTrue("DOWN must never commit", h.typed.isEmpty())
            h.event(up(1), n, l)
            assertEquals(if (ordered) listOf("n", "l") else listOf("l"), h.typed)
            if (ordered) assertFalse(h.key("N").isPressed)
            h.event(MotionEvent.ACTION_MOVE, n)
            h.event(MotionEvent.ACTION_UP, n)
            assertEquals(if (ordered) listOf("n", "l") else listOf("l", "n"), h.typed)
            val contacts = h.traces.single().getJSONArray("contacts")
            assertEquals(ordered, contacts.getJSONObject(0).optString("release_reason") == "down_order")
            assertFalse(contacts.getJSONObject(0).isNull("up_key"))
        }
    }

    @Test fun delayedUpDeliveryKeepsSampleTimeSeparateFromActualDispatchTime() {
        Harness(ordered = false, correction = false, alternatives = true).use { h ->
            val n = h.center("N", 2)
            val start = SystemClock.uptimeMillis()
            h.event(MotionEvent.ACTION_DOWN, n)
            h.waitFor(64)
            val dispatchAt = SystemClock.uptimeMillis()
            h.event(MotionEvent.ACTION_UP, n, reportedTime = start + 8)
            val evidence = h.actions.filterIsInstance<KeyAction.FcitxKeyAction>().single().pinyinTapEvidence!!
            assertEquals(start, evidence.downTime)
            assertEquals(start + 8, evidence.physicalUpTime)
            assertEquals(dispatchAt, evidence.dispatchTime)
            assertTrue(evidence.dispatchTime!! > evidence.physicalUpTime!!)
        }
    }

    @Test fun threeFingerInverseUpPreservesDownEvidenceAndIgnoresArrayAndIdOrder() {
        Harness(correction = true).use { h ->
            val q = h.center("Q", 13)
            val r = h.center("R", 2)
            val y = h.center("Y", 27)
            h.event(MotionEvent.ACTION_DOWN, q, advance = 0)
            val at = SystemClock.uptimeMillis()
            h.event(down(0), r, q, advance = 0)
            h.event(down(0), y, q, r, advance = 0)
            assertTrue(h.typed.isEmpty())
            h.event(up(0), y, q, r)
            assertEquals(listOf("q", "r", "y"), h.typed)
            val evidence = h.actions.filterIsInstance<KeyAction.FcitxKeyAction>().map { it.pinyinTapEvidence!! }
            assertEquals(listOf(13, 2, 27), evidence.map { it.pointerId })
            assertEquals(listOf(q.x, r.x, y.x), evidence.map { it.tap.downX })
            assertEquals(listOf(q.y, r.y, y.y), evidence.map { it.tap.downY })
            assertEquals(listOf(at, at, at), evidence.map { it.downTime })
            assertTrue(evidence.all { it.cells.isNotEmpty() })
            assertEquals(listOf(0L, 1L, 2L), evidence.map { it.downSequence })
            assertEquals(listOf(0L, 1L, 2L), evidence.map { it.dispatchSequence })
            assertEquals(3, evidence.map { it.contactId }.toSet().size)
            assertTrue(evidence.all { it.contactId > 0 })
            // q/r were confirmed by the younger finger's UP, not physically released.
            assertNull(evidence[0].physicalUpTime)
            assertNull(evidence[1].physicalUpTime)
            assertEquals(evidence[2].dispatchTime, evidence[2].physicalUpTime)
            h.event(MotionEvent.ACTION_MOVE, r, q)
            h.event(up(0), r, q)
            h.event(MotionEvent.ACTION_UP, q)
            assertEquals(listOf("q", "r", "y"), h.typed)
            val trace = h.traces.single()
            assertEquals(listOf(13, 2, 27), (0 until 3).map {
                trace.getJSONArray("actions").getJSONObject(it).getInt("pointer_id")
            })
            val contacts = trace.getJSONArray("contacts")
            assertEquals("down_order", contacts.getJSONObject(0).getString("release_reason"))
            assertEquals("down_order", contacts.getJSONObject(1).getString("release_reason"))
            assertTrue(contacts.getJSONObject(0).getLong("release_t") < contacts.getJSONObject(0).getLong("up_t"))
            assertEquals(contacts.getJSONObject(0).getLong("up_t"),
                contacts.getJSONObject(0).getLong("physical_up_t"))
            assertEquals(evidence[0].contactId, contacts.getJSONObject(0).getLong("contact_id"))
            val actions = trace.getJSONArray("actions")
            assertTrue(actions.getJSONObject(0).isNull("physical_up_t"))
            assertEquals("down_order", actions.getJSONObject(0).getString("confirmation_kind"))
            assertEquals("physical_up", actions.getJSONObject(2).getString("confirmation_kind"))
            h.event(MotionEvent.ACTION_DOWN, q)
            h.event(MotionEvent.ACTION_UP, q)
            assertEquals(listOf("q", "r", "y", "q"), h.typed)
            val laterEvidence = h.actions.filterIsInstance<KeyAction.FcitxKeyAction>().last().pinyinTapEvidence!!
            assertFalse(evidence.any { it.contactId == laterEvidence.contactId })
            assertEquals(0L, laterEvidence.downSequence)
            assertEquals(0L, laterEvidence.dispatchSequence)
        }
    }

    @Test fun quickPlainTapCapturesDownEvidenceWhenOnlyAlternativesAreEnabled() {
        Harness(ordered = false, correction = false, alternatives = true).use { h ->
            val f = h.bounds("F")
            val initial = Finger(13, f.right - 1f, f.exactCenterY())
            val lifted = initial.copy(x = f.right + 8f)
            val downAt = SystemClock.uptimeMillis()
            h.event(MotionEvent.ACTION_DOWN, initial)
            assertTrue(h.actions.isEmpty())
            h.event(MotionEvent.ACTION_UP, lifted)
            assertEquals(listOf("f"), h.typed)
            val evidence = h.actions.filterIsInstance<KeyAction.FcitxKeyAction>().single().pinyinTapEvidence!!
            assertEquals('f', evidence.tap.original)
            assertEquals(initial.x, evidence.tap.downX, 0f)
            assertEquals(initial.y, evidence.tap.downY, 0f)
            assertEquals(13, evidence.pointerId)
            assertEquals(downAt, evidence.downTime)
            assertEquals(downAt + 8L, evidence.physicalUpTime)
            assertEquals(evidence.physicalUpTime, evidence.dispatchTime)
            assertEquals(lifted.x, evidence.physicalUpX!!, 0f)
            assertEquals(lifted.y, evidence.physicalUpY!!, 0f)
            assertTrue(evidence.cells.any { it.letter == 'f' })
            assertTrue(evidence.cells.any { it.letter == 'g' })
            assertEquals(h.traces.single().getString("id"), evidence.diagnosticTraceId)
            val settings = h.traces.single().getJSONObject("layout").getJSONObject("settings")
            assertFalse(settings.getBoolean("pinyin_touch_correction"))
            assertTrue(settings.getBoolean("pinyin_touch_alternatives"))
            assertFalse(settings.getBoolean("pinyin_down_order"))
        }
    }

    @Test fun phantomReleaseRetainsDownEvidenceWhenOnlyAlternativesAreEnabled() {
        Harness(correction = false, alternatives = true).use { h ->
            val q = h.center("Q", 13)
            val w = h.center("W", 2)
            val qDownAt = SystemClock.uptimeMillis()
            h.event(MotionEvent.ACTION_DOWN, q)
            val wDownAt = SystemClock.uptimeMillis()
            h.event(down(1), q, w)
            assertTrue(h.actions.isEmpty())
            h.event(up(1), q.copy(x = q.x + 3f, y = q.y + 2f), w)
            assertEquals(listOf("q", "w"), h.typed)
            val evidence = h.actions.filterIsInstance<KeyAction.FcitxKeyAction>().map { it.pinyinTapEvidence!! }
            assertEquals(listOf('q', 'w'), evidence.map { it.tap.original })
            assertEquals(listOf(13, 2), evidence.map { it.pointerId })
            assertEquals(listOf(q.x, w.x), evidence.map { it.tap.downX })
            assertEquals(listOf(q.y, w.y), evidence.map { it.tap.downY })
            assertEquals(listOf(qDownAt, wDownAt), evidence.map { it.downTime })
            assertTrue(evidence.all { it.cells.isNotEmpty() })
            h.event(MotionEvent.ACTION_UP, q)
            assertEquals(2, h.actions.size)
            val trace = h.traces.single()
            assertTrue(evidence.all { it.diagnosticTraceId == trace.getString("id") })
            assertEquals("down_order", trace.getJSONArray("contacts").getJSONObject(0).getString("release_reason"))
            val settings = trace.getJSONObject("layout").getJSONObject("settings")
            assertFalse(settings.getBoolean("pinyin_touch_correction"))
            assertTrue(settings.getBoolean("pinyin_touch_alternatives"))
            assertTrue(settings.getBoolean("pinyin_down_order"))
        }
    }

    @Test fun bothTouchFeaturesOffAttachNoEvidenceToPlainOrOrderedReleases() {
        for (ordered in listOf(false, true)) Harness(ordered, correction = false, alternatives = false).use { h ->
            val r = h.center("R", 7)
            h.event(MotionEvent.ACTION_DOWN, r)
            h.event(MotionEvent.ACTION_UP, r)
            val q = h.center("Q", 13)
            val w = h.center("W", 2)
            h.event(MotionEvent.ACTION_DOWN, q)
            h.event(down(1), q, w)
            h.event(up(1), q, w)
            h.event(MotionEvent.ACTION_UP, q)
            assertEquals(if (ordered) listOf("r", "q", "w") else listOf("r", "w", "q"), h.typed)
            assertTrue(h.actions.filterIsInstance<KeyAction.FcitxKeyAction>().all { it.pinyinTapEvidence == null })
            for (trace in h.traces) {
                val settings = trace.getJSONObject("layout").getJSONObject("settings")
                assertFalse(settings.getBoolean("pinyin_touch_correction"))
                assertFalse(settings.getBoolean("pinyin_touch_alternatives"))
                assertEquals(ordered, settings.getBoolean("pinyin_down_order"))
            }
        }
    }

    @Test fun releasingTheOldestDoesNotReleaseAnyNewerContact() {
        Harness().use { h ->
            val q = h.center("Q", 7)
            val w = h.center("W", 1)
            h.event(MotionEvent.ACTION_DOWN, q)
            h.event(down(1), q, w)
            h.event(up(0), q, w)
            assertEquals(listOf("q"), h.typed)
            assertTrue(h.key("W").isPressed)
            h.event(MotionEvent.ACTION_UP, w)
            assertEquals(listOf("q", "w"), h.typed)
        }
    }

    @Test fun deliberateSlideStaysLiveWhenAnotherLetterIsReleased() {
        Harness().use { h ->
            val g = h.center("G", 4)
            val moved = h.center("H", 4)
            val l = h.center("L", 12)
            h.event(MotionEvent.ACTION_DOWN, g)
            h.event(MotionEvent.ACTION_MOVE, moved)
            h.waitFor(64)
            h.event(MotionEvent.ACTION_MOVE, moved)
            h.event(down(1), moved, l)
            h.event(up(1), moved, l)
            assertEquals(listOf("l"), h.typed)
            assertTrue(h.key("H").isPressed)
            h.event(MotionEvent.ACTION_UP, h.center("J", 4))
            assertEquals(listOf("l", "j"), h.typed)
            assertEquals("slide", h.traces.single().getJSONArray("decisions").getJSONObject(0).getString("mode"))
        }
    }

    @Test fun releasingASlideDoesNotFlushAnOlderOrdinaryLetter() {
        Harness().use { h ->
            val q = h.center("Q", 4)
            val g = h.center("G", 12)
            val moved = h.center("H", 12)
            h.event(MotionEvent.ACTION_DOWN, q)
            h.event(down(1), q, g)
            h.event(MotionEvent.ACTION_MOVE, q, moved)
            h.waitFor(64)
            h.event(MotionEvent.ACTION_MOVE, q, moved)
            h.event(up(1), q, moved)
            assertEquals(listOf("h"), h.typed)
            assertTrue(h.key("Q").isPressed)
            h.event(MotionEvent.ACTION_UP, q)
            assertEquals(listOf("h", "q"), h.typed)
        }
    }

    @Test fun modifierAndSymbolContactsKeepTheirOwnRealUp() {
        for (modifier in listOf(true, false)) Harness().use { h ->
            val view = if (modifier) h.keyboard.caps else h.key(".")
            val special = h.center(view, 4)
            val q = h.center("Q", 12)
            h.event(MotionEvent.ACTION_DOWN, special)
            h.event(down(1), special, q)
            h.event(up(1), special, q)
            assertEquals(listOf("q"), h.typed)
            assertTrue(view.isPressed)
            h.event(MotionEvent.ACTION_UP, special)
            if (modifier) assertTrue(h.actions.last() is KeyAction.CapsAction)
            else assertTrue(h.actions.last() is KeyAction.CommitAction)
        }
    }

    @Test fun sharedKeyContactsKeepExistingGestureHandling() {
        Harness().use { h ->
            val q0 = h.center("Q", 4)
            val q1 = h.center("Q", 12)
            val w = h.center("W", 2)
            h.event(MotionEvent.ACTION_DOWN, q0)
            h.event(down(1), q0, q1)
            h.event(down(2), q0, q1, w)
            h.event(up(2), q0, q1, w)
            assertEquals(listOf("w"), h.typed)
            h.event(up(1), q0, q1)
            h.event(MotionEvent.ACTION_UP, q0)
            assertEquals(listOf("w", "q", "q"), h.typed)
        }
    }

    @Test fun englishCapsAndSensitiveEditorsKeepReversePhysicalUpOrder() {
        for (scope in listOf("english", "capsOnce", "capsLock", "password", "noLearning", "unknown"))
            Harness().use { h ->
                when (scope) {
                    "english" -> h.keyboard.onInputMethodUpdate(english())
                    "capsOnce" -> h.keyboard.caps.performClick()
                    "capsLock" -> h.keyboard.caps.performLongClick()
                    "password" -> h.editor!!.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    "noLearning" -> h.editor!!.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    "unknown" -> h.editor = null
                }
                val q = h.center("Q", 4)
                val w = h.center("W", 12)
                h.event(MotionEvent.ACTION_DOWN, q)
                h.event(down(1), q, w)
                h.event(up(1), q, w)
                assertEquals(1, h.typed.size)
                h.event(MotionEvent.ACTION_UP, q)
                assertEquals(if (scope == "capsLock") listOf("W", "Q")
                    else if (scope == "capsOnce") listOf("W", "q") else listOf("w", "q"), h.typed)
            }
    }

    @Test fun openLongPressPopupIsNotPhantomReleased() {
        Harness().use { h ->
            val q = h.center("Q", 4)
            val w = h.center("W", 12)
            h.event(MotionEvent.ACTION_DOWN, q)
            h.waitFor(320)
            assertTrue(h.popups.any { it is PopupAction.ShowKeyboardAction })
            h.event(down(1), q, w)
            h.event(up(1), q, w)
            assertEquals(listOf("w"), h.typed)
            assertTrue(h.key("Q").isPressed)
            h.event(MotionEvent.ACTION_UP, q)
            assertEquals(listOf("w", "q"), h.typed)
        }
    }

    @Test fun phantomReleaseCancelsHoldJobsAndReleasesPressMotionImmediately() {
        Harness(motion = ThemePrefs.KeyMotionEffect.Press).use { h ->
            val q = h.center("Q", 4)
            val w = h.center("W", 12)
            h.event(MotionEvent.ACTION_DOWN, q)
            h.event(down(1), q, w)
            assertTrue("Older press is still active before the newer UP", h.key("Q").isPressed)
            val contacts = ReflectionHelpers.getField<Map<Int, Any>>(h.keyboard, "touchTargets")
            h.event(up(1), q, w)
            assertFalse("Older pointer is finalized", contacts.containsKey(4))
            assertEquals(listOf("q", "w"), h.typed)
            assertFalse(h.key("Q").isPressed)
            assertFalse(h.key("W").isPressed)
            val motions = ReflectionHelpers.getField<android.util.SparseArray<View>>(h.keyboard, "motionViews")
            assertEquals(0, motions.size())
            h.waitFor(360)
            assertFalse(h.popups.any { it is PopupAction.ShowKeyboardAction })
            h.event(MotionEvent.ACTION_UP, q)
            assertEquals(listOf("q", "w"), h.typed)
        }
    }

    @Test fun cancelClearsPendingContactsAndDoesNotCancelPreviouslyFinalizedLetters() {
        for (early in listOf(false, true)) Harness().use { h ->
            val q = h.center("Q", 4)
            val w = h.center("W", 12)
            h.event(MotionEvent.ACTION_DOWN, q)
            h.event(down(1), q, w)
            if (early) h.event(up(1), q, w)
            h.event(MotionEvent.ACTION_CANCEL, q)
            assertEquals(if (early) listOf("q", "w") else emptyList<String>(), h.typed)
            assertFalse(h.key("Q").isPressed)
            assertFalse(h.key("W").isPressed)
            val contacts = h.traces.single().getJSONArray("contacts")
            assertEquals(!early, contacts.getJSONObject(0).optBoolean("cancelled"))
            h.event(down(1), q, w)
            h.event(up(1), q, w)
            h.event(MotionEvent.ACTION_UP, q)
            assertEquals(if (early) listOf("q", "w") else emptyList<String>(), h.typed)
            h.waitFor(360)
            assertFalse(h.popups.any { it is PopupAction.ShowKeyboardAction })
            h.event(MotionEvent.ACTION_DOWN, q)
            h.event(MotionEvent.ACTION_UP, q)
            assertEquals(if (early) listOf("q", "w", "q") else listOf("q"), h.typed)
        }
    }

    @Test fun changedEditorLayoutAndWindowCancelEnabledPendingContacts() {
        for (change in listOf("editor", "editorPolicy", "layout", "detach", "hidden", "ime")) Harness().use { h ->
            val q = h.center("Q", 4)
            val w = h.center("W", 12)
            h.event(MotionEvent.ACTION_DOWN, q)
            h.event(down(1), q, w)
            when (change) {
                "editor" -> h.editor = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
                "editorPolicy" -> h.editor!!.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                "layout" -> h.key("Q").layout(1, 0, h.key("Q").right, h.key("Q").bottom)
                "detach" -> h.keyboard.onDetach()
                "hidden" -> h.keyboard.visibility = View.INVISIBLE
                "ime" -> h.keyboard.onInputMethodUpdate(chinese())
            }
            h.event(up(1), q, w)
            h.event(MotionEvent.ACTION_UP, q)
            assertTrue(change, h.typed.isEmpty())
            h.waitFor(360)
            assertFalse(change, h.popups.any { it is PopupAction.ShowKeyboardAction })
        }
    }

    @Test fun changingTheSwitchOnlyAffectsTheNextGesture() {
        for (initial in listOf(false, true)) Harness(initial).use { h ->
            val q = h.center("Q", 4)
            val w = h.center("W", 12)
            h.event(MotionEvent.ACTION_DOWN, q)
            AppPrefs.getInstance().keyboard.pinyinDownOrder.setValue(!initial)
            h.event(down(1), q, w)
            h.event(up(1), q, w)
            h.event(MotionEvent.ACTION_UP, q)
            assertEquals(if (initial) listOf("q", "w") else listOf("w", "q"), h.typed)
            h.typed.clear()
            h.event(MotionEvent.ACTION_DOWN, q)
            h.event(down(1), q, w)
            h.event(up(1), q, w)
            h.event(MotionEvent.ACTION_UP, q)
            assertEquals(if (initial) listOf("w", "q") else listOf("q", "w"), h.typed)
        }
    }

    @Test fun editorOrLayoutChangesDuringAnEarlyCallbackCancelTheRemainingReleases() {
        for (change in listOf("editor", "layout")) Harness().use { h ->
            val q = h.center("Q", 4)
            val w = h.center("W", 12)
            val e = h.center("E", 2)
            h.afterAction = { action ->
                if (action is KeyAction.FcitxKeyAction && action.act == "q") {
                    if (change == "editor") h.editor = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
                    else h.key("W").layout(1, 0, h.key("W").right, h.key("W").bottom)
                }
            }
            h.event(MotionEvent.ACTION_DOWN, q)
            h.event(down(1), q, w)
            h.event(down(2), q, w, e)
            h.event(up(2), q, w, e)
            assertEquals(change, listOf("q"), h.typed)
            h.event(up(1), q, w)
            h.event(MotionEvent.ACTION_UP, q)
            assertEquals(change, listOf("q"), h.typed)
        }
    }

    @Test fun aFreshDownInAnEarlyCallbackKeepsItsPressAndItsOwnTrace() {
        Harness(motion = ThemePrefs.KeyMotionEffect.Press).use { h ->
            val q = h.center("Q", 4)
            val w = h.center("W", 12)
            var restarted = false
            h.afterAction = { action ->
                if (!restarted && action is KeyAction.FcitxKeyAction && action.act == "q") {
                    restarted = true
                    h.event(MotionEvent.ACTION_DOWN, q)
                }
            }
            h.event(MotionEvent.ACTION_DOWN, q)
            h.event(down(1), q, w)
            h.event(up(1), q, w)
            assertEquals(listOf("q"), h.typed)
            assertTrue(h.key("Q").isPressed)
            val motions = ReflectionHelpers.getField<android.util.SparseArray<View>>(h.keyboard, "motionViews")
            assertEquals(1, motions.size())
            h.event(MotionEvent.ACTION_UP, q)
            assertEquals(listOf("q", "q"), h.typed)
            val contact = h.traces.single().getJSONArray("contacts").getJSONObject(0)
            assertEquals("physical_up", contact.getString("release_reason"))
            assertEquals(contact.getLong("up_t"), contact.getLong("release_t"))
            assertEquals(0L, contact.getLong("down_sequence"))
        }
    }

    @Test fun boundarySettledContactsKeepTheirNormalReleaseAndRemainOutsideCorrection() {
        Harness(correction = true).use { h ->
            AppPrefs.getInstance().keyboard.touchBoundarySettling.setValue(true)
            val f = h.bounds("F")
            val initial = Finger(4, f.right - 1f, f.exactCenterY())
            val settled = Finger(4, f.right + 8f, f.exactCenterY())
            val l = h.center("L", 12)
            h.event(MotionEvent.ACTION_DOWN, initial)
            h.waitFor(192)
            h.event(MotionEvent.ACTION_MOVE, settled)
            h.waitFor(48)
            h.event(MotionEvent.ACTION_MOVE, settled)
            h.event(down(1), settled, l)
            h.event(up(1), settled, l)
            assertEquals(listOf("l"), h.typed)
            h.event(MotionEvent.ACTION_UP, settled)
            assertEquals(listOf("l", "g"), h.typed)
            assertNull(h.actions.filterIsInstance<KeyAction.FcitxKeyAction>().last().pinyinTapEvidence)
            assertEquals("settle", h.traces.single().getJSONArray("decisions").getJSONObject(0).getString("mode"))
        }
    }

    @Test fun aDetachedGestureCannotAcceptAnotherPointerDownUntilAFreshDown() {
        Harness(motion = ThemePrefs.KeyMotionEffect.Press).use { h ->
            val q = h.center("Q", 4)
            val w = h.center("W", 12)
            h.event(MotionEvent.ACTION_DOWN, q)
            h.keyboard.onDetach()
            h.event(down(1), q, w)
            val motions = ReflectionHelpers.getField<android.util.SparseArray<View>>(h.keyboard, "motionViews")
            assertEquals(0, motions.size())
            h.event(up(1), q, w)
            h.event(MotionEvent.ACTION_UP, q)
            assertTrue(h.typed.isEmpty())
            h.event(MotionEvent.ACTION_DOWN, w)
            h.event(MotionEvent.ACTION_UP, w)
            assertEquals(listOf("w"), h.typed)
        }
    }
}
