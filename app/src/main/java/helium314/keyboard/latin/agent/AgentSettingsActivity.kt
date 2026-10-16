// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import helium314.keyboard.latin.R

/** No credentials in intents, saved-instance state, ordinary preferences, or logs. */
class AgentSettingsActivity : Activity() {
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
        setContentView(ScrollView(this).apply { addView(form) })
        fun label(id: Int) = TextView(this).apply { setText(id); form.addView(this) }
        label(R.string.agent_settings).textSize = 22f
        label(R.string.agent_privacy)
        val store = AgentCredentials(this)
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
                val saved = runCatching {
                    val enteredToken = token.text.toString()
                    store.save(AgentCredentials.configurationForSave(base.text.toString().trim(),
                        enteredToken, enabled.isChecked, if (enteredToken.isEmpty()) store.load() else null))
                }.isSuccess
                status.setText(if (saved) R.string.agent_saved else R.string.agent_config_error)
                if (saved) token.text.clear()
            }
        })
        form.addView(Button(this).apply {
            setText(R.string.agent_forget)
            isEnabled = Build.VERSION.SDK_INT >= 23
            setOnClickListener {
                val cleared = runCatching { store.clear() }.isSuccess
                if (cleared) { base.text.clear(); token.text.clear(); enabled.isChecked = false }
                status.setText(if (cleared) R.string.agent_forgotten else R.string.agent_config_error)
            }
        })
        form.addView(Button(this).apply { setText(R.string.agent_close); setOnClickListener { finish() } })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Intentionally do not serialize the form or token into Activity state.
    }
}
