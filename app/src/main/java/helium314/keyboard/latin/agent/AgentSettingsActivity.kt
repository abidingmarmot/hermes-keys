// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import helium314.keyboard.latin.R
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** No credentials in intents, saved-instance state, ordinary preferences, or logs. */
class AgentSettingsActivity : Activity() {
    private val diagnosticHandler = Handler(Looper.getMainLooper())
    private lateinit var store: AgentCredentials
    private lateinit var diagnosticButton: Button
    private lateinit var diagnosticStatus: TextView
    private var confirmation: AlertDialog? = null
    private var diagnosticRun: AgentConnectionDiagnostic.Run? = null
    private lateinit var diagnosticBusyStatus: TextView
    private var diagnosticGeneration = 0
    private var diagnosticEligible = false
    private val busyRefresh = object : Runnable {
        override fun run() { refreshDiagnosticAvailability() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            isSaveEnabled = false
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        setContentView(ScrollView(this).apply {
            isSaveEnabled = false
            addView(form)
            if (Build.VERSION.SDK_INT >= 30) setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
            requestApplyInsets()
        })
        fun label(id: Int) = TextView(this).apply { setText(id); form.addView(this) }
        label(R.string.agent_settings).textSize = 22f
        label(R.string.agent_privacy)
        store = AgentCredentials(this)
        val config = if (Build.VERSION.SDK_INT >= 23) runCatching { store.load() }.getOrNull() else null
        label(R.string.agent_base)
        val base = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(2048))
            isSaveEnabled = false
            setText(config?.base.orEmpty())
            form.addView(this)
        }
        label(R.string.agent_token)
        val token = EditText(this).apply {
            // setSingleLine installs a non-password transformation: configure masking last.
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            contentDescription = getString(R.string.agent_token)
            filters = arrayOf(InputFilter.LengthFilter(4096))
            isSaveEnabled = false
            form.addView(this)
        }
        val enabled = CheckBox(this).apply {
            setText(R.string.agent_opt_in)
            isChecked = config?.enabled == true
            isSaveEnabled = false
            form.addView(this)
        }
        val status = label(if (Build.VERSION.SDK_INT < 23) R.string.agent_requires_23 else R.string.agent_token_hint)
        form.addView(Button(this).apply {
            setText(R.string.agent_save)
            isEnabled = Build.VERSION.SDK_INT >= 23
            setOnClickListener {
                val cancellation = cancelDiagnostic()
                val saved = runCatching {
                    val enteredToken = token.text.toString()
                    store.save(AgentCredentials.configurationForSave(base.text.toString().trim(),
                        enteredToken, enabled.isChecked, if (enteredToken.isEmpty()) store.load() else null))
                }.isSuccess
                status.setText(if (saved) R.string.agent_saved else R.string.agent_config_error)
                if (saved) token.text.clear()
                refreshDiagnostic()
                cancellation?.let { diagnosticStatus.setText(it) }
            }
        })
        form.addView(Button(this).apply {
            setText(R.string.agent_forget)
            isEnabled = Build.VERSION.SDK_INT >= 23
            setOnClickListener {
                val cancellation = cancelDiagnostic()
                val cleared = runCatching { store.clear() }.isSuccess
                if (cleared) { base.text.clear(); token.text.clear(); enabled.isChecked = false }
                status.setText(if (cleared) R.string.agent_forgotten else R.string.agent_config_error)
                refreshDiagnostic()
                cancellation?.let { diagnosticStatus.setText(it) }
            }
        })
        label(R.string.agent_test_saved_notice)
        diagnosticStatus = label(R.string.agent_test_no_config).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        diagnosticBusyStatus = label(R.string.agent_test_busy)
        diagnosticButton = Button(this).apply {
            setText(R.string.agent_test_connection)
            setOnClickListener { confirmDiagnostic() }
            form.addView(this)
        }
        refreshDiagnostic()
        enabled.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                val cancellation = cancelDiagnostic()
                refreshDiagnostic()
                cancellation?.let { diagnosticStatus.setText(it) }
            }
        }
        form.addView(Button(this).apply {
            setText(R.string.agent_close)
            setOnClickListener { cancelDiagnostic(); finish() }
        })
    }

    /** Always read saved storage, never the editable form or any keyboard/editor data. */
    private fun refreshDiagnostic(): AgentCredentials.Config? {
        val loaded = if (Build.VERSION.SDK_INT >= 23) runCatching { store.load() } else null
        val config = loaded?.getOrNull()
        val message = when {
            Build.VERSION.SDK_INT < 23 -> R.string.agent_requires_23
            loaded?.isFailure == true -> R.string.agent_test_storage_error
            config == null -> R.string.agent_test_no_config
            !config.enabled -> R.string.agent_test_opt_in_needed
            else -> R.string.agent_test_ready
        }
        diagnosticEligible = config?.enabled == true
        refreshDiagnosticAvailability()
        diagnosticStatus.setText(message)
        return config?.takeIf { it.enabled }
    }

    /** Poll only local slot availability; never retry, load credentials or contact the server. */
    private fun refreshDiagnosticAvailability() {
        diagnosticHandler.removeCallbacks(busyRefresh)
        val busy = AgentConnectionDiagnostic.isBusy()
        diagnosticBusyStatus.visibility = if (busy) View.VISIBLE else View.GONE
        diagnosticButton.isEnabled = diagnosticEligible && confirmation == null &&
            diagnosticRun == null && !busy
        if (busy && !isFinishing && !isDestroyed) diagnosticHandler.postDelayed(busyRefresh, 250)
    }

    private fun confirmDiagnostic() {
        if (confirmation != null || diagnosticRun != null || isFinishing || isDestroyed) return
        if (AgentConnectionDiagnostic.isBusy()) { refreshDiagnostic(); return }
        val saved = refreshDiagnostic() ?: return
        val generation = diagnosticGeneration
        val dialog = AlertDialog.Builder(this).setTitle(R.string.agent_test_connection)
            .setMessage(getString(R.string.agent_test_confirm, TEST_PROMPT))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.agent_test_send, null).create()
        confirmation = dialog
        diagnosticButton.isEnabled = false
        dialog.setOnDismissListener {
            if (confirmation === dialog) {
                confirmation = null
                refreshDiagnostic()
                diagnosticStatus.setText(R.string.agent_test_not_sent)
            }
        }
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (confirmation !== dialog || generation != diagnosticGeneration || isFinishing || isDestroyed) return@setOnClickListener
            // Refuse stale consent if saved storage changed or became unavailable during confirmation.
            val current = refreshDiagnostic()
            confirmation = null
            dialog.dismiss()
            if (current == null || saved.base != current.base || saved.token != current.token) {
                refreshDiagnostic()
                diagnosticStatus.setText(R.string.agent_test_config_changed)
                return@setOnClickListener
            }
            startDiagnostic(current)
        }
    }

    private fun startDiagnostic(config: AgentCredentials.Config) {
        val generation = diagnosticGeneration
        diagnosticRun = AgentConnectionDiagnostic.start(config) { message ->
            if (!isFinishing && !isDestroyed && generation == diagnosticGeneration) {
                diagnosticRun = null
                refreshDiagnostic()
                diagnosticStatus.setText(message)
            }
        }
        refreshDiagnostic()
        if (diagnosticRun != null) diagnosticStatus.setText(R.string.agent_test_waiting)
    }

    /** Return a truthful status for visible cancellation, distinguishing consent from a sent request. */
    private fun cancelDiagnostic(): Int? {
        val message = when {
            diagnosticRun != null -> R.string.agent_test_cancelled
            confirmation != null -> R.string.agent_test_not_sent
            else -> null
        }
        diagnosticGeneration++
        diagnosticRun?.cancel()
        diagnosticRun = null
        diagnosticHandler.removeCallbacksAndMessages(null)
        val previous = confirmation
        confirmation = null
        previous?.dismiss()
        return message
    }

    override fun onDestroy() {
        cancelDiagnostic()
        super.onDestroy()
    }

    companion object {
        internal const val TEST_PROMPT = "Reply exactly READY."

        /** Only fixed local messages escape this boundary. Never render/log exception details. */
        internal fun diagnosticMessage(failure: Exception): Int = when (failure) {
            is AgentClient.HttpStatusException -> when (failure.status) {
                401, 403 -> R.string.agent_test_auth
                404 -> R.string.agent_test_route
                429 -> R.string.agent_test_quota
                in 500..599 -> R.string.agent_test_server
                else -> R.string.agent_test_http
            }
            is SSLException -> R.string.agent_test_tls
            is UnknownHostException -> R.string.agent_test_dns
            is SocketTimeoutException -> R.string.agent_test_timeout
            is IOException -> R.string.agent_test_network
            else -> R.string.agent_test_unexpected
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Intentionally do not serialize the form or token into Activity state.
    }
}
