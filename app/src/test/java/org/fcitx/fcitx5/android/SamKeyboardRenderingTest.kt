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
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodSubMode
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyboardSizePolicy
import org.fcitx.fcitx5.android.input.keyboard.PressEffect
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
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
import splitties.dimensions.dp
import java.io.File
import java.time.Duration
import kotlin.math.abs
import kotlin.random.Random

/** Full production keyboard, measured at a 360dp portrait width; no recreated keycap artwork. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SamKeyboardRenderingTest {
    @Before
    fun prepare() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val application = RuntimeEnvironment.getApplication()
        val ui = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(ui, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, ui) }
        AppPrefs.init(application.getSharedPreferences("sam-native-review", Context.MODE_PRIVATE))
        ThemeManager.prefs.pressEffect.sharedPreferences.edit().clear().commit()
        AppPrefs.getInstance().advanced.disableAnimation.setValue(false)
    }

    @After
    fun restoreVsync() {
        ShadowChoreographer.setPaused(false)
    }

    private fun advance(time: Long) {
        var left = time
        while (left > 0) {
            val step = minOf(left, 16L)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            left -= step
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun intensity(pixel: Int) = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
    private fun chroma(pixel: Int) = intensity(pixel) - minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
    private fun coloured(pixel: Int): Boolean = intensity(pixel) >= 25 && chroma(pixel) >= 12

    private fun save(image: Bitmap, name: String) {
        val file = File("build/outputs/effect-checks/dev8-sam/$name.png")
        file.parentFile.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private inner class Keyboard(shape: ThemePrefs.RippleShape, randomColours: Boolean = false,
        numberRow: Boolean = true, customColours: String? = null,
        configure: (ThemePrefs) -> Unit = {}) : AutoCloseable {
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val root = FrameLayout(activity)
        val pixelHeight = KeyboardSizePolicy.heightForLayout(root.dp(288), true, true, numberRow)
        val keyboard: TextKeyboard
        val effect: PressEffect
        val keys: List<KeyView>
        val letters: Map<String, TextKeyView>
        var elapsed = 0L
            private set
        private var now = SystemClock.uptimeMillis()
        private val start: Long
        val typed = StringBuilder()

        init {
            val prefs = ThemeManager.prefs
            prefs.rippleShape.setValue(shape)
            // Deliberately ON: Sam must remain black even after upgrading a saved breathing setting.
            prefs.idleBreathing.setValue(true)
            prefs.pressColorMode.setValue(when {
                customColours != null -> ThemePrefs.PressColorMode.Custom
                randomColours -> ThemePrefs.PressColorMode.Random
                else -> ThemePrefs.PressColorMode.Single
            })
            if (customColours != null) prefs.pressUserColors.setValue(customColours)
            prefs.pressSingleColor.setValue(Color.parseColor("#00F0FF"))
            prefs.portraitNumberRow.setValue(numberRow)
            configure(prefs)
            AppPrefs.getInstance().keyboard.popupOnKeyPress.setValue(false)
            activity.setTheme(R.style.Theme_InputViewTheme)
            // The activity owns a full-window host. Keep the recorded keyboard in
            // a fixed-size child so later Android traversals cannot resize the
            // review root from keyboard height to the entire activity window.
            activity.setContentView(FrameLayout(activity).apply {
                addView(root, FrameLayout.LayoutParams(root.dp(360), pixelHeight))
            })
            keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
            root.addView(keyboard, FrameLayout.LayoutParams(root.dp(360), pixelHeight))
            keyboard.keyActionListener = KeyActionListener { action, _ ->
                if (action is KeyAction.FcitxKeyAction) typed.append(action.act)
            }
            controller.visible()
            advance(32)
            layout()
            keyboard.onInputMethodUpdate(InputMethodEntry("rime", "", "", "", "", "zh", "", false, InputMethodSubMode()))
            keyboard.onPunctuationUpdate(mapOf("," to "，", "." to "。"))
            layout()
            effect = ReflectionHelpers.getField(keyboard, "pressEffectLayer")
            effect.prepareTexturesForTest()
            ReflectionHelpers.setField(effect, "random", Random(1401))
            now = SystemClock.uptimeMillis()
            start = now
            ReflectionHelpers.setField(effect, "clock", { now } as () -> Long)
            keys = descendants(keyboard).filterIsInstance<KeyView>()
            letters = keys.filterIsInstance<TextKeyView>().filter { it.mainText.text.toString().matches(Regex("[a-zA-Z]")) }
                .associateBy { it.mainText.text.toString().lowercase() }
            assertEquals(26, letters.size)
            assertTrue(keyboard.isAttachedToWindow && keyboard.isShown)
            frame().recycle()
        }

        private fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(root.dp(360), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(pixelHeight, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }

        fun bounds(key: KeyView) = Rect().also { key.getDrawingRect(it); keyboard.offsetDescendantRectToMyCoords(key, it) }

        fun go(time: Long) {
            require(time >= elapsed)
            now = start + time
            advance(time - elapsed)
            elapsed = time
        }

        fun touch(letter: String, action: Int, downAt: Long = elapsed) {
            val rectangle = bounds(letters.getValue(letter))
            val event = MotionEvent.obtain(start + downAt, now, action, rectangle.exactCenterX(), rectangle.exactCenterY(), 0)
            try { assertTrue(keyboard.dispatchTouchEvent(event)) } finally { event.recycle() }
        }

        fun frame(): Bitmap {
            assertEquals("The actual portrait keyboard keeps its measured width", root.dp(360), root.width)
            assertEquals("Activity traversals must preserve the keyboard-height review surface", pixelHeight, root.height)
            return Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply { drawColor(Color.BLACK); root.draw(this) }
            }
        }

        /** Observe the real appearance Canvas while its normal black mask, fill and glyphs draw. */
        fun motionFrame(key: KeyView): MotionFrame {
            val painter = key.keySurfacePainter
            var scale = Float.NaN
            var centreY = Float.NaN
            var sampleX = Float.NaN
            var sampleY = Float.NaN
            return try {
                key.keySurfacePainter = KeyView.KeySurfacePainter { canvas, width, height ->
                    @Suppress("DEPRECATION")
                    val matrix = Matrix(canvas.matrix)
                    val values = FloatArray(9).also(matrix::getValues)
                    scale = values[Matrix.MSCALE_X]
                    val points = floatArrayOf(width / 2f, height / 2f, width / 2f, height / 4f)
                    matrix.mapPoints(points)
                    centreY = points[1]
                    sampleX = points[2]
                    sampleY = points[3]
                    painter?.draw(canvas, width, height)
                }
                val image = frame()
                assertTrue("The actual key surface must draw through the recorded transform", scale.isFinite())
                val x = sampleX.toInt()
                val y = sampleY.toInt()
                assertTrue(x in 0 until image.width && y in 0 until image.height)
                MotionFrame(image, scale, centreY, image.getPixel(x, y), key.floatingFaceOpacity())
            } finally { key.keySurfacePainter = painter }
        }

        fun withoutLegends(): Bitmap {
            val labels = descendants(keyboard).filter { it is TextView || it is ImageView }
            val visibility = labels.map { it.visibility }
            return try {
                labels.forEach { it.visibility = View.INVISIBLE }
                frame()
            } finally { labels.zip(visibility).forEach { (view, visible) -> view.visibility = visible } }
        }

        fun gapMask(): BooleanArray {
            val result = BooleanArray(root.width * root.height) { true }
            keys.forEach { key ->
                val rectangle = bounds(key).apply { inset(key.hMargin, key.vMargin) }
                for (y in rectangle.top.coerceAtLeast(0) until rectangle.bottom.coerceAtMost(root.height))
                    for (x in rectangle.left.coerceAtLeast(0) until rectangle.right.coerceAtMost(root.width))
                        result[y * root.width + x] = false
            }
            return result
        }

        fun assertBlackSurface(label: String) {
            val surface = withoutLegends()
            try {
                for (y in 0 until surface.height) for (x in 0 until surface.width)
                    assertEquals("$label: backgrounds/cap edges must disappear into black at $x,$y", 0, intensity(surface.getPixel(x, y)))
            } finally { surface.recycle() }
        }

        fun assertReadable(frame: Bitmap, letter: String) {
            val key = letters.getValue(letter)
            assertEquals("Original light legend colour is stable", key.theme.keyTextColor, key.mainText.currentTextColor)
            assertEquals("No black outline is painted around the glyph", 0f, key.mainText.contrastOutlineWidth, 0f)
            val visibility = key.mainText.visibility
            val behind = try { key.mainText.visibility = View.INVISIBLE; this.frame() } finally { key.mainText.visibility = visibility }
            try {
                var visiblePixels = 0
                val rectangle = bounds(key)
                for (y in rectangle.top until rectangle.bottom) for (x in rectangle.left until rectangle.right) {
                    val a = frame.getPixel(x, y)
                    val b = behind.getPixel(x, y)
                    if (maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)),
                            abs(Color.blue(a) - Color.blue(b))) >= 40) visiblePixels++
                }
                assertTrue("The real white $letter glyph remains legible without a stroke ($visiblePixels pixels)", visiblePixels >= 12)
            } finally { behind.recycle() }
        }

        override fun close() {
            keyboard.onDetach()
            controller.pause().stop().destroy()
        }
    }

    private data class MotionFrame(val image: Bitmap, val scale: Float, val centreY: Float,
        val actualCapColour: Int, val modelOpacity: Float)

    @Test
    fun actualSamCapCompressesContinuouslyAndRemainsVisibleThroughItsRebound() {
        Keyboard(ThemePrefs.RippleShape.Sam, numberRow = false).use { board ->
            val key = board.letters.getValue("f")
            val idle = board.motionFrame(key)
            save(idle.image, "single/idle")
            val baselineY = idle.centreY
            assertEquals(1f, idle.scale, 0.00001f)
            idle.image.recycle()
            val rows = ArrayList<String>()
            var earlyScale = 1f
            var previousTime = -1L
            var released = false
            board.touch("f", MotionEvent.ACTION_DOWN)
            for (index in 0..84) {
                val time = index * 1000L / 60
                if (!released && time >= 50) {
                    board.go(50)
                    board.touch("f", MotionEvent.ACTION_UP, 0)
                    released = true
                    assertEquals("Input finishes on UP, independently of the visible rebound", "f", board.typed.toString())
                }
                board.go(time)
                val observed = board.motionFrame(key)
                val capLight = intensity(observed.actualCapColour)
                val capChroma = chroma(observed.actualCapColour)
                try {
                    save(observed.image, "single/frame-%03d".format(index))
                    board.assertReadable(observed.image, "f")
                    rows.add("$index,$time,${observed.scale},${observed.centreY},$capLight,$capChroma,${observed.modelOpacity}")
                    if (time == 0L) {
                        assertEquals("A resting key must not jump to a compressed size on DOWN", 1f, observed.scale, 0.00001f)
                        assertEquals("The cap starts from its real resting position", baselineY, observed.centreY, 0.00001f)
                        assertTrue("Immediate feedback comes from the actual coloured face", capLight >= 30 && capChroma >= 12)
                    }
                    if (time == 16L) {
                        assertTrue("The first display frame already shows smooth compression", observed.scale < 0.998f && observed.scale > 0.98f)
                        assertTrue("The real cap moves down as it compresses", observed.centreY > baselineY)
                        earlyScale = observed.scale
                    }
                    if (time == 33L) assertTrue("The second display frame continues down rather than remaining at an instant seed", observed.scale < earlyScale)
                    if (time in 233L..350L) {
                        assertTrue("The cap visibly rises above rest after UP", observed.scale > 1.005f && observed.centreY < baselineY)
                        assertTrue("The Sam coloured cap remains visible during the real rise and landing, after the old 180ms cutoff", capLight >= 30 && capChroma >= 12)
                    }
                    if (time == 500L) assertTrue("The soft landing still has a visible coloured face", capLight >= 20 && capChroma >= 10)
                    assertTrue(time > previousTime)
                    previousTime = time
                } finally { observed.image.recycle() }
            }
            board.assertBlackSurface("Single tap completes its physical landing")
            val restored = board.motionFrame(key)
            try {
                assertEquals(1f, restored.scale, 0f)
                assertEquals(baselineY, restored.centreY, 0f)
                assertEquals(0, intensity(restored.actualCapColour))
            } finally { restored.image.recycle() }
            File("build/outputs/effect-checks/dev8-sam/single/motion.csv").writeText(
                "frame,time_ms,actual_canvas_scale,actual_cap_centre_y,actual_cap_max_rgb,actual_cap_chroma,model_face_opacity\n" + rows.joinToString("\n") + "\n")
            File("build/outputs/effect-checks/dev8-sam/single/provenance.txt").writeText(
                "source=actual Android TextKeyboard/KeyView/PressEffect\ngraphics=Robolectric NATIVE\n" +
                    "portrait_width_dp=360\nheight_px=${board.pixelHeight}\ndensity=${board.root.resources.displayMetrics.density}\n" +
                    "number_row=false\nfps=60\nframes=85\ninput=f\ndown_ms=0\nup_ms=50\n" +
                    "colour=#00F0FF\nrecorded_transform=unmodified production key surface Canvas\n" +
                    "colour_measurement=actual transformed upper-quarter cap pixel away from glyph\n" +
                    "direct_native_frames=true\ninterpolation=false\nphysical_device_capture=false\n")
        }
    }

    @Test
    fun idleIsSeamlessBlackAndOneTapRapidlyRevealsBroadGuttersBehindBlackCaps() {
        Keyboard(ThemePrefs.RippleShape.Sam).use { board ->
            board.frame().also { save(it, "idle"); it.recycle() }
            board.assertBlackSurface("Before typing")
            board.go(5000)
            board.assertBlackSurface("No idle breathing after five seconds")
            board.touch("f", MotionEvent.ACTION_DOWN)
            board.go(5050)
            board.touch("f", MotionEvent.ACTION_UP, 5000)
            val gap = board.gapMask()
            board.go(5150)
            val frame = board.frame()
            try {
                save(frame, "tap-150ms")
                board.assertReadable(frame, "f")
                var colouredGaps = 0
                var left = frame.width
                var right = -1
                for (y in 0 until frame.height) for (x in 0 until frame.width)
                    if (gap[y * frame.width + x] && coloured(frame.getPixel(x, y))) {
                        colouredGaps++
                        left = minOf(left, x); right = maxOf(right, x)
                    }
                assertTrue("A visible field must reveal a substantial fraction of the true gutters by 150ms ($colouredGaps)",
                    colouredGaps >= gap.count { it } * 0.06)
                assertTrue("Rapid expansion must span multiple adjacent key columns, not just the pressed cap", 
                    right - left >= board.letters.getValue("f").width * 3)
                val surface = board.withoutLegends()
                try {
                    board.letters.filterKeys { it != "f" }.values.forEach { key ->
                        val r = board.bounds(key)
                        assertEquals("Unpressed cap interiors remain black while the gaps reveal the field", 0,
                            intensity(surface.getPixel(r.centerX(), r.centerY())))
                    }
                } finally { surface.recycle() }
            } finally { frame.recycle() }
            board.go(7000)
            board.assertBlackSurface("After the wave and rebound finish")
        }
    }

    private data class GapLevels(
        val total: Int, val coloured: Int, val dark: Int, val bright: Int, val nearPeak: Int,
        val p10: Int, val p50: Int, val p90: Int, val peak: Int
    ) {
        fun csv() = "$total,$coloured,$dark,$bright,$nearPeak,$p10,$p50,$p90,$peak"
    }

    /** Pixel distributions are recorded for review, independently of the renderer's exposure formula. */
    private fun gapLevels(frame: Bitmap, mask: BooleanArray): GapLevels {
        val histogram = IntArray(256)
        var total = 0
        var colouredCount = 0
        for (y in 0 until frame.height) for (x in 0 until frame.width) if (mask[y * frame.width + x]) {
            val pixel = frame.getPixel(x, y)
            histogram[intensity(pixel)]++
            total++
            if (coloured(pixel)) colouredCount++
        }
        fun percentile(percent: Int): Int {
            var count = 0
            for (value in histogram.indices) {
                count += histogram[value]
                if (count * 100 >= total * percent) return value
            }
            return 255
        }
        return GapLevels(total, colouredCount, histogram.take(17).sum(), histogram.drop(160).sum(),
            histogram.drop(210).sum(), percentile(10), percentile(50), percentile(90), histogram.indexOfLast { it > 0 })
    }

    /** A fixed two-colour setting isolates spatial colour retention from random palette changes. */
    private fun separatedColoursRetainTheirOwnRegions() {
        Keyboard(ThemePrefs.RippleShape.Sam, numberRow = false, customColours = "#FF3355,#18FFC1").use { board ->
            val mask = board.gapMask()
            board.touch("q", MotionEvent.ACTION_DOWN)
            val firstColour = requireNotNull(PressEffect.colorForKey(board.letters.getValue("q").id))
            board.go(50)
            board.touch("q", MotionEvent.ACTION_UP, 0)
            board.go(150)
            val before = board.frame()
            save(before, "separated-before-second-key-150ms")
            board.touch("p", MotionEvent.ACTION_DOWN)
            val secondColour = requireNotNull(PressEffect.colorForKey(board.letters.getValue("p").id))
            assertNotEquals("The controlled adjacent presses must use different colours", firstColour, secondColour)
            board.go(200)
            board.touch("p", MotionEvent.ACTION_UP, 150)
            board.go(300)
            val after = board.frame()
            try {
                save(after, "separated-both-regions-300ms")
                board.assertReadable(after, "q")
                board.assertReadable(after, "p")
                fun colourDistance(pixel: Int, colour: Int): Float {
                    val a = intensity(pixel).coerceAtLeast(1).toFloat()
                    val b = intensity(colour).coerceAtLeast(1).toFloat()
                    return abs(Color.red(pixel) / a - Color.red(colour) / b) +
                        abs(Color.green(pixel) / a - Color.green(colour) / b) +
                        abs(Color.blue(pixel) / a - Color.blue(colour) / b)
                }
                fun matchingRegion(frame: Bitmap, source: String, expected: Int, other: Int): Pair<Int, Int> {
                    val cell = board.bounds(board.letters.getValue(source))
                    // Examine a physical neighbourhood of one key pitch, using only
                    // gutters so bright caps/letters cannot substitute for the field.
                    val region = Rect(cell).apply { inset(-cell.width(), -cell.height() / 2) }
                    var eligible = 0
                    var matching = 0
                    for (y in region.top.coerceAtLeast(0) until region.bottom.coerceAtMost(frame.height))
                        for (x in region.left.coerceAtLeast(0) until region.right.coerceAtMost(frame.width)) {
                            val pixel = frame.getPixel(x, y)
                            if (!mask[y * frame.width + x] || !coloured(pixel)) continue
                            eligible++
                            if (colourDistance(pixel, expected) < colourDistance(pixel, other)) matching++
                        }
                    return eligible to matching
                }
                val original = matchingRegion(before, "q", firstColour, secondColour)
                val old = matchingRegion(after, "q", firstColour, secondColour)
                val fresh = matchingRegion(after, "p", secondColour, firstColour)
                assertTrue("The original key must first produce a measurable local coloured field", original.first > 0)
                assertTrue("The older local glow remains visible after a distant press", old.first > 0)
                assertTrue("The new key also produces its own local glow", fresh.first > 0)
                assertTrue("Most of the older left-hand region keeps its own hue rather than following the new key", old.second * 2 > old.first)
                assertTrue("Most of the new right-hand region receives the new key's hue", fresh.second * 2 > fresh.first)
                File("build/outputs/effect-checks/dev8-sam/separated-colours.csv").writeText(
                    "region,time_ms,visible_gap_pixels,pixels_closer_to_own_colour\n" +
                        "old_before,150,${original.first},${original.second}\n" +
                        "old_after,300,${old.first},${old.second}\n" +
                        "new_after,300,${fresh.first},${fresh.second}\n")
            } finally { before.recycle(); after.recycle() }
        }
    }

    @Test
    fun continuousTypingExportsRealFramesAndEachLetterKeepsItsOwnTail() {
        separatedColoursRetainTheirOwnRegions()
        Keyboard(ThemePrefs.RippleShape.Sam, randomColours = true, numberRow = false).use { board ->
            data class Gesture(val time: Long, val letter: String, val action: Int, val down: Long)
            val input = listOf("q", "p", "a", "l", "z", "m", "w", "o", "s", "k", "x", "n")
            val presses = input.indices.map { it * 80L }
            val gestures = input.flatMapIndexed { index, letter -> listOf(
                Gesture(presses[index], letter, MotionEvent.ACTION_DOWN, presses[index]),
                Gesture(presses[index] + 50, letter, MotionEvent.ACTION_UP, presses[index])) }
            var next = 0
            var focusIndex = 0
            val metrics = ArrayList<String>()
            val levelsByTime = ArrayList<Pair<Long, GapLevels>>()
            val mask = board.gapMask()
            board.frame().also { save(it, "burst/idle"); it.recycle() }
            for (index in 0..144) {
                val time = index * 1000L / 60
                while (next < gestures.size && gestures[next].time <= time) {
                    val gesture = gestures[next++]
                    board.go(gesture.time)
                    board.touch(gesture.letter, gesture.action, gesture.down)
                    if (gesture.action == MotionEvent.ACTION_DOWN) focusIndex = input.indexOf(gesture.letter)
                }
                board.go(time)
                val firstKeyMotion = board.motionFrame(board.letters.getValue("q"))
                val frame = firstKeyMotion.image
                try {
                    save(frame, "burst/frame-%03d".format(index))
                    // Test the current and preceding real glyphs, rather than paying
                    // for twelve hidden-glyph reference draws on every exported frame.
                    board.assertReadable(frame, input[focusIndex])
                    if (focusIndex > 0) board.assertReadable(frame, input[focusIndex - 1])
                    val levels = gapLevels(frame, mask)
                    levelsByTime.add(time to levels)
                    metrics.add("$index,$time,${levels.csv()}")
                    if (time in 80..300) {
                        assertTrue("The preceding Q face motion must not be cancelled when another key begins",
                            firstKeyMotion.modelOpacity > 0f)
                        assertTrue("The preceding Q must retain a visibly coloured real cap during later presses",
                            coloured(firstKeyMotion.actualCapColour))
                    }
                } finally { frame.recycle() }
            }
            assertEquals("All rapid cross-key touches still enter text in sequence", input.joinToString(""), board.typed.toString())
            assertTrue("Dense typing must leave visible light outside the actual keycaps", levelsByTime.any { it.second.coloured > 0 })
            assertTrue("The final sampled gaps return to black after both light and cap motion finish",
                levelsByTime.filter { it.first >= 2350 }.all { it.second.peak == 0 })
            board.assertBlackSurface("End of sequential typing")
            File("build/outputs/effect-checks/dev8-sam/burst/metrics.csv").writeText(
                "frame,time_ms,gap_pixels,coloured_gap_pixels,dark_le16,bright_ge160,near_peak_ge210,p10,p50,p90,peak\n" + metrics.joinToString("\n") + "\n")
            File("build/outputs/effect-checks/dev8-sam/burst/provenance.txt").writeText(
                "source=actual Android TextKeyboard and PressEffect\ngraphics=Robolectric NATIVE\n" +
                    "portrait_width_dp=360\nheight_px=${board.pixelHeight}\ndensity=${board.root.resources.displayMetrics.density}\nbase_height_dp=288\nfps=60\nframes=145\n" +
                    "input=${input.joinToString("")}\npress_times_ms=${presses.joinToString(",")}\nrelease_after_ms=50\n" +
                    "portrait_number_row=false\npalette=production Cyberpunk random\nseed=1401\nripple_shape=Sam\n" +
                    "idle_breathing_preference=true (Sam suppresses it)\npopup=false\n" +
                    "gap_metrics=fixed physical gaps outside all resting cap interiors; no glyphs or deliberately hidden cap drawings\n" +
                    "direct_native_frames=true\ninterpolation=false\nphysical_device_capture=false\n")
        }
    }

    @Test
    fun samColourTailRemainsVisibleAfterGeometryLandsWithEitherMotionPath() {
        for (motion in listOf(ThemePrefs.KeyMotionEffect.Press, ThemePrefs.KeyMotionEffect.Off)) {
            Keyboard(ThemePrefs.RippleShape.Sam, numberRow = false, configure = {
                it.keyMotionEffect.setValue(motion)
                it.reboundMotionDuration.setValue(400)
                // Old, explicitly saved mist values must not shorten Sam's tail.
                it.pressKeyHoldTime.setValue(20)
                it.pressKeyRetreatTime.setValue(20)
                it.samKeyHoldTime.setValue(80)
                it.samKeyRetreatTime.setValue(800)
            }).use { board ->
                board.touch("f", MotionEvent.ACTION_DOWN)
                board.go(50)
                board.touch("f", MotionEvent.ACTION_UP, 0)
                board.go(650)
                val late = board.motionFrame(board.letters.getValue("f"))
                try {
                    assertEquals("$motion has already returned to its resting geometry", 1f, late.scale, 0.0001f)
                    assertTrue("$motion retains actual coloured cap pixels after landing", coloured(late.actualCapColour))
                    board.assertReadable(late.image, "f")
                    save(late.image, "release-tail-${motion.name}-650ms")
                } finally { late.image.recycle() }
                board.go(1600)
                board.assertBlackSurface("$motion finishes the independent colour tail and wave")
            }
        }
    }

    @Test
    fun samCapTimingPreferencesChangeRealPixelsWithoutChangingLegacyValues() {
        val samples = listOf(200, 1600).map { fade ->
            Keyboard(ThemePrefs.RippleShape.Sam, numberRow = false, configure = {
                it.keyMotionEffect.setValue(ThemePrefs.KeyMotionEffect.Off)
                it.samKeyHoldTime.setValue(120)
                it.samKeyRetreatTime.setValue(fade)
                it.pressKeyHoldTime.setValue(30)
                it.pressKeyRetreatTime.setValue(100)
            }).use { board ->
                board.touch("f", MotionEvent.ACTION_DOWN)
                board.go(50)
                board.touch("f", MotionEvent.ACTION_UP, 0)
                board.go(1200)
                val frame = board.motionFrame(board.letters.getValue("f"))
                try { intensity(frame.actualCapColour) } finally { frame.image.recycle() }
            }
        }
        assertEquals("A short selected Sam tail has fully ended", 0, samples[0])
        assertTrue("A long selected Sam tail is still visibly coloured", samples[1] >= 25)
        assertEquals(30, ThemeManager.prefs.pressKeyHoldTime.getValue())
        assertEquals(100, ThemeManager.prefs.pressKeyRetreatTime.getValue())
    }

    @Test
    fun oldModesRemainVisuallyDistinctAndDisabledOrDetachedSamStopsDrawing() {
        val frames = ThemePrefs.RippleShape.entries.associateWith { shape ->
            Keyboard(shape).use { board ->
                board.touch("f", MotionEvent.ACTION_DOWN)
                board.go(50)
                board.touch("f", MotionEvent.ACTION_UP, 0)
                board.go(250)
                board.frame().also { save(it, "mode-${shape.name}-250ms") }
            }
        }
        try {
            frames.values.toList().let { images ->
                for (i in images.indices) for (j in i + 1 until images.size)
                    assertFalse("Each mode must produce visibly different actual keyboard pixels", images[i].sameAs(images[j]))
            }
        } finally { frames.values.forEach(Bitmap::recycle) }
        Keyboard(ThemePrefs.RippleShape.Sam).use { board ->
            board.touch("f", MotionEvent.ACTION_DOWN)
            board.go(50)
            board.touch("f", MotionEvent.ACTION_UP, 0)
            AppPrefs.getInstance().advanced.disableAnimation.setValue(true)
            board.go(150)
            board.frame().recycle()
            board.assertBlackSurface("Animation disabled during an active effect")
            AppPrefs.getInstance().advanced.disableAnimation.setValue(false)
            board.go(300)
            board.touch("a", MotionEvent.ACTION_DOWN)
            board.keyboard.onDetach()
            board.go(350)
            val offscreen = Bitmap.createBitmap(board.root.width, board.root.height, Bitmap.Config.ARGB_8888)
            Canvas(offscreen).apply { drawColor(Color.BLACK); board.effect.drawUnder(this) }
            try {
                for (y in 0 until offscreen.height) for (x in 0 until offscreen.width)
                    assertEquals("Detached keyboards retain no rendered wave", 0, intensity(offscreen.getPixel(x, y)))
            } finally { offscreen.recycle() }
        }
    }
}
