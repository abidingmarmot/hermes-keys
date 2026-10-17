// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.os.Handler
import android.os.Looper

/** Process-wide editor request slot, separate from diagnostics. No queue or retries.
 * As in AgentConnectionDiagnostic, BOTH request and cancellation cleanup must finish before reuse.
 * A stuck transport holds the slot until force-stop. Only the detachable callback knows the IME;
 * workers must never capture the controller, editor, views, or callback directly.
 */
internal object AgentEditorRequest {
    const val DEADLINE_MS = 45_000L
    private val main = Handler(Looper.getMainLooper())
    private var active: Run? = null

    internal class Run(val client: AgentClient, var callback: ((AgentProtocol.Result?, Boolean) -> Unit)?) {
        var canceled = false
        var requestDone = false
        var cleanupDone = true
        lateinit var deadline: Runnable
        fun cancel() = AgentEditorRequest.cancel(this)
    }

    @Synchronized fun isBusy() = active != null

    @Synchronized fun start(config: AgentCredentials.Config, prompt: String,
        callback: (AgentProtocol.Result?, Boolean) -> Unit): Run? {
        if (active != null) return null
        val run = Run(AgentClient(), callback)
        active = run
        run.deadline = Runnable { cancel(run, timeout = true) }
        main.postDelayed(run.deadline, DEADLINE_MS)
        Thread({
            val result = try {
                runCatching { run.client.complete(config, prompt) }.getOrNull()
            } finally {
                synchronized(this) {
                    run.requestDone = true
                    releaseIfDone(run)
                }
            }
            main.post {
                val notify = synchronized(this) {
                    main.removeCallbacks(run.deadline)
                    run.callback.also { run.callback = null }
                }
                notify?.invoke(result, false)
            }
        }, "hermes-editor-request").apply { isDaemon = true }.start()
        return run
    }

    private fun cancel(run: Run, timeout: Boolean = false) {
        val notify: ((AgentProtocol.Result?, Boolean) -> Unit)?
        synchronized(this) {
            notify = if (timeout) run.callback else null
            run.callback = null // Detach even if request/disconnect never return.
            main.removeCallbacks(run.deadline)
            if (!run.canceled && !run.requestDone) {
                run.canceled = true
                run.cleanupDone = false
                Thread({
                    try {
                        run.client.cancelAndDisconnect()
                    } catch (_: Exception) {
                        // No transport details in UI or logs.
                    } finally {
                        synchronized(this) {
                            run.cleanupDone = true
                            releaseIfDone(run)
                        }
                    }
                }, "hermes-editor-close").apply { isDaemon = true }.start()
            }
        }
        notify?.invoke(null, true)
    }

    private fun releaseIfDone(run: Run) {
        if (run.requestDone && run.cleanupDone && active === run) active = null
    }
}
