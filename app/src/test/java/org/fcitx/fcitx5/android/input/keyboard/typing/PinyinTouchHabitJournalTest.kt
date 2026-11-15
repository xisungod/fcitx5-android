/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinTouchHabitJournalTest {
    private val editor = Any()
    private val cells = "qwertyuiop".mapIndexed { index, letter ->
        KeyCell(letter, index * 100f, 0f, index * 100f + 100f, 150f)
    } + "asdfghjkl".mapIndexed { index, letter ->
        KeyCell(letter, 50f + index * 100f, 150f, 150f + index * 100f, 300f)
    } + "zxcvbnm".mapIndexed { index, letter ->
        KeyCell(letter, 150f + index * 100f, 300f, 250f + index * 100f, 450f)
    }

    private fun tap(letter: Char, dx: Float = 0f): PinyinTapEvidence {
        val cell = cells.first { it.letter == letter }
        return PinyinTapEvidence(TapEvidence(letter, cell.centerX + dx, cell.centerY, 2f), cells)
    }

    /** Types [word] letter by letter on top of [prefix], as the engine would report it. */
    private fun PinyinTouchHabitJournal.type(word: String, prefix: String = "", editor: Any = this@PinyinTouchHabitJournalTest.editor) {
        var spelling = prefix
        for (letter in word) {
            recordTap(editor, spelling, spelling + letter, tap(letter))
            spelling += letter
        }
    }

    @Test fun `a composition committed as typed yields one labelled tap per letter in order`() {
        val journal = PinyinTouchHabitJournal()
        journal.type("nihao")
        val samples = journal.takeCommitted(editor, "nihao")!!
        assertEquals("nihao", samples.map { it.intended }.joinToString(""))
        assertEquals("nihao", samples.map { it.evidence.tap.original }.joinToString(""))
        assertTrue(samples.all { it.confirmation == PinyinTouchHabitJournal.Confirmation.UnchangedCommit })
        assertEquals(0, journal.size)
        assertNull("A commit is consumed once", journal.takeCommitted(editor, "nihao"))
    }

    @Test fun `a deletion anywhere in the composition teaches nothing`() {
        val journal = PinyinTouchHabitJournal()
        journal.type("nih")
        journal.recordEdit() // backspace: "nih" -> "ni"
        journal.type("hao", prefix = "ni")
        assertNull(journal.takeCommitted(editor, "nihao"))
        assertEquals(0, journal.size)
    }

    @Test fun `deleting everything and starting again keeps only the fresh composition`() {
        val journal = PinyinTouchHabitJournal()
        journal.type("mi")
        journal.recordEdit()
        journal.recordEdit()
        journal.type("ni")
        assertEquals("ni", journal.takeCommitted(editor, "ni")!!.map { it.intended }.joinToString(""))
    }

    @Test fun `letters of the next composition typed before the commit arrives are kept for their own commit`() {
        val journal = PinyinTouchHabitJournal()
        journal.type("nihao")
        journal.type("wo") // the commit event of "nihao" is still on its way
        assertEquals(5, journal.takeCommitted(editor, "nihao")!!.size)
        assertEquals(2, journal.size)
        journal.type("men", prefix = "wo")
        assertEquals("women", journal.takeCommitted(editor, "women")!!.map { it.intended }.joinToString(""))
    }

    @Test fun `a spelling that is not plain letters cannot be matched and clears the journal`() {
        val journal = PinyinTouchHabitJournal()
        journal.type("nihaoma")
        assertNull(journal.takeCommitted(editor, null))
        assertEquals(0, journal.size)
        journal.type("ni")
        assertNull(journal.takeCommitted(null, "ni"))
        assertEquals(0, journal.size)
    }

    @Test fun `an unknown spelling leaves later compositions untouched`() {
        val journal = PinyinTouchHabitJournal()
        journal.type("wo")
        assertNull(journal.takeCommitted(editor, "nihao"))
        assertEquals(2, journal.takeCommitted(editor, "wo")!!.size)
    }

    @Test fun `a letter that did not arrive as a tap breaks the chain`() {
        val journal = PinyinTouchHabitJournal()
        journal.type("ni")
        // "h" came from a slide or a hardware key: no tap was recorded for it.
        journal.type("ao", prefix = "nih")
        assertNull(journal.takeCommitted(editor, "nihao"))
        // A tap whose letter is not what the engine appended is no evidence either.
        journal.recordTap(editor, "", "x", tap('z'))
        assertNull(journal.takeCommitted(editor, "x"))
    }

    @Test fun `taps from another input field never complete a composition`() {
        val journal = PinyinTouchHabitJournal()
        val other = Any()
        journal.type("ni", editor = other)
        journal.type("hao", prefix = "ni")
        assertNull(journal.takeCommitted(editor, "nihao"))
        assertNull(journal.takeCommitted(other, "ni"))
    }

    @Test fun `choosing a repaired spelling labels the differing tap with the key that was meant`() {
        val journal = PinyinTouchHabitJournal()
        // The finger meant "i" and landed just inside "u".
        journal.recordTap(editor, "", "n", tap('n'))
        journal.recordTap(editor, "n", "nu", tap('u', dx = 45f))
        journal.type("hao", prefix = "nu")
        val samples = journal.takeRepaired(editor, "nuhao", "nihao")!!
        assertEquals("nihao", samples.map { it.intended }.joinToString(""))
        assertEquals('u', samples[1].evidence.tap.original)
        assertTrue(samples.all { it.confirmation == PinyinTouchHabitJournal.Confirmation.SelectedRepair })
        assertEquals(0, journal.size)
    }

    @Test fun `a repair of another length is not understood and teaches nothing`() {
        val journal = PinyinTouchHabitJournal()
        journal.type("nhao")
        assertNull(journal.takeRepaired(editor, "nhao", "nihao"))
        assertEquals(0, journal.size)
        journal.type("ni")
        assertNull(journal.takeRepaired(editor, "ni", "n1"))
    }

    @Test fun `the journal stays bounded and an over-long composition is dropped rather than half learned`() {
        val journal = PinyinTouchHabitJournal()
        val long = "a".repeat(PinyinTouchHabitJournal.MAX_ENTRIES + 10)
        journal.type(long)
        assertEquals(PinyinTouchHabitJournal.MAX_ENTRIES, journal.size)
        assertNull(journal.takeCommitted(editor, long))
        repeat(500) { journal.recordEdit() }
        assertTrue(journal.size <= 1)
    }

    @Test fun `committed everyday taps move a key centre only after enough of them and never past the bound`() {
        val profile = PinyinTouchProfile()
        val journal = PinyinTouchHabitJournal()
        // This thumb lands a fifth of a key to the right of "i", every time.
        repeat(63) {
            journal.recordTap(editor, "", "i", tap('i', dx = 20f))
            for (sample in journal.takeCommitted(editor, "i")!!)
                assertTrue(profile.observeHabit(sample))
        }
        assertTrue("Too few taps change nothing", profile.offsets(cells, 2f).isEmpty())
        repeat(200) {
            journal.recordTap(editor, "", "i", tap('i', dx = 20f))
            for (sample in journal.takeCommitted(editor, "i")!!)
                profile.observeHabit(sample)
        }
        val offset = profile.offsets(cells, 2f).getValue('i')
        assertTrue("Learned direction is to the right: ${offset.x}", offset.x > 0.05f)
        assertTrue("And stays inside the bound: ${offset.x}", offset.x <= PinyinSpatialKeyDecider.MAX_CENTER_OFFSET)
        assertEquals(0f, offset.y, 0.001f)
        assertEquals(setOf('i'), profile.offsets(cells, 2f).keys)
    }
}
