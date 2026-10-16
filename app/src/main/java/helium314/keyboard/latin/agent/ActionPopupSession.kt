// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

/** One hold, one immutable mapping. No key codes are dispatched as text or Agent commands. */
class ActionPopupSession internal constructor(
    val labels: List<String>,
    private val post: (() -> Unit) -> Unit,
    private var preview: ((Int) -> Unit)?
) {
    private var queued = false
    fun cancel() { preview = null }
    fun cancelUnlessQueued() { if (!queued) cancel() }

    /** Disarm synchronously; delivery is deferred until the pointer stack has unwound. */
    fun release(index: Int) {
        if (queued || preview == null) return
        queued = true
        if (index !in labels.indices) { cancel(); return }
        post {
            val callback = preview
            preview = null
            callback?.invoke(index)
        }
    }

    companion object {
        internal fun label(name: String): String {
            val count = name.codePointCount(0, name.length)
            return name.substring(0, name.offsetByCodePoints(0, minOf(count, 10))) + if (count > 10) "…" else ""
        }
    }
}
