// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import helium314.keyboard.latin.R
import java.util.UUID

/** Accessible native controls, with no private form data in saved state or autofill. */
class LocalActsActivity : Activity() {
    private lateinit var store: LocalActStore
    private lateinit var form: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        store = LocalActStore(this)
        showList()
    }

    private fun page(title: Int) {
        form = LinearLayout(this).apply {
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
                // Match settings' safe drawing area, including side bars/cutouts in landscape.
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
            requestApplyInsets()
        })
        label(getString(title)).textSize = 22f
    }

    private fun label(value: String) = TextView(this).apply { text = value; form.addView(this) }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        form.addView(this)
        setOnClickListener { action() }
    }

    private fun showList() {
        page(R.string.acts)
        label(getString(R.string.acts_privacy))
        val acts = runCatching { store.load() }.getOrNull()
        if (acts == null) {
            label(getString(if (Build.VERSION.SDK_INT < 23) R.string.agent_requires_23 else R.string.acts_storage_error))
        } else {
            button(getString(R.string.acts_add)) { edit(null) }.isEnabled = acts.size < LocalActs.MAX_COUNT
            if (acts.isEmpty()) label(getString(R.string.acts_empty))
            LocalActs.ordered(acts).forEach { act ->
                label(act.name).textSize = 18f
                button(getString(R.string.acts_edit_named, act.name)) { edit(act) }
                form.addView(CheckBox(this).apply {
                    text = getString(R.string.acts_pin_named, act.name)
                    isSaveEnabled = false
                    isChecked = act.pinned
                    setOnCheckedChangeListener { _, checked ->
                        if (runCatching { store.update { all -> all.map { if (it.id == act.id) it.withPin(checked) else it } } }.isSuccess)
                            showList()
                        else {
                            setOnCheckedChangeListener(null)
                            isChecked = act.pinned
                            label(getString(R.string.acts_storage_error))
                        }
                    }
                })
                button(getString(R.string.acts_delete_named, act.name)) {
                    AlertDialog.Builder(this).setTitle(R.string.acts_delete)
                        .setMessage(getString(R.string.acts_delete_confirm, act.name))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.acts_delete) { _, _ ->
                            if (runCatching { store.update { all -> all.filterNot { it.id == act.id } } }.isSuccess) showList()
                            else label(getString(R.string.acts_storage_error))
                        }.create().apply {
                            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            show()
                        }
                }
            }
        }
        button(getString(R.string.agent_close)) { finish() }
    }

    private fun field(title: Int, limit: Int, value: String, multiline: Boolean): EditText {
        val heading = label(getString(title))
        return EditText(this).apply {
            id = View.generateViewId()
            heading.labelFor = id
            isSaveEnabled = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
            if (Build.VERSION.SDK_INT >= 26) imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            if (!multiline) isSingleLine = true else minLines = 5
            filters = arrayOf(InputFilter.LengthFilter(limit))
            setText(value)
            form.addView(this)
        }
    }

    private fun edit(previous: LocalAct?) {
        page(if (previous == null) R.string.acts_add else R.string.acts_edit)
        label(getString(R.string.acts_template_help))
        val name = field(R.string.acts_name, LocalActs.MAX_NAME, previous?.name.orEmpty(), false)
        val template = field(R.string.acts_template, LocalActs.MAX_TEMPLATE, previous?.template ?: "{input}", true)
        val status = label("").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        button(getString(R.string.acts_save)) {
            val act = LocalAct(previous?.id ?: UUID.randomUUID().toString(), name.text.toString().trim(),
                template.text.toString(), previous?.pinned ?: false)
            val saved = runCatching {
                store.update { all ->
                    if (previous == null) all + act
                    else {
                        require(all.any { it.id == act.id })
                        all.map { if (it.id == act.id) LocalAct(act.id, act.name, act.template, it.pinned) else it }
                    }
                }
            }.isSuccess
            if (saved) showList() else status.setText(R.string.acts_validation_error)
        }
        button(getString(android.R.string.cancel)) { showList() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Deliberately do not serialize private instructions or editor drafts.
    }
}
