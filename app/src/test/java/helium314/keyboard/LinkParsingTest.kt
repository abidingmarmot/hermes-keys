// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import kotlin.test.Test
import kotlin.test.assertEquals

// Test-fixture parsing only; keep the two asset formats aligned with DictionaryUtils.kt.
internal fun dictionaryLocaleString(record: String): String =
    if (record.startsWith("cldr,")) record.substringAfter("cldr,")
    else record.substringAfter('_').substringBefore("__")

// Markdown inline destinations/autolinks and quoted HTML href/src attributes, not bare tokens.
private val httpDestination = """https?://(?:[^\s<>"'()]|\([^\s<>"'()]*\))+"""
private val explicitLinkRegex = Regex(
    """\]\(\s*<?($httpDestination)>?(?:\s+["'][^"']*["'])?\s*\)|<[a-z][^>]*?\b(?:href|src)\s*=\s*["']($httpDestination)["']|<($httpDestination)>""",
    RegexOption.IGNORE_CASE
)

internal fun explicitHttpLinks(text: String): Set<String> = explicitLinkRegex.findAll(text)
    .map { match -> match.groupValues.drop(1).first { it.isNotEmpty() } }.toSet()

class LinkParsingTest {
    @Test fun dictionaryRecordFormats() {
        mapOf(
            "addon_ml_zz__experimental" to "ml_zz",
            "cldr,zh_hant" to "zh_hant",
            "cldr,en" to "en",
            "main_en_us" to "en_us",
            "main_bn__legacy" to "bn",
            "main_hy_east__experimental" to "hy_east",
            "emoji_en" to "en",
            "symbols_fr__experimental" to "fr"
        ).forEach { (record, locale) -> assertEquals(locale, dictionaryLocaleString(record), record) }
    }

    @Test fun packageNamesAreNotLinks() {
        assertEquals(emptySet(), explicitHttpLinks("""
            `helium314.keyboard.latin.agent` and helium314.keyboard.latin.agent.*
            [local](app/src/main/java/helium314/keyboard/latin/agent) [anchor](#helium)
            [no scheme](helium314.keyboard.latin.agent)
        """))
    }

    @Test fun markdownDestinationsArePreserved() {
        assertEquals(setOf(
            "https://github.com/HeliBorg/HeliBoard/issues?q=",
            "http://example.org/path#section",
            "https://example.org/wiki/Topic_(detail)"
        ), explicitHttpLinks("""
            [issues](https://github.com/HeliBorg/HeliBoard/issues?q=).
            [titled](<http://example.org/path#section> "Title")
            [parentheses](https://example.org/wiki/Topic_(detail))
        """))
    }

    @Test fun htmlAndNestedBadgeDestinationsArePreserved() {
        assertEquals(setOf(
            "https://f-droid.org/packages/helium314.keyboard/",
            "https://example.org/badge.png",
            "https://codeberg.org/Helium314/aosp-dictionaries#dictionaries",
            "http://example.org/autolink"
        ), explicitHttpLinks("""
            [<img src="https://example.org/badge.png" alt="badge">](https://f-droid.org/packages/helium314.keyboard/)
            <a class="link" href='https://codeberg.org/Helium314/aosp-dictionaries#dictionaries'>here</a>
            <http://example.org/autolink>
        """))
    }
}
