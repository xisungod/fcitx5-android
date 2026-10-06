/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.typingtest

import org.junit.Assert.*
import org.junit.Test

class TypingTestAlignmentTest {
    @Test fun `full regression has two unique substitutions in observed order`() {
        val alignment = TypingTestAligner.align("jibgchsnghui", "jingchanghui")!!
        assertEquals(2, alignment.distance)
        assertFalse(alignment.ambiguous)
        assertEquals(listOf(2, 6), alignment.edits.filter { it.kind == TypingTestEditKind.SUBSTITUTE }
            .map { it.inputIndex })
    }

    @Test fun `repeated letter ambiguity provides distance but no guessed label mapping`() {
        val alignment = TypingTestAligner.align("nn", "n")!!
        assertEquals(1, alignment.distance)
        assertTrue(alignment.ambiguous)
        assertTrue(alignment.edits.isEmpty())
    }

    @Test fun `swaps and insertion deletion ties cannot label physical intent`() {
        val alignment = TypingTestAligner.align("gn", "ng")!!
        assertEquals(2, alignment.distance)
        assertTrue(alignment.ambiguous)
        assertTrue(alignment.edits.isEmpty())
    }

    @Test fun `unique insertion and deletion carry nullable indexes`() {
        val insert = TypingTestAligner.align("jinxg", "jing")!!
        assertFalse(insert.ambiguous)
        val edit = insert.edits.single { it.kind == TypingTestEditKind.INSERT }
        assertEquals('x', edit.actual)
        assertNull(edit.targetIndex)
        val delete = TypingTestAligner.align("jing", "jinxg")!!
        assertNull(delete.edits.single { it.kind == TypingTestEditKind.DELETE }.inputIndex)
    }

    @Test fun `unsupported alphabet oversized inputs and cost limits fail closed`() {
        assertNull(TypingTestAligner.align("ni hao", "nihao"))
        assertNull(TypingTestAligner.align("NIHAO", "nihao"))
        assertNull(TypingTestAligner.align("a".repeat(65), "a".repeat(65)))
        assertNull(TypingTestAligner.align("abcdefg", "zzzzzzz"))
        assertNull(TypingTestAligner.align("a", "b", maximumEdits = 0))
        assertNull(TypingTestAligner.align("a", "a", maximumEdits = 1000))
    }

    @Test fun `qwerty adjacency covers every key symmetric and excludes remote pairs`() {
        for (letter in 'a'..'z') {
            assertFalse(TypingTestQwerty.areAdjacent(letter, letter))
            assertTrue(('a'..'z').any { TypingTestQwerty.areAdjacent(letter, it) })
            for (other in 'a'..'z') assertEquals(TypingTestQwerty.areAdjacent(letter, other),
                TypingTestQwerty.areAdjacent(other, letter))
        }
        for ((a, b) in listOf('b' to 'n', 'l' to 'k', 'a' to 's', 'j' to 'u', 'n' to 'h'))
            assertTrue(TypingTestQwerty.areAdjacent(a, b))
        assertFalse(TypingTestQwerty.areAdjacent('q', 'p'))
        assertFalse(TypingTestQwerty.areAdjacent('q', 'z'))
        assertFalse(TypingTestQwerty.areAdjacent('?', 'n'))
    }

    @Test fun `every conventional row neighbour pair is counted in either substitution direction`() {
        val pairs = ("qw we er rt ty yu ui io op as sd df fg gh hj jk kl zx xc cv vb bn nm " +
            "qa wa ws es ed rd rf tf tg yg yh uh uj ij ik ok ol pl " +
            "az sz sx dz dx dc fx fc fv gc gv gb hv hb hn jb jn jm kn km lm")
            .split(' ').map { it[0] to it[1] }
        assertEquals(62, pairs.size)
        for ((a, b) in pairs) for ((actual, intended) in listOf(a to b, b to a)) {
            assertTrue("$actual -> $intended", TypingTestQwerty.areAdjacent(actual, intended))
            val prompt = TypingTestPrompt(99, "测", intended.toString())
            val result = TypingTestMetrics.evaluate(TypingTestTrialInput(prompt,
                TypingTestInputKind.FULL_PINYIN, firstAttemptPinyin = actual.toString(),
                firstAttemptComplete = true))
            assertEquals("$actual -> $intended", TypingTestFraction(1, 1), result.adjacentSubstitutionRate)
        }
    }
}
