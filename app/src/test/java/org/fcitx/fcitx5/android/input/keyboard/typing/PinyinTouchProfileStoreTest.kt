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
}
