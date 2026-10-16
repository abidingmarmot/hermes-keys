// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.Dialog
import android.app.KeyguardManager
import android.os.Binder
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import android.widget.TextView
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsValues
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LocalActsFlowTest {
    private fun dialog(controller: AgentController) = AgentController::class.java.getDeclaredField("dialog")
        .apply { isAccessible = true }.get(controller) as AlertDialog?
    private fun texts(view: View): List<String> = (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()

    private inner class Harness {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        // Delegate Context APIs to a real themed Activity without starting the actual IME/native engine.
        val ime = mock(LatinIME::class.java) { invocation ->
            try {
                val method = activity.javaClass.getMethod(invocation.method.name, *invocation.method.parameterTypes)
                if (invocation.method.returnType.isAssignableFrom(method.returnType))
                    method.invoke(activity, *invocation.rawArguments)
                else RETURNS_DEFAULTS.answer(invocation)
            } catch (_: Exception) { RETURNS_DEFAULTS.answer(invocation) }
        }
        val connection = mock(InputConnection::class.java)
        val editor = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        var text = "chosen private suffix"
        var start = 0
        var end = 6
        var acts = LocalActs.presets().map { it.withPin(true) }
        var configured = true
        var storageFails = false
        val controller = AgentController(ime)
        init {
            val imeDialog = mock(Dialog::class.java)
            val window = mock(Window::class.java)
            val decor = mock(View::class.java)
            `when`(ime.window).thenReturn(imeDialog)
            `when`(imeDialog.window).thenReturn(window)
            `when`(window.decorView).thenReturn(decor)
            `when`(decor.windowToken).thenReturn(Binder())
            `when`(ime.isInputViewShown).thenReturn(true)
            `when`(ime.currentInputConnection).thenReturn(connection)
            `when`(ime.currentInputEditorInfo).thenReturn(editor)
            `when`(connection.getExtractedText(any(), anyInt())).thenAnswer {
                ExtractedText().apply {
                    text = this@Harness.text
                    startOffset = 0; partialStartOffset = -1; partialEndOffset = -1
                    selectionStart = start; selectionEnd = end
                }
            }
            `when`(ime.insertAgentResult(anyString(), any())).thenReturn(true)
        }
        fun pick() {
            val picker = requireNotNull(dialog(controller))
            picker.listView.performItemClick(null, 0, 0)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun actionFlow(block: (Harness) -> Unit) {
        val h = Harness()
        val settings = mock(SettingsValues::class.java)
        SettingsValues::class.java.getField("mSpaceActionPopup").apply { isAccessible = true }.setBoolean(settings, true)
        mockStatic(Settings::class.java).use { static ->
            static.`when`<SettingsValues> { Settings.getValues() }.thenReturn(settings)
            mockConstruction(LocalActStore::class.java) { store, _ ->
                `when`(store.load()).thenAnswer { check(!h.storageFails); h.acts }
            }.use {
                mockConstruction(AgentCredentials::class.java) { credentials, _ ->
                    `when`(credentials.load()).thenAnswer {
                        if (h.configured) AgentCredentials.Config("https://example.invalid", "unit-test-placeholder", true) else null
                    }
                }.use {
                    mockConstruction(AgentClient::class.java).use { clients ->
                        try {
                            block(h)
                            assertTrue(clients.constructed().isEmpty(), "Preview must never construct a network client")
                            verify(h.ime, never()).insertAgentResult(anyString(), any())
                            verify(h.connection, never()).commitText(any(), anyInt())
                        } finally { h.controller.invalidate(); h.activity.finish() }
                    }
                }
            }
        }
    }

    @Test fun actionPopupExactGenericAndThreePinnedPreviewsAreDeferredAndNeverSend() = actionFlow { h ->
        for (index in 0..3) {
            val session = requireNotNull(h.controller.prepareActionPopup())
            assertEquals(4, session.labels.size)
            assertTrue(session.labels.all { it.length <= 16 })
            session.release(index); session.release((index + 1) % 4)
            assertNull(dialog(h.controller))
            shadowOf(Looper.getMainLooper()).idle()
            val preview = requireNotNull(dialog(h.controller))
            val prompt = if (index == 0) "chosen" else LocalActs.expand(h.acts[index - 1], "chosen")
            assertTrue(texts(preview.window!!.decorView).contains(h.activity.getString(R.string.agent_send_preview,
                "https://example.invalid/v1/chat/completions", prompt)))
            h.controller.invalidate()
        }
    }

    @Test fun actionPopupRejectsStaleEditorSnapshotPinAndGeneration() {
        val mutations: List<(Harness) -> Unit> = listOf(
            { it.text = "changed private suffix" },
            { it.start = 1 },
            { `when`(it.ime.currentInputEditorInfo).thenReturn(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }) },
            { `when`(it.ime.currentInputConnection).thenReturn(mock(InputConnection::class.java)) },
            { it.controller.invalidate() },
            { it.controller.selectionChanged(1, 6) },
            { h -> h.acts = h.acts.drop(1) },
            { h -> h.acts = h.acts.map { it.withPin(false) } },
            { h -> h.acts = h.acts.reversed() },
            { h -> h.acts = h.acts.map { LocalAct(it.id, it.name + " edited", it.template, it.pinned) } },
            { h -> h.acts = h.acts.map { LocalAct(it.id, it.name, "New instruction: {input}", it.pinned) } },
            { it.storageFails = true },
            { it.configured = false },
            { `when`(it.ime.isInputViewShown).thenReturn(false) }
        )
        for (mutate in mutations) actionFlow { h ->
            val session = requireNotNull(h.controller.prepareActionPopup())
            session.release(1) // Mutation between release and posted preview is also rejected.
            mutate(h)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(dialog(h.controller))
        }
    }

    @Test fun actionPopupPrivacyAndMissingConfigurationFailBeforeReadingText() {
        val mutations: List<(Harness) -> Unit> = listOf(
            { it.configured = false }, { it.storageFails = true },
            { it.editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD },
            { it.editor.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING },
            { SettingsValues::class.java.getField("mIncognitoModeEnabled").apply { isAccessible = true }.setBoolean(Settings.getValues(), true) },
            { shadowOf(it.activity.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true) },
            { `when`(it.ime.currentInputEditorInfo).thenReturn(null) },
            { `when`(it.ime.currentInputConnection).thenReturn(null) },
            { `when`(it.ime.isInputViewShown).thenReturn(false) },
            { SettingsValues::class.java.getField("mSpaceActionPopup").apply { isAccessible = true }.setBoolean(Settings.getValues(), false) }
        )
        for (mutate in mutations) actionFlow { h ->
            mutate(h)
            assertNull(h.controller.prepareActionPopup())
            verify(h.connection, never()).getExtractedText(any(), anyInt())
            shadowOf(h.activity.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        }
    }

    @Test fun actionPopupPolicyRecheckedOnReleaseBeforeReadingText() {
        for (mutation in 0..4) actionFlow { h ->
            val session = requireNotNull(h.controller.prepareActionPopup())
            clearInvocations(h.connection)
            when (mutation) {
                0 -> h.editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                1 -> h.editor.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                2 -> SettingsValues::class.java.getField("mIncognitoModeEnabled").apply { isAccessible = true }.setBoolean(Settings.getValues(), true)
                3 -> shadowOf(h.activity.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
                4 -> SettingsValues::class.java.getField("mSpaceActionPopup").apply { isAccessible = true }.setBoolean(Settings.getValues(), false)
            }
            session.release(0)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(dialog(h.controller))
            verify(h.connection, never()).getExtractedText(any(), anyInt())
            shadowOf(h.activity.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        }
    }

    @Test fun actionPopupLabelsRefreshAndQueuedCancellationDoesNotPreview() = actionFlow { h ->
        val first = requireNotNull(h.controller.prepareActionPopup())
        first.release(1); first.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(dialog(h.controller))
        h.acts = listOf(LocalAct(h.acts[0].id, "Very long renamed Act", "{input}", true))
        val second = requireNotNull(h.controller.prepareActionPopup())
        assertEquals(listOf("Agent", "1 Very long …"), second.labels)
        second.release(-1)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(dialog(h.controller))
        h.acts = emptyList()
        assertEquals(listOf("Agent"), requireNotNull(h.controller.prepareActionPopup()).labels)
    }

    @Test fun exactPreviewThenExplicitSendReviewAndExplicitInsert() {
        val h = Harness()
        val completed = CountDownLatch(1)
        val settings = mock(SettingsValues::class.java)
        mockStatic(Settings::class.java).use { static ->
            static.`when`<SettingsValues> { Settings.getValues() }.thenReturn(settings)
            mockConstruction(LocalActStore::class.java) { store, _ ->
                `when`(store.load()).thenReturn(LocalActs.presets())
            }.use { stores ->
                mockConstruction(AgentCredentials::class.java) { credentials, _ ->
                    `when`(credentials.load()).thenReturn(AgentCredentials.Config("https://example.invalid/profile", "unit-test-placeholder", true))
                }.use {
                    mockConstruction(AgentClient::class.java) { client, _ ->
                        `when`(client.complete(any<AgentCredentials.Config>() ?: AgentCredentials.Config("", "", false), anyString())).thenAnswer {
                            completed.countDown()
                            AgentProtocol.Result("Reviewed result", "stop")
                        }
                    }.use { clients ->
                        h.controller.showActs()
                        assertEquals(1, stores.constructed().size)
                        assertTrue(clients.constructed().isEmpty())
                        h.pick()
                        val preview = requireNotNull(dialog(h.controller))
                        val prompt = "Summarize the following text concisely:\n\nchosen"
                        assertTrue(texts(preview.window!!.decorView).contains(h.activity.getString(R.string.agent_send_preview,
                            "https://example.invalid/profile/v1/chat/completions", prompt)))
                        assertTrue(clients.constructed().isEmpty())
                        verify(h.ime, never()).insertAgentResult(anyString(), any())
                        preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                        assertTrue(completed.await(5, TimeUnit.SECONDS))
                        for (i in 0..100) {
                            shadowOf(Looper.getMainLooper()).idle()
                            if (dialog(h.controller) !== preview) break
                            Thread.sleep(10)
                        }
                        val result = requireNotNull(dialog(h.controller))
                        assertNotSame(preview, result)
                        verify(clients.constructed().single()).complete(
                            any<AgentCredentials.Config>() ?: AgentCredentials.Config("", "", false), eq(prompt) ?: prompt)
                        verify(h.ime, never()).insertAgentResult(anyString(), any())
                        result.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                        verify(h.ime).insertAgentResult(eq("Reviewed result"), eq(AgentEditor.Snapshot(h.text, 0, 6)))
                        assertNull(dialog(h.controller))
                    }
                }
            }
        }
        h.activity.finish()
    }

    @Test fun noInputPrivacyGatesAndChangedEditorNeverSend() {
        val h = Harness()
        val settings = mock(SettingsValues::class.java)
        mockStatic(Settings::class.java).use { static ->
            static.`when`<SettingsValues> { Settings.getValues() }.thenReturn(settings)
            mockConstruction(LocalActStore::class.java) { store, _ -> `when`(store.load()).thenReturn(LocalActs.presets()) }.use { stores ->
                mockConstruction(AgentClient::class.java).use { clients ->
                    h.start = 0; h.end = 0
                    h.controller.showActs()
                    assertNull(dialog(h.controller))
                    assertTrue(stores.constructed().isEmpty())
                    h.end = 6
                    h.editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    clearInvocations(h.connection)
                    h.controller.showActs()
                    verify(h.connection, never()).getExtractedText(any(), anyInt())
                    h.editor.inputType = InputType.TYPE_CLASS_TEXT
                    h.editor.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    h.controller.showActs()
                    assertNull(dialog(h.controller))
                    h.editor.imeOptions = 0
                    val incognito = SettingsValues::class.java.getField("mIncognitoModeEnabled").apply { isAccessible = true }
                    incognito.setBoolean(settings, true)
                    h.controller.showActs()
                    assertNull(dialog(h.controller))
                    incognito.setBoolean(settings, false)
                    val lock = h.activity.getSystemService(KeyguardManager::class.java)
                    shadowOf(lock).setKeyguardLocked(true)
                    h.controller.showActs()
                    assertNull(dialog(h.controller))
                    shadowOf(lock).setKeyguardLocked(false)
                    assertTrue(stores.constructed().isEmpty())
                    h.controller.showActs()
                    h.text = "mutate private suffix" // Same selection coordinates, different content.
                    h.pick()
                    assertNull(dialog(h.controller))
                    assertTrue(clients.constructed().isEmpty())
                    verify(h.ime, never()).insertAgentResult(anyString(), any())
                }
            }
        }
        h.activity.finish()
    }

    @Test fun standardAgentStillUsesRawTextAndActOverflowNeverSends() {
        val h = Harness()
        mockStatic(Settings::class.java).use { static ->
            static.`when`<SettingsValues> { Settings.getValues() }.thenReturn(mock(SettingsValues::class.java))
            mockConstruction(AgentCredentials::class.java) { credentials, _ ->
                `when`(credentials.load()).thenReturn(AgentCredentials.Config("https://example.invalid", "unit-test-placeholder", true))
            }.use {
                mockConstruction(AgentClient::class.java).use { clients ->
                    h.controller.show()
                    val preview = requireNotNull(dialog(h.controller))
                    assertTrue(texts(preview.window!!.decorView).contains(h.activity.getString(R.string.agent_send_preview,
                        "https://example.invalid/v1/chat/completions", "chosen")))
                    preview.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                    shadowOf(Looper.getMainLooper()).idle()
                    h.text = "x".repeat(3995); h.start = 3995; h.end = 3995
                    h.controller.show(LocalActs.presets().first())
                    assertNull(dialog(h.controller))
                    assertTrue(clients.constructed().isEmpty())
                    verify(h.ime, never()).insertAgentResult(anyString(), any())
                }
            }
        }
        h.activity.finish()
    }

    @Test fun changedSnapshotAfterPreviewAndCancelDoNotSendOrInsert() {
        val h = Harness()
        mockStatic(Settings::class.java).use { static ->
            static.`when`<SettingsValues> { Settings.getValues() }.thenReturn(mock(SettingsValues::class.java))
            mockConstruction(AgentCredentials::class.java) { credentials, _ ->
                `when`(credentials.load()).thenReturn(AgentCredentials.Config("https://example.invalid", "unit-test-placeholder", true))
            }.use {
                mockConstruction(AgentClient::class.java).use { clients ->
                    h.controller.show(LocalActs.presets().first())
                    val preview = requireNotNull(dialog(h.controller))
                    h.text = "mutate private suffix"
                    preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertNull(dialog(h.controller))
                    h.controller.show(LocalActs.presets().first())
                    requireNotNull(dialog(h.controller)).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                    shadowOf(Looper.getMainLooper()).idle()
                    assertNull(dialog(h.controller))
                    assertTrue(clients.constructed().isEmpty())
                    verify(h.ime, never()).insertAgentResult(anyString(), any())
                }
            }
        }
        h.activity.finish()
    }
}
