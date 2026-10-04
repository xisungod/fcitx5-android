package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.keyboard.PressEffectTrail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Collections
import java.util.IdentityHashMap

class PressEffectTrailTest {
    @Test
    fun rapidTypingKeepsOnlyNewestPressesAndReusesFixedStorage() {
        val trail = PressEffectTrail(10)
        val identities = Collections.newSetFromMap(
            IdentityHashMap<PressEffectTrail.Ripple, Boolean>()
        )
        repeat(100_000) { index ->
            identities.add(trail.obtain(index.toLong()))
        }
        assertEquals(10, identities.size)
        assertEquals(10, trail.size)
        repeat(10) { assertEquals(99_990L + it, trail[it].start) }
    }

    @Test
    fun expiryKeepsNewPressAliveAndClearDropsAllPendingAnimation() {
        val trail = PressEffectTrail(3)
        trail.obtain(0)
        val held = trail.obtain(500)
        trail.expire(780, 780)
        assertEquals(1, trail.size)
        assertSame(held, trail[0])
        val next = trail.obtain(781)
        trail.expire(1280, 780)
        assertEquals(1, trail.size)
        assertSame(next, trail[0])
        trail.clear()
        assertEquals(0, trail.size)
        val fresh = trail.obtain(2000)
        trail.expire(2779, 780)
        assertSame(fresh, trail[0])
        trail.expire(2780, 780)
        assertEquals(0, trail.size)
    }
    @Test
    fun heldHeadDoesNotPreventFinishedLaterEntriesFromExpiring() {
        val trail = PressEffectTrail(4)
        val held = trail.obtain(0).also { it.releasedAt = -1; it.keyId = 10; it.pointerId = 7 }
        trail.obtain(100).releasedAt = 200
        trail.obtain(300).releasedAt = 400
        trail.expireWhile { it.releasedAt >= 0 }
        assertEquals(1, trail.size)
        assertSame(held, trail[0])
        assertSame(held, trail.findKey(10))
        val fresh = trail.obtain(500)
        assertEquals(2, trail.size)
        assertEquals(500, fresh.start)
        assertSame(held, trail[0])
    }

}
