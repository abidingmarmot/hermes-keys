// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Single-Act, data-only interchange. No IDs, pins, configuration, URL resolution or execution. */
object LocalActTransfer {
    const val MAX_BYTES = 16 * 1024

    fun encode(act: LocalAct): String {
        LocalActs.validate(act)
        return JSONObject().put("version", 1).put("name", act.name).put("template", act.template).toString()
    }

    /** Caller owns the stream. Read at most limit + 1 bytes, even if a provider lies about size. */
    fun read(input: InputStream): LocalAct {
        val bytes = ByteArray(MAX_BYTES + 1)
        var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            check(count > 0)
            size += count
        }
        require(size in 1..MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, size)).toString()
        requireValidJsonStrings(text)
        var name: String? = null
        var template: String? = null
        val seen = mutableSetOf<String>()
        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName()
                require(seen.add(field))
                when (field) {
                    "version" -> {
                        require(reader.peek() == JsonToken.NUMBER)
                        require(reader.nextString() == "1")
                    }
                    "name", "template" -> {
                        require(reader.peek() == JsonToken.STRING)
                        val value = reader.nextString()
                        if (field == "name") name = value else template = value
                    }
                    else -> throw IllegalArgumentException("Unknown Act field")
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT)
        }
        require(seen == setOf("version", "name", "template"))
        return LocalAct(UUID.randomUUID().toString(), requireNotNull(name), requireNotNull(template))
            .also(LocalActs::validate)
    }

    /** Android's non-lenient JsonReader still normalizes illegal escapes and raw string controls.
     * Check their wire spelling before decoding; leave structure/schema checks to JsonReader above.
     */
    private fun requireValidJsonStrings(text: String) {
        var inString = false
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            if (!inString) {
                if (char == '"') inString = true
                continue
            }
            when (char) {
                '"' -> inString = false
                '\\' -> {
                    require(index < text.length)
                    when (text[index++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> {
                            require(index + 4 <= text.length)
                            repeat(4) {
                                val digit = text[index++]
                                require(digit in '0'..'9' || digit in 'a'..'f' || digit in 'A'..'F')
                            }
                        }
                        else -> throw IllegalArgumentException("Invalid JSON string escape")
                    }
                }
                else -> require(char >= ' ')
            }
        }
        require(!inString)
    }

    /** Run inside LocalActStore.update: recheck capacity/duplicates against the latest stored list. */
    fun addNew(existing: List<LocalAct>, imported: LocalAct): List<LocalAct> {
        LocalActs.validate(imported)
        require(existing.none { it.template.trim() == imported.template.trim() })
        val added = LocalAct(UUID.randomUUID().toString(), imported.name, imported.template, false)
        return (existing + added).also(LocalActs::validateAll)
    }
}
