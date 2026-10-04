/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import android.view.inputmethod.InputConnection

/** Main-thread editor writer. Revisions replace only the current dictated composing sentence. */
internal class DictationEditorWriter(
    private val editor: InputConnection,
    private val selectionStart: () -> Int,
    private val onComposition: (Int, String) -> Unit = { _, _ -> },
    private val onCommit: (Int) -> Unit = {},
    private val onFinish: () -> Unit = {}
) {
    private var run = Long.MIN_VALUE
    private var committedPrefix = ""
    private var compositionStart: Int? = null
    private var partial = ""

    fun apply(run: Long, result: DictationTranscript): Boolean = runCatching {
        if (this.run != run) {
            finish()
            this.run = run
            committedPrefix = ""
        }
        if (!result.committed.startsWith(committedPrefix)) return false
        val nextSentence = result.committed.removePrefix(committedPrefix)
        editor.beginBatchEdit()
        try {
            if (nextSentence.isNotEmpty()) {
                val start = compositionStart ?: selectionStart()
                if (!editor.commitText(nextSentence, 1)) return false
                committedPrefix = result.committed
                compositionStart = null
                partial = ""
                onCommit(start + nextSentence.length)
            }
            if (result.partial.isNotEmpty() && result.partial != partial) {
                val start = compositionStart ?: selectionStart()
                if (!editor.setComposingText(result.partial, 1)) return false
                compositionStart = start
                partial = result.partial
                onComposition(start, partial)
            } else if (result.complete && result.partial.isEmpty()) {
                // A blank interim result must not end the span: the next revision still replaces it.
                finish()
            }
            true
        } finally {
            editor.endBatchEdit()
        }
    }.getOrDefault(false)

    /** Keep already displayed words when recording is cancelled; never erase user text. */
    fun finish() {
        if (compositionStart == null) return
        runCatching { editor.finishComposingText() }
        compositionStart = null
        partial = ""
        onFinish()
    }
}
