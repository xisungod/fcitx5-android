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
    private fun coloured(pixel: Int): Boolean = intensity(pixel) >= 25 &&
        intensity(pixel) - minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) >= 12

    private fun save(image: Bitmap, name: String) {
        val file = File("build/outputs/effect-checks/dev6-sam/$name.png")
        file.parentFile.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private inner class Keyboard(shape: ThemePrefs.RippleShape, randomColours: Boolean = false) : AutoCloseable {
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val root = FrameLayout(activity)
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
            prefs.pressColorMode.setValue(if (randomColours) ThemePrefs.PressColorMode.Random else ThemePrefs.PressColorMode.Single)
            prefs.pressSingleColor.setValue(Color.parseColor("#00F0FF"))
            prefs.portraitNumberRow.setValue(true)
            AppPrefs.getInstance().keyboard.popupOnKeyPress.setValue(false)
            activity.setTheme(R.style.Theme_InputViewTheme)
            // The activity owns a full-window host. Keep the recorded keyboard in
            // a fixed-size child so later Android traversals cannot resize the
            // review root from keyboard height to the entire activity window.
            activity.setContentView(FrameLayout(activity).apply {
                addView(root, FrameLayout.LayoutParams(root.dp(360), root.dp(288)))
            })
            keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
            root.addView(keyboard, FrameLayout.LayoutParams(root.dp(360), root.dp(288)))
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
                View.MeasureSpec.makeMeasureSpec(root.dp(288), View.MeasureSpec.EXACTLY))
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
            assertEquals("Activity traversals must preserve the keyboard-height review surface", root.dp(288), root.height)
            return Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply { drawColor(Color.BLACK); root.draw(this) }
            }
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

    @Test
    fun continuousTypingExportsRealFramesAndEachLetterKeepsItsOwnTail() {
        Keyboard(ThemePrefs.RippleShape.Sam, randomColours = true).use { board ->
            data class Gesture(val time: Long, val letter: String, val action: Int, val down: Long)
            val input = listOf("s", "a", "m")
            val gestures = input.flatMapIndexed { index, letter -> listOf(
                Gesture(index * 120L, letter, MotionEvent.ACTION_DOWN, index * 120L),
                Gesture(index * 120L + 50, letter, MotionEvent.ACTION_UP, index * 120L)) }
            var next = 0
            val metrics = ArrayList<String>()
            val mask = board.gapMask()
            for (index in 0..108) {
                val time = index * 1000L / 60
                while (next < gestures.size && gestures[next].time <= time) {
                    val gesture = gestures[next++]
                    board.go(gesture.time)
                    board.touch(gesture.letter, gesture.action, gesture.down)
                }
                board.go(time)
                val frame = board.frame()
                try {
                    save(frame, "burst/frame-%03d".format(index))
                    input.forEach { board.assertReadable(frame, it) }
                    var illuminated = 0
                    for (y in 0 until frame.height) for (x in 0 until frame.width)
                        if (mask[y * frame.width + x] && coloured(frame.getPixel(x, y))) illuminated++
                    metrics.add("$index,$time,$illuminated")
                    if (time in 120..220) assertTrue("The preceding S face must not be cancelled when A begins",
                        board.letters.getValue("s").floatingFaceOpacity() > 0f)
                } finally { frame.recycle() }
            }
            assertEquals("All real touch events still enter text in sequence", "sam", board.typed.toString())
            board.assertBlackSurface("End of sequential typing")
            File("build/outputs/effect-checks/dev6-sam/burst/metrics.csv").writeText(
                "frame,time_ms,coloured_gap_pixels\n" + metrics.joinToString("\n") + "\n")
            File("build/outputs/effect-checks/dev6-sam/burst/provenance.txt").writeText(
                "source=actual Android TextKeyboard and PressEffect\ngraphics=Robolectric NATIVE\n" +
                    "portrait_width_dp=360\nheight_dp=288\nfps=60\nframes=109\n" +
                    "input=sam\npress_times_ms=0,120,240\nrelease_after_ms=50\n" +
                    "palette=production Cyberpunk random\nseed=1401\nripple_shape=Sam\n" +
                    "idle_breathing_preference=true (Sam suppresses it)\npopup=false\n" +
                    "direct_native_frames=true\ninterpolation=false\nphysical_device_capture=false\n")
        }
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
