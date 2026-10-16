// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.app.Application
import android.text.InputFilter
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AgentEditorTest {
    private class Editor(text: String, start: Int = text.length, end: Int = start) : BaseInputConnection(
        View(ApplicationProvider.getApplicationContext<Application>()), true) {
        val document = SpannableStringBuilder(text).apply { Selection.setSelection(this, start, end) }
        var finishWorks = true
        var selectionWorks = true
        var readable = true
        var partial = false
        var commits = 0
        override fun getEditable() = document
        override fun finishComposingText(): Boolean = if (finishWorks) super.finishComposingText() else false
        override fun setSelection(start: Int, end: Int): Boolean =
            if (selectionWorks) super.setSelection(start, end) else false
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commits++
            return super.commitText(text, newCursorPosition)
        }
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
            if (!readable) null else ExtractedText().apply {
                text = document.toString()
                startOffset = 0
                partialStartOffset = if (partial) 0 else -1
                partialEndOffset = -1
                selectionStart = Selection.getSelectionStart(document)
                selectionEnd = Selection.getSelectionEnd(document)
            }
    }
    private fun insert(editor: InputConnection, snapshot: AgentEditor.Snapshot, result: String = " reply") =
        AgentEditor.insert(editor, snapshot, result, Runnable {})

    @Test fun failedFinishComposingNeverCommitsOrReplacesPrompt() {
        val editor = Editor("prompt")
        editor.setComposingRegion(0, 6)
        val before = AgentEditor.capture(editor)!!
        editor.finishWorks = false
        var reset = false
        assertFalse(AgentEditor.insert(editor, before, "reply", Runnable { reset = true }))
        assertFalse(reset)
        assertEquals(0, editor.commits)
        assertEquals("prompt", editor.document.toString())
    }

    @Test fun appendAfterSelectionPreservesOriginalAndFinishesComposition() {
        for ((start, end) in listOf(0 to 6, 6 to 0, 6 to 6)) {
            val editor = Editor("prompt rest", start, end)
            editor.setComposingRegion(0, 6)
            assertTrue(insert(editor, AgentEditor.capture(editor)!!))
            assertEquals("prompt reply rest", editor.document.toString())
            assertEquals(1, editor.commits)
        }
    }

    @Test fun acknowledgedTruncatedOrRejectedCommitIsNotFullInsertion() {
        for (limit in listOf(6, 8)) {
            val editor = Editor("prompt")
            editor.document.filters = arrayOf(InputFilter.LengthFilter(limit))
            assertFalse(insert(editor, AgentEditor.capture(editor)!!))
            assertEquals(1, editor.commits)
            assertEquals(if (limit == 6) "prompt" else "prompt r", editor.document.toString())
        }
    }

    @Test fun sameCoordinateMutationAndMovedSelectionFailClosed() {
        val editor = Editor("prompt")
        val before = AgentEditor.capture(editor)!!
        editor.document.replace(0, 6, "change")
        assertEquals(6, Selection.getSelectionEnd(editor.document))
        assertFalse(insert(editor, before))
        assertEquals(0, editor.commits)
        editor.document.replace(0, 6, "prompt")
        editor.setSelection(2, 2)
        assertFalse(insert(editor, before))
        assertEquals(0, editor.commits)
    }

    @Test fun missingPartialAndOversizedSnapshotsFailClosed() {
        val editor = Editor("prompt")
        val before = AgentEditor.capture(editor)!!
        editor.readable = false
        assertNull(AgentEditor.capture(editor))
        assertFalse(insert(editor, before))
        editor.readable = true
        editor.partial = true
        assertNull(AgentEditor.capture(editor))
        assertNull(AgentEditor.capture(Editor("x".repeat(65537))))
        assertEquals(0, editor.commits)
    }

    @Test fun failedSelectionOrUnavailableVerificationNeverRetries() {
        val editor = Editor("prompt")
        val before = AgentEditor.capture(editor)!!
        editor.selectionWorks = false
        assertFalse(insert(editor, before))
        assertEquals(0, editor.commits)
        editor.selectionWorks = true
        val connection = object : InputConnectionWrapper(editor, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val accepted = super.commitText(text, newCursorPosition)
                editor.readable = false
                return accepted
            }
        }
        assertFalse(insert(connection, before))
        assertEquals(1, editor.commits)
        assertEquals("prompt reply", editor.document.toString())
    }

    @Test fun promptUsesFreshSelectionAndNeverTruncates() {
        assertEquals("prompt", AgentEditor.capture(Editor("prompt rest", 0, 6))!!.prompt())
        assertEquals("prompt", AgentEditor.capture(Editor("prompt rest", 6, 0))!!.prompt())
        assertEquals("prompt", AgentEditor.capture(Editor("prompt rest", 6))!!.prompt())
        assertFails { AgentEditor.capture(Editor("x".repeat(4001)))!!.prompt() }
    }
}
