package org.fcitx.fcitx5.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.input.keyboard.IdleBreathing
import org.fcitx.fcitx5.android.input.keyboard.PressEffect
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.PressColorPalette
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
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
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PressEffectRenderingTest {
    @Before
    fun attachUiApplicationContext() {
        val application = RuntimeEnvironment.getApplication()
        val uiApplication = FcitxApplication()
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Void>(uiApplication, "attachBaseContext",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, uiApplication)
        }
    }

    private class Harness(press: Boolean = false, expansion: Int = 500, fade: Int = 280,
        ignition: Int = 100, hold: Int = 260, retreat: Int = 480,
        production: Boolean = false, palette: IntArray = PressEffect.CYBERPUNK, sequential: Boolean = false,
        glowBrightness: Int = 100, randomSeed: Int? = null,
        waveHold: Int = 0, idle: Boolean = true) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = FrameLayout(activity)
        var invalidations = 0
        val host = object : View(activity) {
            override fun invalidate() {
                invalidations++
                super.invalidate()
            }
        }
        var now = 0L
        var enabled = true
        val envelope = IdleBreathing()
        val effect = PressEffect(host, palette, true, 100, expansion, fade, 0,
            press, idle, envelope, intArrayOf(0xff00f0ff.toInt(), 0xffbe38ff.toInt()),
            clock = { now }, ignitionTimeMs = ignition, keyHoldTimeMs = hold,
            keyRetreatTimeMs = retreat, animationsAllowed = { enabled },
            keySurfaceEffects = production, holdWhilePressed = production, dimExit = production,
            exactKeyShape = production, normalizeOldLight = production, keyCornerRadius = 5f,
            sequentialColors = sequential, glowBrightnessPercent = glowBrightness, waveHoldTimeMs = waveHold)

        init {
            if (randomSeed != null) org.robolectric.util.ReflectionHelpers.setField(effect, "random", kotlin.random.Random(randomSeed))
            // Reproduce attach-before-visible without relying on a subsequent visibility callback.
            effect.setActive(true)
            assertFalse(envelope.running)
            activity.setContentView(root)
            root.addView(host, FrameLayout.LayoutParams(600, 240))
            controller.visible()
            host.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, 600, 240)
            assertTrue(host.isAttachedToWindow && host.isShown)
        }

        fun render(time: Long): Bitmap {
            now = time
            val image = Bitmap.createBitmap(600, 240, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image)
            canvas.drawColor(Color.BLACK)
            effect.drawUnder(canvas)
            effect.drawOver(canvas)
            return image
        }

        fun surface(keyId: Int, time: Long): Pair<Bitmap, Float> {
            now = time
            val image = Bitmap.createBitmap(60, 70, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image)
            canvas.drawColor(Color.BLACK)
            val brightness = effect.drawKeySurface(canvas, keyId, 60, 70)
            return image to brightness
        }

        fun candidate(time: Long): Bitmap {
            now = time
            val image = Bitmap.createBitmap(600, 60, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image)
            canvas.drawColor(Color.BLACK)
            canvas.translate(0f, 60f)
            effect.drawExtension(canvas)
            return image
        }

        fun caps(theme: Theme.Builtin, time: Long): Bitmap {
            val image = render(time)
            val canvas = Canvas(image)
            repeat(3) { i ->
                val keyId = 1301 + i
                val key = TextKeyView(activity, theme, KeyDef.Appearance.Text(
                    arrayOf("f", "g", "h")[i], 24f, viewId = keyId))
                key.keySurfacePainter = KeyView.KeySurfacePainter { target, width, height ->
                    effect.drawKeySurface(target, keyId, width, height, key.hMargin, key.vMargin)
                }
                key.measure(View.MeasureSpec.makeMeasureSpec(60, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(70, View.MeasureSpec.EXACTLY))
                key.layout(0, 0, 60, 70)
                val saveCount = canvas.save()
                canvas.translate(210f + i * 60f, 65f)
                key.draw(canvas)
                canvas.restoreToCount(saveCount)
            }
            return image
        }

        fun press(keyId: Int, pointerId: Int, time: Long, x: Int = 300) {
            now = time
            effect.onPress(x.toFloat(), 100f, android.graphics.Rect(x - 30, 65, x + 30, 135), true, keyId, pointerId)
        }

        fun finish() {
            effect.setActive(false)
            controller.pause().stop().destroy()
        }
    }

    private fun peak(image: Bitmap): Int {
        var result = 0
        for (y in 0 until image.height step 3) for (x in 0 until image.width step 3) {
            val p = image.getPixel(x, y)
            result = maxOf(result, Color.red(p), Color.green(p), Color.blue(p))
        }
        return result
    }

    private fun save(image: Bitmap, name: String) {
        val file = File("build/outputs/effect-checks/$name.png")
        file.parentFile.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun currentProductionDefaultsHarness(palette: IntArray = PressEffect.CYBERPUNK): Harness {
        val application = RuntimeEnvironment.getApplication()
        val storage = application.getSharedPreferences("press-effect-current-production-defaults", Context.MODE_PRIVATE)
        storage.edit().clear().commit()
        AppPrefs.init(storage)
        val prefs = ThemeManager.prefs
        // AppPrefs.init can reuse preferences registered by an earlier class.
        // Measure actual defaults rather than that class's custom timing fixture.
        prefs.pressEffect.sharedPreferences.edit().clear().commit()
        return Harness(press = true, production = true, idle = false, palette = palette, randomSeed = 1401,
            ignition = prefs.pressIgnitionTime.getValue(), expansion = prefs.pressExpansionTime.getValue(),
            waveHold = prefs.pressWaveHoldTime.getValue(), fade = prefs.pressFadeOutTime.getValue(),
            hold = prefs.pressKeyHoldTime.getValue(), retreat = prefs.pressKeyRetreatTime.getValue())
    }

    @Test
    fun productionDefaultStrongLightStaysNearThreeToFourColumnsWithWeakVisibleCandidateSpill() {
        val h = currentProductionDefaultsHarness()
        try {
            h.press(1680, 0, 0)
            h.now = 50
            h.effect.onRelease(0)
            var peakArea = 0
            var peakAreaTime = 0L
            var peakAreaSpan = 0
            var nearPeak = 0
            var candidatePeak = 0
            var candidateVisiblePixels = 0
            for (time in 50L..800L step 25L) {
                val image = h.render(time)
                var area = 0
                var first = image.width
                var last = -1
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    if (x in 270 until 330 && y in 65 until 135) continue
                    val pixel = image.getPixel(x, y)
                    val high = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                    val low = minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                    nearPeak = maxOf(nearPeak, high)
                    if (high >= 90 && high - low >= 15) {
                        area++
                        first = minOf(first, x)
                        last = maxOf(last, x)
                    }
                }
                if (area > peakArea) {
                    peakArea = area
                    peakAreaTime = time
                    peakAreaSpan = last - first + 1
                }
                image.recycle()
                val candidate = h.candidate(time)
                candidatePeak = maxOf(candidatePeak, peak(candidate))
                var visible = 0
                for (y in 0 until candidate.height) for (x in 0 until candidate.width) {
                    val pixel = candidate.getPixel(x, y)
                    val high = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                    val low = minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                    if (high >= 3 && high > low) visible++
                }
                candidateVisiblePixels = maxOf(candidateVisiblePixels, visible)
                candidate.recycle()
            }
            val report = "peak_area_time_ms=$peakAreaTime\nstrong_area_pixels=$peakArea\n" +
                "strong_span_px=$peakAreaSpan\nstrong_span_columns=${peakAreaSpan / 60f}\n" +
                "near_peak_rgb=$nearPeak\ncandidate_peak_rgb=$candidatePeak\n" +
                "candidate_visible_pixels=$candidateVisiblePixels\n"
            File("build/outputs/effect-checks/v15-soft-default-field.txt").apply {
                parentFile!!.mkdirs()
            }.writeText(report)
            assertTrue("Compactness cannot pass by dimming away the near field: $report", nearPeak >= 110)
            assertTrue("The peak strong field has a real lit body outside its key: $report", peakArea >= 750)
            assertTrue("At peak lit area, strong light covers three to four letter columns: $report",
                peakAreaSpan in 180..240)
            assertTrue("Candidate light stays subordinate to the visible near field: $report", candidatePeak * 4 <= nearPeak)
            assertTrue("The weak continuation reaches real candidate pixels instead of disabling spill: $report",
                candidateVisiblePixels >= 150)
        } finally { h.finish() }
    }

    @Test
    fun productionDefaultNewDownRetainsAtLeastNinetyPercentOfDistantOldLight() {
        val cyan = intArrayOf(0xff00ffff.toInt())
        val control = currentProductionDefaultsHarness(cyan)
        val added = currentProductionDefaultsHarness(cyan)
        try {
            for ((h, keyId) in arrayOf(control to 1681, added to 1682)) {
                h.press(keyId, 0, 0, 180)
                h.render(0).recycle()
                h.now = 50
                h.effect.onRelease(0)
                h.render(50).recycle()
            }
            added.press(1683, 1, 200, 420)
            var minimumRetained = 1.0
            val report = StringBuilder("new_down_age_ms,old_lit_pixels,baseline_peak_rgb,retained_fraction\n")
            for (age in longArrayOf(0, 16, 33, 50)) {
                val before = control.render(200 + age)
                val after = added.render(200 + age)
                var baselineEnergy = 0L
                var retainedEnergy = 0L
                var eligible = 0
                var baselinePeak = 0
                for (y in 0 until before.height) for (x in 0 until before.width) {
                    if (kotlin.math.hypot((x - 420).toDouble(), (y - 100).toDouble()) < 120.0) continue
                    val original = before.getPixel(x, y)
                    val high = maxOf(Color.red(original), Color.green(original), Color.blue(original))
                    val low = minOf(Color.red(original), Color.green(original), Color.blue(original))
                    baselinePeak = maxOf(baselinePeak, high)
                    if (high < 30 || high - low < 15) continue
                    val current = after.getPixel(x, y)
                    val next = maxOf(Color.red(current), Color.green(current), Color.blue(current))
                    baselineEnergy += high
                    // Added light cannot compensate for dimming a different old pixel.
                    retainedEnergy += minOf(high, next)
                    eligible++
                }
                val retained = if (baselineEnergy > 0) retainedEnergy.toDouble() / baselineEnergy else 0.0
                minimumRetained = minOf(minimumRetained, retained)
                report.append("$age,$eligible,$baselinePeak,$retained\n")
                before.recycle()
                after.recycle()
                assertTrue("The comparison must contain substantial old light at age=$age", eligible >= 600 && baselinePeak >= 100)
            }
            File("build/outputs/effect-checks/v15-soft-old-light.txt").apply {
                parentFile!!.mkdirs()
            }.writeText(report.toString())
            assertTrue("New DOWN cannot abruptly dim distant existing light in its first 50ms: $report",
                minimumRetained >= 0.90)
        } finally {
            control.finish()
            added.finish()
        }
    }

    @Test
    fun firstVisibleDrawStartsWithoutAnyKeyPressAndActuallyChangesPixels() {
        val h = Harness()
        try {
            val before = h.render(0)
            assertEquals(0, peak(before))
            assertTrue(h.envelope.running)
            val lit = h.render(4400)
            val dim = h.render(7000)
            assertTrue("Default breathing must be visible, not just a running timer", peak(lit) >= 40)
            assertTrue("The breath must have visibly different bright and dark phases", peak(lit) > peak(dim) * 5)
            assertEquals(Color.BLACK, lit.getPixel(0, 0))
            assertEquals(Color.BLACK, lit.getPixel(599, 239))
            save(before, "idle-before")
            save(lit, "idle-bright")
            save(dim, "idle-dim")
        } finally { h.finish() }
    }

    @Test
    fun idleSchedulesARealViewInvalidationWithoutTouchEvents() {
        val h = Harness()
        try {
            h.render(0)
            val count = h.invalidations
            h.now = 1850
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1850))
            assertTrue(h.invalidations > count)
        } finally { h.finish() }
    }

    @Test
    fun hidingStopsDrawingAndShowingRestartsWithANewDelay() {
        val h = Harness()
        try {
            h.render(0)
            assertTrue(peak(h.render(4400)) >= 40)
            h.host.visibility = View.GONE
            h.effect.syncVisibility()
            assertFalse(h.envelope.running)
            assertEquals(0, peak(h.render(9000)))
            h.host.visibility = View.VISIBLE
            // Again test draw recovery even when a visibility notification was missed.
            assertEquals(0, peak(h.render(10000)))
            assertTrue(peak(h.render(14400)) >= 40)
            h.effect.setActive(false)
            assertEquals(0, peak(h.render(20000)))
            assertFalse(h.envelope.running)
        } finally { h.finish() }
    }

    @Test
    fun disablingAndReenablingEffectsCanRecoverOnTheNextVisibleDraw() {
        val h = Harness()
        try {
            h.render(0)
            assertTrue(peak(h.render(4400)) >= 40)
            h.enabled = false
            assertEquals(0, peak(h.render(4500)))
            assertFalse(h.envelope.running)
            h.enabled = true
            assertEquals(0, peak(h.render(5000)))
            assertTrue(peak(h.render(9400)) >= 40)
        } finally { h.finish() }
    }
    @Test
    fun expiredIdleGlowCannotBeRestartedByVisibleDrawing() {
        val h = Harness()
        try {
            h.render(0)
            assertTrue(peak(h.render(4400)) >= 40)
            assertEquals(0, peak(h.render(61000)))
            assertEquals(0, peak(h.render(120000)))
            assertNull(h.envelope.nextFrameDelay(120000))
        } finally { h.finish() }
    }

    @Test
    fun animationInvalidatesChildSurfacesAsWellAsTheHostDisplayList() {
        val h = Harness(press = true)
        var surfaces = 0
        h.effect.invalidateKeySurfaces = { surfaces++ }
        try {
            h.render(0)
            h.effect.onPress(300f, 100f)
            assertTrue(surfaces > 0)
            h.render(0)
            val before = surfaces
            h.now = 50
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            assertTrue("Hardware-cached key faces must be invalidated on animation frames", surfaces > before)
            val pressed = surfaces
            h.effect.onRelease()
            assertTrue(surfaces > pressed)
        } finally { h.finish() }
    }

    @Test
    fun bottomRowRippleReachesTheCandidateStripAndThenClears() {
        val h = Harness(press = true)
        try {
            h.render(0)
            h.effect.onPress(300f, 220f)
            h.effect.onRelease()
            fun candidateStrip(time: Long): Bitmap {
                h.now = time
                val image = Bitmap.createBitmap(600, 52, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(image)
                canvas.drawColor(Color.BLACK)
                canvas.translate(0f, 52f)
                h.effect.drawExtension(canvas)
                return image
            }
            assertTrue("Bottom-row mist must continue above the keyboard", peak(candidateStrip(490)) > 0)
            assertEquals(0, peak(candidateStrip(1200)))
        } finally { h.finish() }
    }

    @Test
    fun keyChangesColourBeforeTheSameWaveExpandsOutwards() {
        val h = Harness(press = true)
        try {
            h.render(0)
            h.effect.onPress(300f, 100f, android.graphics.Rect(270, 70, 330, 140), true)
            h.effect.onRelease()
            val initial = h.render(0)
            assertTrue(peak(initial) > 100)
            assertEquals(Color.BLACK, initial.getPixel(360, 100))
            assertEquals(Color.BLACK, h.render(40).getPixel(360, 100))
            val spread = h.render(350)
            var outside = 0
            for (y in 60..150) for (x in 345..400) {
                val pixel = spread.getPixel(x, y)
                outside = maxOf(outside, Color.red(pixel), Color.green(pixel), Color.blue(pixel))
            }
            assertTrue("Mist must follow the local key colour", outside > 0)
            assertEquals(0, peak(h.render(1200)))
        } finally { h.finish() }
    }

    @Test
    fun keyColourOutlivesTheWaveThenShrinksToASpotAndClears() {
        val h = Harness(press = true, expansion = 100, fade = 100, ignition = 30)
        try {
            h.render(0)
            h.effect.onPress(300f, 100f, android.graphics.Rect(260, 55, 340, 145), true)
            h.effect.onRelease()
            fun area(image: Bitmap): Int {
                var count = 0
                for (y in 45..155) for (x in 250..350) {
                    val pixel = image.getPixel(x, y)
                    if (maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 25) count++
                }
                return count
            }
            val held = h.render(260)
            val shrinking = h.render(470)
            val spot = h.render(640)
            assertTrue("A lifted key must still be coloured after 0.26 seconds", peak(held) > 150)
            assertTrue(area(held) > area(shrinking) * 1.5)
            assertTrue(area(shrinking) > area(spot) * 2)
            assertTrue(area(spot) > 0)
            assertEquals(0, peak(h.render(800)))
            save(held, "key-colour-hold")
            save(shrinking, "key-colour-retreat")
            save(spot, "key-colour-spot")
        } finally { h.finish() }
    }

    @Test
    fun longUserSelectedHoldIsNotCutOffByAShortWave() {
        val h = Harness(press = true, expansion = 100, fade = 100, ignition = 30, hold = 1000, retreat = 500)
        try {
            h.render(0)
            h.effect.onPress(300f, 100f, android.graphics.Rect(260, 55, 340, 145), true)
            h.effect.onRelease()
            assertTrue(peak(h.render(950)) > 150)
            assertEquals(0, peak(h.render(1550)))
        } finally { h.finish() }
    }

    @Test
    fun productionHeldKeyWaitsForItsOwnFingerAndReleaseStartsTheTail() {
        val h = Harness(press = true, production = true, hold = 120, retreat = 1400)
        try {
            h.render(0)
            h.press(11, 1, 0, 200)
            h.press(12, 2, 100, 400)
            h.render(1800)
            assertTrue("First finger is still held despite another key press", peak(h.surface(11, 1800).first) > 150)
            h.effect.onRelease(2)
            assertTrue("Second finger gets a release tail", peak(h.surface(12, 2000).first) > 80)
            assertTrue("Releasing the second finger must not fade the first", peak(h.surface(11, 3400).first) > 150)
            assertEquals(0, peak(h.surface(12, 3400).first))
            h.now = 3500
            h.effect.onRelease(1)
            assertTrue(peak(h.surface(11, 3700).first) > 80)
            assertEquals(0, peak(h.surface(11, 5000).first))
        } finally { h.finish() }
    }

    @Test
    fun productionSlideReleasesOnlyTheSlidingPointer() {
        val h = Harness(press = true, production = true, hold = 120, retreat = 1400)
        try {
            h.render(0)
            h.press(11, 1, 0, 180)
            h.press(12, 2, 100, 360)
            h.press(13, 1, 250, 240)
            h.render(1800)
            assertEquals("Old slide target finishes its release tail", 0, peak(h.surface(11, 1800).first))
            assertTrue(peak(h.surface(12, 1800).first) > 150)
            assertTrue(peak(h.surface(13, 1800).first) > 150)
            h.effect.onRelease()
            assertEquals(0, peak(h.surface(12, 3300).first))
            assertEquals(0, peak(h.surface(13, 3300).first))
        } finally { h.finish() }
    }

    @Test
    fun productionLongFunctionalHoldKeepsItsReleaseTail() {
        val h = Harness(press = true, production = true, hold = 120, retreat = 1400)
        try {
            h.render(0)
            h.effect.onPress(300f, 100f, android.graphics.Rect(240, 65, 360, 135), false, 77, 3)
            assertTrue(peak(h.render(3000)) > 100)
            h.effect.onRelease(3)
            assertTrue("Long-held space/function key cannot disappear immediately on UP", peak(h.render(3400)) > 30)
            assertEquals(0, peak(h.render(4500)))
        } finally { h.finish() }
    }

    @Test
    fun productionWaveCapacityCannotDeleteAStillVisibleKeyFace() {
        val h = Harness(press = true, production = true, hold = 120, retreat = 1400)
        try {
            h.render(0)
            repeat(40) { i ->
                h.press(100 + i, 0, i * 20L)
                h.now += 5
                h.effect.onRelease(0)
            }
            // Forty waves exceed the fixed travelling-light queue, but each key keeps its own tail.
            assertTrue("First key must fade naturally even after 40 rapid presses", peak(h.surface(100, 800).first) > 20)
            assertEquals(0, peak(h.surface(100, 1600).first))
        } finally { h.finish() }
    }

    @Test
    fun productionRepeatedKeyUsesOnlyItsNewColourAndLegendBrightness() {
        val h = Harness(press = true, production = true, hold = 120, retreat = 1400,
            palette = intArrayOf(0xff00ffff.toInt(), 0xff202060.toInt()), sequential = true)
        try {
            h.render(0)
            h.press(5, 0, 0)
            assertTrue(h.surface(5, 0).second > 0.5f)
            h.effect.onRelease(0)
            h.press(5, 0, 100)
            val dark = h.surface(5, 100)
            assertTrue("Previous bright cyan must not force dark text over the new dark-blue key", dark.second < 0.3f)
            assertTrue(Color.blue(dark.first.getPixel(30, 50)) > Color.green(dark.first.getPixel(30, 50)) * 2)
            assertEquals(0xff202060.toInt(), PressEffect.colorForKey(5))
            h.effect.clear()
            assertNull(PressEffect.colorForKey(5))
        } finally { h.finish() }
    }

    @Test
    fun productionRepressAfterAnUndrawnExpiredFacePreservesItsPreviewColour() {
        val cyan = 0xff00ffff.toInt()
        val darkBlue = 0xff202060.toInt()
        val h = Harness(press = true, production = true, hold = 120, retreat = 1400,
            palette = intArrayOf(cyan, darkBlue), sequential = true)
        try {
            h.render(0)
            h.press(5, 0, 0)
            assertEquals(cyan, PressEffect.colorForKey(5))
            assertTrue(h.surface(5, 0).second > 0.5f)
            h.now = 40
            h.effect.onRelease(0)
            // Input can arrive before the next traversal after a busy main thread.
            // Do not draw while the old 120 + 1400 ms key tail expires.
            h.press(5, 0, 1700)
            assertEquals("Retiring an undrawn old face must preserve the new popup tint",
                darkBlue, PressEffect.colorForKey(5))
            val fresh = h.surface(5, 1700)
            val pixel = fresh.first.getPixel(30, 50)
            assertTrue("The real key surface must use the new dark-blue tint, not old cyan",
                Color.blue(pixel) > Color.green(pixel) * 2)
            assertTrue("Legend contrast must follow the new colour", fresh.second < 0.3f)
        } finally { h.finish() }
    }

    @Test
    fun productionStaticHeldFaceStopsSchedulingWholeKeyboardFrames() {
        val h = Harness(press = true, production = true, hold = 120, retreat = 1400)
        try {
            h.render(0)
            h.press(11, 1, 0)
            h.render(2000)
            assertTrue(peak(h.surface(11, 2000).first) > 150)
            // DOWN posts ordinary one-shot View invalidations too. Drain those before
            // measuring whether the effect itself keeps a static held face animating.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            h.render(2000)
            val before = h.invalidations
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            assertEquals("A static held face must not keep invalidating the full view", before, h.invalidations)
            h.effect.onRelease(1)
            h.render(2000)
            val released = h.invalidations
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            assertTrue("The release tail must resume animation", h.invalidations > released)
        } finally { h.finish() }
    }

    @Test
    fun productionExpiredFaceRemovesItsPerKeyPreviewColour() {
        val h = Harness(press = true, production = true, hold = 120, retreat = 1400)
        try {
            h.render(0)
            h.press(99, 0, 0)
            assertNotNull(PressEffect.colorForKey(99))
            h.effect.onRelease(0)
            h.render(1700)
            assertNull("Expiring a face must not leave an orphaned key-ID colour", PressEffect.colorForKey(99))
            h.press(100, 0, 1800)
            assertNotNull(PressEffect.colorForKey(100))
            h.effect.clear()
            assertNull(PressEffect.colorForKey(100))
        } finally { h.finish() }
    }

    @Test
    fun productionGlowBrightnessZeroLeavesTheKeyFaceButRemovesTravellingLight() {
        // Separate activity lifetimes: making a second Activity visible may hide the
        // first host, which is unrelated to the brightness value being compared.
        fun renderCase(brightness: Int, keyId: Int): Pair<Bitmap, Bitmap> {
            val h = Harness(press = true, production = true, hold = 120, retreat = 1400,
                palette = intArrayOf(0xff00ffff.toInt()), glowBrightness = brightness)
            try {
                h.effect.prepareTexturesForTest()
                h.render(0)
                h.press(keyId, 0, 0)
                assertTrue("Brightness sample must have an attached visible window",
                    h.host.isAttachedToWindow && h.host.isShown && h.host.windowVisibility == View.VISIBLE)
                val field = h.render(450)
                val face = h.surface(keyId, 450).first
                assertTrue("The press must reach the key surface before comparing peripheral light", peak(face) > 150)
                save(field, "glow-brightness-$brightness")
                save(face, "glow-brightness-$brightness-key-face")
                return field to face
            } finally { h.finish() }
        }
        fun outsidePeak(image: Bitmap): Int {
            var maximum = 0
            // An irregular field can favour any direction. Sample the whole periphery
            // instead of assuming that a randomly warped shape must be bright on the right.
            for (y in 0 until image.height) for (x in 0 until image.width) {
                if (x in 270 until 330 && y in 65 until 135) continue
                val pixel = image.getPixel(x, y)
                maximum = maxOf(maximum, Color.red(pixel), Color.green(pixel), Color.blue(pixel))
            }
            return maximum
        }
        val bright = renderCase(100, 201)
        val off = renderCase(0, 202)
        val peripheral = outsidePeak(bright.first)
        assertTrue("100 percent keeps visible travelling light outside the key (peak=$peripheral)", peripheral > 10)
        assertEquals("0 percent removes the travelling field", 0, outsidePeak(off.first))
        assertTrue("Brightness affects glow only, preserving full key colour", peak(off.second) > 150)
    }

    @Test
    fun productionMaskDensityCannotMoveOrRescaleTheKeyAndItsLight() {
        fun pixels(image: Bitmap): IntArray = IntArray(image.width * image.height).also {
            image.getPixels(it, 0, image.width, 0, 0, image.width, image.height)
        }
        val h = Harness(press = true, production = true, hold = 120, retreat = 1400,
            palette = intArrayOf(0xff00ffff.toInt()))
        try {
            h.effect.prepareTexturesForTest()
            h.effect.setTextureDensityForTest(Bitmap.DENSITY_NONE)
            h.render(0)
            h.press(301, 0, 0)
            val expectedField = pixels(h.render(450))
            val expectedFace = pixels(h.surface(301, 450).first)
            h.effect.setTextureDensityForTest(160)
            assertArrayEquals("mdpi metadata must not alter cloud position or footprint", expectedField, pixels(h.render(450)))
            assertArrayEquals(expectedFace, pixels(h.surface(301, 450).first))
            h.effect.setTextureDensityForTest(320)
            assertArrayEquals("xhdpi metadata must not apply a second transform to cached masks", expectedField, pixels(h.render(450)))
            assertArrayEquals(expectedFace, pixels(h.surface(301, 450).first))
        } finally {
            h.effect.setTextureDensityForTest(Bitmap.DENSITY_NONE)
            h.finish()
        }
        // The compatibility shrink-mask path obeys the same pixel geometry contract.
        val legacy = Harness(press = true, production = false, palette = intArrayOf(0xff00ffff.toInt()))
        try {
            legacy.render(0)
            legacy.press(302, 0, 0)
            val expected = pixels(legacy.render(40))
            legacy.effect.setTextureDensityForTest(320)
            assertArrayEquals("Legacy cached key masks must also retain their exact key bounds", expected, pixels(legacy.render(40)))
        } finally {
            legacy.effect.setTextureDensityForTest(Bitmap.DENSITY_NONE)
            legacy.finish()
        }
    }

    @Test
    fun productionSinglePressShowsAnEarlyAreaOfLightAndAnOutwardMovingFront() {
        val h = Harness(press = true, production = true, expansion = 900, fade = 450,
            hold = 120, retreat = 1400, palette = intArrayOf(0xff00ffff.toInt()), randomSeed = 1401)
        try {
            h.effect.prepareTexturesForTest()
            h.render(0)
            h.press(401, 0, 0)
            h.now = 50
            h.effect.onRelease(0)
            fun outsideCount(image: Bitmap, threshold: Int): Int {
                var count = 0
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    if (x in 262 until 338 && y in 57 until 143) continue
                    val pixel = image.getPixel(x, y)
                    if (maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) >= threshold) count++
                }
                return count
            }
            fun lightRadius(image: Bitmap): Double {
                var energy = 0.0
                var radialEnergy = 0.0
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    if (x in 262 until 338 && y in 57 until 143) continue
                    val pixel = image.getPixel(x, y)
                    val value = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                    if (value < 30) continue
                    energy += value
                    radialEnergy += value * kotlin.math.hypot((x - 300).toDouble(), (y - 100).toDouble())
                }
                return if (energy > 0) radialEnergy / energy else 0.0
            }
            val early = h.render(200)
            val late = h.render(550)
            save(early, "flow-single-200ms")
            save(late, "flow-single-550ms")
            val earlyArea = outsideCount(early, 60)
            assertTrue("200ms must show a visible area outside the key, not a few changed pixels (area=$earlyArea)", earlyArea >= 600)
            val earlyRadius = lightRadius(early)
            val lateRadius = lightRadius(late)
            assertTrue("The light field must visibly travel outwards (r200=$earlyRadius,r550=$lateRadius)", lateRadius >= earlyRadius + 20.0)
        } finally { h.finish() }
    }

    @Test
    fun productionRapidTypingKeepsVisibleNewLightAndCannotRelightOldWavesAfterStopping() {
        val h = Harness(press = true, production = true, expansion = 900, fade = 450,
            hold = 120, retreat = 1400, palette = intArrayOf(0xff00ffff.toInt()), randomSeed = 1401)
        try {
            h.effect.prepareTexturesForTest()
            h.render(0)
            var visibleFrames = 0
            var inputPeak = 0
            var lastTime = 0L
            repeat(12) { i ->
                val time = i * 150L
                h.press(500 + i, 0, time, intArrayOf(220, 300, 380, 300)[i % 4])
                h.render(time)
                h.now = time + 50
                h.effect.onRelease(0)
                h.render(time + 50)
                lastTime = time + 149
                val image = h.render(lastTime)
                if (i >= 4) {
                    var area = 0
                    for (y in 0 until image.height) for (x in 0 until image.width) {
                        // Exclude every pressed key-cell in this synthetic row.
                        if (x in 180 until 420 && y in 55 until 145) continue
                        val pixel = image.getPixel(x, y)
                        val value = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                        inputPeak = maxOf(inputPeak, value)
                        if (value >= 60) area++
                    }
                    if (area >= 1500) visibleFrames++
                    val trail = org.robolectric.util.ReflectionHelpers.getField<org.fcitx.fcitx5.android.input.keyboard.PressEffectTrail>(h.effect, "ripples")
                    assertTrue("Typing must not divide the latest front down to a fifth", trail[trail.size - 1].lightGain >= 0.45f)
                }
                if (i == 11) save(image, "flow-typing-active")
            }
            assertTrue("Repeated input needs a sustained visible field outside all key-cells", visibleFrames >= 6)
            val gains = mutableMapOf<Long, Float>()
            val trail = org.robolectric.util.ReflectionHelpers.getField<org.fcitx.fcitx5.android.input.keyboard.PressEffectTrail>(h.effect, "ripples")
            for (i in 0 until trail.size) gains[trail[i].start] = trail[i].lightGain
            var postStopPeak = 0
            for (step in 1..12) {
                val image = h.render(lastTime + step * 100L)
                for (i in 0 until trail.size) {
                    val r = trail[i]
                    val before = gains[r.start]!!
                    assertTrue("Removing other waves cannot brighten an older wave", r.lightGain <= before + 0.0001f)
                    gains[r.start] = r.lightGain
                }
                if (step >= 4) {
                    for (y in 0 until image.height) for (x in 0 until image.width) {
                        if (x in 180 until 420 && y in 55 until 145) continue
                        val pixel = image.getPixel(x, y)
                        postStopPeak = maxOf(postStopPeak, Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                    }
                }
                if (step == 4) save(image, "flow-typing-release-400ms")
            }
            assertTrue("The tail must not become brighter than the input field", postStopPeak <= inputPeak + 20)
        } finally { h.finish() }
    }

    @Test
    fun productionSlowWaveHasARealHoldThenUsesTheConfiguredFade() {
        val h = Harness(press = true, production = true, expansion = 1400, waveHold = 350,
            fade = 900, hold = 120, retreat = 1800, idle = false,
            palette = intArrayOf(0xff00ffff.toInt()), randomSeed = 1401)
        fun pixels(image: Bitmap): IntArray = IntArray(image.width * image.height).also {
            image.getPixels(it, 0, image.width, 0, 0, image.width, image.height)
        }
        try {
            h.effect.prepareTexturesForTest()
            h.render(0).recycle()
            h.press(801, 0, 0)
            h.now = 50
            h.effect.onRelease(0)
            val heldEarly = h.render(1550)
            val heldLate = h.render(1800)
            assertTrue("The fully expanded hold must retain visible colour", peak(heldEarly) > 30)
            val earlyPixels = pixels(heldEarly)
            val latePixels = pixels(heldLate)
            assertTrue("Held light keeps gently moving instead of freezing a cached shape",
                earlyPixels.indices.count { earlyPixels[it] != latePixels[it] } > 100)
            assertTrue("Micro-motion must preserve a stable held colour strength", kotlin.math.abs(peak(heldEarly) - peak(heldLate)) <= 5)
            val fading = h.render(2400)
            assertTrue("A 900ms fade must still have a visible field beyond the old 1450ms limit", peak(fading) > 10)
            assertTrue("Fade changes opacity without cutting the whole field at once", peak(fading) < peak(heldLate))
            assertEquals("100 + 1400 + 350 + 900 completes at 2750ms", 0, peak(h.render(2750)))
            save(heldEarly, "soft-wave-hold-1550ms")
            save(heldLate, "soft-wave-hold-1800ms")
            save(fading, "soft-wave-fade-2400ms")
        } finally { h.finish() }
    }

    @Test
    fun productionLongExpansionHoldAndFadeReachTheirActualConfiguredEnd() {
        val h = Harness(press = true, production = true, expansion = 4000, waveHold = 2000,
            fade = 5000, hold = 120, retreat = 5000, idle = false,
            palette = intArrayOf(0xff00ffff.toInt()), randomSeed = 1401)
        fun pixels(image: Bitmap): IntArray = IntArray(image.width * image.height).also {
            image.getPixels(it, 0, image.width, 0, 0, image.width, image.height)
        }
        try {
            h.effect.prepareTexturesForTest()
            h.render(0).recycle()
            h.press(802, 0, 0)
            h.now = 50
            h.effect.onRelease(0)
            val early = h.render(200)
            assertTrue("Long expansion must still start promptly", peak(early) > 80)
            val holdStart = h.render(4400)
            val holdEnd = h.render(5800)
            assertTrue("The full 2000ms hold retains visible colour", peak(holdStart) > 30 && peak(holdEnd) > 30)
            assertTrue("Long held light continues moving without being cut off", !pixels(holdStart).contentEquals(pixels(holdEnd)))
            val late = h.render(9000)
            assertTrue("The 5000ms fade must retain real pixels at 9000ms", peak(late) > 12)
            assertEquals("100 + 4000 + 2000 + 5000 reaches 11100ms", 0, peak(h.render(11100)))
            save(early, "long-setting-local-200ms")
            save(holdEnd, "long-setting-hold-5800ms")
            save(late, "long-setting-fade-9000ms")
        } finally { h.finish() }
    }

    @Test
    fun productionExpansionSliderChangesTheActualFrontWithoutSlowingTheLocalStart() {
        fun radius(image: Bitmap): Double {
            var weighted = 0.0
            var energy = 0.0
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val pixel = image.getPixel(x, y)
                val value = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                if (value <= 2) continue
                energy += value
                weighted += value * kotlin.math.hypot((x - 300).toDouble(), (y - 100).toDouble())
            }
            return weighted / energy
        }
        fun renderCase(expansion: Int): Pair<Bitmap, Bitmap> {
            val h = Harness(press = true, production = true, expansion = expansion, waveHold = 350,
                fade = 900, hold = 120, retreat = 1800, idle = false,
                palette = intArrayOf(0xff00ffff.toInt()), randomSeed = 1401)
            try {
                h.effect.prepareTexturesForTest()
                h.render(0).recycle()
                h.press(803, 0, 0)
                h.now = 50
                h.effect.onRelease(0)
                return h.render(200) to h.render(950)
            } finally { h.finish() }
        }
        val fast = renderCase(900)
        val slow = renderCase(4000)
        assertTrue("Both speeds need immediate visible local feedback", peak(fast.first) > 80 && peak(slow.first) > 80)
        assertTrue("The configured expansion time must change the rendered propagation distance",
            radius(fast.second) >= radius(slow.second) + 20.0)
        save(fast.second, "expansion-900ms-front-at-950ms")
        save(slow.second, "expansion-4000ms-front-at-950ms")
    }

    @Test
    fun productionLongSettingsKeepTheNewestTypingLightAndFixedWaveStorage() {
        val h = Harness(press = true, production = true, expansion = 4000, waveHold = 2000,
            fade = 5000, hold = 120, retreat = 5000, idle = false,
            palette = intArrayOf(0xff00ffff.toInt()), randomSeed = 1401)
        try {
            h.effect.prepareTexturesForTest()
            h.render(0).recycle()
            val trail = org.robolectric.util.ReflectionHelpers.getField<org.fcitx.fcitx5.android.input.keyboard.PressEffectTrail>(h.effect, "ripples")
            val identities = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<org.fcitx.fcitx5.android.input.keyboard.PressEffectTrail.Ripple, Boolean>())
            var lastTime = 0L
            repeat(80) { i ->
                h.press(900 + i, 0, i * 150L)
                h.render(i * 150L).recycle()
                h.now += 50
                h.effect.onRelease(0)
                lastTime = i * 150L + 149
                val image = h.render(lastTime)
                assertTrue("Long trails must not divide the latest light among dozens of waves", trail[trail.size - 1].lightGain >= 0.45f)
                assertTrue("The long-setting active field remains visible", peak(image) > 70)
                for (j in 0 until trail.size) identities.add(trail[j])
                assertTrue("Wave storage stays bounded regardless of the selected duration", trail.size <= 32)
                image.recycle()
            }
            assertEquals("Typing reuses exactly the fixed 32 wave objects", 32, identities.size)
            val gains = mutableMapOf<Long, Float>()
            val bridgeGains = mutableMapOf<Long, Float>()
            for (i in 0 until trail.size) gains[trail[i].start] = trail[i].lightGain
            for (i in 0 until trail.size) bridgeGains[trail[i].start] = trail[i].bridgeGain
            repeat(12) { step ->
                h.render(lastTime + (step + 1) * 400L).recycle()
                for (i in 0 until trail.size) {
                    val r = trail[i]
                    assertTrue("Long-set tails cannot become brighter after a burst", r.lightGain <= gains[r.start]!! + 0.0001f)
                    assertTrue("The far bridge also cannot brighten as other waves disappear", r.bridgeGain <= bridgeGains[r.start]!! + 0.0001f)
                    gains[r.start] = r.lightGain
                    bridgeGains[r.start] = r.bridgeGain
                }
            }
        } finally { h.finish() }
    }

    @Test
    fun productionSlowTypingLetsTheWeakBridgeReachTheCandidateStrip() {
        val h = Harness(press = true, production = true, expansion = 1400, waveHold = 350,
            fade = 900, hold = 120, retreat = 1800, idle = false,
            palette = intArrayOf(0xff00ffff.toInt()), randomSeed = 1401)
        fun colouredStripArea(image: Bitmap): Int {
            var count = 0
            for (y in 0 until 7) for (x in 0 until image.width) {
                val p = image.getPixel(x, y)
                val high = maxOf(Color.red(p), Color.green(p), Color.blue(p))
                val low = minOf(Color.red(p), Color.green(p), Color.blue(p))
                if (high >= 3 && high > low) count++
            }
            return count
        }
        try {
            h.effect.prepareTexturesForTest()
            h.render(0).recycle()
            val trail = org.robolectric.util.ReflectionHelpers.getField<org.fcitx.fcitx5.android.input.keyboard.PressEffectTrail>(h.effect, "ripples")
            var visibleSamples = 0
            repeat(24) { i ->
                val time = i * 150L
                h.press(1100 + i, 0, time, intArrayOf(120, 300, 480)[i % 3])
                h.render(time).recycle()
                h.now = time + 50
                h.effect.onRelease(0)
                val field = h.render(time + 149)
                val nearPeak = peak(field)
                field.recycle()
                val candidate = h.candidate(time + 149)
                if (colouredStripArea(candidate) >= 630) visibleSamples++
                assertTrue("The candidate bridge remains a weak continuation rather than a bright panel",
                    peak(candidate) * 4 <= nearPeak)
                if (i == 22) save(candidate, "soft-typing-candidate-bridge")
                candidate.recycle()
            }
            assertTrue("A long expansion must retain real, faint coloured candidate pixels during input", visibleSamples >= 3)
            val shared = org.robolectric.util.ReflectionHelpers.getField<Any>(h.effect, "neonField")
            assertTrue("The same bounded source field reaches the candidate area",
                org.robolectric.util.ReflectionHelpers.getField<Int>(shared, "sourceCount") in 1..9)
            val past = mutableMapOf<Long, Float>()
            for (i in 0 until trail.size) past[trail[i].start] = trail[i].bridgeGain
            repeat(10) { step ->
                h.render(3599L + (step + 1) * 200L).recycle()
                for (i in 0 until trail.size) {
                    val r = trail[i]
                    assertTrue("Stopping input cannot relight the weak candidate bridge", r.bridgeGain <= past[r.start]!! + 0.0001f)
                    past[r.start] = r.bridgeGain
                }
            }
        } finally { h.finish() }
    }

    @Test
    fun productionDeepCapsKeepUnpressedKeysDarkAndConnectedGapsBright() {
        val h = Harness(press = true, production = true, expansion = 1400, waveHold = 350,
            fade = 900, hold = 120, retreat = 1800, idle = false,
            palette = intArrayOf(0xff00ffff.toInt()), randomSeed = 1401)
        AppPrefs.init(h.activity.getSharedPreferences("press-effect-key-caps", Context.MODE_PRIVATE))
        val prefs = ThemeManager.prefs
        val previousBorder = prefs.keyBorder.getValue()
        val previousStroke = prefs.keyBorderStroke.getValue()
        val previousHorizontal = prefs.keyHorizontalMargin.getValue()
        val previousVertical = prefs.keyVerticalMargin.getValue()
        val previousRadius = prefs.keyRadius.getValue()
        prefs.keyBorder.setValue(true)
        prefs.keyBorderStroke.setValue(false)
        prefs.keyHorizontalMargin.setValue(3)
        prefs.keyVerticalMargin.setValue(4)
        prefs.keyRadius.setValue(5)
        fun caps(theme: Theme.Builtin, time: Long) = h.caps(theme, time)
        fun maximum(image: Bitmap, x: Int, y: Int): Int {
            val p = image.getPixel(x, y)
            return maxOf(Color.red(p), Color.green(p), Color.blue(p))
        }
        try {
            h.effect.prepareTexturesForTest()
            val rest = caps(ThemePreset.XuancaiBlackV09, 0)
            h.now = 0
            h.effect.onPress(300f, 100f, android.graphics.Rect(273, 69, 327, 131), true, 1302, 0)
            h.now = 50
            h.effect.onRelease(0)
            val lit = caps(ThemePreset.XuancaiBlackV09, 400)
            val previous = caps(ThemePreset.XuancaiBlackV09.copy(keyBackgroundColor = 0x80505660.toInt()), 400)
            assertTrue("The unpressed key remains visible on black at rest", maximum(rest, 258, 83) in 11..35)
            assertTrue("The neighbouring face stays dark instead of becoming a coloured tile", maximum(lit, 258, 83) < 45)
            assertTrue("The deeper actual key background admits much less adjacent field colour",
                maximum(previous, 258, 83) > maximum(lit, 258, 83) + 25)
            assertTrue("The connected gap field must be much brighter than the dark neighbour",
                maximum(lit, 270, 83) > maximum(lit, 258, 83) + 70)
            val unpressedEdge = maximum(lit, 266, 100)
            assertTrue("The actual dark cap edge stays visible without an additional black outline",
                unpressedEdge in 11..45 && kotlin.math.abs(unpressedEdge - maximum(lit, 264, 100)) <= 4 &&
                    maximum(lit, 270, 100) > unpressedEdge + 70)
            assertTrue("The pressed left and right edges share their solid face colour without a dark stroke",
                Color.green(lit.getPixel(273, 100)) >= Color.green(lit.getPixel(276, 100)) - 6 &&
                    Color.green(lit.getPixel(326, 100)) >= Color.green(lit.getPixel(324, 100)) - 6 &&
                    maximum(lit, 273, 100) > 120)
            assertTrue("The current exact key face still fills with its selected colour", maximum(lit, 311, 74) > 120)
            save(rest, "grid-key-caps-rest")
            save(lit, "grid-field-real-key-caps-400ms")
            save(previous, "grid-field-old-translucent-caps-400ms")
        } finally {
            prefs.keyBorder.setValue(previousBorder)
            prefs.keyBorderStroke.setValue(previousStroke)
            prefs.keyHorizontalMargin.setValue(previousHorizontal)
            prefs.keyVerticalMargin.setValue(previousVertical)
            prefs.keyRadius.setValue(previousRadius)
            h.finish()
        }
    }

    @Test
    fun productionSoftIgnitionKeepsTheCapContourWhileTheGapLightBuilds() {
        val h = Harness(press = true, production = true, expansion = 1400, waveHold = 350,
            fade = 900, hold = 120, retreat = 1800, idle = false,
            palette = intArrayOf(0xff00ffff.toInt()), randomSeed = 1401)
        AppPrefs.init(h.activity.getSharedPreferences("press-effect-key-caps", Context.MODE_PRIVATE))
        val prefs = ThemeManager.prefs
        val previousBorder = prefs.keyBorder.getValue()
        val previousStroke = prefs.keyBorderStroke.getValue()
        val previousHorizontal = prefs.keyHorizontalMargin.getValue()
        val previousVertical = prefs.keyVerticalMargin.getValue()
        val previousRadius = prefs.keyRadius.getValue()
        prefs.keyBorder.setValue(true)
        prefs.keyBorderStroke.setValue(false)
        prefs.keyHorizontalMargin.setValue(3)
        prefs.keyVerticalMargin.setValue(4)
        prefs.keyRadius.setValue(5)
        fun brightBounds(image: Bitmap): android.graphics.Rect {
            val bounds = android.graphics.Rect(330, 135, 270, 65)
            for (y in 65 until 135) for (x in 270 until 330) {
                val pixel = image.getPixel(x, y)
                if (Color.green(pixel) <= 100 || Color.green(pixel) - Color.red(pixel) <= 60) continue
                bounds.left = minOf(bounds.left, x)
                bounds.top = minOf(bounds.top, y)
                bounds.right = maxOf(bounds.right, x + 1)
                bounds.bottom = maxOf(bounds.bottom, y + 1)
            }
            return bounds
        }
        fun gap(image: Bitmap): Int = Color.green(image.getPixel(270, 83))
        try {
            h.effect.prepareTexturesForTest()
            h.render(0).recycle()
            h.effect.onPress(300f, 100f, android.graphics.Rect(273, 69, 327, 131), true, 1302, 0)
            val pressed = h.caps(ThemePreset.XuancaiBlackV09, 0)
            h.now = 50
            h.effect.onRelease(0)
            var previousGap = gap(h.caps(ThemePreset.XuancaiBlackV09, 100))
            var ignition: Bitmap? = null
            var visible: Bitmap? = null
            for (time in longArrayOf(125, 150, 175, 200)) {
                val image = h.caps(ThemePreset.XuancaiBlackV09, time)
                val currentGap = gap(image)
                assertTrue("Gap light changes over several frames, without an engulfing first-frame jump", currentGap - previousGap <= 70)
                previousGap = currentGap
                when (time) {
                    150L -> ignition = image
                    200L -> visible = image
                    else -> image.recycle()
                }
            }
            val earlyField = requireNotNull(ignition)
            val visibleField = requireNotNull(visible)
            assertEquals("Initial spreading must not make the bright cap abruptly fill its whole touch cell",
                brightBounds(pressed), brightBounds(earlyField))
            assertTrue("The early gap stays subordinate to the immediate key face", gap(earlyField) < 100)
            assertTrue("The front still becomes clearly visible by 200ms", gap(visibleField) > 100)
            assertEquals("The shared press colour remains unchanged", 0xff00ffff.toInt(), PressEffect.colorForKey(1302))
            assertEquals("Slower local ignition does not lengthen the configured wave", 0, peak(h.render(2750)))
            save(pressed, "stable-cap-000ms")
            save(earlyField, "stable-cap-150ms")
            save(visibleField, "stable-cap-flow-200ms")
        } finally {
            prefs.keyBorder.setValue(previousBorder)
            prefs.keyBorderStroke.setValue(previousStroke)
            prefs.keyHorizontalMargin.setValue(previousHorizontal)
            prefs.keyVerticalMargin.setValue(previousVertical)
            prefs.keyRadius.setValue(previousRadius)
            h.finish()
        }
    }

    @Test
    fun productionExpansionSlowsContinuouslyWithoutInternalPausesOrReacceleration() {
        for (expansion in intArrayOf(100, 400, 900, 1400, 4000)) {
            val h = Harness(press = true, production = true, expansion = expansion,
                fade = 900, waveHold = 350, idle = false)
            try {
                val method = PressEffect::class.java.getDeclaredMethod("waveProgress", Long::class.javaPrimitiveType!!)
                    .apply { isAccessible = true }
                fun progress(time: Long) = method.invoke(h.effect, time) as Float
                assertEquals("The wave starts at its key", 0f, progress(0), 0.00001f)
                assertTrue("The light reaches neighbouring keys early", progress(expansion / 4L) > 0.50f)
                assertTrue("Most expansion precedes the soft ending", progress(expansion / 2L) > 0.80f)
                var previous = progress(0)
                var previousSpeed = Float.POSITIVE_INFINITY
                for (time in 2L..expansion.toLong() step 2) {
                    val value = progress(time)
                    assertTrue("Expansion must remain monotone at every configured speed", value + 0.000001f >= previous)
                    val speed = value - previous
                    assertTrue("Outward travel decelerates continuously instead of speeding up again",
                        speed <= previousSpeed + 0.000001f)
                    if (time <= expansion * 3L / 4) assertTrue("The main expansion has no internal stop", speed > 0.00001f)
                    previous = value
                    previousSpeed = speed
                }
                assertEquals("Only the deliberately held full shape stops expanding", 1f, progress(expansion.toLong()), 0.00001f)
            } finally { h.finish() }
        }
    }

    @Test
    fun productionNativeFrontKeepsTravellingAcrossTheJoin() {
        val h = Harness(press = true, production = true, expansion = 1400, waveHold = 350,
            fade = 900, hold = 120, retreat = 1800, idle = false,
            palette = intArrayOf(0xff00ffff.toInt()), randomSeed = 1401)
        fun outsideRadius(image: Bitmap): Double {
            var energy = 0.0
            var distance = 0.0
            for (y in 0 until image.height) for (x in 0 until image.width) {
                if (x in 262 until 338 && y in 57 until 143) continue
                val pixel = image.getPixel(x, y)
                val value = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                if (value < 30) continue
                energy += value
                distance += value * kotlin.math.hypot((x - 300).toDouble(), (y - 100).toDouble())
            }
            assertTrue("The measured front must contain a visible exterior field", energy > 10000)
            return distance / energy
        }
        try {
            h.effect.prepareTexturesForTest()
            h.render(0).recycle()
            h.press(1501, 0, 0)
            h.now = 50
            h.effect.onRelease(0)
            val before = h.render(260)
            val after = h.render(300)
            assertTrue("The native exterior light keeps advancing through the old pause",
                outsideRadius(after) >= outsideRadius(before) + 1.0)
            save(before, "continuous-front-260ms")
            save(after, "continuous-front-300ms")
            assertEquals("Saved 100ms ignition keeps its configured 2750ms wave", 0, peak(h.render(2750)))
        } finally { h.finish() }
    }

    @Test
    fun productionNextPressSharesLightSmoothlyWithoutInterruptingTheOlderWave() {
        val h = Harness(press = true, production = true, expansion = 1400, waveHold = 350,
            ignition = 40, fade = 900, hold = 120, retreat = 1800, idle = false,
            palette = intArrayOf(0xff00ffff.toInt()), randomSeed = 1401)
        try {
            h.effect.prepareTexturesForTest()
            h.render(0).recycle()
            val trail = org.robolectric.util.ReflectionHelpers.getField<org.fcitx.fcitx5.android.input.keyboard.PressEffectTrail>(h.effect, "ripples")
            h.press(1502, 0, 0, 240)
            val older = trail[0]
            h.now = 50
            h.effect.onRelease(0)
            h.render(150).recycle()
            var previousGain = older.lightGain
            var largestDrop = 0f
            h.press(1503, 0, 150, 420)
            for (time in 150L..800L step 25) {
                if (time == 200L) { h.now = time; h.effect.onRelease(0) }
                val image = h.render(time)
                assertEquals("A new key adds a wave instead of cancelling the previous wave", 2, trail.size)
                assertSame("The old wave retains its original object and clock", older, trail[0])
                assertEquals(0L, older.start)
                assertTrue("Reducing old light never makes it relight on a later frame", older.lightGain <= previousGain + 0.00001f)
                largestDrop = maxOf(largestDrop, previousGain - older.lightGain)
                previousGain = older.lightGain
                assertTrue("The common field respects a per-pixel exposure ceiling independent of the number of keys", peak(image) <= 224)
                if (time >= 250L) {
                    val oldPoint = image.getPixel(240, 100)
                    assertTrue("Adding another key cannot globally divide the travelling old colour",
                        maxOf(Color.red(oldPoint), Color.green(oldPoint), Color.blue(oldPoint)) >= 100)
                }
                if (time == 250L || time == 300L || time == 350L) save(image, "continuous-two-key-${time}ms")
                image.recycle()
            }
            assertTrue("Budget saturation must not abruptly cut an older front in one 25ms step (drop=$largestDrop)", largestDrop <= 0.15f)
            assertTrue("The surviving older front retains visible gain", older.lightGain > 0.20f)
            assertEquals("40 + 1400 + 350 + 900 ends the newer wave 2690ms after its press", 0, peak(h.render(2840)))
        } finally { h.finish() }
    }

    @Test
    fun productionArbitraryPalettePreservesEachChosenKeyAndCloudHue() {
        val custom = intArrayOf(0xff8d23c2.toInt(), 0xff14a37f.toInt(), 0xffecb119.toInt())
        val h = Harness(press = true, production = true, expansion = 1400, waveHold = 350,
            fade = 900, hold = 120, retreat = 1800, idle = false,
            palette = custom, sequential = true, randomSeed = 1401)
        try {
            h.effect.prepareTexturesForTest()
            repeat(custom.size) { i ->
                h.effect.clear()
                val time = i * 3000L
                h.render(time).recycle()
                h.press(1001 + i, 0, time)
                assertEquals("An arbitrary palette colour is the shared press/popup source", custom[i], PressEffect.colorForKey(1001 + i))
                h.now = time + 50
                h.effect.onRelease(0)
                val cloud = h.render(time + 200)
                var strongest = 0
                var strongestValue = 0
                for (y in 0 until cloud.height) for (x in 0 until cloud.width) {
                    if (x in 270 until 330 && y in 65 until 135) continue
                    val pixel = cloud.getPixel(x, y)
                    val value = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                    if (value > strongestValue) { strongestValue = value; strongest = pixel }
                }
                assertTrue("Every custom colour needs actual peripheral pixels", strongestValue > 40)
                val expectedHue = FloatArray(3)
                val actualHue = FloatArray(3)
                Color.colorToHSV(custom[i], expectedHue)
                Color.colorToHSV(strongest, actualHue)
                val difference = kotlin.math.abs(expectedHue[0] - actualHue[0])
                assertTrue("A cloud must retain its selected base hue", minOf(difference, 360f - difference) < 3f)
                save(cloud, "custom-colour-${i + 1}-flow")
                cloud.recycle()
            }
        } finally { h.finish() }
    }

    @Test
    fun productionDefaultCapTailOverlapsTheNextKeyWhileTheSharedFieldContinues() {
        val application = RuntimeEnvironment.getApplication()
        val storage = application.getSharedPreferences("press-effect-default-stages", Context.MODE_PRIVATE)
        storage.edit().clear().commit()
        AppPrefs.init(storage)
        val prefs = ThemeManager.prefs
        // init is idempotent; clear the preference file actually backing ThemePrefs
        // rather than relying on this fixture's newly requested file being selected.
        prefs.pressKeyHoldTime.sharedPreferences.edit().clear().commit()
        assertEquals(50, prefs.pressKeyHoldTime.getValue())
        assertEquals(100, prefs.pressKeyRetreatTime.getValue())
        val h = Harness(press = true, production = true, idle = false, randomSeed = 1401,
            ignition = prefs.pressIgnitionTime.getValue(), expansion = prefs.pressExpansionTime.getValue(),
            waveHold = prefs.pressWaveHoldTime.getValue(), fade = prefs.pressFadeOutTime.getValue(),
            hold = prefs.pressKeyHoldTime.getValue(), retreat = prefs.pressKeyRetreatTime.getValue())
        fun facePeak(keyId: Int, time: Long): Int = h.surface(keyId, time).first.let { image ->
            try { peak(image) } finally { image.recycle() }
        }
        fun fieldPeak(time: Long): Int = h.render(time).let { image ->
            try { peak(image) } finally { image.recycle() }
        }
        try {
            h.press(1698, 0, 0)
            val pressed = facePeak(1698, 0)
            assertTrue("A new tap begins with a clearly coloured key face", pressed > 190)
            h.now = 50
            h.effect.onRelease(0)
            val earlyTail = facePeak(1698, 75)
            val nextKeyTail = facePeak(1698, 100)
            assertTrue("UP begins a gradual cap exit rather than cutting off its colour", earlyTail in 60 until pressed)
            assertTrue("The first key retains a weaker afterglow when the next key arrives", nextKeyTail in 20 until earlyTail)
            h.press(1700, 1, 100, 360)
            assertEquals("A different key cannot cancel or restart the previous cap tail",
                nextKeyTail, facePeak(1698, 100))
            assertTrue("The next key lights immediately alongside the old key's tail", facePeak(1700, 100) > 190)
            assertTrue("The base light continues while the black cap recovers", fieldPeak(100) > 40)
            assertTrue("The old cap keeps dimming after the new key arrives", facePeak(1698, 125) < nextKeyTail)
            assertEquals("The first cap fully clears after its own 100ms release", 0, facePeak(1698, 150))
            assertTrue("The new cap remains held independently", facePeak(1700, 150) > 190)
            h.now = 150
            h.effect.onRelease(1)
            assertEquals("The second cap has its own complete exit", 0, facePeak(1700, 250))
            assertTrue("Shortening the key flash does not truncate the independent travelling field", fieldPeak(350) > 40)

            h.press(1699, 1, 1200)
            assertTrue("Holding a key keeps its full colour even after its travelling field expires",
                facePeak(1699, 2600) > 190)
            assertEquals("A held key does not invent a permanently running base wave", 0, fieldPeak(2600))
            h.now = 2600
            h.effect.onRelease(1)
            assertTrue("The held face starts its exit from release, not the original press time",
                facePeak(1699, 2650) > 40)
            assertEquals("The long-held face also clears after its own configured release tail",
                0, facePeak(1699, 2700))
        } finally { h.finish() }
    }

    @Test
    fun productionV15ShortCapTrailLeavesTheConnectedFieldVisible() {
        val h = Harness(press = true, production = true, ignition = 40, expansion = 1000,
            waveHold = 120, fade = 800, hold = 120, retreat = 520, idle = false,
            randomSeed = 1401)
        try {
            h.render(0).recycle()
            h.press(1701, 0, 0, 240)
            val pressed = h.surface(1701, 0)
            assertTrue("A short tap still has an immediate full neon cap", peak(pressed.first) > 190)
            val selected = PressEffect.colorForKey(1701)
            h.now = 50
            h.effect.onRelease(0)
            h.press(1702, 0, 130, 360)
            h.now = 180
            h.effect.onRelease(0)
            val connected = h.render(400)
            val middle = connected.getPixel(300, 100)
            val high = maxOf(Color.red(middle), Color.green(middle), Color.blue(middle))
            val low = minOf(Color.red(middle), Color.green(middle), Color.blue(middle))
            assertTrue("The two presses join through an unpressed region", high >= 100 && high - low >= 60)
            assertNotNull("The current key/popup colour remains attached to its own press", selected)
            assertEquals("Old bright cap has finished its 520ms exit", 0, peak(h.surface(1701, 650).first))
            assertTrue("The base field remains vivid after the short cap has returned dark", peak(h.render(800)) > 100)
            assertEquals("The newer configured 1960ms field clears completely", 0, peak(h.render(2090)))
            save(connected, "v15-connected-neon-field-400ms")
        } finally { h.finish() }
    }

    @Test
    fun productionV15FieldContinuesMovingDuringHoldAndFadeWithoutMovingCaps() {
        val h = Harness(press = true, production = true, ignition = 40, expansion = 1000,
            waveHold = 120, fade = 800, hold = 120, retreat = 520, idle = false,
            palette = intArrayOf(0xff00ffff.toInt()), randomSeed = 1401)
        fun changed(a: Bitmap, b: Bitmap): Int {
            var count = 0
            for (y in 0 until a.height) for (x in 0 until a.width) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) count++
            }
            return count
        }
        try {
            h.render(0).recycle()
            h.press(1703, 0, 0)
            h.now = 50
            h.effect.onRelease(0)
            val heldFirst = h.render(1060)
            val heldSecond = h.render(1140)
            assertTrue("The expanded field keeps gently moving throughout the actual hold", changed(heldFirst, heldSecond) > 100)
            assertTrue("Hold motion changes position without dimming the held colour", kotlin.math.abs(peak(heldFirst) - peak(heldSecond)) <= 5)
            val fading = h.render(1500)
            assertTrue("Fade retains a visible moving field", peak(fading) > 30 && peak(fading) < peak(heldSecond))
            assertEquals(0, peak(h.render(1960)))
            save(heldFirst, "v15-expanded-flow-1060ms")
            save(heldSecond, "v15-expanded-flow-1140ms")
            save(fading, "v15-expanded-flow-fade-1500ms")
        } finally { h.finish() }
    }

    @Test
    fun productionV15SharedFieldHasFixedStorageWorkAndSaturatedExposure() {
        val h = Harness(press = true, production = true, ignition = 40, expansion = 4000,
            waveHold = 2000, fade = 5000, hold = 120, retreat = 520, idle = false,
            randomSeed = 1401)
        try {
            h.render(0).recycle()
            val shared = org.robolectric.util.ReflectionHelpers.getField<Any>(h.effect, "neonField")
            val buffer = org.robolectric.util.ReflectionHelpers.getField<Bitmap>(shared, "bitmap")
            val channels = org.robolectric.util.ReflectionHelpers.getField<FloatArray>(shared, "weight")
            assertEquals(120, buffer.width)
            assertEquals(96, buffer.height)
            var largeVisibleFrames = 0
            repeat(60) { i ->
                val time = i * 100L
                h.press(1800 + i, 0, time, intArrayOf(180, 240, 300, 360, 420)[i % 5])
                h.now = time + 50
                h.effect.onRelease(0)
                val image = h.render(time + 80)
                assertSame("Typing reuses the same field Bitmap", buffer,
                    org.robolectric.util.ReflectionHelpers.getField<Bitmap>(shared, "bitmap"))
                assertSame("Typing reuses the same channel storage", channels,
                    org.robolectric.util.ReflectionHelpers.getField<FloatArray>(shared, "weight"))
                assertTrue("The draw budget is bounded regardless of the long setting or wave queue",
                    org.robolectric.util.ReflectionHelpers.getField<Int>(shared, "sourceCount") <= 9 &&
                        org.robolectric.util.ReflectionHelpers.getField<Int>(shared, "visitedSamples") <=
                            8 * 120 * 96 + 24 * 40 * 32)
                assertTrue("The older grid adds at most one fixed-size bilinear merge",
                    org.robolectric.util.ReflectionHelpers.getField<Int>(shared, "mergedSamples") <= 120 * 96)
                assertTrue("The newest cap does not share away its immediate brightness", peak(h.surface(1800 + i, time + 80).first) > 190)
                var visible = 0
                for (y in 0 until image.height step 2) for (x in 0 until image.width step 2) {
                    val p = image.getPixel(x, y)
                    val high = maxOf(Color.red(p), Color.green(p), Color.blue(p))
                    val low = minOf(Color.red(p), Color.green(p), Color.blue(p))
                    assertTrue("Common exposure cannot become an unbounded bright wash", high <= 224)
                    if (high > 80) {
                        if (high - low < high * 0.65f) {
                            save(image, "v15-expanded-palette-chroma-failure")
                            save(buffer, "v15-expanded-palette-chroma-field-failure")
                        }
                        assertTrue("Cyber-neon overlap must retain colour instead of mixing to grey-white: frame=$i x=$x y=$y rgb=${Color.red(p)},${Color.green(p)},${Color.blue(p)}", high - low >= high * 0.65f)
                        visible++
                    }
                }
                if (visible >= 1500) largeVisibleFrames++
                if (i == 40) save(image, "v15-fast-input-common-field")
                image.recycle()
            }
            assertTrue("Long continuous input keeps a substantial saturated field", largeVisibleFrames >= 20)
        } finally { h.finish() }
    }

    /** Samples the actual field contour away from corners; a moving rectangle remains flat. */
    private fun fieldSideProfile(effect: PressEffect): Pair<FloatArray, FloatArray> {
        val field = org.robolectric.util.ReflectionHelpers.getField<Any>(effect, "neonField")
        val bitmap = org.robolectric.util.ReflectionHelpers.getField<Bitmap>(field, "bitmap")
        val nodes = org.robolectric.util.ReflectionHelpers.getField<Array<Any>>(field, "nodes")
        val node = nodes[0]
        val centreY = org.robolectric.util.ReflectionHelpers.getField<Float>(node, "y")
        val halfHeight = org.robolectric.util.ReflectionHelpers.getField<Float>(node, "halfHeight")
        val corner = org.robolectric.util.ReflectionHelpers.getField<Float>(node, "corner")
        val bounds = org.robolectric.util.ReflectionHelpers.getField<android.graphics.RectF>(field, "bounds")
        val sample = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(sample, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val threshold = sample.maxOf { Color.alpha(it) } * 0.55f
        val left = FloatArray(13)
        val right = FloatArray(13)
        repeat(13) { row ->
            val y = centreY + (row - 6) / 6f * (halfHeight - corner) * 0.40f
            val sampleY = ((y - bounds.top) / bounds.height() * bitmap.height).toInt()
                .coerceIn(0, bitmap.height - 1)
            var first = 0
            while (first < bitmap.width && Color.alpha(sample[sampleY * bitmap.width + first]) < threshold) first++
            var last = bitmap.width - 1
            while (last >= 0 && Color.alpha(sample[sampleY * bitmap.width + last]) < threshold) last--
            assertTrue("The sampled side remains inside the field, rather than a clipped screen edge",
                first in 1 until bitmap.width - 1 && last in 1 until bitmap.width - 1)
            fun alpha(x: Int) = Color.alpha(sample[sampleY * bitmap.width + x]).toFloat()
            left[row] = (first - 1f + (threshold - alpha(first - 1)) / (alpha(first) - alpha(first - 1))) *
                bounds.width() / bitmap.width
            right[row] = (last + (alpha(last) - threshold) / (alpha(last) - alpha(last + 1))) *
                bounds.width() / bitmap.width
        }
        return left to right
    }

    @Test
    fun productionV15FrontBendsAndHasAnInteriorGradientInsteadOfAFlatRectangle() {
        val h = Harness(press = true, production = true, ignition = 40, expansion = 1000,
            waveHold = 120, fade = 800, hold = 120, retreat = 520, idle = false,
            palette = intArrayOf(0xff00ffff.toInt()), randomSeed = 1401)
        try {
            h.press(1900, 0, 0)
            h.now = 50
            h.effect.onRelease(0)
            var bentFrames = 0
            val report = StringBuilder("time_ms,left_range_px,right_range_px\n")
            for (time in longArrayOf(450, 650, 850)) {
                val image = h.render(time)
                val profile = fieldSideProfile(h.effect)
                val leftRange = profile.first.maxOrNull()!! - profile.first.minOrNull()!!
                val rightRange = profile.second.maxOrNull()!! - profile.second.minOrNull()!!
                if (maxOf(leftRange, rightRange) >= 3f) bentFrames++
                report.append("$time,$leftRange,$rightRange\n")
                save(image, "v15-bending-front-$time")
                image.recycle()
            }
            File("build/outputs/effect-checks/v15-bending-front.csv").writeText(report.toString())
            assertTrue("At least two expanding frames have visibly non-straight mid-side fronts ($bentFrames)", bentFrames >= 2)
            val field = org.robolectric.util.ReflectionHelpers.getField<Any>(h.effect, "neonField")
            val weights = org.robolectric.util.ReflectionHelpers.getField<FloatArray>(field, "weight")
            val central = weights[51 * 120 + 60]
            val offCentre = weights[51 * 120 + 46]
            assertTrue("The bright core keeps its body while the shoulder has meaningfully lower density",
                central > 0.6f && offCentre > 0.1f && offCentre < central * 0.90f)
        } finally { h.finish() }
    }

    @Test
    fun productionV15HoldDeformsTheFrontBeyondWholeFieldTranslationOrScaling() {
        val h = Harness(press = true, production = true, ignition = 40, expansion = 1000,
            waveHold = 2000, fade = 800, hold = 120, retreat = 520, idle = false,
            palette = intArrayOf(0xff00ffff.toInt()), randomSeed = 1401)
        try {
            h.press(1901, 0, 0)
            h.now = 50
            h.effect.onRelease(0)
            var previous: Pair<FloatArray, FloatArray>? = null
            var movingIntervals = 0
            var totalMotion = 0f
            val report = StringBuilder("time_ms,residual_rms_px\n")
            for (time in 1200L..1600L step 80) {
                val image = h.render(time)
                save(image, "v15-held-contour-$time")
                image.recycle()
                val next = fieldSideProfile(h.effect)
                previous?.let { before ->
                    var sum = 0f
                    for ((old, new) in arrayOf(before.first to next.first, before.second to next.second)) {
                        val oldMean = old.average().toFloat()
                        val newMean = new.average().toFloat()
                        for (i in old.indices) {
                            val difference = (new[i] - newMean) - (old[i] - oldMean)
                            sum += difference * difference
                        }
                    }
                    val rms = kotlin.math.sqrt(sum / 26f)
                    if (rms >= 0.15f) movingIntervals++
                    totalMotion += rms
                    report.append("$time,$rms\n")
                    assertTrue("The held front flows gently rather than jumping ($time: $rms)", rms < 3f)
                }
                previous = next
            }
            File("build/outputs/effect-checks/v15-held-contour.csv").writeText(report.toString())
            assertTrue("Several held frames change their contour after removing translation and width ($movingIntervals)", movingIntervals >= 3)
            assertTrue("The held contour changes by a measurable amount ($totalMotion)", totalMotion >= 0.90f)
        } finally { h.finish() }
    }

    @Test
    fun productionV15NinthPressDoesNotReplaceOlderLightWithABoundingBoxOrDimIt() {
        val h = Harness(press = true, production = true, ignition = 40, expansion = 1000,
            waveHold = 120, fade = 800, hold = 120, retreat = 520, idle = false,
            palette = intArrayOf(0xff00ffff.toInt()), randomSeed = 1401)
        try {
            repeat(8) { i ->
                h.press(1920 + i, 0, i * 100L, if (i % 2 == 0) 180 else 420)
                h.now += 50
                h.effect.onRelease(0)
            }
            val before = h.render(800)
            h.press(1928, 0, 800, 300)
            val after = h.render(800) // the new wave has zero spread: only the old-grid handoff changes
            var totalDifference = 0L
            var maximumDifference = 0
            for (y in 0 until before.height) for (x in 0 until before.width) {
                val difference = kotlin.math.abs(Color.green(before.getPixel(x, y)) - Color.green(after.getPixel(x, y)))
                totalDifference += difference
                maximumDifference = maxOf(maximumDifference, difference)
            }
            val meanDifference = totalDifference.toDouble() / (before.width * before.height)
            save(before, "v15-old-field-before-ninth-press")
            save(after, "v15-old-field-after-ninth-press")
            File("build/outputs/effect-checks/v15-old-field-handoff.txt")
                .writeText("mean_rgb_delta=$meanDifference\npeak_rgb_delta=$maximumDifference\n")
            assertTrue("The lower-resolution handoff changes only a small feathered fringe ($meanDifference)", meanDifference < 3.0)
            assertTrue("The ninth press cannot impose a hard old-field step ($maximumDifference)", maximumDifference <= 35)
            assertTrue("The existing field remains bright instead of being globally divided", Color.green(after.getPixel(180, 100)) >=
                Color.green(before.getPixel(180, 100)) - 5)
        } finally { h.finish() }
    }

    @Test
    fun productionV15CandidateAndKeyboardShareOneFieldBuildPerDisplayFrame() {
        val h = Harness(press = true, production = true, ignition = 40, expansion = 1000,
            waveHold = 120, fade = 800, idle = false, randomSeed = 1401)
        try {
            var frameNanos = 123000000L
            val displayClock: () -> Long = { frameNanos }
            org.robolectric.util.ReflectionHelpers.setField(h.effect, "displayFrameNanos", displayClock)
            h.press(1950, 0, 0)
            h.now = 50
            h.effect.onRelease(0)
            val field = org.robolectric.util.ReflectionHelpers.getField<Any>(h.effect, "neonField")
            h.candidate(300).recycle()
            val count = org.robolectric.util.ReflectionHelpers.getField<Int>(field, "prepareCount")
            h.render(307).recycle()
            assertEquals("Different View draw times in one Choreographer frame reuse one field build", count,
                org.robolectric.util.ReflectionHelpers.getField<Int>(field, "prepareCount"))
            frameNanos += 8333333L // a distinct 120Hz frame, without a 16ms cache bucket
            h.render(308).recycle()
            assertEquals("The next high-refresh frame gets its own field, even only 1ms later", count + 1,
                org.robolectric.util.ReflectionHelpers.getField<Int>(field, "prepareCount"))
            h.press(1951, 0, 308, 360)
            h.render(308).recycle()
            assertEquals("A new press invalidates the cache immediately within the same frame", count + 2,
                org.robolectric.util.ReflectionHelpers.getField<Int>(field, "prepareCount"))
        } finally { h.finish() }
    }

    @Test
    fun productionV15DisplayClockExpiresAfterTraversalInsteadOfFreezingLaterDraws() {
        val h = Harness(press = true, production = true, ignition = 40, expansion = 1000,
            waveHold = 120, fade = 800, idle = false, randomSeed = 1401)
        try {
            val displayClock: () -> Long = {
                org.robolectric.util.ReflectionHelpers.getField<Long>(h.effect, "callbackFrameId")
            }
            org.robolectric.util.ReflectionHelpers.setField(h.effect, "displayFrameNanos", displayClock)
            h.press(1952, 0, 0)
            h.now = 300
            val callback = org.robolectric.util.ReflectionHelpers.getField<android.view.Choreographer.FrameCallback>(
                h.effect, "frameCallback")
            callback.doFrame(123000000L)
            h.candidate(300).recycle()
            val field = org.robolectric.util.ReflectionHelpers.getField<Any>(h.effect, "neonField")
            val count = org.robolectric.util.ReflectionHelpers.getField<Int>(field, "prepareCount")
            h.render(307).recycle()
            assertEquals("The active traversal shares its frame clock", count,
                org.robolectric.util.ReflectionHelpers.getField<Int>(field, "prepareCount"))
            org.robolectric.util.ReflectionHelpers.getField<Runnable>(h.effect, "endDisplayFrame").run()
            h.render(330).recycle()
            assertEquals("A later draw outside that traversal advances its clock", 330L,
                org.robolectric.util.ReflectionHelpers.getField<Long>(h.effect, "frameTime"))
            assertEquals("A released frame stamp cannot retain a stale field bitmap", count + 1,
                org.robolectric.util.ReflectionHelpers.getField<Int>(field, "prepareCount"))
        } finally { h.finish() }
    }

    @Test
    fun warmAndCoolPresetFieldsStaySaturatedAndInsideTheirChosenHueFamilyDuringContinuousTyping() {
        listOf(PressColorPalette.CYAN_BLUE_PURPLE to false,
            PressColorPalette.RED_ORANGE_YELLOW to true).forEach { (palette, warm) ->
            val h = Harness(press = true, production = true, idle = false, palette = palette,
                randomSeed = 1107, ignition = 40, expansion = 400, waveHold = 40, fade = 520)
            var checked = 0
            try {
                repeat(16) { index ->
                    val time = index * 90L
                    h.press(8000 + index, 0, time, 60 + (index % 9) * 55)
                    h.now = time + 30
                    h.effect.onRelease(0)
                    val bitmap = h.render(time + 60)
                    try {
                        for (y in 0 until bitmap.height step 6) for (x in 0 until bitmap.width step 6) {
                            val pixel = bitmap.getPixel(x, y)
                            val high = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                            if (high < 32) continue
                            val hsv = FloatArray(3).also { Color.colorToHSV(pixel, it) }
                            assertTrue("Preset field should retain neon saturation", hsv[1] >= 0.65f)
                            assertTrue("Preset field must not introduce unrelated hues: warm=$warm hue=${hsv[0]}",
                                if (warm) hsv[0] >= 345f || hsv[0] <= 65f else hsv[0] in 170f..290f)
                            checked++
                        }
                        if (index == 15) save(bitmap, if (warm) "cyber-palette-warm" else "cyber-palette-cool")
                    } finally { bitmap.recycle() }
                }
                assertTrue("The color checks must cover a visible travelling field", checked > 200)
            } finally { h.finish() }
        }
    }

}
