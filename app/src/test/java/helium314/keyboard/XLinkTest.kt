package helium314.keyboard

import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import helium314.keyboard.latin.common.Links
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.utils.getKnownDictionariesForLocale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class XLinkTest { // Without the X, SubtypeTests fail with ClassCastException. WTF?
    // Exhaustive, offline coverage of both record formats, including all experimental/legacy suffixes.
    @Test fun knownDictionaries() {
        val context = ApplicationProvider.getApplicationContext<App>()
        val records = context.assets.open("dictionaries_in_dict_repo.txt").bufferedReader().use {
            it.readLines().filter(String::isNotBlank)
        }
        assertTrue(records.isNotEmpty())
        val actualByLocale = records.map { dictionaryLocaleString(it).constructLocale() }.distinct()
            .associateWith { locale -> getKnownDictionariesForLocale(locale, context).map { it.second }.toSet() }
        val expectedUrls = records.map { record ->
            val path = if (record.startsWith("cldr,"))
                Links.DICTIONARY_EMOJI_CLDR_SUFFIX + "emoji_${dictionaryLocaleString(record)}"
            else Links.DICTIONARY_NORMAL_SUFFIX + record
            val expectedUrl = Links.DICTIONARY_URL + Links.DICTIONARY_DOWNLOAD_SUFFIX + path + ".dict"
            assertTrue(expectedUrl in actualByLocale.getValue(dictionaryLocaleString(record).constructLocale()), record)
            expectedUrl
        }.toSet()
        assertEquals(expectedUrls, actualByLocale.values.flatten().toSet())
    }

    // Representative live checks for each repository directory; exhaustive URL generation is above.
    // Do not randomly request hundreds of dictionary downloads and trigger Codeberg rate limits.
    @Test(timeout = 60_000) fun dictionaryLinksLive() {
        val base = Links.DICTIONARY_URL + Links.DICTIONARY_DOWNLOAD_SUFFIX
        listOf(
            base + Links.DICTIONARY_NORMAL_SUFFIX + "addon_ml_zz__experimental.dict",
            base + Links.DICTIONARY_EMOJI_CLDR_SUFFIX + "emoji_en.dict"
        ).forEach(::checkLink)
    }

    @Test(timeout = 120_000) fun readmeLinks() {
        checkHeliBoardLinks(File("../README.md"))
    }

    @Test(timeout = 120_000) fun layoutsLinks() {
        checkHeliBoardLinks(File("../layouts.md"))
    }

    @Test fun layoutsLinksInternal() {
        val file = File("../layouts.md")
        val internalLinkRegex = "app/src/\\b(?:[-a-zA-Z0-9@:%_\\+.~#?&\\/\\/=]*)".toRegex()
        val links = internalLinkRegex.findAll(file.readText()).map { it.value.substringBefore('#') }.toSet()
        assertTrue(links.isNotEmpty())
        links.forEach { assertTrue(File("../$it").isFile || File("../$it").isDirectory, it) }
    }

    @Test(timeout = 120_000) fun otherLinks() {
        listOf(Links.LICENSE, Links.LAYOUT_WIKI_URL, Links.WIKI_URL, Links.CUSTOM_LAYOUTS, Links.CUSTOM_COLORS).forEach {
            checkLink(it)
        }
    }

    private fun checkHeliBoardLinks(file: File) {
        // Retain the original live-check scope, but only consider explicit link destinations.
        val links = explicitHttpLinks(file.readText()).filter { it.contains("heli", true) }
        assertTrue(links.isNotEmpty(), "No HeliBoard links found in $file")
        links.forEach(::checkLink)
    }

    private fun checkLink(link: String) {
        val connection = URL(link).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val wiki = "wiki/" in link
            connection.requestMethod = if (wiki) "GET" else "HEAD"
            assertEquals(200, connection.responseCode, "HTTP check failed: $link")
            if (wiki) {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                assertFalse("Create new page" in text, "Missing wiki page: $link")
            }
        } finally {
            connection.disconnect()
        }
    }
}
