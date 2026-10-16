// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URI

/** Fail closed before reading any editor text. No automatic keyboard events call the client. */
object AgentProtocol {
    const val MAX_PROMPT = 4000
    const val MAX_RESULT = 16000
    const val MAX_RESPONSE_BYTES = 65536

    fun allowed(editor: EditorInfo?, incognito: Boolean, connected: Boolean): Boolean {
        if (editor == null || incognito || !connected) return false
        if (editor.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return false
        val type = editor.inputType
        val variation = type and InputType.TYPE_MASK_VARIATION
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation !in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
            InputType.TYPE_CLASS_NUMBER -> variation != InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    // Base is the server root (optionally a reverse-proxy/profile prefix), NOT the /v1 URL.
    fun endpoint(base: String): URI {
        require(base.length in 1..2048)
        val uri = URI(base)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
            && uri.rawQuery == null && uri.rawFragment == null && uri.port in -1..65535
            && uri.port != 0 && !uri.rawPath.orEmpty().contains('%')
            && uri.path.split('/').none { it == "." || it == ".." })
        return URI(base.trimEnd('/') + "/v1/chat/completions")
    }

    fun validToken(token: String) = token.length in 1..4096 && token.all { it.code in 33..126 }

    fun request(prompt: String): String {
        require(prompt.isNotBlank() && prompt.length <= MAX_PROMPT)
        return JSONObject().put("model", "hermes-agent").put("stream", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt))).toString()
    }

    data class Result(val text: String, val finishReason: String)

    fun parse(body: String): Result {
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES)
        val root = JSONObject(body)
        require(absentMetadata(root.opt("error")))
        val choices = root.getJSONArray("choices")
        require(choices.length() == 1)
        val choice = choices.getJSONObject(0)
        val reason = choice.getString("finish_reason")
        // cancelled/error can contain diagnostic text, never treat that as a successful reply.
        // Truncated or tool-call-only responses are also not final answers in this first wave.
        require(reason == "stop")
        val message = choice.getJSONObject("message")
        require(message.getString("role") == "assistant" && absentMetadata(message.opt("tool_calls"))
            && absentMetadata(message.opt("function_call")))
        val text = message.get("content")
        require(text is String && text.isNotBlank() && text.length <= MAX_RESULT)
        return Result(text, reason)
    }

    private fun absentMetadata(value: Any?): Boolean = when (value) {
        null, JSONObject.NULL -> true
        is String -> value.isEmpty()
        is JSONArray -> value.length() == 0
        is JSONObject -> value.length() == 0
        else -> false
    }

    fun readBounded(input: InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_RESPONSE_BYTES)
            output.write(buffer, 0, count)
        }
        return output.toString("UTF-8")
    }
}
