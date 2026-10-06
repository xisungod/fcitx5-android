/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PinyinMultiPathTrackerTest {
    private val editor = Any()
    private val raw = "jibgchsnghui"
    private val corrected = "jingchanghui"
    private val cells = listOf(row("qwertyuiop", 0f, 0f), row("asdfghjkl", 50f, 140f),
        row("zxcvbnm", 150f, 280f)).flatten()
    private val model by lazy {
        listOf(File("src/main/assets/typing/pinyin_touch_model.tsv"),
            File("app/src/main/assets/typing/pinyin_touch_model.tsv")).first { it.isFile }
            .reader().use(PinyinTouchLanguageModel::parse)
    }
    private fun tracker() = PinyinMultiPathTracker(model, model.syllables)
    private fun row(letters: String, left: Float, top: Float) = letters.mapIndexed { i, letter ->
        KeyCell(letter, left + i * 100f, top, left + (i + 1) * 100f, top + 140f)
    }
    private fun evidence(letter: Char, boundary: Boolean, geometry: List<KeyCell> = cells,
                         density: Float = 1f, inset: Float = .25f, jitterY: Float = 0f): PinyinTapEvidence {
        val cell = geometry.first { it.letter == letter }
        val x = if (boundary) when (letter) {
            'b', 'y' -> cell.right - inset
            's' -> cell.left + inset
            else -> cell.centerX
        } else cell.centerX
        return PinyinTapEvidence(TapEvidence(letter, x, cell.centerY + jitterY, density), geometry)
    }
    private fun formatted(spelling: String) = spelling.chunked(3).joinToString("' ")
    private fun type(
        tracker: PinyinMultiPathTracker,
        spelling: String = raw,
        boundary: Boolean = true,
        geometry: List<KeyCell> = cells,
        density: Float = 1f,
        inset: Float = .25f,
        jitterY: Float = 0f
    ): PinyinMultiPathProposal? {
        var result: PinyinMultiPathProposal? = null
        for (i in spelling.indices) {
            val before = formatted(spelling.take(i))
            val after = formatted(spelling.take(i + 1))
            result = tracker.recordTap(tracker.nextAction(), editor, before, before.length,
                after, after.length, evidence(spelling[i], boundary, geometry, density, inset, jitterY))
        }
        return result
    }

    @Test fun `two boundary errors yield one backup while literal original remains tracked`() {
        val tracker = tracker()
        val proposal = type(tracker)!!
        assertEquals(raw, tracker.rawSpelling)
        assertEquals(raw.length, tracker.contactCount)
        assertEquals(raw, proposal.originalSpelling)
        assertEquals(corrected, proposal.alternativeSpelling)
        assertEquals(listOf(2, 6), proposal.changedIndices)
        assertTrue(proposal.originalCost.isFinite())
        assertTrue(proposal.alternativeCost < proposal.originalCost)
        assertTrue(proposal.modelConfidence >= .82f)
        assertTrue(proposal.enumeratedPathCount <= 512)
        assertTrue(tracker.matchesProposal(proposal.editorSequence, editor, formatted(raw), formatted(raw).length))
    }

    @Test fun `center contacts are hard protected even under strong language evidence`() {
        val tracker = tracker()
        assertNull(type(tracker, boundary = false))
        assertEquals(raw, tracker.rawSpelling)
    }

    @Test fun `legitimate unfinished syllables and initials receive no expansion backup`() {
        for (spelling in listOf("jib", "jibe", "jiben", "jch", "bj", "nijch", "jingch", "changh")) {
            val tracker = tracker()
            assertNull(spelling, type(tracker, spelling))
            assertEquals(spelling, tracker.rawSpelling)
        }
    }

    @Test fun `third error cannot be repaired by a two substitution path`() {
        val tracker = tracker()
        assertNull(type(tracker, "jibgchsnghyi"))
        assertEquals("jibgchsnghyi", tracker.rawSpelling)
    }

    @Test fun `density normalized jitter retains the constructed double error backup`() {
        // Constructed contacts exercise geometry; this is not a claim about user accuracy.
        for (scale in listOf(.75f, 1f, 2f, 3.5f)) for (inset in listOf(.1f, 1f, 2.5f, 4f)) {
            val geometry = cells.map { KeyCell(it.letter, it.left * scale, it.top * scale,
                it.right * scale, it.bottom * scale) }
            val proposal = type(tracker(), geometry = geometry, density = scale,
                inset = inset * scale, jitterY = 2.3f * scale)!!
            assertEquals("scale=$scale inset=$inset", corrected, proposal.alternativeSpelling)
        }
        val baseline = type(tracker())!!
        val scale = 3f
        val geometry = cells.map { KeyCell(it.letter, it.left * scale, it.top * scale,
            it.right * scale, it.bottom * scale) }
        val scaled = type(tracker(), geometry = geometry, density = scale, inset = .25f * scale)!!
        assertEquals(baseline.originalCost, scaled.originalCost, .000001)
        assertEquals(baseline.alternativeCost, scaled.alternativeCost, .000001)
        assertEquals(baseline.modelConfidence, scaled.modelConfidence, .000001f)
    }

    @Test fun `custom key widths supply current geometry rather than nominal qwerty positions`() {
        var left = 150f
        val customBottom = "zxcvbnm".map { letter ->
            val width = when (letter) { 'b' -> 70f; 'n' -> 135f; else -> 100f }
            KeyCell(letter, left, 280f, left + width, 420f).also { left += width }
        }
        val custom = cells.filter { it.top != 280f } + customBottom
        val proposal = type(tracker(), geometry = custom)!!
        assertEquals(corrected, proposal.alternativeSpelling)
        assertTrue(proposal.originalCost.isFinite())
        assertTrue(proposal.alternativeCost.isFinite())
    }

    @Test fun `corner-only neighbours and touches outside the boundary band stay literal`() {
        val b = cells.first { it.letter == 'b' }
        val cornerOnly = cells.filter { it.letter != 'n' } +
            KeyCell('n', b.right, b.bottom, b.right + 100f, b.bottom + 140f)
        assertNull(type(tracker(), geometry = cornerOnly))
        assertNull(type(tracker(), inset = 7f))
    }

    @Test fun `unknown geometry and malformed distributions cannot create a backup`() {
        assertNull(type(tracker(), geometry = cells + cells.first()))
        for (distribution in listOf(null, floatArrayOf(1f), FloatArray(26),
            FloatArray(26) { Float.NaN }, FloatArray(26) { -1f }, FloatArray(26) { Float.POSITIVE_INFINITY })) {
            val tracker = PinyinMultiPathTracker(NextLetterProbabilityModel { distribution }, model.syllables)
            assertNull(type(tracker))
            assertEquals(raw, tracker.rawSpelling)
        }
    }

    @Test fun `invalid contact coordinates or density never supply a substituted letter`() {
        for (invalid in listOf(TapEvidence('b', Float.NaN, 350f, 1f),
            TapEvidence('b', 649f, Float.POSITIVE_INFINITY, 1f),
            TapEvidence('b', 649f, 350f, 0f), TapEvidence('b', 649f, 350f, Float.NaN))) {
            val tracker = tracker()
            var proposal: PinyinMultiPathProposal? = null
            for (i in raw.indices) {
                val before = raw.take(i)
                val after = raw.take(i + 1)
                val contact = if (raw[i] == 'b') PinyinTapEvidence(invalid, cells) else evidence(raw[i], true)
                proposal = tracker.recordTap(tracker.nextAction(), editor, before, before.length,
                    after, after.length, contact)
            }
            assertNull(proposal)
            assertEquals(raw, tracker.rawSpelling)
        }
    }

    @Test fun `excessive ambiguous contacts retain raw without pruning into a speculative path`() {
        val tracker = tracker()
        val spelling = "ba".repeat(PinyinMultiPathTracker.MAX_AMBIGUOUS_CONTACTS + 1)
        assertNull(type(tracker, spelling))
        assertEquals(spelling, tracker.rawSpelling)
        assertTrue(PinyinMultiPathTracker.MAX_ENUMERATED_PATHS <= 512)
    }

    @Test fun `queued older letters update literal history but cannot publish stale offers`() {
        val tracker = tracker()
        val tokens = raw.map { tracker.nextAction() }
        for (i in raw.indices) {
            val before = raw.take(i)
            val after = raw.take(i + 1)
            val proposal = tracker.recordTap(tokens[i], editor, before, before.length, after, after.length,
                evidence(raw[i], true))
            assertEquals(after, tracker.rawSpelling)
            if (i != raw.lastIndex) assertNull(proposal)
            else assertEquals(corrected, proposal!!.alternativeSpelling)
        }
        assertNull(tracker.recordTap(tokens[0], editor, "", 0, "j", 1, evidence('j', true)))
        assertEquals(raw, tracker.rawSpelling)
        assertNotNull(tracker.currentProposal)
    }

    @Test fun `externally advanced sequence supports loading after actions were enqueued`() {
        val tracker = tracker()
        tracker.advanceSequence(100L)
        for (i in raw.indices) {
            val before = raw.take(i)
            val after = raw.take(i + 1)
            val token = 100L - raw.lastIndex + i
            val proposal = tracker.recordTap(token, editor, before, before.length, after, after.length,
                evidence(raw[i], true))
            if (i != raw.lastIndex) assertNull(proposal)
            else assertEquals(100L, proposal!!.editorSequence)
        }
        tracker.advanceSequence(99L)
        assertNotNull(tracker.currentProposal)
        tracker.advanceSequence(101L)
        assertNull(tracker.currentProposal)
        assertEquals(raw, tracker.rawSpelling)
    }

    @Test fun `backspace removes only final verified original and can later reproduce backup`() {
        val tracker = tracker()
        val proposal = type(tracker)!!
        val shorter = raw.dropLast(1)
        val sequence = tracker.nextAction()
        tracker.recordBackspace(sequence, editor, formatted(raw), formatted(raw).length,
            formatted(shorter), formatted(shorter).length)
        assertEquals(shorter, tracker.rawSpelling)
        assertFalse(tracker.matchesProposal(proposal.editorSequence, editor, raw, raw.length))
        val restored = tracker.recordTap(tracker.nextAction(), editor, shorter, shorter.length,
            raw, raw.length, evidence(raw.last(), false))!!
        assertEquals(corrected, restored.alternativeSpelling)
    }

    @Test fun `unexpected deletion cursor editing conversion and partial commit clear history`() {
        for ((before, cursor, after) in listOf(
            Triple(raw, 2, raw.dropLast(1)), Triple(raw, raw.length, raw.dropLast(2)),
            Triple(raw, raw.length, "经常hui"), Triple(raw, raw.length, "hui"))) {
            val tracker = tracker()
            type(tracker)
            assertNull(tracker.recordBackspace(tracker.nextAction(), editor, before, cursor, after, after.length))
            assertEquals("", tracker.rawSpelling)
            assertNull(tracker.currentProposal)
        }
        val tracker = tracker()
        type(tracker)
        assertNull(tracker.recordTap(tracker.nextAction(), editor, raw, raw.length, "", 0,
            evidence('a', false)))
        assertEquals("", tracker.rawSpelling)
    }

    @Test fun `a different editor or inline-corrected before spelling cannot inherit raw history`() {
        val tracker = tracker()
        type(tracker)
        assertNull(tracker.recordTap(tracker.nextAction(), Any(), raw, raw.length, raw + 'a', raw.length + 1,
            evidence('a', false)))
        assertEquals("", tracker.rawSpelling)
        type(tracker)
        assertNull(tracker.recordTap(tracker.nextAction(), editor, corrected, corrected.length,
            corrected + 'a', corrected.length + 1, evidence('a', false)))
        assertEquals("", tracker.rawSpelling)
    }

    @Test fun `new action reset and conflicting selection tokens invalidate exact proposal guard`() {
        val tracker = tracker()
        val proposal = type(tracker)!!
        assertFalse(tracker.matchesProposal(proposal.editorSequence - 1, editor, raw, raw.length))
        assertFalse(tracker.matchesProposal(proposal.editorSequence, Any(), raw, raw.length))
        assertFalse(tracker.matchesProposal(proposal.editorSequence, editor, raw, 2))
        tracker.nextAction()
        assertFalse(tracker.matchesProposal(proposal.editorSequence, editor, raw, raw.length))
        val old = tracker.nextAction()
        tracker.clear()
        assertNull(tracker.recordTap(old, editor, "", 0, "j", 1, evidence('j', false)))
        assertEquals("", tracker.rawSpelling)
        assertNull(tracker.currentProposal)
    }

    @Test fun `history stops at contact cap rather than retaining an uncertain suffix`() {
        val tracker = tracker()
        type(tracker, "a".repeat(PinyinMultiPathTracker.MAX_CONTACTS), boundary = false)
        assertEquals(PinyinMultiPathTracker.MAX_CONTACTS, tracker.contactCount)
        val before = "a".repeat(PinyinMultiPathTracker.MAX_CONTACTS)
        assertNull(tracker.recordTap(tracker.nextAction(), editor, before, before.length,
            before + 'a', before.length + 1, evidence('a', false)))
        assertEquals(0, tracker.contactCount)
    }

    @Test fun `formatted native spelling recognizes only unchanged latin letters at the end`() {
        assertEquals("jingchanghui", PinyinMultiPathTracker.spellingAtEnd("jing' chang hui", 15))
        assertEquals("xian", PinyinMultiPathTracker.spellingAtEnd("xi'an", 5))
        assertEquals("", PinyinMultiPathTracker.spellingAtEnd("", -1))
        for (text in listOf("经常hui", "nü", "ABC", "abc\n", "abc1", "abc@", "' "))
            assertNull(text, PinyinMultiPathTracker.spellingAtEnd(text, text.length))
        assertNull(PinyinMultiPathTracker.spellingAtEnd("jing' chang hui", 4))
    }
}
