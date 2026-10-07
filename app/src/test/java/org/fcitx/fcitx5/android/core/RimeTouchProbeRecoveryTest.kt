/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.*
import org.junit.Test

class RimeTouchProbeRecoveryTest {
    @Test fun coldInitializationTimeoutLeavesNextDifferentSpellingAvailable() {
        val recovery = RimeTouchProbeRecovery()
        recovery.completed("wozhe", 100, true, false, true, true)
        assertEquals("ProbeRepeatedTimedOutSpelling", recovery.rejection("wozhe", 101))
        assertNull(recovery.rejection("wozhen", 101))
    }

    @Test fun actualSlowQueriesCoolDownThenAllowOnlyTwoRecoveryAttempts() {
        val recovery = RimeTouchProbeRecovery(cooldownNanos = 50)
        recovery.completed("zhou", 100, true, false, false, true)
        assertEquals("ProbeCoolingDown", recovery.rejection("zhoumo", 149))
        assertNull(recovery.rejection("zhoumo", 150))
        recovery.completed("zhoumo", 150, true, false, false, false)
        assertNull(recovery.rejection("zhoumochu", 200))
        recovery.completed("zhoumochu", 200, true, false, false, false)
        assertEquals("ProbeRetryLimit", recovery.rejection("zhoumochuqu", 1_000))
    }

    @Test fun unavailableAndSuccessfulQueriesDoNotBecomeNativeTimeouts() {
        val recovery = RimeTouchProbeRecovery(cooldownNanos = 50)
        recovery.completed("nihao", 100, false, false, false, true)
        recovery.completed("nihao", 101, true, true, true, false)
        assertNull(recovery.rejection("nihao", 102))
    }

    @Test fun compositionResetRestoresBudgetAndRemovesRetainedSpellings() {
        val recovery = RimeTouchProbeRecovery(cooldownNanos = 50, maxNativeTimeouts = 1)
        recovery.completed("nihao", 100, true, false, false, false)
        recovery.reset()
        assertNull(recovery.rejection("nihao", 101))
    }

    @Test fun repeatedColdFailuresAreBoundedEvenIfBackspacesRecreateTheHandle() {
        val recovery = RimeTouchProbeRecovery()
        for (spelling in listOf("wo", "wozhe", "wozhen"))
            recovery.completed(spelling, 100, true, false, true, true)
        assertEquals("ProbeInitializationRetryLimit", recovery.rejection("wozheng", 101))
        // The healthy handle created by the last cold call can always get its
        // next different hot query; only another expensive create is capped.
        assertNull(recovery.rejection("wozheng", 101, warmHandle = true))
        recovery.reset()
        assertNull(recovery.rejection("wozheng", 102))
    }

    @Test fun cacheSeparatesSchemaContextAndDictionaryGeneration() {
        val cache = RimeTouchProbeValueCache<List<String>>()
        val key = RimeTouchProbeValueCache.Key("rime_ice", "nihao", "", 7)
        cache.put(key, listOf("你好"), 100)
        assertEquals(listOf("你好"), cache.get(key, 101))
        assertNull(cache.get(key.copy(schema = "other"), 101))
        assertNull(cache.get(key.copy(context = "今天"), 101))
        assertNull(cache.get(key.copy(generation = 8), 101))
        assertNull(cache.get(key.copy(spelling = "nihaoa"), 101))
    }

    @Test fun cacheEvictsLeastRecentlyUsedAndExpiresAtTheBudgetedLifetime() {
        val cache = RimeTouchProbeValueCache<String>(capacity = 2, ttlNanos = 50)
        val a = RimeTouchProbeValueCache.Key("rime_ice", "a", "", 0)
        val b = a.copy(spelling = "b")
        val c = a.copy(spelling = "c")
        cache.put(a, "甲", 100)
        cache.put(b, "乙", 100)
        assertEquals("甲", cache.get(a, 101))
        cache.put(c, "丙", 101)
        assertNull(cache.get(b, 102))
        assertNull(cache.get(a, 150))
        assertEquals("丙", cache.get(c, 150))
        cache.clear()
        assertNull(cache.get(c, 150))
    }

    @Test fun aClockGoingBackwardsCannotReviveCachedValues() {
        val cache = RimeTouchProbeValueCache<String>()
        val key = RimeTouchProbeValueCache.Key("rime_ice", "nihao", "", 0)
        cache.put(key, "你好", 100)
        assertNull(cache.get(key, 99))
        assertNull(cache.get(key, 101))
    }
}
