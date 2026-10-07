/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.UserManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PinyinTouchProfileStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val cells = listOf(KeyCell('b', 0f, 0f, 100f, 140f), KeyCell('n', 100f, 0f, 200f, 140f))
    private val tap = TapEvidence('n', 160f, 84f, 1f)

    @Before fun reset() {
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        PinyinTouchProfileStore.clear(context)
    }

    @Test fun `clear affects loaded instances and invalidates queued confirmation generation`() {
        val first = PinyinTouchProfileStore(context)
        repeat(8) { assertTrue(first.observe(cells, tap, 'n')) }
        val second = PinyinTouchProfileStore(context)
        assertFalse(second.offsets(cells, 1f).isEmpty())
        val staleGeneration = first.generation
        PinyinTouchProfileStore.clear(context)
        assertTrue(first.offsets(cells, 1f).isEmpty())
        assertTrue(second.offsets(cells, 1f).isEmpty())
        assertFalse(first.observe(cells, tap, 'n', staleGeneration))
        assertEquals(0, second.confirmedSampleCount(cells, 1f, 'n'))
        assertTrue(first.observe(cells, tap, 'n', first.generation))
        assertEquals(1, second.confirmedSampleCount(cells, 1f, 'n'))
    }

    @Test fun `before first unlock and device protected contexts supply no learned center`() {
        val manager = shadowOf(context.getSystemService(UserManager::class.java))
        manager.setUserUnlocked(false)
        val locked = PinyinTouchProfileStore(context)
        assertFalse(locked.observe(cells, tap, 'n'))
        assertTrue(locked.offsets(cells, 1f).isEmpty())
        manager.setUserUnlocked(true)
        assertTrue(locked.observe(cells, tap, 'n'))
        assertEquals(1, locked.confirmedSampleCount(cells, 1f, 'n'))
        val deviceProtected = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun isDeviceProtectedStorage() = true
        }
        val unavailable = PinyinTouchProfileStore(deviceProtected)
        assertFalse(unavailable.observe(cells, tap, 'n'))
        assertTrue(unavailable.offsets(cells, 1f).isEmpty())
    }

    @Test fun `first unlock background preload restores saved offsets without observing a new tap`() {
        val saved = PinyinTouchProfileStore(context)
        repeat(8) { assertTrue(saved.observe(cells, tap, 'n')) }
        val expected = saved.offsets(cells, 1f)
        assertFalse(expected.isEmpty())
        // A separate cache identity simulates a fresh process profile snapshot;
        // preferences still delegate to the same credential-protected storage.
        val freshProcess = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getDataDir(): File = File(super.getDataDir(), "profile-first-unlock-regression")
        }
        val manager = shadowOf(context.getSystemService(UserManager::class.java))
        manager.setUserUnlocked(false)
        val early = PinyinTouchProfileStore(freshProcess)
        assertFalse(early.isPreloaded)
        assertFalse(early.preload())
        assertTrue(early.offsets(cells, 1f).isEmpty())
        manager.setUserUnlocked(true)
        // No implicit disk load in the decision path, even after unlock.
        assertTrue(early.offsets(cells, 1f).isEmpty())
        assertFalse(early.isPreloaded)
        assertTrue(early.preload())
        assertTrue(early.isPreloaded)
        assertEquals(expected, early.offsets(cells, 1f))
        assertEquals(8, early.confirmedSampleCount(cells, 1f, 'n'))
    }

    @Test fun `explicit prompted group is imported once and stores only aggregate numbers plus opaque receipts`() {
        val store = PinyinTouchProfileStore(context)
        val layout = PinyinTouchProfile.layoutSignature(cells, 1f)!!
        val samples = List(8) { PinyinTouchProfile.ConfirmedSample(layout, 'n', -.1, .05) }
        val receipt = "a".repeat(64)
        assertEquals(PinyinTouchProfileStore.ImportResult(8, 0),
            store.observeConfirmedGroup(receipt, samples, store.generation))
        assertEquals(PinyinTouchProfileStore.ImportResult(0, 8),
            store.observeConfirmedGroup(receipt, samples, store.generation))
        assertEquals(8, store.confirmedSampleCount(cells, 1f, 'n'))
        assertEquals(0, store.confirmedSampleCount(cells, 1f, 'b'))
        val root = org.json.JSONObject(context.getSharedPreferences("axiang_pinyin_touch_profile", Context.MODE_PRIVATE)
            .getString("normalized_statistics_v1", null)!!)
        assertEquals(setOf("version", "layouts", "confirmed_receipts"), root.keys().asSequence().toSet())
        assertEquals(receipt, root.getJSONArray("confirmed_receipts").getString(0))
        assertFalse(root.toString().contains("down_time"))
        assertFalse(root.toString().contains("pinyin"))
    }

    @Test fun `a reset and a revoked authorization reject queued prompted groups`() {
        val store = PinyinTouchProfileStore(context)
        val layout = PinyinTouchProfile.layoutSignature(cells, 1f)!!
        val sample = listOf(PinyinTouchProfile.ConfirmedSample(layout, 'n', .1, .1))
        val old = PinyinTouchProfileStore.generation(context)
        PinyinTouchProfileStore.clear(context)
        assertTrue(store.observeConfirmedGroup("b".repeat(64), sample, old).unavailable)
        assertTrue(store.observeConfirmedGroup("b".repeat(64), sample, store.generation) { false }.unavailable)
        assertEquals(0, store.confirmedSampleCount(cells, 1f, 'n'))
        assertEquals(1, store.observeConfirmedGroup("b".repeat(64), sample, store.generation).applied)
        PinyinTouchProfileStore.clear(context)
        assertTrue(store.offsets(cells, 1f).isEmpty())
        // Clearing explicitly permits a fresh calibration, never an old in-flight one.
        assertEquals(1, store.observeConfirmedGroup("b".repeat(64), sample, store.generation).applied)
    }

    @Test fun `malformed group is atomic and cannot import a valid prefix`() {
        val store = PinyinTouchProfileStore(context)
        val layout = PinyinTouchProfile.layoutSignature(cells, 1f)!!
        val valid = PinyinTouchProfile.ConfirmedSample(layout, 'n', .1, .1)
        for (samples in listOf(emptyList(), List(65) { valid },
            listOf(valid, valid.copy(x = Double.NaN)), listOf(valid, valid.copy(layout = "private prompt")),
            listOf(valid, valid.copy(x = .7)))) {
            assertTrue(store.observeConfirmedGroup("c".repeat(64), samples, store.generation).unavailable)
            assertEquals(0, store.confirmedSampleCount(cells, 1f, 'n'))
        }
    }

    @Test fun `confirmed group receipts survive a fresh profile cache`() {
        val first = PinyinTouchProfileStore(context)
        val layout = PinyinTouchProfile.layoutSignature(cells, 1f)!!
        val sample = listOf(PinyinTouchProfile.ConfirmedSample(layout, 'n', .1, .1))
        assertEquals(1, first.observeConfirmedGroup("d".repeat(64), sample, first.generation).applied)
        val newCache = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getDataDir(): File = File(super.getDataDir(), "profile-receipt-cache-regression")
        }
        val next = PinyinTouchProfileStore(newCache)
        assertEquals(1, next.confirmedSampleCount(cells, 1f, 'n'))
        assertEquals(1, next.observeConfirmedGroup("d".repeat(64), sample, next.generation).alreadyApplied)
        assertEquals(1, next.confirmedSampleCount(cells, 1f, 'n'))
    }
}
