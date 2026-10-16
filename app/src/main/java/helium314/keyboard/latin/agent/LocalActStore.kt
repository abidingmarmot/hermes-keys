// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.content.Context
import android.os.Build
import android.os.UserManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Same AES-GCM/Keystore + AtomicFile pattern as AgentCredentials, with a separate key/file.
 * The keyboard defaults to device-protected storage; encrypted Acts remain unavailable until unlock.
 * No preferences, settings exports, logs, or Android saved-state contain Acts.
 * Only explicit, confirmed single-Act sharing exposes name/template plaintext via an intent.
 */
class LocalActStore internal constructor(context: Context, private val keyProvider: ((Boolean) -> SecretKey)?) {
    constructor(context: Context) : this(context, null)
    private val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
    private val directory = context.noBackupFilesDir
    private val file = AtomicFile(File(directory, "hermes-local-acts.enc"))

    private fun requireUnlocked() {
        check(Build.VERSION.SDK_INT >= 23)
        check(Build.VERSION.SDK_INT < 24 || userManager.isUserUnlocked)
    }

    fun load(): List<LocalAct> = synchronized(LOCK) { read() }

    /** Read-modify-write is serialized across settings/IME instances; failed writes keep old data. */
    fun update(change: (List<LocalAct>) -> List<LocalAct>): List<LocalAct> = synchronized(LOCK) {
        val updated = change(read())
        write(updated)
        updated
    }

    private fun read(): List<LocalAct> {
        requireUnlocked()
        val input = try { file.openRead() } catch (e: FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw e
            return LocalActs.presets().also { write(it) }
        }
        val bytes = input.use {
            val buffer = ByteArray(MAX_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val n = it.read(buffer, size, buffer.size - size)
                if (n < 0) break
                size += n
            }
            require(size in 29..MAX_BYTES)
            buffer.copyOf(size)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val plain = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        val root = try {
            JSONObject(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plain)).toString())
        } finally { plain.fill(0) }
        require(root.getInt("version") == 1)
        val array = root.getJSONArray("acts")
        require(array.length() <= LocalActs.MAX_COUNT)
        return (0 until array.length()).map { index ->
            val act = array.getJSONObject(index)
            LocalAct(act.getString("id"), act.getString("name"), act.getString("template"), act.getBoolean("pinned"))
        }.also { LocalActs.validateAll(it) }
    }

    private fun write(acts: List<LocalAct>) {
        requireUnlocked()
        LocalActs.validateAll(acts)
        val array = JSONArray()
        acts.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)
            .put("template", it.template).put("pinned", it.pinned)) }
        // Never replace malformed UTF-16 with '?' and commit text different from the reviewed Act.
        // Encode before touching AtomicFile, independently of shared definition validation.
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(JSONObject().put("version", 1).put("acts", array).toString()))
        val plain = ByteArray(encoded.remaining())
        encoded.get(plain)
        if (encoded.hasArray()) encoded.array().fill(0)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        require(encrypted.size + cipher.iv.size <= MAX_BYTES)
        check(directory.isDirectory || directory.mkdirs())
        val output = file.startWrite()
        try {
            output.write(cipher.iv)
            output.write(encrypted)
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    private fun key(create: Boolean): SecretKey {
        // Keep the API guard local, including when a test key provider is injected.
        if (Build.VERSION.SDK_INT < 23) throw IllegalStateException("Keystore requires Android 6")
        keyProvider?.let { return it(create) }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    companion object {
        private val LOCK = Any()
        private const val ALIAS = "hermes-local-acts-v1"
        private const val MAX_BYTES = 524288
    }
}
