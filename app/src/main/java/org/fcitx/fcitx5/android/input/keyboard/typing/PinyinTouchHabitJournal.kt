/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

/**
 * Turns everyday typing into touch calibration evidence.
 *
 * A letter someone typed, kept, and then committed is very probably the letter they meant.
 * This journal holds the taps of the compositions still on screen and hands back the taps
 * of one composition only when that exact spelling was committed without any correction
 * in between. The caller then stores where on each key those taps landed.
 *
 * It keeps taps only until their composition ends, at most [MAX_ENTRIES] of them, and never
 * the committed text. Anything it cannot explain breaks the chain, and a broken chain
 * teaches nothing.
 */
internal class PinyinTouchHabitJournal {
    /** One tap and the key it is evidence for, which differs from the key hit after a repair. */
    data class Sample(val evidence: PinyinTapEvidence, val intended: Char)

    private sealed interface Entry
    private class Tap(
        val editor: Any, val before: String, val after: String, val evidence: PinyinTapEvidence
    ) : Entry
    /** A deletion or an edit this journal cannot follow. */
    private object Break : Entry

    private val entries = ArrayDeque<Entry>()

    /**
     * Call after the letter reached the engine, with the spelling before and after it.
     * A tap that did not simply append its own letter is recorded as a break.
     */
    @Synchronized
    fun recordTap(editor: Any?, before: String?, after: String?, evidence: PinyinTapEvidence?) {
        val letter = evidence?.tap?.original
        if (editor == null || before == null || after == null || letter == null ||
            letter !in 'a'..'z' || after != before + letter) {
            recordEdit()
            return
        }
        entries.addLast(Tap(editor, before, after, evidence))
        trim()
    }

    /** Backspace, a moved cursor, or any other change to the letters already typed. */
    @Synchronized
    fun recordEdit() {
        if (entries.lastOrNull() !== Break) entries.addLast(Break)
        trim()
    }

    @Synchronized
    fun clear() = entries.clear()

    val size: Int
        @Synchronized get() = entries.size

    /**
     * The composition that read [spelling] was committed in [editor]. Returns its taps in
     * typing order, each labelled with the letter that was typed, or null when the
     * composition was edited on the way or cannot be identified with certainty.
     * Everything up to and including that composition is forgotten either way.
     */
    @Synchronized
    fun takeCommitted(editor: Any?, spelling: String?): List<Sample>? {
        if (editor == null || spelling.isNullOrEmpty()) {
            // The spelling on screen was not plain letters, for example after choosing a
            // word for only part of it. Nothing here can be matched to what was committed.
            entries.clear()
            return null
        }
        val taps = takeChain(editor, spelling) ?: return null
        return taps.map { Sample(it.evidence, it.evidence.tap.original) }
    }

    /**
     * The user chose the repaired spelling [intended] in place of the letters they hit,
     * [typed]. Where the two differ, the tap is evidence for the key that was meant.
     * Only same-length repairs are understood; anything else returns null.
     */
    @Synchronized
    fun takeRepaired(editor: Any?, typed: String?, intended: String?): List<Sample>? {
        if (editor == null || typed.isNullOrEmpty() || intended == null) return null
        val taps = takeChain(editor, typed) ?: return null
        if (intended.length != typed.length || intended.any { it !in 'a'..'z' }) return null
        return taps.mapIndexed { index, tap -> Sample(tap.evidence, intended[index]) }
    }

    private fun takeChain(editor: Any, spelling: String): List<Tap>? {
        val end = entries.indexOfLast { it is Tap && it.editor === editor && it.after == spelling }
        if (end < 0) return null
        val chain = ArrayList<Tap>()
        var index = end
        var complete = false
        while (index >= 0) {
            val tap = entries[index] as? Tap ?: break
            if (tap.editor !== editor) break
            if (chain.isNotEmpty() && chain.last().before != tap.after) break
            chain += tap
            if (tap.before.isEmpty()) {
                complete = true
                break
            }
            index--
        }
        repeat(end + 1) { entries.removeFirst() }
        if (!complete || chain.size != spelling.length) return null
        chain.reverse()
        return chain
    }

    private fun trim() {
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    companion object {
        /** Room for a long sentence plus the first letters of the next one. */
        const val MAX_ENTRIES = 96
    }
}
