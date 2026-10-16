// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.app.Application
import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AgentProtocolTest {
    private fun editor(type: Int = InputType.TYPE_CLASS_TEXT) = EditorInfo().apply { inputType = type }
    private fun reply(reason: String = "stop", content: Any = "Final text") = JSONObject()
        .put("choices", org.json.JSONArray().put(JSONObject().put("finish_reason", reason)
            .put("message", JSONObject().put("role", "assistant").put("content", content)))).toString()

    @Test fun securityGates() {
        assertTrue(AgentProtocol.allowed(editor(), false, true))
        assertFalse(AgentProtocol.allowed(null, false, true))
        assertFalse(AgentProtocol.allowed(editor(), true, true))
        assertFalse(AgentProtocol.allowed(editor(), false, false))
        assertFalse(AgentProtocol.allowed(editor(InputType.TYPE_NULL), false, true))
        assertFalse(AgentProtocol.allowed(editor().apply {
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }, false, true))
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            assertFalse(AgentProtocol.allowed(editor(InputType.TYPE_CLASS_TEXT or variation), false, true))
        }
        assertFalse(AgentProtocol.allowed(editor(InputType.TYPE_CLASS_NUMBER or
            InputType.TYPE_NUMBER_VARIATION_PASSWORD), false, true))
        assertTrue(AgentProtocol.allowed(editor(InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS), false, true))
    }

    @Test fun httpsOnlyAndNoEmbeddedCredentials() {
        assertEquals("https://example.invalid/p/keyboard/v1/chat/completions",
            AgentProtocol.endpoint("https://example.invalid/p/keyboard/").toString())
        for (url in listOf("", "http://localhost:8642", "http://example.invalid", "file:///secret",
            "https://token@example.invalid", "https://example.invalid?token=no", "https://example.invalid#key",
            "https://example.invalid/../main", "https://example.invalid/%2e%2e/main", "https:///missing",
            "https://example.invalid:0", "https://example.invalid:65536")) {
            assertFails(url) { AgentProtocol.endpoint(url) }
        }
        assertFalse(AgentProtocol.validToken(""))
        assertFalse(AgentProtocol.validToken("test\r\nInjected: true"))
        assertFalse(AgentProtocol.validToken("has space"))
        assertFalse(AgentProtocol.validToken("x".repeat(4097)))
        assertTrue(AgentProtocol.validToken("unit-test-placeholder"))
    }

    @Test fun singleUserMessageOnlyAndPromptBounds() {
        val json = JSONObject(AgentProtocol.request("quoted \"text\"\n✓"))
        assertEquals("hermes-agent", json.getString("model"))
        assertFalse(json.getBoolean("stream"))
        val messages = json.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("quoted \"text\"\n✓", messages.getJSONObject(0).getString("content"))
        assertFails { AgentProtocol.request(" ") }
        assertFails { AgentProtocol.request("x".repeat(4001)) }
        AgentProtocol.request("x".repeat(4000))
    }

    @Test fun finalTextAndReason() {
        assertEquals(AgentProtocol.Result("Final text", "stop"), AgentProtocol.parse(reply()))
        for (reason in listOf("cancelled", "error", "length", "tool_calls", "", "unknown")) {
            assertFails(reason) { AgentProtocol.parse(reply(reason)) }
        }
    }

    @Test fun malformedOrNonFinalAnswersRejected() {
        for (body in listOf("not json", "{}", "{\"choices\":[]}",
            reply(content = JSONObject.NULL), reply(content = 42), reply(content = " "),
            reply(content = "x".repeat(16001)),
            JSONObject(reply()).put("error", "diagnostic").toString(),
            reply().replace("assistant", "user"),
            reply().replace("\"role\":", "\"tool_calls\":[{}],\"role\":"))) {
            assertFails { AgentProtocol.parse(body) }
        }
        assertEquals(16000, AgentProtocol.parse(reply(content = "x".repeat(16000))).text.length)
    }

    @Test fun emptyOptionalMetadataIsAbsentButActualCallsAndErrorsAreRejected() {
        for (empty in listOf(JSONObject.NULL, "", org.json.JSONArray(), JSONObject())) {
            val json = JSONObject(reply()).put("error", empty)
            json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("tool_calls", empty)
            assertEquals("Final text", AgentProtocol.parse(json.toString()).text)
        }
        for (value in listOf("error", false, 0, JSONObject().put("message", "failed"),
            org.json.JSONArray().put(JSONObject().put("function", "test")))) {
            assertFails { AgentProtocol.parse(JSONObject(reply()).put("error", value).toString()) }
            val json = JSONObject(reply())
            json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("tool_calls", value)
            assertFails { AgentProtocol.parse(json.toString()) }
        }
        val legacy = JSONObject(reply())
        legacy.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            .put("function_call", JSONObject().put("name", "test"))
        assertFails { AgentProtocol.parse(legacy.toString()) }
    }

    @Test fun boundedNetworkRead() {
        assertEquals("hello", AgentProtocol.readBounded("hello".byteInputStream()))
        assertEquals(65536, AgentProtocol.readBounded(ByteArray(65536) { 65 }.inputStream()).length)
        assertFails { AgentProtocol.readBounded(ByteArray(65537).inputStream()) }
        assertFails { AgentProtocol.parse("x".repeat(65537)) }
    }
}
