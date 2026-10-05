/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyPressDepth
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.PressEffect
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.junit.Assert.*
import org.junit.After
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
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w600dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class KeyPressDepthTest {
    private var priorAppInstance: FcitxApplication? = null

    @Before
    fun prepareWithoutStartingTheNativeEngine() {
        val instanceField = FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
        }
        priorAppInstance = instanceField.get(null) as? FcitxApplication
        val application = RuntimeEnvironment.getApplication()
        val uiApplication = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(uiApplication, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, uiApplication)
        }
        AppPrefs.init(application.getSharedPreferences("key-press-depth", Context.MODE_PRIVATE))
    }

    @After
    fun restoreApplicationInstance() {
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, priorAppInstance)
        }
    }

    @Test
    fun theFirstFrameCompressesAndEvenZeroDurationTouchesReboundOnceWithoutAColourGap() {
        for (upTime in longArrayOf(0L, 10L, 20L, 40L)) {
            var now = 0L
            val released = KeyPressDepth { now }
            val held = KeyPressDepth { now }
            released.pressed()
            held.pressed()
            assertEquals("DOWN must visibly compress in the first frame", 0.96f, released.currentScale(), 0.000001f)
            assertEquals(-0.5f, released.currentLift(), 0f)
            assertEquals(1f, released.currentFaceOpacity(), 0f)
            now = upTime
            val position = released.currentLift()
            val velocity = released.currentVelocity()
            released.released()
            assertEquals("UP preserves compression even for a zero-duration touch", position, released.currentLift(), 0f)
            assertEquals("UP changes the target without a velocity impulse", velocity, released.currentVelocity(), 0f)
            now = upTime + 1
            assertTrue("UP immediately accelerates upward instead of waiting for a queued press",
                released.currentVelocity() > held.currentVelocity())
            var peak = 0f
            var peakTime = 0
            var crossedZero = false
            var descending = false
            var priorPosition = released.currentLift()
            for (elapsed in 2..1100) {
                now = upTime + elapsed
                val lift = released.currentLift()
                val speed = released.currentVelocity()
                if (!crossedZero && lift >= 0f) {
                    crossedZero = true
                    assertTrue("Crossing the neutral position is still moving", released.isTransitioning())
                    assertEquals("The cap must not blink off while changing direction", 1f, released.currentFaceOpacity(), 0f)
                }
                if (lift > peak) { peak = lift; peakTime = elapsed }
                val alreadyDescending = descending
                if (crossedZero && speed < 0f) descending = true
                if (descending) {
                    assertTrue("Only one positive rebound is allowed", speed <= 0f)
                    assertTrue("The soft landing must never dip below rest", lift >= 0f)
                    // The first descending sample can still exceed the last rising
                    // sample when the analytic peak falls between those two frames.
                    if (alreadyDescending) {
                        assertTrue("The landing cannot start another bounce", lift <= priorPosition)
                    }
                } else {
                    assertEquals("Compression and rise retain the complete coloured face", 1f, released.currentFaceOpacity(), 0f)
                }
                priorPosition = lift
            }
            assertTrue("Even ${upTime}ms touches must produce a visible positive rebound", peak in 0.89f..0.99f)
            assertTrue("The peak must be perceptible, without delaying input", peakTime in 180..230)
            assertTrue(KeyPressDepth.scaleDelta(peak) in 0.028f..0.031f)
            assertEquals(1f, released.currentScale(), 0f)
            assertEquals(0f, released.currentFaceOpacity(), 0f)
            assertFalse(released.isTransitioning())
        }
        assertEquals("Negative and positive scale mappings meet with a continuous slope",
            KeyPressDepth.PRESS_SCALE_LOSS,
            KeyPressDepth.scaleDelta(0.0001f) / 0.0001f, 0.00002f)
    }

    @Test
    fun repressPreservesPositionAndVelocityAcrossTheRisePeakAndLanding() {
        for (repressAfter in longArrayOf(60L, 80L, 100L, 200L, 350L)) {
            var now = 0L
            val motion = KeyPressDepth { now }
            val control = KeyPressDepth { now }
            listOf(motion, control).forEach { it.pressed() }
            now = 200
            assertEquals(-1f, motion.currentLift(), 0f)
            assertFalse("A held key stops scheduling after reaching compression", motion.isTransitioning())
            listOf(motion, control).forEach { it.released() }
            now += repressAfter
            val before = motion.currentLift()
            val velocity = motion.currentVelocity()
            listOf(motion, control).forEach { it.pressed() }
            assertEquals(before, motion.currentLift(), 0f)
            assertEquals("Repress must retain the current speed on either side of zero", velocity, motion.currentVelocity(), 0f)
            now += 1
            if (velocity > 0.5f) assertTrue("An upward key decelerates without snapping direction", motion.currentLift() > before)
            if (velocity < -0.5f) assertTrue("A descending key retains its immediate momentum", motion.currentLift() < before)
            motion.pressed()
            now += 19
            assertEquals("Duplicate DOWN cannot restart the moving spring", control.currentLift(), motion.currentLift(), 0f)
            assertEquals(control.currentVelocity(), motion.currentVelocity(), 0f)
            now += 250
            assertEquals(-1f, motion.currentLift(), 0f)
            assertEquals(0.92f, motion.currentScale(), 0.000001f)
            assertFalse(motion.isTransitioning())
            motion.reset()
            now += 1000
            assertEquals(1f, motion.currentScale(), 0f)
            assertEquals(0f, motion.currentFaceOpacity(), 0f)
            assertEquals(0f, motion.currentVelocity(), 0f)
            assertFalse(motion.isTransitioning())
        }
    }

    @Test
    fun repressAtTheNeutralCrossingDoesNotTurnItIntoAnIdleKey() {
        var now = 0L
        val depth = KeyPressDepth { now }
        depth.pressed()
        depth.released()
        while (depth.currentLift() < 0f) now++
        val crossing = depth.currentLift()
        val velocity = depth.currentVelocity()
        assertTrue(crossing in 0f..0.02f)
        assertTrue(velocity > 0f)
        depth.pressed()
        assertEquals("A crossing key must not receive the resting-key compression seed", crossing, depth.currentLift(), 0f)
        assertEquals(velocity, depth.currentVelocity(), 0f)
        assertEquals(1f, depth.currentFaceOpacity(), 0f)
        now += 1
        assertTrue(depth.currentLift() > crossing)
    }

    @Test
    fun releaseReboundsAboveRestThenKeepsAColouredTailWithoutASecondBounce() {
        var now = 0L
        val depth = KeyPressDepth { now }
        depth.pressed()
        now = 200
        assertEquals(-1f, depth.currentLift(), 0f)
        assertEquals(0.92f, depth.currentScale(), 0.000001f)
        depth.released()
        now = 300
        assertTrue("After 100ms the cap has crossed upward through rest", depth.currentLift() in 0.39f..0.46f)
        assertEquals(1f, depth.currentFaceOpacity(), 0f)
        now = 400
        assertTrue("At 200ms the single rebound is near its positive peak", depth.currentLift() in 0.94f..0.99f)
        assertTrue(depth.currentFaceOpacity() > 0.99f)
        now = 700
        assertTrue("A visible cap still connects consecutive input after 500ms", depth.currentFaceOpacity() > 0.4f)
        var previous = depth.currentLift()
        for (elapsed in 501L..1100L) {
            now = 200 + elapsed
            val lift = depth.currentLift()
            assertTrue("The landing never restarts an oscillation", lift <= previous)
            assertTrue(lift >= 0f)
            assertTrue(depth.currentVelocity() <= 0f)
            if (elapsed == 750L) assertTrue("The late colour fades rather than cuts off", depth.currentFaceOpacity() > 0.05f)
            previous = lift
        }
        assertEquals(1f, depth.currentScale(), 0f)
        assertEquals(0f, depth.currentFaceOpacity(), 0f)
        assertFalse(depth.isTransitioning())
    }

    @Test
    fun aNewKeyDoesNotCancelThePreviousReboundAndFramesCanSkipTheExactPeak() {
        var now = 0L
        val left = KeyPressDepth { now }
        val control = KeyPressDepth { now }
        val next = KeyPressDepth { now }
        left.pressed()
        control.pressed()
        now = 200
        left.released()
        control.released()
        now = 260
        val beforeNext = left.currentLift()
        next.pressed()
        assertEquals(beforeNext, left.currentLift(), 0f)
        for (t in 261L..600L) {
            now = t
            left.currentLift()
            left.currentFaceOpacity()
        }
        assertEquals("One late draw that skips the rebound peak must have the same position",
            control.currentLift(), left.currentLift(), 0f)
        assertEquals(control.currentFaceOpacity(), left.currentFaceOpacity(), 0f)
        assertEquals(control.currentVelocity(), left.currentVelocity(), 0f)
        assertTrue("L has its own positive tail while the next key remains compressed", left.currentLift() > 0f)
        assertEquals(-1f, next.currentLift(), 0f)
        assertTrue(left.currentFaceOpacity() > 0.5f)
        next.released()
        now = 1700
        assertEquals(1f, left.currentScale(), 0f)
        assertEquals(1f, next.currentScale(), 0f)
        assertFalse(left.isTransitioning())
        assertFalse(next.isTransitioning())
    }

    @Test
    fun rapidRemashingPreservesMotionAndStillFinishesWithoutEscapingItsDrawingRange() {
        var now = 0L
        val depth = KeyPressDepth { now }
        for (duration in intArrayOf(0, 10, 20, 40, 60, 5, 80, 0, 25, 50, 15, 100)) {
            val wasMoving = depth.isTransitioning()
            val before = depth.currentLift()
            val speed = depth.currentVelocity()
            depth.pressed()
            if (wasMoving) {
                assertEquals(before, depth.currentLift(), 0f)
                assertEquals(speed, depth.currentVelocity(), 0f)
            }
            repeat(duration) { now++; assertTrue(depth.currentLift() in -1f..1f) }
            val upPosition = depth.currentLift()
            val upVelocity = depth.currentVelocity()
            depth.released()
            assertEquals(upPosition, depth.currentLift(), 0f)
            assertEquals(upVelocity, depth.currentVelocity(), 0f)
            repeat(35) {
                now++
                assertTrue(depth.currentScale() in 0.919f..1.031f)
                assertEquals(1f, depth.currentFaceOpacity(), 0f)
            }
        }
        now += 1200
        assertEquals(1f, depth.currentScale(), 0f)
        assertEquals(0f, depth.currentFaceOpacity(), 0f)
        assertFalse(depth.isTransitioning())
    }

    private class Harness(
        mode: ThemePrefs.KeyMotionEffect = ThemePrefs.KeyMotionEffect.Press,
        appAnimationsDisabled: Boolean = false,
        lights: Boolean = false,
        savedRetreat: Int = 100,
        shape: ThemePrefs.RippleShape = ThemePrefs.RippleShape.SoftMist,
        singleColor: Int = 0xFFFF243F.toInt(),
        waveFade: Int = 520
    ) {
        init {
            ShadowChoreographer.setPaused(true)
            ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        }
        private val restore = mutableListOf<() -> Unit>()
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        private val activity = controller.get()
        val keyboard: TextKeyboard
        val typed = mutableListOf<String>()
        private var downTime = 0L

        init {
            val p = ThemeManager.prefs
            setting(p.pressEffect, lights)
            setting(p.rippleShape, shape)
            setting(p.idleBreathing, false)
            setting(p.pressColorMode, ThemePrefs.PressColorMode.Single)
            setting(p.pressSingleColor, singleColor)
            setting(p.pressKeyRetreatTime, savedRetreat)
            setting(p.pressIgnitionTime, 40)
            setting(p.pressExpansionTime, 400)
            setting(p.pressWaveHoldTime, 40)
            setting(p.pressFadeOutTime, waveFade)
            setting(p.keyColorStyle, ThemePrefs.KeyColorStyle.Fill)
            setting(p.keyExitStyle, ThemePrefs.KeyExitStyle.Dim)
            setting(p.keyMotionEffect, mode)
            setting(p.effectsFollowSystemAnimation, false)
            setting(p.keyBorder, true)
            setting(p.keyBorderStroke, false)
            setting(p.keyRippleEffect, false)
            setting(p.keyHorizontalMargin, 3)
            setting(p.keyVerticalMargin, 4)
            setting(p.keyRadius, 5)
            setting(AppPrefs.getInstance().advanced.disableAnimation, appAnimationsDisabled)
            setting(AppPrefs.getInstance().keyboard.popupOnKeyPress, false)
            activity.setTheme(R.style.Theme_InputViewTheme)
            keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
            activity.setContentView(keyboard)
            controller.visible()
            advance(32)
            keyboard.measure(
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(450, View.MeasureSpec.EXACTLY))
            keyboard.layout(0, 0, 600, 450)
            keys(keyboard).forEach { it.longPressEnabled = false; it.repeatEnabled = false }
            keyboard.keyActionListener = KeyActionListener { action, _ ->
                if (action is KeyAction.FcitxKeyAction) typed.add(action.act)
            }
        }

        private fun <T : Any> setting(preference: ManagedPreference<T>, value: T) {
            val existed = preference.sharedPreferences.contains(preference.key)
            val old = preference.getValue()
            restore.add {
                if (existed) preference.setValue(old)
                else assertTrue("Remove fixture-only preference ${preference.key}",
                    preference.sharedPreferences.edit().remove(preference.key).commit())
            }
            preference.setValue(value)
        }

        private fun keys(view: View): List<KeyView> = when (view) {
            is KeyView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { keys(view.getChildAt(it)) }
            else -> emptyList()
        }

        fun key(label: String): TextKeyView = keys(keyboard).filterIsInstance<TextKeyView>().first {
            (it.def as? KeyDef.Appearance.Text)?.displayText == label
        }

        fun outerBounds(key: KeyView) = Rect().also {
            key.getDrawingRect(it)
            keyboard.offsetDescendantRectToMyCoords(key, it)
        }

        fun appearance(key: KeyView): View = ReflectionHelpers.getField(key, "appearanceView")

        fun depth(key: KeyView): KeyPressDepth = ReflectionHelpers.getField(key, "pressDepth")

        fun event(action: Int, vararg pointers: Pair<Int, String>) {
            if ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) {
                downTime = SystemClock.uptimeMillis()
            }
            val properties = pointers.map { (id, _) -> MotionEvent.PointerProperties().apply {
                this.id = id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            } }.toTypedArray()
            val coordinates = pointers.map { (_, label) ->
                val rect = outerBounds(key(label))
                MotionEvent.PointerCoords().apply {
                    x = rect.exactCenterX(); y = rect.exactCenterY(); pressure = 1f; size = 1f
                }
            }.toTypedArray()
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, pointers.size,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                assertTrue("The real keyboard must handle the touch sequence", keyboard.dispatchTouchEvent(event))
            } finally { event.recycle() }
        }

        fun advance(milliseconds: Long) {
            var remaining = milliseconds
            while (remaining > 0L) {
                val step = minOf(remaining, 16L)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
                remaining -= step
            }
        }

        fun render(view: View): Bitmap {
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image).apply { drawColor(Color.BLACK) })
            return image
        }

        fun finish() {
            keyboard.onDetach()
            controller.pause().stop().destroy()
            restore.asReversed().forEach { it() }
            ShadowChoreographer.setPaused(false)
        }
    }

    private fun brightness(color: Int) = maxOf(Color.red(color), Color.green(color), Color.blue(color))

    private class GlyphRecordingCanvas(bitmap: Bitmap, private val label: String) : Canvas(bitmap) {
        private val recordedMatrix = Matrix()
        private val values = FloatArray(9)
        var glyphDraws = 0
            private set
        var glyphScaleX = Float.NaN
            private set
        var glyphScaleY = Float.NaN
            private set
        var glyphBaselineY = Float.NaN
            private set

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            if (text == label) {
                @Suppress("DEPRECATION")
                getMatrix(recordedMatrix)
                recordedMatrix.getValues(values)
                glyphScaleX = values[Matrix.MSCALE_X]
                glyphScaleY = values[Matrix.MSCALE_Y]
                glyphBaselineY = values[Matrix.MSCALE_Y] * y + values[Matrix.MTRANS_Y]
                glyphDraws++
            }
            // Record the real AutoScaleTextView call, then render it normally.
            super.drawText(text, x, y, paint)
        }
    }

    private fun saveDepthFrame(image: Bitmap, name: String) {
        val output = File("build/outputs/press-depth-checks/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun assertFixedGeometry(h: Harness, key: KeyView, outer: Rect, anchor: Rect) {
        assertEquals("Press depth must preserve the touch cell", outer, h.outerBounds(key))
        assertEquals("Press depth must preserve the popup anchor", anchor, key.bounds)
        for (view in listOf(key, h.appearance(key))) {
            assertEquals(1f, view.scaleX, 0f)
            assertEquals(1f, view.scaleY, 0f)
            assertEquals(0f, view.translationX, 0f)
            assertEquals(0f, view.translationY, 0f)
            assertEquals(1f, view.alpha, 0f)
        }
    }

    @Test
    fun realPressCompressesThenReboundsTheBackgroundAndCharacterTogetherEvenWithAllLightsOff() {
        val h = Harness()
        try {
            val key = h.key("A")
            val outer = h.outerBounds(key)
            val anchor = Rect(key.bounds)
            val textColor = key.mainText.currentTextColor
            val values = FloatArray(9)
            var paintedScale = 1f
            // Capture the transform at the neon-face hook, without painting any light.
            key.keySurfacePainter = KeyView.KeySurfacePainter { canvas, _, _ ->
                @Suppress("DEPRECATION")
                val matrix = canvas.matrix
                matrix.getValues(values)
                paintedScale = values[Matrix.MSCALE_X]
                assertEquals(paintedScale, values[Matrix.MSCALE_Y], 0.00001f)
                val cap = floatArrayOf(key.hMargin.toFloat(), key.vMargin.toFloat(),
                    (key.width - key.hMargin).toFloat(), (key.height - key.vMargin + 1).toFloat())
                matrix.mapPoints(cap)
                assertTrue("The moving cap and its bottom shadow must stay inside the fixed cell",
                    cap[0] >= 0f && cap[1] >= 0f && cap[2] <= key.width && cap[3] <= key.height)
            }
            val before = Bitmap.createBitmap(key.width, key.height, Bitmap.Config.ARGB_8888)
            val beforeCanvas = GlyphRecordingCanvas(before, "A").apply { drawColor(Color.BLACK) }
            key.draw(beforeCanvas)
            saveDepthFrame(before, "before")
            h.event(MotionEvent.ACTION_DOWN, 7 to "A")
            h.advance(200)
            val pressed = Bitmap.createBitmap(key.width, key.height, Bitmap.Config.ARGB_8888)
            val pressedCanvas = GlyphRecordingCanvas(pressed, "A").apply { drawColor(Color.BLACK) }
            key.draw(pressedCanvas)
            saveDepthFrame(pressed, "pressed")
            assertEquals(0.92f, h.depth(key).currentScale(), 0.00001f)
            assertEquals("The face painter must share the appearance transform", 0.92f, paintedScale, 0.00001f)
            assertEquals("Without lighting the glyph ink must remain unchanged", textColor, key.mainText.currentTextColor)
            var backgroundChanges = 0
            var characterChanges = 0
            var beforeCharacterPixels = 0
            var afterCharacterPixels = 0
            for (y in 0 until before.height) for (x in 0 until before.width) {
                val first = brightness(before.getPixel(x, y))
                val second = brightness(pressed.getPixel(x, y))
                if (first >= 160) beforeCharacterPixels++
                if (second >= 160) afterCharacterPixels++
                if (abs(first - second) < 2) continue
                if (first < 120 && second < 120) backgroundChanges++
                if (first >= 160 || second >= 160) characterChanges++
            }
            val metrics = key.mainText.paint.fontMetrics
            File("build/outputs/press-depth-checks/layout.txt").writeText(
                "density=${key.resources.displayMetrics.density}\n" +
                    "key=${key.width}x${key.height},appearance=${h.appearance(key).width}x${h.appearance(key).height}\n" +
                    "text=${key.mainText.text},view=${key.mainText.width}x${key.mainText.height}," +
                    "measured=${key.mainText.measuredWidth}x${key.mainText.measuredHeight}," +
                    "position=${key.mainText.left},${key.mainText.top},visibility=${key.mainText.visibility}\n" +
                    "size=${key.mainText.paint.textSize},colour=${key.mainText.currentTextColor}," +
                    "font_top=${metrics.top},font_bottom=${metrics.bottom},baseline=${key.mainText.baseline}\n" +
                    "before_bright_pixels=$beforeCharacterPixels,after_bright_pixels=$afterCharacterPixels," +
                    "glyph_changes=$characterChanges,background_changes=$backgroundChanges\n" +
                    "before_glyph_scale=${beforeCanvas.glyphScaleX},${beforeCanvas.glyphScaleY}," +
                    "pressed_glyph_scale=${pressedCanvas.glyphScaleX},${pressedCanvas.glyphScaleY}\n")
            assertTrue("The baseline fixture must contain the actual readable letter ($beforeCharacterPixels bright pixels)",
                beforeCharacterPixels >= 12)
            assertTrue("The pressed fixture must keep the real letter readable", afterCharacterPixels >= 12)
            assertTrue("The dark keycap itself must visibly move ($backgroundChanges changed pixels)", backgroundChanges >= 8)
            assertTrue("The compressed press must visibly move the actual letter ($characterChanges changed pixels)", characterChanges >= 3)
            assertTrue("Both frames must execute the real AutoScaleTextView glyph draw", beforeCanvas.glyphDraws > 0 && pressedCanvas.glyphDraws > 0)
            assertEquals(1f, beforeCanvas.glyphScaleX, 0.00001f)
            assertEquals(1f, beforeCanvas.glyphScaleY, 0.00001f)
            assertEquals("The glyph must share the real compressed keycap scale", 0.92f, pressedCanvas.glyphScaleX, 0.00001f)
            assertEquals("The glyph must share the real compressed keycap scale", 0.92f, pressedCanvas.glyphScaleY, 0.00001f)
            assertEquals("The neon face and character must use the same matrix", paintedScale, pressedCanvas.glyphScaleX, 0.00001f)
            assertTrue("The real glyph must sink on the actual Canvas, not just change a model value",
                pressedCanvas.glyphBaselineY > beforeCanvas.glyphBaselineY + 0.3f)
            assertFixedGeometry(h, key, outer, anchor)
            h.advance(200)
            assertFalse("Holding a settled key must not keep animating", h.depth(key).isTransitioning())
            h.event(MotionEvent.ACTION_UP, 7 to "A")
            assertEquals("Input commits on UP rather than waiting for the visual return", listOf("a"), h.typed)
            h.advance(200)
            val rebound = Bitmap.createBitmap(key.width, key.height, Bitmap.Config.ARGB_8888)
            val reboundCanvas = GlyphRecordingCanvas(rebound, "A").apply { drawColor(Color.BLACK) }
            key.draw(reboundCanvas)
            val releaseScale = h.depth(key).currentScale()
            assertTrue("At 200ms the actual cap rebounds above its resting size", releaseScale in 1.028f..1.031f)
            assertEquals(releaseScale, reboundCanvas.glyphScaleX, 0.00001f)
            assertEquals(releaseScale, reboundCanvas.glyphScaleY, 0.00001f)
            assertTrue("The same glyph must rebound above its original baseline after UP",
                reboundCanvas.glyphBaselineY < beforeCanvas.glyphBaselineY - 1f)
            saveDepthFrame(rebound, "released-200ms")
            assertFixedGeometry(h, key, outer, anchor)
            h.advance(900)
            assertEquals(1f, h.depth(key).currentScale(), 0f)
            assertTrue("The finished release must return the whole key to its original pixels", before.sameAs(h.render(key)))
            assertFixedGeometry(h, key, outer, anchor)
            assertEquals(listOf("a"), h.typed)
        } finally { h.finish() }
    }

    @Test
    fun realZeroAndShortTouchesCommitImmediatelyAndStillShowCompressionThenRebound() {
        val h = Harness(lights = true)
        try {
            val key = h.key("A")
            val outer = h.outerBounds(key)
            val anchor = Rect(key.bounds)
            for ((index, duration) in longArrayOf(0L, 10L, 20L, 40L).withIndex()) {
                h.event(MotionEvent.ACTION_DOWN, 7 to "A")
                assertEquals(0.96f, h.depth(key).currentScale(), 0.000001f)
                h.advance(duration)
                val compressed = h.depth(key).currentLift()
                h.event(MotionEvent.ACTION_UP, 7 to "A")
                assertEquals("Text never waits for the visual rebound", List(index + 1) { "a" }, h.typed)
                assertEquals(compressed, h.depth(key).currentLift(), 0f)
                h.advance(200)
                val image = Bitmap.createBitmap(key.width, key.height, Bitmap.Config.ARGB_8888)
                val canvas = GlyphRecordingCanvas(image, "A")
                key.draw(canvas)
                assertTrue("A ${duration}ms real touch must visibly rebound", canvas.glyphScaleX in 1.028f..1.031f)
                assertTrue(canvas.glyphDraws > 0)
                saveDepthFrame(image, "short-$duration-released-200ms")
                image.recycle()
                assertFixedGeometry(h, key, outer, anchor)
                h.advance(1100)
                assertEquals(1f, h.depth(key).currentScale(), 0f)
                assertFalse(h.depth(key).isTransitioning())
            }
        } finally { h.finish() }
    }

    @Test
    fun actualRedTextKeyboardCompressesAndReboundsLThenAWithImmediateInputForOldFadeSettings() {
        for (savedRetreat in intArrayOf(30, 100)) {
            val h = Harness(lights = true, savedRetreat = savedRetreat)
            try {
                val folder = "floating-red-retreat-$savedRetreat"
                val left = h.key("L")
                val next = h.key("A")
                val baseline = h.render(h.keyboard)
                val geometry = listOf(left, next).associateWith { h.outerBounds(it) to Rect(it.bounds) }
                val baselineGlyph = listOf(left, next).associateWith { key ->
                    val image = Bitmap.createBitmap(key.width, key.height, Bitmap.Config.ARGB_8888)
                    val canvas = GlyphRecordingCanvas(image, key.mainText.text.toString())
                    key.draw(canvas)
                    image.recycle()
                    canvas.glyphBaselineY
                }
                fun redCoverage(image: Bitmap, key: KeyView): Float {
                    val cell = h.outerBounds(key)
                    var red = 0
                    var pixels = 0
                    for (y in cell.top + key.vMargin + 8 until cell.bottom - key.vMargin - 8) {
                        // Keep glyph ink out of this key-face colour measurement.
                        if (y - cell.top in cell.height() * 35 / 100..cell.height() * 65 / 100) continue
                        for (x in cell.left + key.hMargin + 7 until cell.right - key.hMargin - 7) {
                            val c = image.getPixel(x, y)
                            if (Color.red(c) > 75 && Color.red(c) - maxOf(Color.green(c), Color.blue(c)) > 40) red++
                            pixels++
                        }
                    }
                    assertTrue(pixels > 50)
                    return red.toFloat() / pixels
                }
                val stripTimes = listOf(0, 100, 200, 300, 400, 550, 900, 1400)
                val strip = Bitmap.createBitmap(1200, 4 * 484, Bitmap.Config.ARGB_8888)
                val stripCanvas = Canvas(strip).apply { drawColor(0xFF0B1018.toInt()) }
                val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 18f }
                val rows = mutableListOf("time_ms,L_lift,L_velocity,L_face_opacity,L_red_fraction,L_glyph_y,A_lift,A_face_opacity,A_red_fraction,A_glyph_y,committed")
                for (time in 0..1400 step 25) {
                    when (time) {
                        0 -> h.event(MotionEvent.ACTION_DOWN, 7 to "L")
                        50 -> {
                            h.event(MotionEvent.ACTION_UP, 7 to "L")
                            assertEquals("The first word is accepted while its visual tail is still beginning", listOf("l"), h.typed)
                        }
                        100 -> h.event(MotionEvent.ACTION_DOWN, 7 to "A")
                        150 -> {
                            h.event(MotionEvent.ACTION_UP, 7 to "A")
                            assertEquals("The next key does not wait for the previous landing", listOf("l", "a"), h.typed)
                        }
                        225 -> {
                            val lift = h.depth(left).currentLift()
                            val velocity = h.depth(left).currentVelocity()
                            assertTrue("The physical L is still rising when retouched", velocity > 0f)
                            h.event(MotionEvent.ACTION_DOWN, 7 to "L")
                            assertEquals("A real retouch keeps elevation", lift, h.depth(left).currentLift(), 0f)
                            assertEquals("A real retouch keeps upward momentum", velocity, h.depth(left).currentVelocity(), 0f)
                        }
                        275 -> {
                            h.event(MotionEvent.ACTION_UP, 7 to "L")
                            assertEquals(listOf("l", "a", "l"), h.typed)
                        }
                    }
                    val image = h.render(h.keyboard)
                    saveDepthFrame(image, "$folder/frame-%04d".format(time))
                    val glyphY = listOf(left, next).associateWith { key ->
                        val glyphImage = Bitmap.createBitmap(key.width, key.height, Bitmap.Config.ARGB_8888)
                        val canvas = GlyphRecordingCanvas(glyphImage, key.mainText.text.toString())
                        key.draw(canvas)
                        glyphImage.recycle()
                        canvas.glyphBaselineY
                    }
                    val leftRed = redCoverage(image, left)
                    val nextRed = redCoverage(image, next)
                    rows.add(listOf(time, h.depth(left).currentLift(), h.depth(left).currentVelocity(),
                        h.depth(left).currentFaceOpacity(), leftRed, glyphY.getValue(left),
                        h.depth(next).currentLift(), h.depth(next).currentFaceOpacity(), nextRed,
                        glyphY.getValue(next), h.typed.joinToString("")).joinToString(","))
                    if (time == 0) {
                        assertTrue("The first real DOWN frame is a full red block", leftRed > 0.8f)
                        assertTrue("The actual first glyph frame is already compressed downward", glyphY.getValue(left) > baselineGlyph.getValue(left) + 0.2f)
                        assertTrue(h.typed.isEmpty())
                    }
                    if (time == 100 || time == 200) {
                        assertTrue("L must retain a red full face across A, despite saved ${savedRetreat}ms fading", leftRed > 0.7f)
                        assertTrue("A has its own independent coloured face", nextRed > 0.7f)
                        if (time == 100) {
                            assertTrue("L is recovering from compression while A first compresses", h.depth(left).currentLift() < 0f)
                            assertTrue(h.depth(next).currentLift() < 0f)
                        } else {
                            assertTrue("L has risen above rest while A follows independently", h.depth(left).currentLift() > 0f)
                            assertTrue("L glyph shares the actual upward rebound", glyphY.getValue(left) < baselineGlyph.getValue(left) - 0.3f)
                        }
                    }
                    if (time == 400) {
                        assertTrue("The new L press cannot cancel A's colour tail", h.depth(next).currentFaceOpacity() > 0.2f)
                        assertTrue("The retouched L also keeps its own release", leftRed > 0.6f)
                    }
                    if (time == 550) {
                        assertTrue("A retains a visible coloured cap 400ms after its real UP", h.depth(next).currentFaceOpacity() > 0.35f)
                        assertTrue("The retouched L independently stays coloured during its slower landing", h.depth(left).currentFaceOpacity() > 0.4f)
                    }
                    for ((key, fixed) in geometry) assertFixedGeometry(h, key, fixed.first, fixed.second)
                    val stripIndex = stripTimes.indexOf(time)
                    if (stripIndex >= 0) {
                        val x = (stripIndex % 2) * 600f
                        val y = (stripIndex / 2) * 484f
                        stripCanvas.drawBitmap(image, x, y + 34f, null)
                        stripCanvas.drawText("${time}ms  " + when (time) {
                            0 -> "L DOWN: immediate red compression"
                            100 -> "A DOWN: L starts rebounding"
                            200 -> "L rebounds above rest; A follows"
                            300 -> "L retouched; A keeps rebounding"
                            400 -> "Soft landing and coloured afterglow"
                            550 -> "Both coloured caps are still descending"
                            900 -> "Final soft tails approach rest"
                            else -> "Exact resting keyboard"
                        }, x + 12f, y + 24f, labelPaint)
                    }
                    if (time == 1400) {
                        assertEquals(0f, h.depth(left).currentLift(), 0f)
                        assertEquals(0f, h.depth(next).currentLift(), 0f)
                        assertFalse(h.depth(left).isTransitioning())
                        assertFalse(h.depth(next).isTransitioning())
                        assertTrue("Every native pixel returns to the resting keyboard", baseline.sameAs(image))
                        assertEquals(listOf("l", "a", "l"), h.typed)
                    }
                    image.recycle()
                    if (time < 1400) h.advance(25)
                }
                saveDepthFrame(strip, "$folder/strip")
                strip.recycle()
                baseline.recycle()
                File("build/outputs/press-depth-checks/$folder/motion.csv").writeText(rows.joinToString("\n") + "\n")
                File("build/outputs/press-depth-checks/$folder/provenance.txt").writeText(
                    "source=actual TextKeyboard, KeyView and PressEffect via MotionEvent\n" +
                        "graphics=Robolectric NATIVE\nframes=57\nfps=40\nframe_interval_ms=25\n" +
                        "single_colour=#FF243F\nsaved_legacy_retreat_ms=$savedRetreat\n" +
                        "events=L down0 up50; A down100 up150; L down225 up275\n" +
                        "view_and_hit_geometry=fixed\ncolour_and_lift=shared signed per-key motion\n" +
                        "frames_are_direct_native_draws=true\ninterpolation=false\n")
            } finally { h.finish() }
        }
    }

    @Test
    fun realSameKeyPointersReleaseOnlyTheLastFingerAndDetachResetsHeldDepth() {
        val h = Harness()
        try {
            val key = h.key("A")
            val baseline = h.render(key)
            h.event(MotionEvent.ACTION_DOWN, 7 to "A")
            h.advance(80)
            h.event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                7 to "A", 23 to "A")
            h.event(MotionEvent.ACTION_POINTER_UP,
                7 to "A", 23 to "A")
            h.advance(180)
            assertEquals("The remaining finger must keep a shared key compressed", 0.92f, h.depth(key).currentScale(), 0.00001f)
            h.event(MotionEvent.ACTION_UP, 23 to "A")
            h.advance(1100)
            assertEquals(1f, h.depth(key).currentScale(), 0f)
            assertTrue(baseline.sameAs(h.render(key)))
            h.event(MotionEvent.ACTION_DOWN, 23 to "A")
            h.advance(80)
            assertTrue(h.depth(key).currentScale() < 1f)
            h.keyboard.onDetach()
            assertEquals("A keyboard switch must immediately reset a held key", 1f, h.depth(key).currentScale(), 0f)
            h.advance(200)
            assertEquals("Old animation work must not raise a detached keyboard again", 1f, h.depth(key).currentScale(), 0f)
        } finally { h.finish() }
    }

    @Test
    fun hidingACachedKeyboardCancelsItsHeldCompressionWithoutCommittingAndShowingStartsAtRest() {
        val h = Harness(lights = true)
        try {
            val key = h.key("L")
            val baseline = h.render(h.keyboard)
            h.event(MotionEvent.ACTION_DOWN, 7 to "L")
            h.advance(80)
            assertTrue(h.depth(key).currentLift() < -0.8f)
            assertTrue(h.keyboard.isAttachedToWindow)
            h.keyboard.visibility = View.INVISIBLE
            assertTrue("This exercises hidden-without-detach, not teardown", h.keyboard.isAttachedToWindow)
            assertEquals(0f, h.depth(key).currentLift(), 0f)
            assertEquals(0f, h.depth(key).currentFaceOpacity(), 0f)
            assertFalse(h.depth(key).isTransitioning())
            assertTrue("Cancelling a hidden key must not submit it", h.typed.isEmpty())
            h.advance(600)
            h.keyboard.visibility = View.VISIBLE
            assertTrue("Showing the cached keyboard must restore the actual dark resting pixels", baseline.sameAs(h.render(h.keyboard)))
            h.event(MotionEvent.ACTION_DOWN, 7 to "L")
            h.advance(80)
            assertTrue("A fresh touch compresses again after hidden pointers are discarded", h.depth(key).currentLift() < -0.8f)
            h.keyboard.dispatchWindowVisibilityChanged(View.INVISIBLE)
            assertEquals("The window-level hide also cancels motion immediately", 0f, h.depth(key).currentLift(), 0f)
            assertFalse(h.depth(key).isTransitioning())
            assertTrue(h.typed.isEmpty())
            h.keyboard.dispatchWindowVisibilityChanged(View.VISIBLE)
            h.event(MotionEvent.ACTION_DOWN, 7 to "L")
            h.advance(25)
            h.event(MotionEvent.ACTION_UP, 7 to "L")
            assertEquals("Only the final real release commits", listOf("l"), h.typed)
            h.advance(1100)
            assertTrue(baseline.sameAs(h.render(h.keyboard)))
            baseline.recycle()
        } finally { h.finish() }
    }

    @Test
    fun actualTextKeyboardShowsDifferentFluidBoundariesForTheSavedShapeWithTheSameColourAndTouches() {
        val reviewTimes = setOf(150, 300, 450, 600, 800, 1000, 1400, 1800)
        data class Recording(val baseline: Bitmap, val frames: Map<Int, Bitmap>,
            val gapSamples: Map<Int, ByteArray>, val gapIndices: IntArray)
        fun save(image: Bitmap, name: String) {
            val file = File("build/outputs/ripple-shape-checks/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun record(shape: ThemePrefs.RippleShape): Recording {
            val h = Harness(lights = true, shape = shape, waveFade = 900)
            try {
                val effect = ReflectionHelpers.getField<PressEffect>(h.keyboard, "pressEffectLayer")
                assertEquals("The actual BaseKeyboard must read the persisted shape into its renderer", shape,
                    ReflectionHelpers.getField<ThemePrefs.RippleShape>(effect, "rippleShape"))
                ReflectionHelpers.setField(effect, "random", kotlin.random.Random(1401))
                val baseline = h.render(h.keyboard)
                val touched = listOf("G", "H", "J").map { h.outerBounds(h.key(it)) }
                // Real black gaps only: neither glyphs nor the coloured, floating
                // faces may masquerade as a difference in base-light geometry.
                val gaps = (0 until baseline.width * baseline.height).filter { index ->
                    val x = index % baseline.width
                    val y = index / baseline.width
                    brightness(baseline.getPixel(x, y)) <= 3 && touched.none { it.contains(x, y) }
                }.toIntArray()
                assertTrue("The real keyboard fixture must expose enough inter-key gaps", gaps.size > 1000)
                val inactiveCapPoints = listOf("Q", "W", "Z", "X", "P").map { label ->
                    val key = h.key(label)
                    val bounds = h.outerBounds(key)
                    (bounds.left + key.hMargin + 8) to (bounds.top + key.vMargin + 8)
                }
                val samples = linkedMapOf<Int, ByteArray>()
                val frames = linkedMapOf<Int, Bitmap>()
                val folder = if (shape == ThemePrefs.RippleShape.SoftMist) "keyboard-mist" else "keyboard-fluid"
                for (time in 0..1800 step 25) {
                    when (time) {
                        0 -> h.event(MotionEvent.ACTION_DOWN, 7 to "G")
                        50 -> h.event(MotionEvent.ACTION_UP, 7 to "G")
                        150 -> h.event(MotionEvent.ACTION_DOWN, 7 to "H")
                        200 -> h.event(MotionEvent.ACTION_UP, 7 to "H")
                        300 -> h.event(MotionEvent.ACTION_DOWN, 7 to "J")
                        350 -> h.event(MotionEvent.ACTION_UP, 7 to "J")
                    }
                    val image = h.render(h.keyboard)
                    save(image, "$folder/frame-%04d".format(time))
                    samples[time] = ByteArray(gaps.size) { i ->
                        brightness(image.getPixel(gaps[i] % image.width, gaps[i] / image.width)).toByte()
                    }
                    for ((x, y) in inactiveCapPoints) {
                        // The production black cap has alpha 0xEB, deliberately
                        // admitting a faint glow rather than being fully opaque.
                        assertTrue("A liquid boundary must leave untouched key interiors dark and readable at ${time}ms",
                            brightness(image.getPixel(x, y)) < 45)
                    }
                    if (time == 350) assertEquals("Changing geometry must not change typing", listOf("g", "h", "j"), h.typed)
                    if (time == 1800) assertTrue("Both real keyboard modes must finish at their exact resting pixels", baseline.sameAs(image))
                    if (time in reviewTimes) frames[time] = image else image.recycle()
                    if (time < 1800) h.advance(25)
                }
                return Recording(baseline, frames, samples, gaps)
            } finally { h.finish() }
        }
        val mist = record(ThemePrefs.RippleShape.SoftMist)
        val fluid = record(ThemePrefs.RippleShape.IrregularFluid)
        try {
            assertTrue("Only the ripple choice changes between recordings", mist.baseline.sameAs(fluid.baseline))
            assertArrayEquals(mist.gapIndices, fluid.gapIndices)
            var maximumBoundaryChanges = 0
            var maximumBoundaryFraction = 0f
            var maximumShapeResidual = 0.0
            val metrics = mutableListOf("time_ms,mist_peak,fluid_peak,normalised_boundary_changed,normalised_boundary_union,shape_residual_after_global_gain_fit")
            for (time in 100..1000 step 25) {
                val a = mist.gapSamples.getValue(time)
                val b = fluid.gapSamples.getValue(time)
                val peakA = a.maxOf { it.toInt() and 255 }.coerceAtLeast(1)
                val peakB = b.maxOf { it.toInt() and 255 }.coerceAtLeast(1)
                var changed = 0
                var union = 0
                var aa = 0.0
                var ab = 0.0
                for (i in a.indices) {
                    val old = a[i].toInt() and 255
                    val next = b[i].toInt() and 255
                    val oldBoundary = old > peakA * 0.30f
                    val newBoundary = next > peakB * 0.30f
                    if (oldBoundary != newBoundary) changed++
                    if (oldBoundary || newBoundary) union++
                    aa += old.toDouble() * old
                    ab += old.toDouble() * next
                }
                val gain = ab / aa.coerceAtLeast(1.0)
                var residual = 0.0
                var visible = 0
                for (i in a.indices) {
                    val old = a[i].toInt() and 255
                    val next = b[i].toInt() and 255
                    if (old >= 20 || next >= 20) {
                        residual += abs(next - gain * old)
                        visible++
                    }
                }
                residual /= visible.coerceAtLeast(1)
                maximumBoundaryChanges = maxOf(maximumBoundaryChanges, changed)
                maximumBoundaryFraction = maxOf(maximumBoundaryFraction, changed.toFloat() / union.coerceAtLeast(1))
                maximumShapeResidual = maxOf(maximumShapeResidual, residual)
                metrics.add("$time,$peakA,$peakB,$changed,$union,$residual")
            }
            assertTrue("The irregular boundary must visibly change real exposed keyboard gaps, not a hidden bare canvas",
                maximumBoundaryChanges > 150 && maximumBoundaryFraction > 0.15f)
            assertTrue("A uniform brightness or colour change cannot explain the fluid contour", maximumShapeResidual > 4.0)
            fun sampleDifference(a: ByteArray, b: ByteArray): Double =
                a.indices.sumOf { abs((a[it].toInt() and 255) - (b[it].toInt() and 255)).toDouble() } / a.size
            val flowing = sampleDifference(fluid.gapSamples.getValue(400), fluid.gapSamples.getValue(650))
            val singleFrame = (425..650 step 25).maxOf { time ->
                sampleDifference(fluid.gapSamples.getValue(time - 25), fluid.gapSamples.getValue(time))
            }
            assertTrue("Real liquid gaps evolve continuously after the last DOWN, without whole-keyboard frame flashes",
                flowing > 0.1 && singleFrame < flowing * 0.45)
            // A bright cyan surface uses dark glyph ink. Compare the actual
            // rendered glyph to the same-time surface with only that glyph hidden;
            // old white-pixel locations are invalid while its cap is floating.
            val cyan = Harness(lights = true, shape = ThemePrefs.RippleShape.IrregularFluid,
                singleColor = 0xFF00EFFF.toInt(), waveFade = 900)
            try {
                val key = cyan.key("G")
                val bounds = cyan.outerBounds(key)
                cyan.event(MotionEvent.ACTION_DOWN, 7 to "G")
                for (time in 0..400 step 50) {
                    if (time == 100) cyan.event(MotionEvent.ACTION_UP, 7 to "G")
                    val withText = cyan.render(cyan.keyboard)
                    key.mainText.visibility = View.INVISIBLE
                    val withoutText = cyan.render(cyan.keyboard)
                    key.mainText.visibility = View.VISIBLE
                    var readable = 0
                    for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
                        val a = withText.getPixel(x, y)
                        val b = withoutText.getPixel(x, y)
                        if (maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)),
                                abs(Color.blue(a) - Color.blue(b))) >= 40) readable++
                    }
                    assertTrue("The actual moving glyph stays readable above the bright liquid at ${time}ms", readable >= 12)
                    save(withText, "keyboard-fluid-cyan/frame-%04d".format(time))
                    withText.recycle(); withoutText.recycle()
                    if (time < 400) cyan.advance(50)
                }
            } finally { cyan.finish() }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 18f }
            for ((name, times) in listOf("keyboard-shapes-early" to listOf(150, 300, 450, 600),
                "keyboard-shapes-tail" to listOf(800, 1000, 1400, 1800))) {
                val strip = Bitmap.createBitmap(1200, times.size * 484, Bitmap.Config.ARGB_8888)
                Canvas(strip).apply {
                    drawColor(0xFF0B1018.toInt())
                    times.forEachIndexed { index, time ->
                        val y = index * 484f
                        drawText("Soft mist / ${time}ms", 12f, y + 24f, paint)
                        drawText("Irregular fluid / ${time}ms", 612f, y + 24f, paint)
                        drawBitmap(mist.frames.getValue(time), 0f, y + 34f, null)
                        drawBitmap(fluid.frames.getValue(time), 600f, y + 34f, null)
                    }
                }
                save(strip, name)
                strip.recycle()
            }
            File("build/outputs/ripple-shape-checks/keyboard-geometry.csv").writeText(metrics.joinToString("\n") + "\n")
            File("build/outputs/ripple-shape-checks/keyboard-provenance.txt").writeText(
                "source=actual TextKeyboard via persisted ThemePrefs and MotionEvent\n" +
                    "graphics=Robolectric NATIVE\nframes_per_mode=73\nfps=40\nseed=1401\n" +
                    "single_colour=#FF243F\nwave_fade_ms=900\nevents=G down0 up50; H down150 up200; J down300 up350\n" +
                    "geometry_measurement=fixed actual black key gaps, all touched cells excluded\n" +
                    "normalised_boundary_changed_max=$maximumBoundaryChanges\nnormalised_boundary_fraction_max=$maximumBoundaryFraction\n" +
                    "shape_residual_after_gain_fit=$maximumShapeResidual\nflow_400_to_650_ms=$flowing\nmaximum_25ms_step=$singleFrame\n" +
                    "frames_are_direct_native_draws=true\ninterpolation=false\n")
        } finally {
            mist.baseline.recycle(); fluid.baseline.recycle()
            mist.frames.values.forEach(Bitmap::recycle)
            fluid.frames.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun realMotionOffAndDisabledAnimationsLeaveTheKeyUnchanged() {
        val cases = listOf(
            ThemePrefs.KeyMotionEffect.Off to false,
            ThemePrefs.KeyMotionEffect.Press to true,
            ThemePrefs.KeyMotionEffect.Shrink to true,
            ThemePrefs.KeyMotionEffect.Bounce to true,
            ThemePrefs.KeyMotionEffect.Tilt to true
        )
        for ((mode, disabled) in cases) {
            val h = Harness(mode, disabled)
            try {
                val key = h.key("A")
                val outer = h.outerBounds(key)
                val anchor = Rect(key.bounds)
                val baseline = h.render(key)
                h.event(MotionEvent.ACTION_DOWN, 7 to "A")
                h.advance(80)
                assertEquals("mode=$mode disabled=$disabled", 1f, h.depth(key).currentScale(), 0f)
                assertEquals("No disabled action may shrink the real View", 1f, key.scaleX, 0f)
                assertEquals(1f, key.scaleY, 0f)
                assertEquals("No disabled action may rotate the real View", 0f, key.rotation, 0f)
                assertTrue("An inactive motion must not change actual key pixels", baseline.sameAs(h.render(key)))
                assertFalse(h.depth(key).isTransitioning())
                assertFixedGeometry(h, key, outer, anchor)
                h.event(MotionEvent.ACTION_UP, 7 to "A")
                h.advance(180)
                assertEquals(1f, key.scaleX, 0f)
                assertEquals(1f, key.scaleY, 0f)
                assertEquals(0f, key.rotation, 0f)
                assertTrue(baseline.sameAs(h.render(key)))
                assertEquals(listOf("a"), h.typed)
            } finally { h.finish() }
        }
    }

    @Test
    fun disablingAnimationsDuringAHoldResetsEveryActionOnReleaseWithoutAnotherAnimation() {
        for (mode in listOf(ThemePrefs.KeyMotionEffect.Press, ThemePrefs.KeyMotionEffect.Shrink,
            ThemePrefs.KeyMotionEffect.Bounce, ThemePrefs.KeyMotionEffect.Tilt)) {
            val h = Harness(mode)
            try {
                val key = h.key("A")
                h.event(MotionEvent.ACTION_DOWN, 7 to "A")
                h.advance(96)
                assertTrue("The $mode fixture must actually animate before it is disabled",
                    h.depth(key).currentScale() < 1f || key.scaleX < 1f || key.rotation != 0f)
                AppPrefs.getInstance().advanced.disableAnimation.setValue(true)
                h.event(MotionEvent.ACTION_UP, 7 to "A")
                assertEquals("Disabled release resets depth immediately", 1f, h.depth(key).currentScale(), 0f)
                assertFalse(h.depth(key).isTransitioning())
                assertEquals("Disabled release cancels the real property animator immediately", 1f, key.scaleX, 0f)
                assertEquals(1f, key.scaleY, 0f)
                assertEquals(0f, key.rotation, 0f)
                assertEquals(0f, key.translationY, 0f)
                h.advance(400)
                assertEquals("Cancelled animation cannot resume later", 1f, key.scaleX, 0f)
                assertEquals(1f, key.scaleY, 0f)
                assertEquals(0f, key.rotation, 0f)
                assertEquals(listOf("a"), h.typed)
            } finally { h.finish() }
        }
    }
}
