// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.app.Application
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LocalActStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val directory get() = context.noBackupFilesDir
    private val file get() = File(directory, "hermes-local-acts.enc")
    private fun store() = LocalActStore(context) { key }
    private fun cleanup() { listOf("", ".bak", ".new").forEach { File(file.path + it).delete() } }
    @Before fun before() { cleanup(); shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true) }
    @After fun after() { cleanup() }

    @Test fun persistsEditsPinsRemovalsAndEmptyListAcrossInstancesWithoutPlaintext() {
        val defaults = store().load()
        val custom = LocalAct(UUID.randomUUID().toString(), "Private unique name", "Private unique instruction {input}", true)
        store().update { it + custom }
        assertEquals(defaults.map { it.id } + custom.id, store().load().map { it.id })
        store().update { all -> all.map { if (it.id == custom.id) LocalAct(it.id, "Changed", "New {input}", it.pinned) else it } }
        val loaded = store().load().last()
        assertEquals("Changed", loaded.name)
        assertEquals("New {input}", loaded.template)
        assertTrue(loaded.pinned)
        val raw = file.readBytes().toString(Charsets.UTF_8)
        assertFalse(raw.contains("Changed"))
        assertFalse(raw.contains("{input}"))
        assertFalse(raw.contains("Summarize"))
        assertTrue(file.canonicalPath.startsWith(directory.canonicalPath + File.separator))
        assertFalse(file.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        assertTrue(context.getSharedPreferences("local-acts", 0).all.isEmpty())
        store().update { it.filterNot { act -> act.id == custom.id } }
        assertEquals(defaults.map { it.id }, store().load().map { it.id })
        store().update { emptyList() }
        assertTrue(store().load().isEmpty()) // Deleted presets must not reappear.
        cleanup() // Models app-data removal: a fresh install has only the public presets.
        assertEquals(defaults.map { it.id }, store().load().map { it.id })
    }

    @Test fun invalidUpdateAndMissingKeyLeaveSavedDataUntouched() {
        val original = store().load().map { it.id }
        val bytes = file.readBytes()
        assertFails { store().update { it + it.first() } }
        assertContentEquals(bytes, file.readBytes())
        assertFails { LocalActStore(context) { error("test key unavailable") }.load() }
        assertContentEquals(bytes, file.readBytes())
        assertEquals(original, store().load().map { it.id })
    }

    @Test fun importsAreAtomicAndStorageFailureOrDuplicatesLeaveCiphertextUntouched() {
        val original = store().load()
        val bytes = file.readBytes()
        val candidate = LocalAct(UUID.randomUUID().toString(), "Imported", "New instruction {input}", true)
        assertFails { store().update { LocalActTransfer.addNew(it, original.first()) } }
        assertContentEquals(bytes, file.readBytes())
        val duplicateTemplate = LocalAct(candidate.id, candidate.name, original.first().template)
        assertFails { store().update { LocalActTransfer.addNew(it, duplicateTemplate) } }
        assertContentEquals(bytes, file.readBytes())
        assertFails { LocalActStore(context) { create ->
            if (create) error("Encryption unavailable") else key
        }.update { LocalActTransfer.addNew(it, candidate) } }
        assertContentEquals(bytes, file.readBytes())
        assertEquals(original.map { it.id }, store().load().map { it.id })
        store().update { LocalActTransfer.addNew(it, candidate) }
        val imported = store().load().last()
        assertNotEquals(candidate.id, imported.id)
        assertEquals(candidate.template, imported.template)
        assertFalse(imported.pinned)
    }

    private fun importDocument(name: String, template: String = "Imported {input}") =
        LocalActTransfer.read("""{"version":1,"name":"$name","template":"$template"}""".byteInputStream())

    @Test fun malformedJsonStringsNeverChangeExistingEncryptedActs() {
        val custom = LocalAct(UUID.randomUUID().toString(), "Existing pinned", "Keep exactly {input}", true)
        store().update { it + custom }
        val original = store().load()
        val bytes = file.readBytes()
        // Literal UTF-8 documents exercise the parser before the real AES-GCM/AtomicFile store.
        val malformed = listOf("\\q", "\\'", "\\v", "\\x41", "\\u+041", "\\u-001", "\\u٠٠٤١",
            "\\u00G0", "\\u123", "\\\n") + (0..0x1f).map { it.toChar().toString() }
        malformed.forEach { wire ->
            val document = """{"version":1,"name":"Imported","template":"New $wire {input}"}"""
                .toByteArray(Charsets.UTF_8)
            assertFails {
                val parsed = LocalActTransfer.read(document.inputStream())
                store().update { LocalActTransfer.addNew(it, parsed) }
            }
            assertContentEquals(bytes, file.readBytes())
            assertEquals(original.map { listOf(it.id, it.name, it.template, it.pinned) },
                store().load().map { listOf(it.id, it.name, it.template, it.pinned) })
        }
    }

    @Test fun escapedLoneHighAndLowSurrogatesNeverChangeEncryptedStore() {
        val original = store().load()
        val bytes = file.readBytes()
        listOf("\\uD800", "\\uDC00", "\\uD800x", "x\\uDC00", "\\uDC00\\uD800").forEach { escaped ->
            assertFails { store().update { LocalActTransfer.addNew(it, importDocument("Bad$escaped")) } }
            assertFails { store().update { LocalActTransfer.addNew(it, importDocument("Valid", "Bad$escaped {input}")) } }
            assertContentEquals(bytes, file.readBytes())
            assertEquals(original.map { it.id }, store().load().map { it.id })
        }
        // Shared validation also protects local CRUD and encoding, not just the import parser.
        listOf("\uD800", "\uDC00").forEach { surrogate ->
            val badName = LocalAct(UUID.randomUUID().toString(), "Bad$surrogate", "Valid {input}")
            val badTemplate = LocalAct(badName.id, "Valid", "Bad$surrogate {input}")
            listOf(badName, badTemplate).forEach { act ->
                assertFails { LocalActTransfer.encode(act) }
                assertFails { store().update { it + act } }
            }
            assertContentEquals(bytes, file.readBytes())
        }
    }

    @Test fun distinctRawSurrogateNamesCannotCollapseToDuplicateReplacementNamesOnDisk() {
        val original = store().load()
        val bytes = file.readBytes()
        // Before the fix both names passed validation, encoded as "Bad?", then broke load().
        listOf("\\uD800", "\\uD801").forEachIndexed { index, escaped ->
            assertFails {
                store().update { LocalActTransfer.addNew(it, importDocument("Bad$escaped", "Prompt $index {input}")) }
            }
            assertContentEquals(bytes, file.readBytes())
        }
        assertEquals(original.map { it.name }, store().load().map { it.name })
        store().update { LocalActTransfer.addNew(it, importDocument("Safe")) }
        assertEquals("Safe", store().load().last().name)
    }

    @Test fun supplementaryEmojiSurviveImportEncryptionReloadAndExportExactly() {
        val name = "Review 🚀"
        val template = "Explain 😀 then 𝄞: {input}"
        val parsed = importDocument("Review \\uD83D\\uDE80", "Explain \\uD83D\\uDE00 then \\uD834\\uDD1E: {input}")
        assertEquals(name, parsed.name)
        assertEquals(template, parsed.template)
        store().update { LocalActTransfer.addNew(it, parsed) }
        val loaded = store().load().last()
        assertEquals(name, loaded.name)
        assertEquals(template, loaded.template)
        val exported = LocalActTransfer.read(LocalActTransfer.encode(loaded).byteInputStream())
        assertEquals(name, exported.name)
        assertEquals(template, exported.template)
        val bytes = file.readBytes()
        assertFails { store().update { LocalActTransfer.addNew(it, importDocument(name, "Other {input}")) } }
        assertContentEquals(bytes, file.readBytes())
    }

    @Test fun legacyUtf8SupplementaryActsRemainReadableAndMalformedUtf8IsNotReplaced() {
        val legacy = LocalAct(UUID.randomUUID().toString(), "Legacy 🚀", "Explain 𝄞 and 😀: {input}", true)
        // Independently reproduce the previous JSONObject + toByteArray serializer, not write().
        val json = JSONObject().put("version", 1).put("acts", JSONArray().put(JSONObject()
            .put("id", legacy.id).put("name", legacy.name).put("template", legacy.template)
            .put("pinned", legacy.pinned))).toString()
        fun encrypt(plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return cipher.iv + cipher.doFinal(plain)
        }
        val bytes = encrypt(json.toByteArray(Charsets.UTF_8))
        file.writeBytes(bytes)
        val loaded = store().load().single()
        assertEquals(legacy.id, loaded.id)
        assertEquals(legacy.name, loaded.name)
        assertEquals(legacy.template, loaded.template)
        assertTrue(loaded.pinned)
        assertContentEquals(bytes, file.readBytes())
        assertFails { store().update { LocalActTransfer.addNew(it, importDocument("Bad\\uD800")) } }
        assertContentEquals(bytes, file.readBytes())
        assertEquals(legacy.template, store().load().single().template)
        // Authenticated but malformed UTF-8 must fail closed, rather than become replacement text.
        val malformed = encrypt(json.replace("Legacy 🚀", "Legacy ").toByteArray() + byteArrayOf(0xc3.toByte()))
        file.writeBytes(malformed)
        assertFails { store().load() }
        assertFails { store().update { emptyList() } }
        assertContentEquals(malformed, file.readBytes())
    }

    @Test fun authenticatedCorruptionAndOversizeFailClosedWithoutReseeding() {
        store().load()
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertFails { store().load() }
        assertContentEquals(bytes, file.readBytes())
        file.writeBytes(ByteArray(524289))
        assertFails { store().load() }
        assertEquals(524289L, file.length())
    }

    @Test fun atomicBackupIsRecoveredRatherThanTreatedAsFreshInstall() {
        store().update { emptyList() }
        val backup = File(file.path + ".bak")
        assertTrue(file.renameTo(backup))
        assertTrue(store().load().isEmpty())
        assertTrue(file.exists())
        assertFalse(backup.exists())
    }

    @Test fun lockedUserCannotReadWriteOrInitializeActs() {
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        assertFails { store().load() }
        assertFails { store().update { emptyList() } }
        assertFalse(file.exists())
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        store().load()
        val bytes = file.readBytes()
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        assertFails { store().update { emptyList() } }
        assertContentEquals(bytes, file.readBytes())
    }
}
