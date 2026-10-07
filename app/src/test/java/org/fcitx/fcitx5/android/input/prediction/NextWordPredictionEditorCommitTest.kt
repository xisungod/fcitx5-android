/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.prediction

import android.app.Application
import android.content.Context
import android.text.Editable
import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Uses the real service editor writer without starting Fcitx or loading native libraries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 33], application = Application::class)
class NextWordPredictionEditorCommitTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var previousApplication: FcitxApplication? = null

    @Before fun prepare() {
        val instance = FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }
        previousApplication = instance.get(null) as? FcitxApplication
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, context))
        instance.set(null, app)
        AppPrefs.init(context.getSharedPreferences("next-word-editor-commit", Context.MODE_PRIVATE))
    }

    @After fun restoreApplication() {
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, previousApplication)
        }
    }

    private class Editor(context: Context) : BaseInputConnection(View(context), true) {
        val buffer: Editable get() = requireNotNull(editable)
        var acceptsCommit = true
        var acceptsSelection = true
        var acceptsFinish = true
        var commitCalls = 0
        var finishCalls = 0
        var beforeCommit: (() -> Unit)? = null

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commitCalls++
            beforeCommit?.invoke()
            return acceptsCommit && super.commitText(text, newCursorPosition)
        }

        override fun setSelection(start: Int, end: Int): Boolean =
            acceptsSelection && super.setSelection(start, end)

        override fun finishComposingText(): Boolean {
            finishCalls++
            return acceptsFinish && super.finishComposingText()
        }
    }

    private fun service(editor: Editor? = null): FcitxInputMethodService =
        Robolectric.buildService(FcitxInputMethodService::class.java).get().also {
            ReflectionHelpers.setField(it, "mStartedInputConnection", editor)
            ReflectionHelpers.getField<CursorTracker>(it, "selection").resetTo(editor?.buffer?.length ?: 0)
        }

    private fun editor(prefix: String = "已有") = Editor(context).apply {
        buffer.append(prefix)
        Selection.setSelection(buffer, buffer.length)
    }

    private fun composing(service: FcitxInputMethodService, text: String) {
        ReflectionHelpers.callInstanceMethod<Unit>(service, "updateComposingText",
            ReflectionHelpers.ClassParameter.from(FormattedText::class.java,
                FormattedText(arrayOf(text), intArrayOf(0), text.length)))
    }

    @Test fun missingConnectionIsNotAnAcceptedPrediction() {
        assertFalse(service().commitText("天气"))
    }

    @Test fun successfulAppendWritesExactlyOnceAndPredictsCursorBeforeEditorCallback() {
        val editor = editor()
        val service = service(editor)
        editor.beforeCommit = {
            assertEquals(4, ReflectionHelpers.getField<CursorTracker>(service, "selection").latest.start)
        }
        assertTrue(service.commitText("天气"))
        assertEquals("已有天气", editor.buffer.toString())
        assertEquals(4, Selection.getSelectionStart(editor.buffer))
        assertEquals(1, editor.commitCalls)
    }

    @Test fun rejectedAppendReturnsFalseWithoutWritingOrRetrying() {
        val editor = editor().apply { acceptsCommit = false }
        assertFalse(service(editor).commitText("天气"))
        assertEquals("已有", editor.buffer.toString())
        assertEquals(1, editor.commitCalls)
    }

    @Test fun requestedCursorMoveMustBeAcceptedAsWellAsTheInsertion() {
        val editor = editor()
        assertTrue(service(editor).commitText("天气", cursor = 1))
        assertEquals("已有天气", editor.buffer.toString())
        assertEquals(3, Selection.getSelectionStart(editor.buffer))

        val rejectsMove = editor().apply { acceptsSelection = false }
        assertFalse(service(rejectsMove).commitText("天气", cursor = 1))
        // The editor accepted the text but rejected the requested cursor position.
        assertEquals("已有天气", rejectsMove.buffer.toString())
        assertEquals(1, rejectsMove.commitCalls)
    }

    @Test fun equalCompositionConfirmsExistingTextWithoutAppendingItAgain() {
        val editor = editor()
        val service = service(editor)
        composing(service, "天气")
        assertEquals(2, BaseInputConnection.getComposingSpanStart(editor.buffer))
        assertTrue(service.commitText("天气"))
        assertEquals("已有天气", editor.buffer.toString())
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.buffer))
        assertEquals(0, editor.commitCalls)
        assertEquals(1, editor.finishCalls)
    }

    @Test fun rejectedCompositionConfirmationIsReportedAsFailure() {
        val editor = editor()
        val service = service(editor)
        composing(service, "天气")
        editor.acceptsFinish = false
        assertFalse(service.commitText("天气"))
        assertEquals("已有天气", editor.buffer.toString())
        assertEquals(2, BaseInputConnection.getComposingSpanStart(editor.buffer))
        assertEquals(0, editor.commitCalls)
        assertEquals(1, editor.finishCalls)
    }

    @Test fun compositionCursorRejectionCannotProduceAnAcceptedPrediction() {
        val editor = editor()
        val service = service(editor)
        composing(service, "天气")
        editor.acceptsSelection = false
        assertFalse(service.commitText("天气", cursor = 1))
        assertEquals("已有天气", editor.buffer.toString())
        assertEquals(0, editor.commitCalls)
        assertEquals(1, editor.finishCalls)
    }
}
