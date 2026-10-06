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
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
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

    private class Harness(
        widthDp: Int = 360, overrides: String = "", numberRow: Boolean = false,
        boundarySettling: Boolean = false, pinyinCorrection: Boolean = false
    ) : AutoCloseable {
        private val restore = mutableListOf<() -> Unit>()
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        private val activity = controller.get()
        val density = activity.resources.displayMetrics.density
        private val expectedWidth = (widthDp * density).roundToInt()
        private val expectedHeight = ((if (numberRow) 320 else 260) * density).roundToInt()
        val keyboard: TextKeyboard
        val typed = mutableListOf<String>()
        val actions = mutableListOf<KeyAction.FcitxKeyAction>()
        val popups = mutableListOf<PopupAction>()
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
            setting(AppPrefs.getInstance().keyboard.touchBoundarySettling, boundarySettling)
            setting(AppPrefs.getInstance().keyboard.pinyinTouchCorrection, pinyinCorrection)
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
                if (action is KeyAction.FcitxKeyAction) { typed += action.act; actions += action }
            }
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

        fun key(label: String) = keys().first { (it.def as? KeyDef.Appearance.Text)?.displayText == label }
        // Touch geometry must not inherit the transient scale/rotation/translation
        // used to draw a pressed key. Keep the oracle independent of hit testing.
        fun bounds(label: String): Rect {
            var child: View = key(label)
            val bounds = Rect(0, 0, child.width, child.height)
            while (child !== keyboard) {
                bounds.offset(child.left, child.top)
                child = child.parent as View
            }
            return bounds
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
            dispatch(event)
        }

        private fun dispatch(event: MotionEvent) {
            try { assertTrue(keyboard.dispatchTouchEvent(event)) } finally { event.recycle() }
            waitFor(8)
            assertEquals("The tested touch viewport must stay at its requested width", expectedWidth, keyboard.width)
            assertEquals("The tested touch viewport must stay at its requested height", expectedHeight, keyboard.height)
        }

        fun waitFor(milliseconds: Long) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
        }

        /** Android may deliver several sensor samples in one MOVE dispatch. */
        fun moveWithHistory(vararg samples: Pair<Long, Finger>) {
            require(samples.isNotEmpty())
            val start = SystemClock.uptimeMillis()
            val (firstOffset, first) = samples.first()
            val event = MotionEvent.obtain(downTime, start + firstOffset, MotionEvent.ACTION_MOVE,
                first.x, first.y, 0)
            for ((offset, finger) in samples.drop(1)) {
                event.addBatch(start + offset, finger.x, finger.y, 1f, 1f, 0)
            }
            waitFor(samples.last().first)
            dispatch(event)
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

    private data class Neighbour(
        val from: String,
        val to: String,
        val edgeX: Float,
        val edgeY: Float,
        val dx: Float,
        val dy: Float
    ) {
        fun point(distance: Float, id: Int = 0) =
            Finger(id, edgeX + dx * distance, edgeY + dy * distance)

        override fun toString() = "$from→$to"
    }

    /** Enumerate shared borders and corners across staggered rows and resized keys. */
    private fun neighbours(h: Harness): List<Neighbour> {
        val labels = ('A'..'Z').map(Char::toString)
        val pairs = labels.flatMap { from ->
            val a = h.bounds(from)
            labels.filter { it != from }.mapNotNull { to ->
                val b = h.bounds(to)
                val overlapLeft = maxOf(a.left, b.left)
                val overlapRight = minOf(a.right, b.right)
                val overlapTop = maxOf(a.top, b.top)
                val overlapBottom = minOf(a.bottom, b.bottom)
                when {
                    overlapBottom > overlapTop && a.right == b.left ->
                        Neighbour(from, to, a.right.toFloat(), (overlapTop + overlapBottom) / 2f, 1f, 0f)
                    overlapBottom > overlapTop && a.left == b.right ->
                        Neighbour(from, to, a.left.toFloat(), (overlapTop + overlapBottom) / 2f, -1f, 0f)
                    overlapRight > overlapLeft && a.bottom == b.top ->
                        Neighbour(from, to, (overlapLeft + overlapRight) / 2f, a.bottom.toFloat(), 0f, 1f)
                    overlapRight > overlapLeft && a.top == b.bottom ->
                        Neighbour(from, to, (overlapLeft + overlapRight) / 2f, a.top.toFloat(), 0f, -1f)
                    a.right == b.left && a.bottom == b.top ->
                        Neighbour(from, to, a.right.toFloat(), a.bottom.toFloat(), 1f, 1f)
                    a.left == b.right && a.bottom == b.top ->
                        Neighbour(from, to, a.left.toFloat(), a.bottom.toFloat(), -1f, 1f)
                    a.right == b.left && a.top == b.bottom ->
                        Neighbour(from, to, a.right.toFloat(), a.top.toFloat(), 1f, -1f)
                    a.left == b.right && a.top == b.bottom ->
                        Neighbour(from, to, a.left.toFloat(), a.top.toFloat(), -1f, -1f)
                    else -> null
                }
            }
        }
        assertEquals("Every letter must participate in the neighbour regression", labels.toSet(), pairs.map { it.from }.toSet())
        assertTrue("The regression must include cross-row neighbours", pairs.any { it.dy != 0f })
        assertTrue("The regression must include same-row neighbours", pairs.any { it.dx != 0f })
        assertTrue("Every shared border must be tested in both directions", pairs.all { pair ->
            pairs.any { it.from == pair.to && it.to == pair.from }
        })
        return pairs
    }

    private val customWidths = "Text:4e=70;Text:42=150;Text:4c=70;Text:4b=150"

    private fun verifyBoundaryTaps() {
        for (width in listOf(320, 360, 480)) for (overrides in listOf("", customWidths)) {
            Harness(width, overrides, numberRow = width == 480).use { h ->
                for (pair in neighbours(h)) for (withMove in listOf(false, true)) {
                    h.typed.clear()
                    val down = pair.point(-h.density)
                    val drift = pair.point(6f * h.density)
                    h.event(MotionEvent.ACTION_DOWN, down)
                    if (withMove) h.event(MotionEvent.ACTION_MOVE, drift)
                    h.event(MotionEvent.ACTION_UP, drift)
                    assertEquals("A brief lift-off excursion must preserve $pair at width=$width, overrides=$overrides, MOVE=$withMove",
                        listOf(pair.from.lowercase()), h.typed)
                }
            }
        }
    }

    @Test fun allAdjacentLetterTapsSurviveLiftOffDriftAcrossWidths() = verifyBoundaryTaps()

    @Test @Config(qualifiers = "zh-rCN-w600dp-h900dp-xhdpi")
    fun allAdjacentLetterTapsUseDensityIndependentTolerance() = verifyBoundaryTaps()

    @Test fun everyLetterCenterKeepsItsIdentityAcrossLayouts() {
        for (width in listOf(320, 360, 480)) {
            Harness(width, if (width == 360) customWidths else "", numberRow = width == 480).use { h ->
                ('A'..'Z').forEach { h.tap(it.toString()) }
                assertEquals("abcdefghijklmnopqrstuvwxyz", h.typed.joinToString(""))
            }
        }
    }

    @Test fun aSingleFastMoveCannotTurnAnyCenteredTapIntoItsNeighbour() {
        for (width in listOf(320, 360, 480)) {
            Harness(width, if (width == 360) customWidths else "").use { h ->
                for (pair in neighbours(h)) {
                    h.typed.clear()
                    h.event(MotionEvent.ACTION_DOWN, h.center(pair.from))
                    h.event(MotionEvent.ACTION_MOVE, h.center(pair.to))
                    h.event(MotionEvent.ACTION_UP, h.center(pair.to))
                    assertEquals("A single fast sample must not turn $pair into a slide at width=$width",
                        listOf(pair.from.lowercase()), h.typed)
                }
            }
        }
    }

    @Test fun multipleFastSamplesStillCannotStartASlideWithoutDwell() {
        Harness().use { h ->
            for (pair in neighbours(h)) {
                h.typed.clear()
                h.event(MotionEvent.ACTION_DOWN, h.center(pair.from))
                repeat(3) { h.event(MotionEvent.ACTION_MOVE, h.center(pair.to)) }
                h.event(MotionEvent.ACTION_UP, h.center(pair.to))
                assertEquals("Sample count alone must not turn a brief $pair excursion into a slide",
                    listOf(pair.from.lowercase()), h.typed)
            }
        }
    }

    @Test fun rapidBoundaryDriftKeepsEveryNeighbourIdentityEvenWhenSlowAdjustmentIsEnabled() {
        for (width in listOf(320, 360, 480)) {
            Harness(width, if (width == 360) customWidths else "", boundarySettling = true).use { h ->
                for (pair in neighbours(h)) {
                    h.typed.clear()
                    val drift = pair.point(6f * h.density)
                    h.event(MotionEvent.ACTION_DOWN, pair.point(-h.density))
                    h.event(MotionEvent.ACTION_MOVE, drift)
                    h.waitFor(48)
                    h.event(MotionEvent.ACTION_MOVE, drift)
                    assertTrue("Rapid drift must leave the original $pair pressed", h.key(pair.from).isPressed)
                    h.event(MotionEvent.ACTION_UP, drift)
                    assertEquals("A sustained but rapid boundary excursion must preserve $pair at width=$width",
                        listOf(pair.from.lowercase()), h.typed)
                }
            }
        }
    }

    @Test fun aDeliberateSlowHoldCanRecoverEveryLowConfidenceNeighbourBoundaryWhenEnabled() {
        for (width in listOf(320, 360, 480)) {
            Harness(width, if (width == 360) customWidths else "", boundarySettling = true).use { h ->
                for (pair in neighbours(h)) {
                    val target = h.bounds(pair.to)
                    // An overlap may be only a sliver after custom resizing. Such a
                    // corner is not a confident interior sample: settle toward the
                    // target centre as a real finger would, keeping movement short.
                    val inset = minOf(8f * h.density, minOf(target.width(), target.height()) * .28f)
                    val interior = pair.point(inset).let {
                        it.copy(x = it.x.coerceIn(target.left + inset, target.right - inset),
                            y = it.y.coerceIn(target.top + inset, target.bottom - inset))
                    }
                    h.typed.clear()
                    h.event(MotionEvent.ACTION_DOWN, pair.point(-h.density))
                    assertTrue("The uncertain initial contact must actually land on ${pair.from}", h.key(pair.from).isPressed)
                    h.waitFor(192)
                    h.event(MotionEvent.ACTION_MOVE, interior)
                    h.waitFor(48)
                    h.event(MotionEvent.ACTION_MOVE, interior)
                    h.event(MotionEvent.ACTION_UP, interior)
                    assertEquals("An enabled slow hold and stable landing may adjust $pair at width=$width",
                        listOf(pair.to.lowercase()), h.typed)
                }
            }
        }
    }

    @Test fun aBoundaryRecoveryDoesNotEnableFreeSlideSelection() {
        Harness(boundarySettling = true).use { h ->
            val pair = neighbours(h).first { it.from == "F" && it.to == "G" }
            val settled = pair.point(8f * h.density)
            h.event(MotionEvent.ACTION_DOWN, pair.point(-h.density))
            h.waitFor(192)
            h.event(MotionEvent.ACTION_MOVE, settled)
            h.waitFor(48)
            h.event(MotionEvent.ACTION_MOVE, settled)
            h.event(MotionEvent.ACTION_UP, h.center("H"))
            assertEquals("UP drift after a corrected landing must keep that corrected key", listOf("g"), h.typed)
        }
    }

    @Test fun samplesBeforeTheSlowHoldThresholdCannotCountTowardAdjustmentDwell() {
        for (batched in listOf(false, true)) Harness(boundarySettling = true).use { h ->
            val pair = neighbours(h).first { it.from == "F" && it.to == "G" }
            val interior = pair.point(8f * h.density)
            h.event(MotionEvent.ACTION_DOWN, pair.point(-h.density))
            h.waitFor(152)
            if (batched) {
                h.moveWithHistory(0L to interior, 32L to interior, 40L to interior)
            } else {
                h.event(MotionEvent.ACTION_MOVE, interior)
                h.waitFor(24)
                h.event(MotionEvent.ACTION_MOVE, interior)
                h.event(MotionEvent.ACTION_MOVE, interior)
            }
            h.event(MotionEvent.ACTION_UP, interior)
            assertEquals("Only the sample at 200ms is eligible; early drift is not slow-adjustment evidence, batched=$batched",
                listOf("f"), h.typed)
        }
    }

    @Test fun aSlowUpWithoutMoveEvidenceStillCannotRetargetTheDownKey() {
        Harness(boundarySettling = true).use { h ->
            val pair = neighbours(h).first { it.from == "F" && it.to == "G" }
            h.event(MotionEvent.ACTION_DOWN, pair.point(-h.density))
            h.waitFor(224)
            h.event(MotionEvent.ACTION_UP, pair.point(8f * h.density))
            assertEquals("Elapsed hold time alone must never make UP start boundary adjustment", listOf("f"), h.typed)
        }
    }

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
            assertTrue("One deep sample still cannot start a slide", h.key("G").isPressed)
            h.waitFor(64)
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

    @Test fun batchedHistoricalSamplesCanEstablishTheSameDeliberateSlide() {
        Harness().use { h ->
            h.event(MotionEvent.ACTION_DOWN, h.center("G"))
            h.moveWithHistory(0L to h.center("H"), 32L to h.center("H"), 64L to h.center("H"))
            assertTrue("Batching must not hide a sustained landing", h.key("H").isPressed)
            h.event(MotionEvent.ACTION_UP, h.center("J"))
            assertEquals(listOf("j"), h.typed)
        }
    }

    @Test fun aSlideConfirmedInHistoryContinuesIntoTheCurrentDifferentKey() {
        for (batched in listOf(false, true)) Harness().use { h ->
            h.event(MotionEvent.ACTION_DOWN, h.center("G"))
            if (batched) {
                h.moveWithHistory(0L to h.center("H"), 64L to h.center("H"), 72L to h.center("J"))
            } else {
                h.event(MotionEvent.ACTION_MOVE, h.center("H"))
                h.waitFor(56)
                h.event(MotionEvent.ACTION_MOVE, h.center("H"))
                h.event(MotionEvent.ACTION_MOVE, h.center("J"))
            }
            assertTrue("An already confirmed slide must reach J, batched=$batched", h.key("J").isPressed)
            assertTrue("Intermediate samples must not commit letters", h.typed.isEmpty())
            h.event(MotionEvent.ACTION_UP, h.center("J"))
            assertEquals("The same timed trajectory must commit J regardless of batching=$batched", listOf("j"), h.typed)
        }
    }

    @Test fun aHistoricalBoundaryRecoverySurvivesTheWindowAndFinalLiftDrift() {
        for (batched in listOf(false, true)) for (liftOn in listOf("G", "H")) Harness(boundarySettling = true).use { h ->
            val pair = neighbours(h).first { it.from == "F" && it.to == "G" }
            val settled = pair.point(8f * h.density)
            h.event(MotionEvent.ACTION_DOWN, pair.point(-h.density))
            h.waitFor(192)
            if (batched) {
                h.moveWithHistory(0L to settled, 48L to settled, 88L to settled)
            } else {
                h.event(MotionEvent.ACTION_MOVE, settled)
                h.waitFor(40)
                h.event(MotionEvent.ACTION_MOVE, settled)
                h.waitFor(32)
                h.event(MotionEvent.ACTION_MOVE, settled)
            }
            assertTrue("The slow landing was confirmed before the 280ms window closed, batched=$batched", h.key("G").isPressed)
            h.event(MotionEvent.ACTION_UP, h.center(liftOn))
            assertEquals("A confirmed correction must neither expire nor become a slide, batched=$batched, UP=$liftOn",
                listOf("g"), h.typed)
        }
    }

    @Test fun waitingToConfirmASlideCannotTriggerThePreviousKeysLongPress() {
        Harness().use { h ->
            h.event(MotionEvent.ACTION_DOWN, h.center("G"))
            h.waitFor(272)
            h.event(MotionEvent.ACTION_MOVE, h.center("H"))
            h.waitFor(56)
            h.event(MotionEvent.ACTION_MOVE, h.center("H"))
            h.event(MotionEvent.ACTION_UP, h.center("H"))
            assertEquals("The intended slide must still type H across the old 300ms hold deadline", listOf("h"), h.typed)
            assertTrue("Waiting for slide evidence must not open G's long-press popup",
                h.popups.none { it is PopupAction.ShowKeyboardAction || it is PopupAction.ShowMenuAction })
        }
    }

    @Test fun returningFromAnUnconfirmedNeighbourKeepsTheTapWithoutOpeningAHoldMenu() {
        Harness().use { h ->
            h.event(MotionEvent.ACTION_DOWN, h.center("G"))
            h.waitFor(272)
            h.event(MotionEvent.ACTION_MOVE, h.center("H"))
            h.waitFor(16)
            h.event(MotionEvent.ACTION_MOVE, h.center("G"))
            h.waitFor(64)
            h.event(MotionEvent.ACTION_UP, h.center("G"))
            assertEquals("An abandoned slide candidate must retain the original click", listOf("g"), h.typed)
            assertTrue("Leaving the key must cancel its pending hold without consuming the tap",
                h.popups.none { it is PopupAction.ShowKeyboardAction || it is PopupAction.ShowMenuAction })
        }
    }

    @Test fun aStationaryLetterStillOpensItsLongPressKeyboard() {
        Harness().use { h ->
            h.event(MotionEvent.ACTION_DOWN, h.center("G"))
            h.waitFor(320)
            assertTrue("The movement guard must preserve ordinary letter long-press menus",
                h.popups.any { it is PopupAction.ShowKeyboardAction && it.viewId == h.key("G").id })
            h.event(MotionEvent.ACTION_CANCEL, h.center("G"))
            assertTrue(h.typed.isEmpty())
        }
    }

    @Test fun anInterruptedHistoricalExcursionCannotAccumulateNeighbourDwell() {
        Harness().use { h ->
            h.event(MotionEvent.ACTION_DOWN, h.center("G"))
            h.moveWithHistory(0L to h.center("H"), 24L to h.center("G"), 64L to h.center("H"))
            h.event(MotionEvent.ACTION_UP, h.center("H"))
            assertEquals("Dwell restarts when the finger leaves the proposed neighbour", listOf("g"), h.typed)
        }
    }

    @Test fun visualScaleRotationAndTranslationNeverChangeTheLetterTouchCells() {
        Harness().use { h ->
            for (label in ('A'..'Z').map(Char::toString)) {
                val view = h.key(label)
                val rect = h.bounds(label)
                view.scaleX = .86f
                view.scaleY = .86f
                view.rotation = 7f
                view.translationY = -3f * h.density
                // Near each untransformed cell edge, including the area vacated
                // by shrinking a cap. Both scale and rotated overlap used to
                // affect getHitRect and could route this contact to a neighbour.
                val edgePoints = listOf(
                    Finger(0, rect.left + h.density, rect.exactCenterY()),
                    Finger(0, rect.right - h.density, rect.exactCenterY()),
                    Finger(0, rect.exactCenterX(), rect.top + h.density),
                    Finger(0, rect.exactCenterX(), rect.bottom - h.density)
                )
                for (point in edgePoints) {
                    h.typed.clear()
                    h.event(MotionEvent.ACTION_DOWN, point)
                    h.event(MotionEvent.ACTION_UP, point)
                    assertEquals("The animated $label must retain all four original cell edges", listOf(label.lowercase()), h.typed)
                }
                view.scaleX = 1f
                view.scaleY = 1f
                view.rotation = 0f
                view.translationY = 0f
            }
        }
    }

    @Test fun simultaneousThumbsDoNotShareTheirSlideIntent() {
        Harness().use { h ->
            val l = h.bounds("L")
            val second = Finger(9, l.left + h.density, l.exactCenterY())
            val drift = second.copy(x = l.left - 6f * h.density)
            h.event(MotionEvent.ACTION_DOWN, h.center("G", 3))
            h.event(MotionEvent.ACTION_MOVE, h.center("T", 3))
            h.waitFor(64)
            h.event(MotionEvent.ACTION_MOVE, h.center("T", 3))
            assertTrue(h.key("T").isPressed)
            h.event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), h.center("T", 3), second)
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

    @Test fun pinyinEvidenceKeepsEachThumbsOriginalDownCoordinatesDespiteReverseReleaseAndDrift() {
        Harness(pinyinCorrection = true).use { h ->
            ReflectionHelpers.setField(h.keyboard, "chineseMode", true)
            val first = h.center("N", 3)
            val second = h.center("I", 9)
            h.event(MotionEvent.ACTION_DOWN, first)
            h.event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), first, second)
            val drift = h.center("B", 3)
            h.event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), drift, second)
            h.event(MotionEvent.ACTION_UP, drift)
            assertEquals(listOf("i", "n"), h.typed)
            val i = h.actions[0].pinyinTapEvidence!!
            val n = h.actions[1].pinyinTapEvidence!!
            assertEquals(9, i.pointerId)
            assertEquals(3, n.pointerId)
            assertEquals(second.x, i.tap.downX, 0f)
            assertEquals(second.y, i.tap.downY, 0f)
            assertEquals(first.x, n.tap.downX, 0f)
            assertEquals(first.y, n.tap.downY, 0f)
            assertEquals('i', i.tap.original)
            assertEquals('n', n.tap.original)
            assertEquals(26, n.cells.size)
        }
    }

    @Test fun explicitSlideAndEnglishCapsAndDisabledCorrectionDoNotSupplyPinyinEvidence() {
        Harness(pinyinCorrection = true).use { h ->
            ReflectionHelpers.setField(h.keyboard, "chineseMode", true)
            h.event(MotionEvent.ACTION_DOWN, h.center("G"))
            h.event(MotionEvent.ACTION_MOVE, h.center("H"))
            h.waitFor(64)
            h.event(MotionEvent.ACTION_MOVE, h.center("H"))
            h.event(MotionEvent.ACTION_UP, h.center("H"))
            assertEquals(listOf("h"), h.typed)
            assertNull(h.actions.last().pinyinTapEvidence)
            ReflectionHelpers.setField(h.keyboard, "englishMode", true)
            h.tap("N")
            assertNull(h.actions.last().pinyinTapEvidence)
            ReflectionHelpers.setField(h.keyboard, "englishMode", false)
            ReflectionHelpers.setField(h.keyboard, "capsState", TextKeyboard.CapsState.Lock)
            h.tap("N")
            assertEquals("N", h.actions.last().act)
            assertNull(h.actions.last().pinyinTapEvidence)
        }
        Harness(pinyinCorrection = false).use { h ->
            ReflectionHelpers.setField(h.keyboard, "chineseMode", true)
            h.tap("N")
            assertNull(h.actions.last().pinyinTapEvidence)
        }
    }
}
