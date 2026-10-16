// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import helium314.keyboard.keyboard.PointerTracker
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.getPlatformDialogThemeContext

/** IME-owned dialogs never move focus to a different Activity/editor to process text. */
class AgentController(private val ime: LatinIME) {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var client: AgentClient? = null
    private var generation = 0
    private var selectionStart = -1
    private var selectionEnd = -1
    private var hasResult = false
    private var actionPopup: ActionPopupSession? = null

    fun invalidate() {
        actionPopup?.let {
            it.cancel()
            PointerTracker.cancelActionPopups()
        }
        actionPopup = null
        generation++
        client?.cancel()
        client = null
        handler.removeCallbacksAndMessages(null)
        val previous = dialog
        dialog = null
        hasResult = false
        // A detached/destroyed IME window must not turn cancellation into a crash.
        runCatching { previous?.dismiss() }
    }

    fun selectionChanged(start: Int, end: Int) {
        if (actionPopup != null && (start != selectionStart || end != selectionEnd)) invalidate()
        if (dialog != null && (start != selectionStart || end != selectionEnd)) {
            if (hasResult) dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
            else invalidate()
        }
    }

    private fun allowed() = AgentProtocol.allowed(ime.currentInputEditorInfo,
        Settings.getValues().mIncognitoModeEnabled, ime.currentInputConnection != null)
        && !(ime.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    /** Fail closed before capture, including missing configuration or unreadable local storage. */
    fun prepareActionPopup(): ActionPopupSession? {
        invalidate()
        if (Build.VERSION.SDK_INT < 23 || !Settings.getValues().mSpaceActionPopup ||
            !allowed() || !ime.isInputViewShown) return null
        if (runCatching { AgentCredentials(ime).load() }.getOrNull()?.enabled != true) return null
        val pins = runCatching { LocalActStore(ime).load().filter { it.pinned }.take(3) }.getOrNull() ?: return null
        val connection = ime.currentInputConnection ?: return null
        val editor = ime.currentInputEditorInfo
        val snapshot = AgentEditor.capture(connection) ?: return null
        if (runCatching { snapshot.prompt() }.isFailure) return null
        selectionStart = snapshot.start
        selectionEnd = snapshot.end
        val session = generation
        fun fingerprint(acts: List<LocalAct>) = acts.map { listOf(it.id, it.name, it.template, it.pinned) }
        val expectedPins = fingerprint(pins)
        return ActionPopupSession(listOf(ime.getString(R.string.agent)) +
            pins.mapIndexed { index, act -> "${index + 1} ${ActionPopupSession.label(act.name)}" },
            { handler.post(it) }) { index ->
            // Identity/generation/policy checks precede any fresh editor read.
            if (session != generation) return@ActionPopupSession
            if (!Settings.getValues().mSpaceActionPopup || !allowed() || !ime.isInputViewShown ||
                ime.currentInputConnection !== connection || ime.currentInputEditorInfo !== editor) {
                invalidate(); return@ActionPopupSession
            }
            if (runCatching { AgentCredentials(ime).load() }.getOrNull()?.enabled != true) {
                invalidate(); return@ActionPopupSession
            }
            val currentPins = runCatching { LocalActStore(ime).load().filter { it.pinned }.take(3) }.getOrNull()
            if (currentPins == null || fingerprint(currentPins) != expectedPins ||
                AgentEditor.capture(connection) != snapshot) { invalidate(); return@ActionPopupSession }
            show(if (index == 0) null else pins[index - 1], snapshot)
        }.also { actionPopup = it }
    }

    fun showActs() {
        invalidate()
        if (!allowed()) { toast(R.string.agent_blocked); return }
        if (Build.VERSION.SDK_INT < 23) { toast(R.string.agent_requires_23); return }
        // Capture before opening the picker; never silently retarget an Act to a changed editor.
        val connection = ime.currentInputConnection ?: return
        val editor = ime.currentInputEditorInfo
        val snapshot = AgentEditor.capture(connection)
        if (snapshot == null || runCatching { snapshot.prompt() }.isFailure) {
            toast(R.string.acts_input_error); return
        }
        val acts = runCatching { LocalActs.ordered(LocalActStore(ime).load()) }.getOrNull()
        if (acts == null) { toast(R.string.acts_storage_error); return }
        selectionStart = snapshot.start
        selectionEnd = snapshot.end
        val session = generation
        val picker = AlertDialog.Builder(getPlatformDialogThemeContext(ime))
            .setTitle(R.string.acts_picker)
            .setItems(acts.map { if (it.pinned) ime.getString(R.string.acts_pinned_name, it.name) else it.name }.toTypedArray()) { _, index ->
                if (session != generation || !allowed() || ime.currentInputConnection !== connection ||
                    ime.currentInputEditorInfo !== editor || AgentEditor.capture(connection) != snapshot) {
                    invalidate(); toast(R.string.agent_blocked)
                } else show(acts[index], snapshot)
            }
            .setNeutralButton(R.string.acts_settings) { _, _ ->
                invalidate()
                ime.startActivity(Intent(ime, LocalActsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> invalidate() }.create()
        if (acts.isEmpty()) picker.setMessage(ime.getString(R.string.acts_empty))
        display(picker)
    }

    @JvmOverloads fun show(act: LocalAct? = null, expected: AgentEditor.Snapshot? = null) {
        invalidate()
        if (!allowed()) { toast(R.string.agent_blocked); return }
        if (Build.VERSION.SDK_INT < 23) { toast(R.string.agent_requires_23); return }
        val config = runCatching { AgentCredentials(ime).load() }.getOrNull()
        if (config?.enabled != true) {
            val setup = AlertDialog.Builder(getPlatformDialogThemeContext(ime))
                .setTitle(R.string.agent_settings).setMessage(R.string.agent_setup_needed)
                .setPositiveButton(R.string.agent_settings) { _, _ ->
                    ime.startActivity(Intent(ime, AgentSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.setNegativeButton(android.R.string.cancel, null).create()
            display(setup)
            return
        }
        val connection = ime.currentInputConnection ?: return
        val editor = ime.currentInputEditorInfo
        val snapshot = AgentEditor.capture(connection)
        val prompt = runCatching {
            require(expected == null || expected == snapshot)
            val input = requireNotNull(snapshot).prompt()
            if (act == null) input else LocalActs.expand(act, input)
        }.getOrNull()
        if (snapshot == null || prompt == null) {
            toast(if (act == null) R.string.agent_prompt_limit else R.string.acts_input_error); return
        }
        selectionStart = snapshot.start
        selectionEnd = snapshot.end
        val session = generation
        fun current() = session == generation && allowed() && ime.currentInputConnection === connection
            && ime.currentInputEditorInfo === editor
        val content = TextView(getPlatformDialogThemeContext(ime)).apply {
            setPadding(24, 16, 24, 16)
            text = ime.getString(R.string.agent_send_preview, AgentProtocol.endpoint(config.base).toString(), prompt)
        }
        val preview = AlertDialog.Builder(getPlatformDialogThemeContext(ime))
            .setTitle(act?.name ?: ime.getString(R.string.agent)).setView(ScrollView(ime).apply { addView(content) })
            .setPositiveButton(R.string.agent_send, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> invalidate() }.create()
        if (!display(preview)) return
        preview.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!current() || AgentEditor.capture(connection) != snapshot) {
                invalidate(); toast(R.string.agent_blocked); return@setOnClickListener
            }
            preview.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            content.text = ime.getString(R.string.agent_waiting, prompt)
            val request = AgentClient()
            client = request
            val timeout = Runnable {
                if (current() && client === request) {
                    request.cancel()
                    client = null
                    content.text = ime.getString(R.string.agent_timeout, prompt)
                    preview.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                }
            }
            handler.postDelayed(timeout, 45000)
            Thread({
                val result = runCatching { request.complete(config, prompt) }.getOrNull()
                handler.post {
                    if (!current() || client !== request) return@post
                    handler.removeCallbacks(timeout)
                    client = null
                    if (result == null) {
                        content.text = ime.getString(R.string.agent_request_error, prompt)
                        preview.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    } else {
                        preview.dismiss()
                        val answer = TextView(getPlatformDialogThemeContext(ime)).apply {
                            setPadding(24, 16, 24, 16)
                            text = ime.getString(R.string.agent_result_preview, result.finishReason, result.text)
                            setTextIsSelectable(true)
                        }
                        val confirm = AlertDialog.Builder(getPlatformDialogThemeContext(ime))
                            .setTitle(R.string.agent_result).setView(ScrollView(ime).apply { addView(answer) })
                            .setPositiveButton(R.string.agent_insert, null)
                            .setNegativeButton(android.R.string.cancel) { _, _ -> invalidate() }.create()
                        hasResult = true
                        if (!display(confirm)) return@post
                        var attempted = false
                        confirm.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            if (attempted) return@setOnClickListener
                            attempted = true
                            // Never retry an uncertain/partial insertion; keep the result copyable.
                            confirm.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                            val inserted = current() && AgentEditor.capture(connection) == snapshot &&
                                runCatching { ime.insertAgentResult(result.text, snapshot) }.getOrDefault(false)
                            if (inserted) {
                                confirm.dismiss()
                                invalidate()
                            } else {
                                answer.text = ime.getString(R.string.agent_insert_error, result.text)
                            }
                        }
                    }
                }
            }, "hermes-keyboard-request").start()
        }
    }

    private fun display(value: AlertDialog): Boolean {
        val token = ime.window?.window?.decorView?.windowToken
        val window = value.window
        if (token == null || window == null || !ime.isInputViewShown) {
            invalidate()
            return false
        }
        dialog = value
        value.setOnCancelListener { invalidate() }
        window.attributes = window.attributes.apply {
            this.token = token
            type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_SECURE)
        return try {
            value.show()
            true
        } catch (_: WindowManager.BadTokenException) {
            invalidate()
            false
        } catch (_: IllegalStateException) {
            invalidate()
            false
        }
    }

    private fun toast(id: Int) { Toast.makeText(ime, id, Toast.LENGTH_LONG).show() }
}
