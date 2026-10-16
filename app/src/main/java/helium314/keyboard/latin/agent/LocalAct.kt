// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.util.Patterns
import java.util.Locale
import java.util.UUID

/** Data only. Never evaluated as code, opened as a URL, or used as client configuration. */
class LocalAct(val id: String, val name: String, val template: String, val pinned: Boolean = false) {
    fun withPin(value: Boolean) = LocalAct(id, name, template, value)
    // No generated toString: templates may contain private instructions.
}

object LocalActs {
    const val MAX_COUNT = 32
    const val MAX_NAME = 48
    const val MAX_TEMPLATE = 2000
    const val INPUT = "{input}"

    fun validate(act: LocalAct) {
        require(UUID.fromString(act.id).toString() == act.id)
        require(act.name.length in 1..MAX_NAME && act.name == act.name.trim())
        requireWellFormedUtf16(act.name)
        requireWellFormedUtf16(act.template)
        require(act.name.none { it.isISOControl() })
        require(act.template.length in 1..MAX_TEMPLATE && act.template.isNotBlank())
        require(act.template.none { it.isISOControl() && it != '\n' && it != '\t' })
        // Exactly one literal placeholder; no recursive expansion or expression language.
        require(act.template.indexOf(INPUT) >= 0 && act.template.indexOf(INPUT) == act.template.lastIndexOf(INPUT))
        val text = act.name + "\n" + act.template
        require(!Patterns.WEB_URL.matcher(text).find())
        // A prose "data:" label is not a data URI; require its metadata/payload separator.
        require(!Regex("[a-z][a-z0-9+.-]*://|\\b(?:https?|ftp|file|content|javascript|intent|mailto|tel|urn):|\\bdata:[^\\s,;]*[,;]|www\\.", RegexOption.IGNORE_CASE).containsMatchIn(text))
        require(!Regex("bearer\\s+\\S+|(?:api[_ -]?key|token|password|secret)\\s*[:=]\\s*\\S+|-----BEGIN .*PRIVATE KEY|\\b(?:sk-|ghp_)[a-z0-9_-]{8,}",
            // The literal input placeholder is not a saved credential value.
            RegexOption.IGNORE_CASE).containsMatchIn(text.replace(INPUT, "")))
    }

    private fun requireWellFormedUtf16(text: String) {
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            when {
                char.isHighSurrogate() -> require(index < text.length && text[index++].isLowSurrogate())
                char.isLowSurrogate() -> throw IllegalArgumentException("Unpaired surrogate")
            }
        }
    }

    fun validateAll(acts: List<LocalAct>) {
        require(acts.size <= MAX_COUNT)
        acts.forEach(::validate)
        require(acts.map { it.id }.distinct().size == acts.size)
        require(acts.map { it.name.lowercase(Locale.ROOT) }.distinct().size == acts.size)
    }

    /** Pinned first, then creation order within each group; rename never reorders. */
    fun ordered(acts: List<LocalAct>) = acts.filter { it.pinned } + acts.filterNot { it.pinned }

    fun expand(act: LocalAct, input: String): String {
        validate(act)
        require(input.isNotBlank() && input.length <= AgentProtocol.MAX_PROMPT)
        val result = act.template.replace(INPUT, input)
        require(result.length <= AgentProtocol.MAX_PROMPT)
        return result
    }

    /** Original, editable copies. Persisted on first successful load, never restored after deletion. */
    fun presets() = listOf(
        "Summarize" to "Summarize the following text concisely:\n\n{input}",
        "Rewrite" to "Rewrite the following text for clarity, preserving its meaning:\n\n{input}",
        "Translate" to "Translate the following text into English:\n\n{input}",
        "Lookup" to "Answer the following question. State uncertainty and do not claim to have browsed if you have not:\n\n{input}"
    ).mapIndexed { index, (name, template) ->
        LocalAct("00000000-0000-4000-8000-00000000000${index + 1}", name, template)
    }
}
