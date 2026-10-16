// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
    private var awaitingDocument = false
    private var importGeneration = 0
    private var documentRead: LocalActDocumentReader.Read? = null

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
        documentRead?.cancel()
        documentRead = null
        importGeneration++
        page(R.string.acts)
        label(getString(R.string.acts_privacy))
        val acts = runCatching { store.load() }.getOrNull()
        if (acts == null) {
            label(getString(if (Build.VERSION.SDK_INT < 23) R.string.agent_requires_23 else R.string.acts_storage_error))
        } else {
            button(getString(R.string.acts_add)) { edit(null) }.isEnabled = acts.size < LocalActs.MAX_COUNT
            button(getString(R.string.acts_import)) { pickDocument() }.isEnabled = acts.size < LocalActs.MAX_COUNT
            if (acts.isEmpty()) label(getString(R.string.acts_empty))
            LocalActs.ordered(acts).forEach { act ->
                label(act.name).textSize = 18f
                button(getString(R.string.acts_edit_named, act.name)) { edit(act) }
                button(getString(R.string.acts_share_named, act.name)) { previewShare(act) }
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

    private fun previewShare(act: LocalAct) {
        val payload = runCatching { LocalActTransfer.encode(act) }.getOrElse {
            label(getString(R.string.acts_transfer_error))
            return
        }
        page(R.string.acts_share)
        label(getString(R.string.acts_share_warning))
        label(payload) // Exact plaintext that will become EXTRA_TEXT, not a summary.
        val status = label("")
        button(getString(R.string.acts_share_confirm)) {
            val shared = runCatching {
                // Fail closed if storage became unavailable or this preview became stale.
                require(store.load().any { it.id == act.id && it.name == act.name && it.template == act.template })
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, payload)
                startActivity(Intent.createChooser(send, getString(R.string.acts_share)))
            }.isSuccess
            if (shared) showList() else status.setText(R.string.acts_transfer_error)
        }
        button(getString(android.R.string.cancel)) { showList() }
    }

    @Suppress("DEPRECATION")
    private fun pickDocument() {
        if (awaitingDocument) return
        if (LocalActDocumentReader.isBusy()) {
            label(getString(R.string.acts_import_busy))
            return
        }
        awaitingDocument = true
        val opened = runCatching {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
                putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            }, IMPORT_DOCUMENT)
        }.isSuccess
        if (!opened) {
            awaitingDocument = false
            label(getString(R.string.acts_transfer_error))
        }
    }

    @Deprecated("Uses the native Activity result callback for this single local picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != IMPORT_DOCUMENT || !awaitingDocument || isFinishing || isDestroyed) return
        awaitingDocument = false
        if (resultCode != RESULT_OK) return
        val uri = data?.data
        // Never accept file paths, remote URLs, clip-data batches or unsolicited callbacks.
        if (uri?.scheme != "content" || data.clipData != null ||
            runCatching { store.load() }.isFailure) {
            label(getString(R.string.acts_transfer_error))
            return
        }
        page(R.string.acts_import)
        label(getString(R.string.acts_import_warning))
        label(getString(R.string.acts_import_reading))
        val generation = ++importGeneration
        button(getString(android.R.string.cancel)) { showList() }
        documentRead = LocalActDocumentReader.start(applicationContext.contentResolver, uri) { result ->
            if (generation == importGeneration && !isFinishing && !isDestroyed) {
                documentRead = null
                val act = result.getOrNull()
                if (act == null) {
                    showList()
                    label(getString(if (result.exceptionOrNull() is LocalActDocumentReader.Timeout)
                        R.string.acts_import_timeout else R.string.acts_transfer_error))
                } else previewImport(act)
            }
        }
        if (documentRead == null) {
            showList()
            label(getString(R.string.acts_import_busy))
        }
    }

    private fun previewImport(act: LocalAct) {
        page(R.string.acts_import)
        label(getString(R.string.acts_import_warning))
        label(getString(R.string.acts_name))
        label(act.name)
        label(getString(R.string.acts_template))
        label(act.template)
        val status = label("")
        button(getString(R.string.acts_import_confirm)) {
            val saved = runCatching { store.update { LocalActTransfer.addNew(it, act) } }.isSuccess
            if (saved) showList() else status.setText(R.string.acts_transfer_error)
        }
        button(getString(android.R.string.cancel)) { showList() }
    }

    override fun onDestroy() {
        awaitingDocument = false
        importGeneration++
        documentRead?.cancel()
        documentRead = null
        super.onDestroy()
    }

    private companion object {
        const val IMPORT_DOCUMENT = 1001
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Deliberately do not serialize private instructions or editor drafts.
    }
}
