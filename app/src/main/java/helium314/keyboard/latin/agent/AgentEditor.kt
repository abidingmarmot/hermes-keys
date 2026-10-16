// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/** Fresh, bounded editor state, never the IME's predicted/cached selection. */
object AgentEditor {
    private const val MAX_EDITOR = 65536
    data class Snapshot(val text: String, val start: Int, val end: Int) {
        fun prompt(): String {
            val value = if (start == end) text.substring(0, start)
                else text.substring(minOf(start, end), maxOf(start, end))
            require(value.isNotBlank() && value.length <= AgentProtocol.MAX_PROMPT)
            return value
        }
    }

    @JvmStatic fun capture(connection: InputConnection): Snapshot? = runCatching {
        val extracted = connection.getExtractedText(ExtractedTextRequest().apply {
            hintMaxChars = MAX_EDITOR + 1
        }, 0) ?: return null
        val text = extracted.text ?: return null
        // Partial/unsupported snapshots cannot establish that the document is unchanged.
        require(extracted.startOffset == 0 && extracted.partialStartOffset == -1
            && extracted.partialEndOffset == -1 && text.length <= MAX_EDITOR)
        require(extracted.selectionStart in 0..text.length && extracted.selectionEnd in 0..text.length)
        Snapshot(text.toString(), extracted.selectionStart, extracted.selectionEnd)
    }.getOrNull()

    /** One attempt only. A successful commitText is merely an acknowledgement, not proof. */
    @JvmStatic fun insert(connection: InputConnection, expected: Snapshot, text: String,
        afterFinishComposing: Runnable): Boolean = runCatching {
        require(text.isNotEmpty() && text.length <= AgentProtocol.MAX_RESULT)
        if (capture(connection) != expected) return false
        val position = maxOf(expected.start, expected.end)
        connection.beginBatchEdit()
        try {
            // If this fails, commitText could replace the old composing range.
            if (!connection.finishComposingText()) return false
            afterFinishComposing.run()
            if (capture(connection) != expected) return false
            if (!connection.setSelection(position, position)) return false
            if (capture(connection) != Snapshot(expected.text, position, position)) return false
            if (!connection.commitText(text, 1)) return false
        } finally {
            connection.endBatchEdit()
        }
        val wanted = expected.text.substring(0, position) + text + expected.text.substring(position)
        capture(connection) == Snapshot(wanted, position + text.length, position + text.length)
    }.getOrDefault(false)
}
