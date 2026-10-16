// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LocalActsTest {
    private fun act(name: String = "My Act", template: String = "Explain:\n{input}") =
        LocalAct(UUID.randomUUID().toString(), name, template)

    @Test fun presetsAreValidEditableIndependentCopies() {
        val defaults = LocalActs.presets()
        LocalActs.validateAll(defaults)
        assertEquals(listOf("Summarize", "Rewrite", "Translate", "Lookup"), defaults.map { it.name })
        assertEquals(defaults.map { it.id }, LocalActs.presets().map { it.id })
        val edited = LocalAct(defaults[0].id, "Short", "Shorten {input}", true)
        LocalActs.validate(edited)
        assertNotEquals(edited.template, LocalActs.presets()[0].template)
        assertFalse(edited.toString().contains(edited.template))
    }

    @Test fun literalExpansionUsesOnlySelectedOrPrecursorTextAndNeverRecurses() {
        val definition = act()
        assertEquals("Explain:\nchosen", LocalActs.expand(definition, AgentEditor.Snapshot("chosen private", 0, 6).prompt()))
        assertEquals("Explain:\nchosen", LocalActs.expand(definition, AgentEditor.Snapshot("chosen private", 6, 0).prompt()))
        assertEquals("Explain:\nchosen", LocalActs.expand(definition, AgentEditor.Snapshot("chosen private", 6, 6).prompt()))
        assertEquals("Explain:\n{input} $1 \\path", LocalActs.expand(definition, "{input} $1 \\path"))
        assertFails { LocalActs.expand(definition, "   ") }
        assertFails { LocalActs.expand(definition, "x".repeat(4000)) }
        assertEquals(4000, LocalActs.expand(act(template = "{input}"), "x".repeat(4000)).length)
        assertFails { AgentEditor.Snapshot("after", 0, 0).prompt() }
    }

    @Test fun namesSizesPlaceholdersCountsAndDuplicatesAreBounded() {
        listOf("", " ", " padded", "x\ny", "x".repeat(49)).forEach { name ->
            assertFails { LocalActs.validate(act(name)) }
        }
        listOf("", "no placeholder", "{INPUT}", "{input} {input}", "{input}\u0000", "x".repeat(2000) + "{input}").forEach {
            assertFails { LocalActs.validate(act(template = it)) }
        }
        LocalActs.validate(act("x".repeat(48), "x".repeat(1993) + "{input}"))
        assertFails { LocalActs.validate(LocalAct("invalid", "Name", "{input}")) }
        assertFails { LocalActs.validateAll(listOf(act("Same"), act("same"))) }
        val one = act()
        assertFails { LocalActs.validateAll(listOf(one, one)) }
        assertFails { LocalActs.validateAll((1..33).map { act("Act $it") }) }
    }

    @Test fun proseLabelsAndInputPlaceholderAreNotURLsOrCredentials() {
        listOf("Summarize this data: {input}", "Return one token: {input}").forEach { template ->
            LocalActs.validate(act(template = template))
            assertEquals(template.replace("{input}", "sample"), LocalActs.expand(act(template = template), "sample"))
        }
    }

    @Test fun definitionsCannotConfigureURLsOrRecognizableCredentials() {
        listOf("https://example.invalid/path", "http://example.org", "file:///tmp/x", "content://local/path",
            "www.example.org", "example.com", "data:text/plain,example", "data:,example", "DATA:;base64,ZXhhbXBsZQ==", "javascript:alert(1)", "mailto:a@example.invalid",
            "Bearer unit-test-placeholder", "api_key=unit-test-placeholder",
            "password: unit-test-placeholder", "token=unit-test-placeholder", "token: unit-test-placeholder", "-----BEGIN RSA PRIVATE KEY-----").forEach {
            assertFails { LocalActs.validate(act(template = "$it {input}")) }
            assertFails { LocalActs.validate(act(name = it.take(48))) }
        }
        assertFails { LocalActs.validate(act(template = "token: {input} unit-test-placeholder")) }
        // Input is user-provided data, not a definition/configuration; preserved verbatim for preview.
        assertEquals("Explain:\nhttps://example.invalid", LocalActs.expand(act(), "https://example.invalid"))
    }

    @Test fun pinningIsStableAndDoesNotRenameOrLoseCreationOrder() {
        val a = act("A"); val b = act("B"); val c = act("C")
        assertEquals(listOf(b.id, a.id, c.id), LocalActs.ordered(listOf(a, b.withPin(true), c)).map { it.id })
        assertEquals(listOf(a.id, c.id, b.id), LocalActs.ordered(listOf(a.withPin(true), b, c.withPin(true))).map { it.id })
    }
}
