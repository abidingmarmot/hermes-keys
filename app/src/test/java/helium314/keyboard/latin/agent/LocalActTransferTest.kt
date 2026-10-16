// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LocalActTransferTest {
    private val act = LocalAct(UUID.randomUUID().toString(), "Portable", "Explain \"precisely\":\n{input}", true)
    private fun read(text: String) = LocalActTransfer.read(text.byteInputStream(Charsets.UTF_8))

    @Test fun compactRoundTripExportsOnlyVersionNameTemplateAndGeneratesNewUnpinnedIdentity() {
        val text = LocalActTransfer.encode(act)
        assertEquals(setOf("version", "name", "template"), org.json.JSONObject(text).keys().asSequence().toSet())
        assertFalse(text.contains(act.id))
        assertFalse(text.contains("pinned"))
        assertFalse(text.contains("\n")) // Embedded newline is escaped in compact JSON.
        val parsed = read(text)
        assertEquals(act.name, parsed.name)
        assertEquals(act.template, parsed.template)
        assertNotEquals(act.id, parsed.id)
        assertFalse(parsed.pinned)
        assertNotEquals(parsed.id, read(text).id)
    }

    @Test fun strictSchemaRejectsMalformedAmbiguousAndPrivilegedFields() {
        val valid = "\"version\":1,\"name\":\"New\",\"template\":\"Explain {input}\""
        val invalid = listOf("", "null", "[]", "{}", "{$valid} trailing", "{$valid} {}",
            "{$valid,}", "{/*comment*/$valid}", "{version:1,name:'New',template:'{input}'}",
            "{$valid,\"name\":\"Other\"}", "{$valid,\"version\":1}", "{$valid,\"id\":\"x\"}",
            "{$valid,\"pinned\":false}", "{$valid,\"token\":\"private-test-token\"}",
            "{$valid,\"endpoint\":\"https://example.invalid\"}", "{$valid,\"config\":{}}",
            "{$valid,\"acts\":[]}", "{$valid,\"unknown\":null}",
            "{\"version\":1,\"name\":2,\"template\":\"{input}\"}",
            "{\"version\":1,\"name\":\"New\",\"template\":null}")
        invalid.forEach { assertFails("Must reject: $it") { read(it) } }
    }

    @Test fun androidStrictReaderStillNormalizesIllegalStringSyntax() {
        // Literal wire syntax, never JSONObject serialization (which would repair these inputs).
        val normalized = listOf("\\q" to "q", "\\'" to "'", "\n" to "\n", "\t" to "\t",
            "\\\n" to "\n", "\\u+041" to "A", "\\u٠٠٤١" to "A")
        normalized.forEach { (wire, decoded) ->
            android.util.JsonReader(java.io.StringReader("[\"$wire\"]")).use { reader ->
                reader.isLenient = false
                reader.beginArray()
                assertEquals(decoded, reader.nextString())
                reader.endArray()
            }
        }
    }

    @Test fun illegalStringEscapesAreRejectedInKeysNamesAndTemplates() {
        listOf("\\q", "\\'", "\\v", "\\0", "\\x41", "\\\n", "\\\t", "\\\r").forEach { escape ->
            assertFails { read("""{"version":1,"name":"New$escape","template":"Explain {input}"}""") }
            assertFails { read("""{"version":1,"name":"New","template":"Explain $escape {input}"}""") }
        }
        // JsonReader would normalize this key to the required schema field.
        assertFails { read("""{"ver\sion":1,"name":"New","template":"Explain {input}"}""") }
    }

    @Test fun everyUnescapedJsonControlIsRejectedInsideStrings() {
        (0..0x1f).forEach { code ->
            val control = code.toChar()
            assertFails("Raw control $code in name") {
                read("""{"version":1,"name":"New${control}Act","template":"Explain {input}"}""")
            }
            assertFails("Raw control $code in template") {
                read("""{"version":1,"name":"New","template":"Explain $control {input}"}""")
            }
        }
    }

    @Test fun unicodeEscapesRequireExactlyFourAsciiHexDigits() {
        listOf("\\u", "\\u0", "\\u00", "\\u000", "\\u00G0", "\\u+041", "\\u-001", "\\u٠٠٤١",
            "\\uＦＦ２１").forEach { escape ->
            assertFails { read("""{"version":1,"name":"New$escape","template":"Explain {input}"}""") }
            assertFails { read("""{"version":1,"name":"New","template":"Explain $escape {input}"}""") }
        }
        assertFails { read("""{"\u+076ersion":1,"name":"New","template":"Explain {input}"}""") }
        listOf("", "\\", "\\u", "\\u0", "\\u00", "\\u000").forEach { suffix ->
            assertFails { read("""{"version":1,"name":"New","template":"Explain {input}$suffix""") }
        }
        assertFails { read("""{"version":1,"name":"New","template":"Explain {input}\""") }
    }

    @Test fun validStringEscapesAndOrdinaryUnicodeArePreservedExactly() {
        val parsed = read("""{"\u0076ersion":1,"name":"Café \uD83D\uDE80","template":"日本語 😀 𝄞 \/ \"quoted\" \\n\n\t\u000A\u0009 \u0041\u00e9 {input}"}""")
        assertEquals("Café 🚀", parsed.name)
        assertEquals("日本語 😀 𝄞 / \"quoted\" \\n\n\t\n\t Aé {input}", parsed.template)
    }

    @Test fun onlyIntegerVersionOneIsAccepted() {
        listOf("0", "2", "-1", "1.0", "1e0", "\"1\"", "true", "null").forEach { version ->
            assertFails { read("{\"version\":$version,\"name\":\"New\",\"template\":\"{input}\"}") }
        }
    }

    @Test fun existingValidationRejectsUrlsCredentialsControlsAndInvalidDefinitions() {
        listOf("https://example.invalid {input}", "content://document/1 {input}", "www.example.com {input}",
            "token=private-test-token {input}", "Bearer private-test-token {input}",
            "password: example {input}", "sk-abcdefgh1234 {input}", "No placeholder", "{input} {input}",
            "\u0000{input}", "x".repeat(LocalActs.MAX_TEMPLATE) + "{input}").forEach { template ->
            val text = org.json.JSONObject().put("version", 1).put("name", "New").put("template", template).toString()
            assertFails { read(text) }
            assertFails { LocalActTransfer.encode(LocalAct(act.id, "New", template)) }
        }
        listOf("", " Bad ", "x".repeat(49), "token=private-test-token").forEach { name ->
            assertFails { read(org.json.JSONObject().put("version", 1).put("name", name).put("template", "{input}").toString()) }
        }
    }

    @Test fun byteBoundAndUtf8AreEnforcedWithoutReadingPastLimitPlusOne() {
        val valid = LocalActTransfer.encode(act).toByteArray()
        val atLimit = valid + ByteArray(LocalActTransfer.MAX_BYTES - valid.size) { 32 }
        assertEquals(act.name, LocalActTransfer.read(ByteArrayInputStream(atLimit)).name)
        assertFails { LocalActTransfer.read(ByteArrayInputStream(atLimit + byteArrayOf(32))) }
        assertFails { LocalActTransfer.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
        var readCount = 0
        val unlimited = object : InputStream() {
            override fun read(): Int { readCount++; return 32 }
        }
        assertFails { LocalActTransfer.read(unlimited) }
        assertEquals(LocalActTransfer.MAX_BYTES + 1, readCount)
        assertFails { LocalActTransfer.read(object : InputStream() {
            override fun read(): Int = error("Provider failed")
        }) }
    }

    @Test fun addNeverOverwritesOrPinsAndRejectsDuplicateNamesTemplatesAndCapacity() {
        val existing = listOf(act)
        val candidate = LocalAct(UUID.randomUUID().toString(), "New", "Different {input}", true)
        val result = LocalActTransfer.addNew(existing, candidate)
        assertSame(act, result.first())
        assertNotEquals(candidate.id, result.last().id)
        assertFalse(result.last().pinned)
        assertFails { LocalActTransfer.addNew(existing, LocalAct(candidate.id, "PORTABLE", candidate.template)) }
        assertFails { LocalActTransfer.addNew(existing, LocalAct(candidate.id, "Other", act.template)) }
        assertFails { LocalActTransfer.addNew(existing, LocalAct(candidate.id, "Other", " ${act.template} ")) }
        val full = (1..LocalActs.MAX_COUNT).map { LocalAct(UUID.randomUUID().toString(), "Act $it", "Prompt $it {input}") }
        assertFails { LocalActTransfer.addNew(full, candidate) }
    }
}
