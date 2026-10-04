/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.text.Editable
import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import org.fcitx.fcitx5.android.input.voice.DictationEditorWriter
import org.fcitx.fcitx5.android.input.voice.DictationTranscript
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DictationEditorWriterTest {
    private val BaseInputConnection.buffer: Editable get() = requireNotNull(editable)
    private fun connection(prefix: String = "已有文字：") = BaseInputConnection(
        View(RuntimeEnvironment.getApplication()), true).apply {
        buffer.append(prefix)
        Selection.setSelection(buffer, buffer.length)
    }

    private fun writer(connection: BaseInputConnection) = DictationEditorWriter(connection,
        { Selection.getSelectionStart(connection.buffer) })

    @Test fun partialRevisionsReplaceOneComposingSpanWithoutDuplicatingWords() {
        val editor = connection()
        val writer = writer(editor)
        assertTrue(writer.apply(1, DictationTranscript(partial = "你")))
        assertEquals("已有文字：你", editor.buffer.toString())
        assertTrue(writer.apply(1, DictationTranscript(partial = "你好")))
        assertTrue(writer.apply(1, DictationTranscript(partial = "你好")))
        assertEquals("已有文字：你好", editor.buffer.toString())
        assertEquals(5, BaseInputConnection.getComposingSpanStart(editor.buffer))
    }

    @Test fun punctuatedEndpointsCommitOnlyTheNewSentenceAndKeepNextPartialEditable() {
        val editor = connection()
        val writer = writer(editor)
        writer.apply(1, DictationTranscript(partial = "你好"))
        writer.apply(1, DictationTranscript(committed = "你好。", partial = "天气"))
        writer.apply(1, DictationTranscript(committed = "你好。", partial = "天气很好"))
        assertEquals("已有文字：你好。天气很好", editor.buffer.toString())
        val final = DictationTranscript(committed = "你好。天气很好。", complete = true)
        assertTrue(writer.apply(1, final))
        assertTrue(writer.apply(1, final))
        assertEquals("已有文字：你好。天气很好。", editor.buffer.toString())
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.buffer))
    }

    @Test fun emptyInterimUpdateDoesNotCloseSpanOrDuplicateTheFollowingRevision() {
        val editor = connection()
        val writer = writer(editor)
        writer.apply(1, DictationTranscript(partial = "你好"))
        writer.apply(1, DictationTranscript())
        assertEquals("已有文字：你好", editor.buffer.toString())
        writer.apply(1, DictationTranscript(partial = "你好阿翔"))
        assertEquals("已有文字：你好阿翔", editor.buffer.toString())
    }

    @Test fun cancelledRecordingPreservesDisplayedWordsAndEndsOnlyTheirComposition() {
        val editor = connection()
        val writer = writer(editor)
        writer.apply(1, DictationTranscript(partial = "正在说话"))
        writer.finish()
        assertEquals("已有文字：正在说话", editor.buffer.toString())
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.buffer))
        editor.commitText("A", 1)
        assertEquals("已有文字：正在说话A", editor.buffer.toString())
    }

    @Test fun newRecordingAppendsAfterPreviousFinalResult() {
        val editor = connection()
        val writer = writer(editor)
        writer.apply(1, DictationTranscript(committed = "第一句。", complete = true))
        writer.apply(2, DictationTranscript(partial = "第二句"))
        writer.apply(2, DictationTranscript(committed = "第二句。", complete = true))
        assertEquals("已有文字：第一句。第二句。", editor.buffer.toString())
    }

    @Test fun originalSelectionIsReplacedOnceAndSurroundingTextIsPreserved() {
        val editor = connection("前文待替换后文")
        Selection.setSelection(editor.buffer, 2, 5)
        val writer = writer(editor)
        writer.apply(1, DictationTranscript(partial = "新"))
        writer.apply(1, DictationTranscript(partial = "新内容"))
        writer.apply(1, DictationTranscript(committed = "新内容。", complete = true))
        assertEquals("前文新内容。后文", editor.buffer.toString())
    }

    @Test fun emptyResultNeverDeletesTheOriginalSelection() {
        val editor = connection("前文待替换后文")
        Selection.setSelection(editor.buffer, 2, 5)
        val writer = writer(editor)
        writer.apply(1, DictationTranscript(complete = true))
        assertEquals("前文待替换后文", editor.buffer.toString())
        assertEquals(2, Selection.getSelectionStart(editor.buffer))
        assertEquals(5, Selection.getSelectionEnd(editor.buffer))
    }

    @Test fun engineCannotRewriteSentencesAlreadyCommitted() {
        val editor = connection()
        val writer = writer(editor)
        writer.apply(1, DictationTranscript(committed = "第一句。"))
        assertFalse(writer.apply(1, DictationTranscript(committed = "别的话。", partial = "更多")))
        assertEquals("已有文字：第一句。", editor.buffer.toString())
    }

    @Test fun editorThatRejectsComposingIsReportedWithoutAppendingEveryPartial() {
        val editor = object : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int) = false
        }
        editor.buffer.append("已有文字")
        Selection.setSelection(editor.buffer, 4)
        assertFalse(writer(editor).apply(1, DictationTranscript(partial = "你好")))
        assertEquals("已有文字", editor.buffer.toString())
    }
}
