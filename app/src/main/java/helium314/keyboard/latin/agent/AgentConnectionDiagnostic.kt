// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.os.Handler
import android.os.Looper
import helium314.keyboard.latin.R

/** Diagnostics only (not editor requests): one process-wide request, no queue or retries.
 * Like LocalActDocumentReader, hold the slot until BOTH request and cancellation cleanup finish.
 * A transport ignoring cancellation can hold it until force-stop. No transport work runs on main.
 * Only the detachable callback knows the Activity; no response/exception escapes the worker.
 */
internal object AgentConnectionDiagnostic {
    const val DEADLINE_MS = 45_000L
    private val main = Handler(Looper.getMainLooper())
    private var active: Run? = null

    internal class Run(val client: AgentClient, var callback: ((Int) -> Unit)?) {
        var canceled = false
        var requestDone = false
        var cleanupDone = true
        lateinit var deadline: Runnable
        fun cancel() = AgentConnectionDiagnostic.cancel(this)
    }

    @Synchronized fun isBusy() = active != null

    @Synchronized fun start(config: AgentCredentials.Config, callback: (Int) -> Unit): Run? {
        if (active != null) return null
        val run = Run(AgentClient(), callback)
        active = run
        run.deadline = Runnable { cancel(run, timeout = true) }
        main.postDelayed(run.deadline, DEADLINE_MS)
        Thread({
            val message = try {
                run.client.complete(config, AgentSettingsActivity.TEST_PROMPT)
                R.string.agent_test_success
            } catch (failure: Exception) {
                AgentSettingsActivity.diagnosticMessage(failure)
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
                notify?.invoke(message)
            }
        }, "hermes-connection-test").apply { isDaemon = true }.start()
        return run
    }

    private fun cancel(run: Run, timeout: Boolean = false) {
        val notify: ((Int) -> Unit)?
        synchronized(this) {
            notify = if (timeout) run.callback else null
            run.callback = null // Do not retain a destroyed Activity, even if transport hangs forever.
            main.removeCallbacks(run.deadline)
            if (!run.canceled && !run.requestDone) {
                run.canceled = true
                run.cleanupDone = false
                Thread({
                    try {
                        run.client.cancelAndDisconnect()
                    } catch (_: Exception) {
                        // No transport details in status or logs.
                    } finally {
                        synchronized(this) {
                            run.cleanupDone = true
                            releaseIfDone(run)
                        }
                    }
                }, "hermes-connection-close").apply { isDaemon = true }.start()
            }
        }
        notify?.invoke(R.string.agent_test_timeout)
    }

    private fun releaseIfDone(run: Run) {
        if (run.requestDone && run.cleanupDone && active === run) active = null
    }
}
