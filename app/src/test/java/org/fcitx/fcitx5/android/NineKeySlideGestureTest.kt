/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.*
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
import java.io.File
import java.time.Duration

/** Actual key views and MotionEvents: a slide selects the final cell, never types its path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NineKeySlideGestureTest {
    private var previousApplication: Any? = null

    @Before fun prepare() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val context = RuntimeEnvironment.getApplication()
        val field = FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }
        previousApplication = field.get(null)
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, context))
        field.set(null, app)
        AppPrefs.init(context.getSharedPreferences("nine-key-slide-host", Context.MODE_PRIVATE))
    }

    @After fun restore() {
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true; set(null, previousApplication)
        }
        ShadowChoreographer.setPaused(false)
    }

    private enum class Kind { Number, T9 }

    private class Harness(val kind: Kind) : AutoCloseable {
        private val restorePreferences = mutableListOf<() -> Unit>()
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).also {
            it.get().setTheme(R.style.Theme_InputViewTheme)
        }.setup()
        private val activity = controller.get()
        val keyboard: BaseKeyboard
        val actions = mutableListOf<KeyAction>()
        private var downTime = 0L

        init {
            val p = ThemeManager.prefs
            setting(p.keyWidthOverrides, "")
            setting(p.rippleShape, ThemePrefs.RippleShape.Sam)
            setting(p.pressEffect, true)
            setting(p.idleBreathing, false)
            setting(p.effectsFollowSystemAnimation, false)
            setting(p.keyMotionEffect, ThemePrefs.KeyMotionEffect.Press)
            setting(p.pressMotionAmplitude, 8)
            setting(p.pressMotionDuration, 180)
            setting(p.reboundMotionAmplitude, 3)
            setting(p.reboundMotionDuration, 1000)
            setting(p.pressColorMode, ThemePrefs.PressColorMode.Single)
            setting(p.pressSingleColor, Color.rgb(0, 240, 255))
            setting(p.pressGlowBrightness, 100)
            setting(p.pressGlowReach, 100)
            setting(p.keyColorStyle, ThemePrefs.KeyColorStyle.Fill)
            val preferences = AppPrefs.getInstance()
            setting(preferences.advanced.disableAnimation, false)
            setting(preferences.advanced.vivoKeypressWorkaround, false)
            setting(preferences.keyboard.longPressDelay, 300)
            setting(preferences.keyboard.hapticStrength, 0)
            setting(preferences.keyboard.popupOnKeyPress, false)
            setting(preferences.keyboard.spaceKeyLongPressBehavior, SpaceLongPressBehavior.MoveCursor)
            setting(preferences.keyboard.spaceSwipeMoveCursor, false)
            keyboard = when (kind) {
                Kind.Number -> NumberKeyboard(activity, ThemePreset.XuancaiBlackV09)
                Kind.T9 -> PinyinT9Keyboard(activity, ThemePreset.XuancaiBlackV09)
            }
            keyboard.keyActionListener = KeyActionListener { action, _ -> actions += action }
            activity.setContentView(FrameLayout(activity).apply {
                addView(keyboard, FrameLayout.LayoutParams(360, 288))
            })
            controller.visible()
            advance(32)
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(288, View.MeasureSpec.EXACTLY))
            keyboard.layout(0, 0, 360, 288)
            assertTrue(keyboard.isAttachedToWindow && keyboard.isShown)
        }

        private fun <T : Any> setting(pref: ManagedPreference<T>, value: T) {
            val existed = pref.sharedPreferences.contains(pref.key)
            val old = pref.getValue()
            restorePreferences += {
                if (existed) pref.setValue(old) else pref.sharedPreferences.edit().remove(pref.key).commit()
                Unit
            }
            pref.setValue(value)
        }

        private fun keys(view: View = keyboard): List<KeyView> = when (view) {
            is KeyView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { keys(view.getChildAt(it)) }
            else -> emptyList()
        }

        fun digit(number: Int): KeyView = if (kind == Kind.T9) keyboard.findViewWithTag("t9-key-$number")
        else keys().first { (it.def as? KeyDef.Appearance.Text)?.displayText == number.toString() }

        fun control(id: Int): KeyView = keyboard.findViewById(id)
        fun depth(key: KeyView): KeyPressDepth = ReflectionHelpers.getField(key, "pressDepth")
        fun bounds(key: View) = Rect().also {
            key.getDrawingRect(it); keyboard.offsetDescendantRectToMyCoords(key, it)
        }

        fun event(action: Int, key: View) {
            val rect = bounds(key)
            event(action, rect.exactCenterX(), rect.exactCenterY())
        }

        fun event(action: Int, x: Float, y: Float) {
            if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            try { assertTrue("$kind must handle the active pointer", keyboard.dispatchTouchEvent(event)) }
            finally { event.recycle() }
        }

        fun advance(ms: Long) {
            var remaining = ms
            while (remaining > 0) {
                val step = minOf(16L, remaining)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
                remaining -= step
            }
        }

        fun expectedDigit(number: Int): KeyAction = if (kind == Kind.T9)
            KeyAction.FcitxKeyAction(number.toString())
        else KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_KP_0 + number), NumLockState)

        fun render(): Bitmap = Bitmap.createBitmap(360, 288, Bitmap.Config.ARGB_8888).also {
            keyboard.draw(Canvas(it).apply { drawColor(Color.BLACK) })
        }

        fun colouredPixels(image: Bitmap, key: View): Int {
            val rect = bounds(key)
            var count = 0
            for (y in rect.top + 8 until rect.bottom - 8) for (x in rect.left + 8 until rect.right - 8) {
                val pixel = image.getPixel(x, y)
                val bright = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                val dark = minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                if (bright >= 30 && bright - dark >= 16) count++
            }
            return count
        }

        override fun close() {
            keyboard.onDetach()
            controller.pause().stop().destroy()
            restorePreferences.asReversed().forEach { it() }
        }
    }

    @Test fun bothNineKeyLayoutsFollowTwoSlidesVisuallyAndCommitOnlyTheLastSix() {
        for (kind in Kind.entries) Harness(kind).use { h ->
            val two = h.digit(2)
            val three = h.digit(3)
            val six = h.digit(6)
            h.render().also { assertEquals(0, h.colouredPixels(it, two)); it.recycle() }
            h.event(MotionEvent.ACTION_DOWN, two)
            h.advance(80)
            assertTrue(h.depth(two).currentLift() < 0f)
            val beforeRelease = h.depth(two).currentLift()
            h.event(MotionEvent.ACTION_MOVE, three)
            assertFalse("The old key must release as soon as its neighbour takes over", two.isPressed)
            assertTrue("The new key must become held before lifting the finger", three.isPressed)
            assertEquals("A slide cannot snap the previous key back to rest", beforeRelease,
                h.depth(two).currentLift(), 0.0001f)
            h.advance(144)
            assertTrue("The former key must rebound independently", h.depth(two).currentLift() > 0f)
            assertTrue("The new key must compress while held", h.depth(three).currentLift() < 0f)
            assertTrue("The previous key keeps a natural colour tail", two.floatingFaceOpacity() > 0f)
            assertEquals(1f, three.floatingFaceOpacity(), 0f)
            val lift = h.depth(three).currentLift()
            repeat(3) { h.event(MotionEvent.ACTION_MOVE, three) }
            assertEquals("MOVEs within one cell must not restart its press", lift, h.depth(three).currentLift(), 0f)
            assertTrue("Crossed cells may not emit intermediate input", h.actions.isEmpty())
            h.event(MotionEvent.ACTION_MOVE, six)
            h.advance(64)
            assertFalse(three.isPressed)
            assertTrue(six.isPressed)
            assertTrue(h.depth(six).currentLift() < 0f)
            assertTrue(three.floatingFaceOpacity() > 0f)
            val image = h.render()
            try {
                assertTrue("The previous cell must still have visible native-rendered colour", h.colouredPixels(image, three) > 20)
                assertTrue("Colour must reach the currently held cell", h.colouredPixels(image, six) > 20)
                val output = File("build/outputs/effect-checks/nine-key-slide-${kind.name.lowercase()}.png")
                output.parentFile!!.mkdirs()
                output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { image.recycle() }
            assertTrue(h.actions.isEmpty())
            h.event(MotionEvent.ACTION_UP, six)
            assertEquals(listOf(h.expectedDigit(6)), h.actions)
            assertFalse(six.isPressed)
        }
    }

    @Test fun aBatchedFinalUpSelectsItsCellWhileCancellationAndOutsideReleaseCommitNothing() {
        for (kind in Kind.entries) Harness(kind).use { h ->
            h.event(MotionEvent.ACTION_DOWN, h.digit(2))
            h.advance(32)
            h.event(MotionEvent.ACTION_UP, h.digit(3))
            assertEquals("An UP without a preceding MOVE must resolve the final cell", listOf(h.expectedDigit(3)), h.actions)
            h.actions.clear()
            h.event(MotionEvent.ACTION_DOWN, h.digit(2))
            h.event(MotionEvent.ACTION_MOVE, h.digit(6))
            h.event(MotionEvent.ACTION_CANCEL, h.digit(6))
            h.advance(400)
            assertTrue(h.actions.isEmpty())
            h.event(MotionEvent.ACTION_DOWN, h.digit(2))
            h.event(MotionEvent.ACTION_MOVE, -40f, -40f)
            h.event(MotionEvent.ACTION_UP, -40f, -40f)
            assertTrue("Leaving the keyboard must cancel the pending digit", h.actions.isEmpty())
            h.event(MotionEvent.ACTION_DOWN, h.digit(2))
            h.event(MotionEvent.ACTION_UP, h.control(R.id.button_return))
            assertTrue("Sliding from a digit into Return must not send a digit or Return", h.actions.isEmpty())
        }
    }

    @Test fun aT9LongPressAlreadyCommittedAsLiteralTwoCannotTurnIntoAnotherShortTap() {
        Harness(Kind.T9).use { h ->
            h.event(MotionEvent.ACTION_DOWN, h.digit(2))
            h.advance(340)
            assertEquals(listOf(KeyAction.CommitAction("2")), h.actions)
            h.event(MotionEvent.ACTION_MOVE, h.digit(3))
            h.advance(32)
            assertFalse("A consumed long press must not start a fresh press on its neighbour", h.digit(3).isPressed)
            h.event(MotionEvent.ACTION_UP, h.digit(3))
            h.advance(400)
            assertEquals(listOf(KeyAction.CommitAction("2")), h.actions)
        }
    }

    @Test fun spaceTrackpadDeleteRepeatAndReturnLongPressRetainTheirOwnGesturesAcrossCells() {
        val arrows = setOf(FcitxKeyMapping.FcitxKey_Up, FcitxKeyMapping.FcitxKey_Down,
            FcitxKeyMapping.FcitxKey_Left, FcitxKeyMapping.FcitxKey_Right).map { KeyAction.SymAction(KeySym(it)) }.toSet()
        val delete = KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace))
        for (kind in Kind.entries) Harness(kind).use { h ->
            h.event(MotionEvent.ACTION_DOWN, h.control(R.id.button_space))
            h.advance(340)
            h.event(MotionEvent.ACTION_MOVE, h.digit(6))
            h.event(MotionEvent.ACTION_UP, h.digit(6))
            assertEquals(KeyAction.SpaceLongPressAction, h.actions.first())
            assertTrue("Crossing a numeric cell remains a cursor movement", h.actions.size > 1)
            assertTrue(h.actions.drop(1).all { it in arrows })
            h.actions.clear()
            h.event(MotionEvent.ACTION_DOWN, h.control(R.id.button_backspace))
            h.advance(340)
            assertTrue("Holding Delete must still repeat", h.actions.isNotEmpty())
            assertTrue(h.actions.all { it == delete })
            h.event(MotionEvent.ACTION_MOVE, h.digit(6))
            val deleted = h.actions.toList()
            h.event(MotionEvent.ACTION_UP, h.digit(6))
            h.advance(400)
            assertEquals("Moving away ends Delete with its normal selection cleanup, without selecting a digit",
                deleted + KeyAction.DeleteSelectionAction(0), h.actions)
            h.actions.clear()
            h.event(MotionEvent.ACTION_DOWN, h.control(R.id.button_return))
            h.advance(340)
            assertEquals(listOf(KeyAction.CommitAction("\n")), h.actions)
            h.event(MotionEvent.ACTION_MOVE, h.digit(6))
            h.event(MotionEvent.ACTION_UP, h.digit(6))
            assertEquals("A completed long Return cannot become a numeric tap", listOf(KeyAction.CommitAction("\n")), h.actions)
        }
    }
}
