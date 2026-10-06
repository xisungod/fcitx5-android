/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class PinyinTouchLanguageModelTest {
    private fun row(context: String, favored: Char): String {
        val values = IntArray(26) { 100 }
        values[favored - 'a'] += PinyinTouchLanguageModel.PROBABILITY_SCALE - values.sum()
        return "P\t$context\t" + values.joinToString("\t")
    }

    private fun source() = listOf(
        "# AXPL1\tfixture", row("-", 'a'), row("^", 'n'), row("^j", 'i'),
        row("i", 'n'), row("ji", 'b'), "S\tji\t1", "S\tben\t1", "S\tni\t1", "S\txi\t1"
    ).joinToString("\n")

    @Test fun interpolatedRowsSupportAcrossSyllableTransitionsAndBackoff() {
        val model = PinyinTouchLanguageModel(StringReader(source()))
        assertTrue(model.syllables.contains("ji"))
        assertEquals(5, model.contextCount)
        assertTrue(model.nextLetterProbabilities("ji")!!['b' - 'a'] > 0.9f)
        assertTrue(model.nextLetterProbabilities("niji")!!['b' - 'a'] > 0.9f)
        assertNotNull(model.nextLetterProbabilities("jib"))
        assertNull(model.nextLetterProbabilities("jch"))
        assertNull(model.nextLetterProbabilities("nijch"))
        assertTrue(model.nextLetterProbabilities("xi")!!['n' - 'a'] > 0.9f)
        assertTrue(model.nextLetterProbabilities("xi'j")!!['i' - 'a'] > 0.9f)
        assertTrue(model.nextLetterProbabilities("xi'")!!['n' - 'a'] > 0.9f)
        assertTrue(model.nextLetterProbabilities("JI")!!['b' - 'a'] > 0.9f)
        assertNull(model.nextLetterProbabilities("ji2"))
    }

    @Test fun callersCannotMutateTheModelAndProbabilitiesAreNormalized() {
        val model = PinyinTouchLanguageModel(StringReader(source()))
        val probabilities = model.nextLetterProbabilities("ji")!!
        assertEquals(1.0f, probabilities.sum(), 0.000001f)
        assertTrue(probabilities.all { it > 0f })
        probabilities.fill(0f)
        assertTrue(model.nextLetterProbabilities("ji")!!['b' - 'a'] > 0.9f)
    }

    @Test fun damagedModelFailsInsteadOfSilentlySupplyingBadEvidence() {
        val invalid = source().replace("S\tben\t1", row("ji", 'n'))
        assertThrows(IllegalArgumentException::class.java) { PinyinTouchLanguageModel(StringReader(invalid)) }
        assertThrows(IllegalArgumentException::class.java) {
            PinyinTouchLanguageModel(StringReader(source().replace("# AXPL1\tfixture", "# AXPL9\tfixture")))
        }
    }
}
