/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import org.junit.Assert.*
import org.junit.Test

class PinyinTouchProfileTest {
    private val cells = listOf(KeyCell('b', 0f, 0f, 100f, 140f), KeyCell('n', 100f, 0f, 200f, 140f))
    private val tap = TapEvidence('n', 160f, 84f, 1f)

    @Test fun `only enough confirmed numeric evidence produces a shrunk bounded center`() {
        val profile = PinyinTouchProfile()
        repeat(7) { assertTrue(profile.observeConfirmed(cells, tap, 'n')) }
        assertTrue(profile.offsets(cells, 1f).isEmpty())
        assertTrue(profile.observeConfirmed(cells, tap, 'n'))
        val offset = profile.offsets(cells, 1f).getValue('n')
        assertEquals(8, profile.confirmedSampleCount(cells, 1f, 'n'))
        assertEquals(0.1f * 8 / 28, offset.x, 0.00001f)
        assertEquals(0.1f * 8 / 28, offset.y, 0.00001f)
        val borderTap = TapEvidence('b', 96f, 70f, 1f)
        repeat(100) { assertTrue(profile.observeConfirmed(cells, borderTap, 'n')) }
        val learned = profile.offsets(cells, 1f).getValue('n')
        assertTrue(learned.x >= -0.12f && learned.x <= 0.12f)
        assertTrue(learned.y >= -0.12f && learned.y <= 0.12f)
    }

    @Test fun `layout signatures separate dimensions and shapes while retaining density invariance`() {
        val original = PinyinTouchProfile.layoutSignature(cells, 1f)
        val scaled = cells.map { KeyCell(it.letter, it.left * 3.5f, it.top * 3.5f,
            it.right * 3.5f, it.bottom * 3.5f) }
        assertEquals(original, PinyinTouchProfile.layoutSignature(scaled, 3.5f))
        assertEquals(original, PinyinTouchProfile.layoutSignature(cells.reversed(), 1f))
        val changed = listOf(cells[0].copy(right = 80f), cells[1].copy(left = 80f))
        assertNotEquals(original, PinyinTouchProfile.layoutSignature(changed, 1f))
        val rotated = cells.map { KeyCell(it.letter, it.top, it.left, it.bottom, it.right) }
        assertNotEquals(original, PinyinTouchProfile.layoutSignature(rotated, 1f))
        val profile = PinyinTouchProfile()
        repeat(8) { profile.observeConfirmed(cells, tap, 'n') }
        assertTrue(profile.offsets(changed, 1f).isEmpty())
        assertTrue(profile.offsets(rotated, 1f).isEmpty())
    }

    @Test fun `only sixteen most recently confirmed layouts remain and snapshots are detached`() {
        val profile = PinyinTouchProfile()
        repeat(17) { size ->
            val current = cells.map { it.copy(bottom = 140f + size) }
            profile.observeConfirmed(current, tap, 'n')
        }
        assertEquals(16, profile.snapshot().size)
        assertEquals(0, profile.confirmedSampleCount(cells, 1f, 'n'))
        val snapshot = profile.snapshot()
        profile.clear()
        assertEquals(16, snapshot.size)
        assertTrue(profile.snapshot().isEmpty())
    }

    @Test fun `malformed stale or far alignment cannot teach a center`() {
        val profile = PinyinTouchProfile()
        for ((evidence, intended) in listOf(
            tap.copy(downX = Float.NaN) to 'n', tap.copy(density = 0f) to 'n',
            tap.copy(original = '1') to 'n', tap.copy(downX = 300f) to 'n', tap to 'x',
            TapEvidence('b', 10f, 70f, 1f) to 'n'
        )) assertFalse(profile.observeConfirmed(cells, evidence, intended))
        assertTrue(profile.snapshot().isEmpty())
        assertNull(PinyinTouchProfile.layoutSignature(cells + cells.first(), 1f))
        assertNull(PinyinTouchProfile.layoutSignature(cells, Float.NaN))
    }

    @Test fun `numeric moments survive restore without unbounded counts or malformed data`() {
        val profile = PinyinTouchProfile()
        val signature = PinyinTouchProfile.layoutSignature(cells, 1f)!!
        profile.restore(mapOf(signature to mapOf(
            'n' to PinyinTouchProfile.Statistics(4096, 0.1, 0.1, 1.0, 1.0),
            'b' to PinyinTouchProfile.Statistics(8, Double.NaN, 0.0, 0.0, 0.0)
        ), "not-a-layout" to mapOf('n' to PinyinTouchProfile.Statistics(8, 0.0, 0.0, 0.0, 0.0))))
        assertEquals(1, profile.snapshot().size)
        assertFalse(profile.snapshot().getValue(signature).containsKey('b'))
        assertTrue(profile.observeConfirmed(cells, tap, 'n'))
        assertEquals(2049, profile.confirmedSampleCount(cells, 1f, 'n'))
        val restored = PinyinTouchProfile().apply { restore(profile.snapshot()) }
        assertEquals(profile.offsets(cells, 1f), restored.offsets(cells, 1f))
    }
}
