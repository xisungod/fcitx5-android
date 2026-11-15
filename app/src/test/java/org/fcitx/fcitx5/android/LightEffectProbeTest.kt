/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodSubMode
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyboardSizePolicy
import org.fcitx.fcitx5.android.input.keyboard.PressEffect
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

/**
 * Renders the real keyboard with the default light effect at fixed moments after a press and
 * writes the frames side by side, so a person can compare them with a reference recording.
 * Also checks the key face itself during a long hold, rather than measuring
 * the surrounding halo or counting the permanent white legend as feedback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class LightEffectProbeTest {
    @Before
    fun prepare() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val application = RuntimeEnvironment.getApplication()
        val ui = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(ui, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, ui) }
        AppPrefs.init(application.getSharedPreferences("light-effect-probe", Context.MODE_PRIVATE))
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

    private inner class Keyboard : AutoCloseable {
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val root = FrameLayout(activity)
        val pixelHeight = KeyboardSizePolicy.heightForLayout(root.dp(288), true, true, true)
        val keyboard: TextKeyboard
        val letters: Map<String, TextKeyView>
        var elapsed = 0L
            private set
        private var now = SystemClock.uptimeMillis()
        private val start: Long

        init {
            // As on the phone it is compared with; everything else is a new installation's default.
            ThemeManager.prefs.portraitNumberRow.setValue(true)
            AppPrefs.getInstance().keyboard.popupOnKeyPress.setValue(false)
            activity.setTheme(R.style.Theme_InputViewTheme)
            activity.setContentView(FrameLayout(activity).apply {
                addView(root, FrameLayout.LayoutParams(root.dp(360), pixelHeight))
            })
            keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
            root.addView(keyboard, FrameLayout.LayoutParams(root.dp(360), pixelHeight))
            keyboard.keyActionListener = KeyActionListener { _, _ -> }
            controller.visible()
            advance(32)
            layout()
            keyboard.onInputMethodUpdate(InputMethodEntry("rime", "", "", "", "", "zh", "", false, InputMethodSubMode()))
            keyboard.onPunctuationUpdate(mapOf("," to "，", "." to "。"))
            layout()
            val effect: PressEffect = ReflectionHelpers.getField(keyboard, "pressEffectLayer")
            effect.prepareTexturesForTest()
            ReflectionHelpers.setField(effect, "random", Random(1401))
            now = SystemClock.uptimeMillis()
            start = now
            ReflectionHelpers.setField(effect, "clock", { now } as () -> Long)
            letters = descendants(keyboard).filterIsInstance<TextKeyView>()
                .filter { it.mainText.text.toString().matches(Regex("[a-zA-Z]")) }
                .associateBy { it.mainText.text.toString().lowercase() }
            assertEquals(26, letters.size)
            frame().recycle()
        }

        private fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(root.dp(360), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(pixelHeight, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }

        fun go(time: Long) {
            require(time >= elapsed)
            now = start + time
            advance(time - elapsed)
            elapsed = time
        }

        fun touch(letter: String, action: Int, downAt: Long) {
            val key: KeyView = letters.getValue(letter)
            val rectangle = Rect().also { key.getDrawingRect(it); keyboard.offsetDescendantRectToMyCoords(key, it) }
            val event = MotionEvent.obtain(start + downAt, now, action,
                rectangle.exactCenterX(), rectangle.exactCenterY(), 0)
            try { assertTrue(keyboard.dispatchTouchEvent(event)) } finally { event.recycle() }
        }

        fun frame(): Bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawColor(Color.BLACK); root.draw(this) }
        }

        fun faceChroma(image: Bitmap, letter: String): Int {
            val key = letters.getValue(letter)
            val cap = key.restingCapBounds()
            root.offsetDescendantRectToMyCoords(key.parent as View, cap)
            // Sample inside the cap, left of the letter and below its upper hint.
            val x = cap.left + cap.width() / 6
            val y = cap.top + cap.height() / 3
            var chroma = 0
            for (dy in -1..1) for (dx in -1..1) {
                val pixel = image.getPixel(x + dx, y + dy)
                val high = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                val low = minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                chroma += high - low
            }
            return chroma / 9
        }

        override fun close() {
            keyboard.onDetach()
            controller.pause().stop().destroy()
        }
    }

    private fun lit(image: Bitmap): Int {
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        return pixels.count { pixel ->
            val high = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
            val low = minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
            high >= 40 && high - low >= 24
        }
    }

    private fun totalColour(image: Bitmap): Long {
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        // For one hue, chroma tracks the coloured brightness and removes the
        // permanent white legends and neutral key backgrounds.
        return pixels.sumOf { pixel ->
            val high = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
            val low = minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
            (high - low).toLong()
        }
    }

    /** [presses] are letter to DOWN time; release time is controlled independently. */
    private fun record(name: String, presses: List<Pair<String, Long>>, moments: List<Long>, columns: Int,
        holdMillis: Long = 70L, momentLabel: (Long) -> String = { "$it ms" },
        observe: Keyboard.(Long, Bitmap) -> Unit = { _, _ -> }): List<Int> {
        val events = presses.flatMap { (letter, at) ->
            listOf(Triple(at, letter, MotionEvent.ACTION_DOWN), Triple(at + holdMillis, letter, MotionEvent.ACTION_UP))
        }.sortedBy { it.first }
        val coverage = ArrayList<Int>()
        Keyboard().use { board ->
            val tileWidth = board.root.width / 2
            val tileHeight = board.root.height / 2
            val rows = (moments.size + columns - 1) / columns
            val sheet = Bitmap.createBitmap(tileWidth * columns, (tileHeight + 14) * rows, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(sheet).apply { drawColor(Color.rgb(24, 24, 24)) }
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 11f }
            val smooth = Paint(Paint.FILTER_BITMAP_FLAG)
            var next = 0
            moments.forEachIndexed { index, moment ->
                while (next < events.size && events[next].first <= moment) {
                    val (at, letter, action) = events[next++]
                    board.go(at)
                    board.touch(letter, action, if (action == MotionEvent.ACTION_DOWN) at else at - holdMillis)
                }
                board.go(moment)
                val image = board.frame()
                coverage += lit(image)
                board.observe(moment, image)
                val left = (index % columns) * tileWidth
                val top = (index / columns) * (tileHeight + 14)
                canvas.drawBitmap(image, null, Rect(left, top + 14, left + tileWidth, top + 14 + tileHeight), smooth)
                canvas.drawText(momentLabel(moment), left + 3f, top + 11f, label)
                image.recycle()
            }
            val file = File("build/outputs/effect-checks/probe/$name.jpg")
            file.parentFile.mkdirs()
            file.outputStream().use { sheet.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            sheet.recycle()
        }
        return coverage
    }

    @Test
    fun onePressSpreadsLightSmoothlyAndReturnsToBlack() {
        val moments = (0L..600L step 16).toList() + listOf(600L, 800, 1000, 1300, 1500, 2000)
        val totals = mutableListOf<Long>()
        val coverage = record("one-press", listOf("g" to 0L), moments, columns = 7) { time, frame ->
            if (time <= 600) totals.add(totalColour(frame))
        }
        assertTrue("Light must appear after a press: $coverage", coverage.max() > 400)
        val peak = totals.max()
        val maxStep = totals.zipWithNext { a, b -> abs(b - a) }.max()
        // The peak is the reference for the whole sequence, including the
        // initial near-dark frame where a relative-to-previous ratio is undefined.
        val change = 100.0 * maxStep / peak
        println("SAM one-press maximum frame-to-frame change: $change% of peak brightness")
        assertTrue("Worst change: $change% of peak brightness ($totals)", change <= 12.0)
        assertTrue("And be gone in the end: $coverage", coverage.last() < coverage.max() / 20)
    }

    @Test
    fun typingAWordLayersSeveralLights() {
        val presses = listOf("n" to 0L, "i" to 150L, "h" to 300L, "a" to 450L, "o" to 600L)
        val moments = listOf(0L, 50, 100, 150, 200, 250, 300, 350, 400, 450, 500, 550, 600, 650, 700, 800, 900, 1000, 1200, 1500, 2000)
        val coverage = record("typing", presses, moments, columns = 7)
        assertTrue("Light must appear while typing: $coverage", coverage.max() > 400)
    }

    @Test
    fun fastTypingKeepsBrightAndDimPlaces() {
        val presses = "zheshiyigeceshi".mapIndexed { index, letter -> letter.toString() to index * 120L }
        val moments = (0L..2400L step 120).map { it + 60 }
        val hueSamples = IntArray(12)
        val hsv = FloatArray(3)
        var framesWithBoth = 0
        val coverage = record("fast-typing", presses, moments, columns = 7) { time, bitmap ->
            if (time !in 300L..1600L) return@record
            var bright = 0
            var dark = 0
            var total = 0
            for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
                total++
                Color.colorToHSV(bitmap.getPixel(x, y), hsv)
                if (hsv[2] < 0.10f) dark++
                if (hsv[1] >= 0.48f && hsv[2] >= 0.22f) {
                    hueSamples[(hsv[0] / 30f).toInt().coerceIn(0, 11)]++
                    bright++
                }
            }
            if (dark > total / 4 && bright > total / 100) framesWithBoth++
        }
        assertTrue("Light must appear while typing: $coverage", coverage.max() > 400)
        assertTrue("Fast typing preserves dark areas alongside visible neon", framesWithBoth >= 4)
        assertTrue("Rapid overlapping waves retain several distinguishable hues: ${hueSamples.toList()}",
            hueSamples.count { it > 100 } >= 4)
    }

    @Test
    fun holdingAKeyThenReleasingReturnsToBlack() {
        val lift = 1800L
        val moments = listOf(0L, 50, 200, 400, 650, 900, 1200, 1500, 1700, 1750, 1799,
            lift, 1833, 1900, 2000, 2100, 2200, 2500, 2600, 2799, 2800)
        val capChroma = mutableMapOf<Long, Int>()
        val coverage = record("long-hold", listOf("g" to 0L), moments, columns = 7,
            holdMillis = lift,
            momentLabel = { time ->
                when {
                    time == 0L -> "0 ms - DOWN"
                    time < lift -> "$time ms - HELD"
                    time == lift -> "$time ms - UP"
                    else -> "$time ms - UP + ${time - lift} ms"
                }
            }) { time, bitmap -> capChroma[time] = faceChroma(bitmap, "g") }
        assertTrue("Light must appear during the press: $coverage", coverage.max() > 400)
        val initial = capChroma.getValue(0L)
        assertTrue("The face responds on DOWN: $capChroma", initial >= 40)
        for (time in listOf(900L, 1700L)) {
            assertTrue("G keeps at least 30% of its initial colour at ${time}ms: $capChroma",
                capChroma.getValue(time) >= initial * 0.30f)
        }
        assertTrue("The visible face does not jump as the finger lifts: $capChroma",
            abs(capChroma.getValue(lift) - capChroma.getValue(lift - 1)) <= 2)
        assertEquals("Less than one second after UP the face is black: $capChroma", 0, capChroma.getValue(2799L))
        assertEquals("The last frame has no remaining coloured pixels", 0, coverage.last())
    }
}
