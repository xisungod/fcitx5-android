/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
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
import kotlin.math.roundToInt

/** Feed real contacts to the keyboard and inspect raw letters before Rime correction. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w600dp-h900dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TapAccuracyTest {
    private var previousApplication: Any? = null

    @Before fun prepare() {
        val context = RuntimeEnvironment.getApplication()
        val field = FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }
        previousApplication = field.get(null)
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, context))
        field.set(null, app)
        AppPrefs.init(context.getSharedPreferences("tap-accuracy", Context.MODE_PRIVATE))
    }

    @After fun restore() {
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true; set(null, previousApplication)
        }
    }

    private data class Finger(val id: Int, val x: Float, val y: Float)

    private class Harness(widthDp: Int = 360, overrides: String = "", numberRow: Boolean = false) : AutoCloseable {
        private val restore = mutableListOf<() -> Unit>()
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        private val activity = controller.get()
        val density = activity.resources.displayMetrics.density
        private val expectedWidth = (widthDp * density).roundToInt()
        private val expectedHeight = ((if (numberRow) 320 else 260) * density).roundToInt()
        val keyboard: TextKeyboard
        val typed = mutableListOf<String>()
        private var downTime = 0L

        init {
            val p = ThemeManager.prefs
            setting(p.pressEffect, false)
            setting(p.idleBreathing, false)
            setting(p.keyMotionEffect, ThemePrefs.KeyMotionEffect.Off)
            setting(p.portraitNumberRow, numberRow)
            setting(p.keyWidthOverrides, overrides)
            setting(AppPrefs.getInstance().keyboard.popupOnKeyPress, false)
            setting(AppPrefs.getInstance().keyboard.expandKeypressArea, true)
            setting(AppPrefs.getInstance().keyboard.longPressDelay, 300)
            activity.setTheme(R.style.Theme_InputViewTheme)
            keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
            // The activity may relayout on any looper tick. Keep the actual
            // touch viewport fixed while time advances between MotionEvents.
            activity.setContentView(FrameLayout(activity).apply {
                addView(keyboard, FrameLayout.LayoutParams(expectedWidth, expectedHeight))
            })
            controller.visible()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(expectedWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(expectedHeight, View.MeasureSpec.EXACTLY))
            keyboard.layout(0, 0, expectedWidth, expectedHeight)
            keyboard.keyActionListener = KeyActionListener { action, _ ->
                if (action is KeyAction.FcitxKeyAction) typed += action.act
            }
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

        fun key(label: String) = keys().first { (it.def as? KeyDef.Appearance.Text)?.displayText == label }
        fun bounds(label: String) = Rect().also {
            key(label).getDrawingRect(it); keyboard.offsetDescendantRectToMyCoords(key(label), it)
        }
        fun center(label: String, id: Int = 0) = bounds(label).let { Finger(id, it.exactCenterX(), it.exactCenterY()) }

        fun event(action: Int, vararg fingers: Finger) {
            if ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            val properties = fingers.map { MotionEvent.PointerProperties().apply {
                id = it.id; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }.toTypedArray()
            val coordinates = fingers.map { MotionEvent.PointerCoords().apply {
                x = it.x; y = it.y; pressure = 1f; size = 1f
            } }.toTypedArray()
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, fingers.size,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(keyboard.dispatchTouchEvent(event)) } finally { event.recycle() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(8))
            assertEquals("The tested touch viewport must stay at its requested width", expectedWidth, keyboard.width)
            assertEquals("The tested touch viewport must stay at its requested height", expectedHeight, keyboard.height)
        }

        fun tap(label: String) { event(MotionEvent.ACTION_DOWN, center(label)); event(MotionEvent.ACTION_UP, center(label)) }

        fun driftingTap(label: String, left: Boolean, move: Boolean, excursionDp: Float = 6f) {
            val rect = bounds(label)
            val edge = if (left) rect.left.toFloat() else rect.right.toFloat()
            val sign = if (left) -1f else 1f
            val down = Finger(0, edge - sign * density, rect.exactCenterY())
            val drift = down.copy(x = edge + sign * excursionDp * density)
            event(MotionEvent.ACTION_DOWN, down)
            if (move) {
                event(MotionEvent.ACTION_MOVE, drift)
                assertTrue("Small drift must keep the original $label contact pressed", key(label).isPressed)
            }
            event(MotionEvent.ACTION_UP, drift)
            assertFalse(key(label).isPressed)
        }

        override fun close() {
            keyboard.onDetach()
            controller.pause().stop().destroy()
            restore.asReversed().forEach { it() }
        }
    }

    private fun verifyBoundaryTaps() {
        for (width in listOf(320, 360, 480)) for (overrides in listOf("", "Text:4e=70;Text:42=150;Text:4c=70;Text:4b=150")) {
            Harness(width, overrides, numberRow = width == 480).use { h ->
                for (withMove in listOf(false, true)) {
                    h.typed.clear()
                    listOf("S", "H", "A").forEach(h::tap)
                    h.driftingTap("N", left = true, move = withMove)
                    h.tap("G")
                    assertEquals("The exact shang sequence must reach the engine at width=$width, overrides=$overrides, MOVE=$withMove",
                        "shang", h.typed.joinToString(""))
                    h.typed.clear()
                    h.driftingTap("N", left = true, move = withMove)
                    h.driftingTap("B", left = false, move = withMove)
                    h.driftingTap("L", left = true, move = withMove)
                    h.driftingTap("K", left = false, move = withMove)
                    assertEquals(listOf("n", "b", "l", "k"), h.typed)
                }
            }
        }
    }

    @Test fun neighbouringLetterTapsSurviveLiftOffDriftAcrossWidths() = verifyBoundaryTaps()

    @Test @Config(qualifiers = "zh-rCN-w600dp-h900dp-xhdpi")
    fun neighbouringLetterTapsUseDensityIndependentTolerance() = verifyBoundaryTaps()

    @Test fun driftBeyondTheChildSlopDoesNotCancelTheOriginalTap() {
        Harness().use { h ->
            h.driftingTap("N", left = true, move = true, excursionDp = 10f)
            assertEquals(listOf("n"), h.typed)
        }
    }

    @Test fun deliberateMoveEntersTheNeighbourAndContinuesToFollowTheFinger() {
        Harness().use { h ->
            h.event(MotionEvent.ACTION_DOWN, h.center("G"))
            val next = h.bounds("H")
            h.event(MotionEvent.ACTION_MOVE, Finger(0, next.left + 5f * h.density, next.exactCenterY()))
            assertTrue("A shallow boundary crossing is still a tap", h.key("G").isPressed)
            assertFalse(h.key("H").isPressed)
            h.event(MotionEvent.ACTION_MOVE, h.center("H"))
            assertFalse(h.key("G").isPressed)
            assertTrue(h.key("H").isPressed)
            h.event(MotionEvent.ACTION_MOVE, h.center("J"))
            assertTrue(h.typed.isEmpty())
            assertTrue(h.key("J").isPressed)
            h.event(MotionEvent.ACTION_UP, h.center("K"))
            assertEquals("An established slide resolves its final batched sample once", listOf("k"), h.typed)
        }
    }

    @Test fun simultaneousThumbsDoNotShareTheirSlideIntent() {
        Harness().use { h ->
            val l = h.bounds("L")
            val second = Finger(9, l.left + h.density, l.exactCenterY())
            val drift = second.copy(x = l.left - 6f * h.density)
            h.event(MotionEvent.ACTION_DOWN, h.center("G", 3))
            h.event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), h.center("G", 3), second)
            h.event(MotionEvent.ACTION_MOVE, h.center("T", 3), drift)
            assertTrue(h.key("T").isPressed)
            assertTrue(h.key("L").isPressed)
            h.event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), h.center("T", 3), drift)
            h.event(MotionEvent.ACTION_UP, h.center("T", 3))
            assertEquals(listOf("l", "t"), h.typed)
        }
    }

    @Test fun cancellationAndLeavingTheKeyboardNeverCommitAPendingTap() {
        Harness().use { h ->
            h.event(MotionEvent.ACTION_DOWN, h.center("N"))
            h.event(MotionEvent.ACTION_CANCEL, h.center("B"))
            h.event(MotionEvent.ACTION_DOWN, h.center("L"))
            h.event(MotionEvent.ACTION_MOVE, Finger(0, -40f, -40f))
            h.event(MotionEvent.ACTION_UP, Finger(0, -40f, -40f))
            assertTrue(h.typed.isEmpty())
            h.tap("N")
            assertEquals(listOf("n"), h.typed)
        }
    }
}
