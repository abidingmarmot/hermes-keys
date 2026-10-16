// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.app.Application
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
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
