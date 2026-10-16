// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import java.io.InputStream

/** One process-wide read, with no queue. An uncooperative provider keeps the slot until restart.
 * Neither provider calls nor close/cancel run on the UI thread. Cancellation can itself block, so
 * at most one cleanup thread is allowed too; the slot is held until BOTH threads finish.
 * Only the detachable callback knows about the Activity; workers use the application resolver.
 */
internal object LocalActDocumentReader {
    const val DEADLINE_MS = 15_000L
    private val main = Handler(Looper.getMainLooper())
    private var active: Read? = null

    class Timeout : Exception()

    internal class Read(val open: (CancellationSignal) -> InputStream, var callback: ((Result<LocalAct>) -> Unit)?) {
        val signal = CancellationSignal()
        var stream: InputStream? = null
        var canceled = false
        var readDone = false
        var cleanupDone = true
        lateinit var worker: Thread
        lateinit var deadline: Runnable

        fun cancel() = LocalActDocumentReader.cancel(this)
    }

    @Synchronized fun isBusy() = active != null

    fun start(resolver: ContentResolver, uri: Uri, callback: (Result<LocalAct>) -> Unit): Read? =
        start({ signal ->
            val descriptor = resolver.openAssetFileDescriptor(uri, "r", signal)
                ?: throw IllegalArgumentException("Unavailable document")
            try { descriptor.createInputStream() } catch (e: Exception) {
                descriptor.close()
                throw e
            }
        }, callback)

    @Synchronized internal fun start(open: (CancellationSignal) -> InputStream,
        callback: (Result<LocalAct>) -> Unit): Read? {
        if (active != null) return null
        val read = Read(open, callback)
        active = read
        read.deadline = Runnable { cancel(read, timeout = true) }
        read.worker = Thread({
            val result = runCatching {
                read.signal.throwIfCanceled()
                read.open(read.signal).use { stream ->
                    synchronized(this) { read.stream = stream }
                    read.signal.throwIfCanceled()
                    LocalActTransfer.read(stream)
                }
            }
            synchronized(this) {
                read.readDone = true
                releaseIfDone(read)
            }
            main.post {
                val notify = synchronized(this) {
                    main.removeCallbacks(read.deadline)
                    read.callback.also { read.callback = null }
                }
                notify?.invoke(result)
            }
        }, "local-act-read").apply { isDaemon = true }
        main.postDelayed(read.deadline, DEADLINE_MS)
        read.worker.start()
        return read
    }

    private fun cancel(read: Read, timeout: Boolean = false) {
        val notify: ((Result<LocalAct>) -> Unit)?
        synchronized(this) {
            notify = if (timeout) read.callback else null
            read.callback = null // Detach Activity even if open/read/cancel/close never returns.
            main.removeCallbacks(read.deadline)
            if (!read.canceled && !read.readDone) {
                read.canceled = true
                read.cleanupDone = false
                Thread({
                    try {
                        // Even interrupt can invoke an I/O blocker; keep all cancellation off main.
                        read.worker.interrupt()
                        try { read.signal.cancel() } finally {
                            val stream = synchronized(this) { read.stream }
                            stream?.close()
                        }
                    } catch (_: Exception) {
                        // No provider details or document text in logs/UI.
                    } finally {
                        synchronized(this) {
                            read.cleanupDone = true
                            releaseIfDone(read)
                        }
                    }
                }, "local-act-close").apply { isDaemon = true }.start()
            }
        }
        notify?.invoke(Result.failure(Timeout()))
    }

    private fun releaseIfDone(read: Read) {
        if (read.readDone && read.cleanupDone && active === read) active = null
    }
}
