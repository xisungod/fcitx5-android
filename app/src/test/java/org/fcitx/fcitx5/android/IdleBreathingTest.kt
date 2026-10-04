package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.keyboard.IdleBreathing
import org.junit.Assert.*
import org.junit.Test

class IdleBreathingTest {
    @Test
    fun customCycleAndBrightnessRespectIdleDelay() {
        val glow = IdleBreathing(idleDelayMs = 1000, cycleMs = 4000, brightness = 0.3f)
        glow.resume(100)
        assertEquals(1000L, glow.nextFrameDelay(100))
        assertEquals(0f, glow.alpha(1099), 0f)
        assertEquals(0f, glow.alpha(1100), 0f)
        assertEquals(0.3f, glow.alpha(3100), 0.0001f)
        assertEquals(glow.alpha(3100), glow.alpha(7100), 0.0001f)
        assertEquals(42L, glow.nextFrameDelay(7100))
    }

    @Test
    fun configurableEntryFadeAndExitFadeHaveIndependentDurations() {
        val slow = IdleBreathing(fadeInMs = 2000, fadeOutMs = 1000)
        val fast = IdleBreathing(fadeInMs = 1000, fadeOutMs = 100)
        slow.resume(0)
        fast.resume(0)
        assertEquals(fast.alpha(2800) / 2f, slow.alpha(2800), 0.0001f)
        val initial = slow.alpha(4400)
        slow.activity(4400, held = true)
        fast.activity(4400, held = true)
        assertEquals(initial, slow.alpha(4400), 0.0001f)
        assertEquals(initial / 2f, slow.alpha(4900), 0.0001f)
        assertEquals(0f, fast.alpha(4900), 0f)
        assertEquals(0f, slow.alpha(5400), 0f)
    }

    @Test
    fun heldKeyStopsIdleTimersUntilReleasedAndHiddenStopsEverything() {
        val glow = IdleBreathing()
        glow.resume(0)
        glow.activity(4400, held = true)
        assertNotNull(glow.nextFrameDelay(4401))
        assertEquals(0f, glow.alpha(10000), 0f)
        assertNull(glow.nextFrameDelay(10000))
        glow.activity(10000, held = false)
        assertEquals(1800L, glow.nextFrameDelay(10000))
        assertTrue(glow.alpha(14400) > 0f)
        glow.stop()
        assertEquals(0f, glow.alpha(15000), 0f)
        assertNull(glow.nextFrameDelay(15000))
        glow.resume(20000)
        assertEquals(0f, glow.alpha(20000), 0f)
        assertEquals(1800L, glow.nextFrameDelay(20000))
    }

    @Test
    fun longExitFadeDoesNotJumpBackToBrightWithShortIdleDelay() {
        val glow = IdleBreathing(idleDelayMs = 500, fadeOutMs = 1500)
        glow.resume(0)
        glow.activity(3100, held = true)
        glow.activity(3110, held = false)
        assertTrue(glow.alpha(3600) > 0f)
        assertEquals(0f, glow.alpha(4610), 0f)
        assertTrue(glow.alpha(4611) < 0.001f)
    }

    @Test
    fun rapidTypingMaintainsContinuousExitAndNeverStartsIdleWhileHeld() {
        val glow = IdleBreathing()
        glow.resume(0)
        var now = 4400L
        repeat(1000) {
            val before = glow.alpha(now)
            glow.activity(now, held = true)
            assertEquals(before, glow.alpha(now), 0.00001f)
            now += 20
            glow.activity(now, held = false)
            now += 30
        }
        assertEquals(0f, glow.alpha(now), 0.00001f)
        glow.activity(now, held = true)
        assertNull(glow.nextFrameDelay(now + 10000))
    }

    @Test
    fun invalidImportedTimingValuesCannotDivideByZeroOrEscapeBrightnessBounds() {
        val glow = IdleBreathing(0, 0, 0, 0, 4f)
        glow.resume(0)
        for (now in 0L..6000L step 10L) {
            val alpha = glow.alpha(now)
            assertTrue(alpha.isFinite() && alpha in 0f..0.6f)
        }
    }
    @Test
    fun inactivityDeadlineStopsFramesUntilNewInputOrKeyboardReopens() {
        val glow = IdleBreathing(timeoutMs = 60000)
        glow.resume(0)
        assertTrue(glow.alpha(4400) > 0f)
        assertEquals(0f, glow.alpha(60000), 0f)
        assertNull(glow.nextFrameDelay(60000))
        assertTrue("Expired envelope must stay running to prevent draw auto-resume", glow.running)
        assertEquals(0f, glow.alpha(300000), 0f)
        assertNull(glow.nextFrameDelay(300000))
        glow.activity(300000, held = false)
        assertTrue(glow.alpha(304400) > 0f)
        glow.stop()
        glow.resume(400000)
        assertTrue(glow.alpha(404400) > 0f)
    }

}
