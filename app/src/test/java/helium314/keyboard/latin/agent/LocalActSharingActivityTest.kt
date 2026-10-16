// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.app.Activity
import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import helium314.keyboard.latin.R
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Exercise the native descriptor API while keeping streams controllable on the host. */
@Implements(ContentResolver::class)
class ActDocumentResolverShadow : ShadowContentResolver() {
    @Implementation
    fun openAssetFileDescriptor(uri: Uri, mode: String, signal: CancellationSignal): AssetFileDescriptor {
        check(mode == "r")
        signal.throwIfCanceled()
        val input = openInputStream(uri)
        val stream = mock(FileInputStream::class.java)
        `when`(stream.read(any<ByteArray>(), anyInt(), anyInt())).thenAnswer {
            input.read(it.getArgument(0), it.getArgument(1), it.getArgument(2))
        }
        doAnswer { input.close(); null }.`when`(stream).close()
        return mock(AssetFileDescriptor::class.java).also { `when`(it.createInputStream()).thenReturn(stream) }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [ActDocumentResolverShadow::class])
class LocalActSharingActivityTest {
    private val chosen = LocalActs.presets().first().withPin(true)
    private val imported = LocalAct("11111111-1111-4111-8111-111111111111", "From document", "New instruction:\n{input}", true)
    private var saved = listOf(chosen)
    private var updates = 0
    private var failStorage = false
    private lateinit var controller: ActivityController<LocalActsActivity>
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun texts(activity: Activity) = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    private fun click(activity: Activity, id: Int) = click(activity, activity.getString(id))
    private fun click(activity: Activity, text: String) {
        views(activity.window.decorView).filterIsInstance<Button>().single { it.text == text }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun anyChange(): (List<LocalAct>) -> List<LocalAct> = any<((List<LocalAct>) -> List<LocalAct>)>() ?: { it }

    private fun withActivity(test: (LocalActsActivity) -> Unit) {
        mockConstruction(AgentCredentials::class.java).use { credentials ->
            mockConstruction(AgentClient::class.java).use { clients ->
                mockConstruction(LocalActStore::class.java) { store, _ ->
                    `when`(store.load()).thenAnswer {
                        check(!failStorage) { "private-test-token must not appear in UI" }
                        saved
                    }
                    `when`(store.update(anyChange())).thenAnswer { call ->
                        updates++
                        check(!failStorage) { "private-test-token must not appear in UI" }
                        call.getArgument<(List<LocalAct>) -> List<LocalAct>>(0)(saved)
                            .also { LocalActs.validateAll(it); saved = it }
                    }
                }.use {
                    controller = Robolectric.buildActivity(LocalActsActivity::class.java).setup()
                    try { test(controller.get()) } finally { controller.pause().stop().destroy() }
                }
                assertTrue(credentials.constructed().isEmpty(), "Portability must not access credentials")
                assertTrue(clients.constructed().isEmpty(), "Portability must not construct a network client")
            }
        }
    }

    private fun pick(activity: LocalActsActivity, bytes: ByteArray, scheme: String = "content") {
        click(activity, R.string.acts_import)
        val picker = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, shadowOf(activity).nextStartedActivity.action)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.intent.action)
        assertTrue(picker.intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertTrue(picker.intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
        val uri = Uri.parse("$scheme://local/test-document")
        shadowOf(activity.contentResolver).registerInputStream(uri, bytes.inputStream())
        shadowOf(activity).receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(uri))
    }

    private fun awaitRead(activity: LocalActsActivity) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (texts(activity).contains(activity.getString(R.string.acts_import_reading)) && System.nanoTime() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertFalse(texts(activity).contains(activity.getString(R.string.acts_import_reading)), "Document read timed out")
    }

    @Test fun chosenPlaintextOnlyEntersNativeChooserAfterExplicitConfirmation() = withActivity { activity ->
        saved = listOf(chosen, LocalActs.presets()[1])
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        val payload = LocalActTransfer.encode(chosen)
        click(activity, activity.getString(R.string.acts_share_named, chosen.name))
        assertTrue(texts(activity).contains(payload))
        assertTrue(texts(activity).any { it.contains("may retain") })
        assertFalse(texts(activity).any { it.contains(LocalActs.presets()[1].template) })
        assertFalse(texts(activity).any { it.contains(chosen.id) })
        assertNull(shadowOf(activity).nextStartedActivity)
        val state = Bundle()
        activity.javaClass.getDeclaredMethod("onSaveInstanceState", Bundle::class.java).apply { isAccessible = true }.invoke(activity, state)
        assertTrue(state.isEmpty)
        click(activity, android.R.string.cancel)
        assertNull(shadowOf(activity).nextStartedActivity)
        click(activity, activity.getString(R.string.acts_share_named, chosen.name))
        click(activity, R.string.acts_share_confirm)
        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(payload, send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(setOf(Intent.EXTRA_TEXT), send.extras!!.keySet())
        assertNull(send.data)
        // Android migrates EXTRA_TEXT to ClipData when starting the chooser.
        assertEquals(1, send.clipData!!.itemCount)
        assertEquals(payload, send.clipData!!.getItemAt(0).text.toString())
        assertNull(send.clipData!!.getItemAt(0).uri)
        assertEquals(0, updates)
    }

    @Test fun previewCancelThenExplicitAddCreatesNewUnpinnedActAndDuplicateCannotOverwrite() = withActivity { activity ->
        val bytes = LocalActTransfer.encode(imported).toByteArray()
        pick(activity, bytes)
        awaitRead(activity)
        assertTrue(texts(activity).contains(imported.name))
        assertTrue(texts(activity).contains(imported.template))
        assertTrue(texts(activity).any { it.contains("Untrusted document") })
        assertEquals(0, updates)
        assertEquals(1, saved.size)
        assertNull(shadowOf(activity).nextStartedActivity)
        click(activity, android.R.string.cancel)
        assertEquals(0, updates)
        pick(activity, bytes)
        awaitRead(activity)
        click(activity, R.string.acts_import_confirm)
        assertEquals(2, saved.size)
        assertSame(chosen, saved.first())
        assertNotEquals(imported.id, saved.last().id)
        assertFalse(saved.last().pinned)
        val id = saved.last().id
        pick(activity, bytes)
        awaitRead(activity)
        click(activity, R.string.acts_import_confirm)
        assertTrue(texts(activity).contains(activity.getString(R.string.acts_transfer_error)))
        assertEquals(2, saved.size)
        assertEquals(id, saved.last().id)
    }

    @Test fun malformedOversizeUrlCredentialUnknownVersionAndPrivilegedDocumentsNeverWrite() = withActivity { activity ->
        val good = LocalActTransfer.encode(imported)
        val invalid = listOf("{", good.replace("\"version\":1", "\"version\":99"),
            good.dropLast(1) + ",\"token\":\"private-test-token\"}",
            good.replace("New instruction:", "https://example.invalid"),
            good.replace("New instruction:", "password=private-test-token"),
            good.dropLast(1) + ",\"name\":\"Duplicate field\"}", " ".repeat(LocalActTransfer.MAX_BYTES + 1))
        invalid.forEach {
            pick(activity, it.toByteArray())
            awaitRead(activity)
            assertTrue(texts(activity).contains(activity.getString(R.string.acts_transfer_error)))
            assertFalse(texts(activity).any { text -> text.contains("private-test-token") })
        }
        pick(activity, good.toByteArray(), "https")
        assertTrue(texts(activity).contains(activity.getString(R.string.acts_transfer_error)))
        assertEquals(0, updates)
        assertEquals(1, saved.size)
    }

    @Test fun latestStorageIsRecheckedOnAddAndShareAndErrorsNeverExposeExceptionText() = withActivity { activity ->
        pick(activity, LocalActTransfer.encode(imported).toByteArray())
        awaitRead(activity)
        // Simulate another settings instance saving the same template under a different name.
        saved = saved + LocalAct(imported.id, "Other name", imported.template)
        click(activity, R.string.acts_import_confirm)
        assertEquals(2, saved.size)
        assertTrue(texts(activity).contains(activity.getString(R.string.acts_transfer_error)))
        failStorage = true
        click(activity, R.string.acts_import_confirm)
        assertEquals(2, saved.size)
        assertFalse(texts(activity).any { it.contains("private-test-token") })
        failStorage = false
        click(activity, android.R.string.cancel)
        click(activity, activity.getString(R.string.acts_share_named, chosen.name))
        failStorage = true
        click(activity, R.string.acts_share_confirm)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(texts(activity).any { it.contains("private-test-token") })
        failStorage = false
        saved = saved.map { if (it.id == chosen.id) LocalAct(it.id, it.name, "Changed {input}") else it }
        click(activity, R.string.acts_share_confirm)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun blockedProviderCancelRotationAndRetriesStayBoundedAndNeverReopenPreview() =
        blockedProviderCancelOrTimeoutAndRotate(timeout = false)

    @Test fun blockedProviderTimeoutRotationAndRetryExplainRestartRecovery() =
        blockedProviderCancelOrTimeoutAndRotate(timeout = true)

    private fun blockedProviderCancelOrTimeoutAndRotate(timeout: Boolean) = withActivity { activity ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val stream = object : ByteArrayInputStream(LocalActTransfer.encode(imported).toByteArray()) {
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                started.countDown()
                // Malicious provider ignores both interruption and close.
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { }
                }
                return super.read(bytes, offset, count)
            }
            override fun close() { super.close(); closed.countDown() }
        }
        click(activity, R.string.acts_import)
        val picker = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, shadowOf(activity).nextStartedActivity.action)
        val uri = Uri.parse("content://local/slow-document")
        shadowOf(activity.contentResolver).registerInputStream(uri, stream)
        shadowOf(activity).receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(uri))
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val read = activity.javaClass.getDeclaredField("documentRead").apply { isAccessible = true }
                .get(activity) as LocalActDocumentReader.Read
            if (timeout) {
                shadowOf(Looper.getMainLooper()).idleFor(LocalActDocumentReader.DEADLINE_MS, TimeUnit.MILLISECONDS)
                assertTrue(texts(activity).contains(activity.getString(R.string.acts_import_timeout)))
                assertTrue(activity.getString(R.string.acts_import_timeout).contains("force-stop and reopen"))
            } else click(activity, android.R.string.cancel)
            synchronized(LocalActDocumentReader) { assertNull(read.callback, "Activity must be detached") }
            assertTrue(closed.await(5, TimeUnit.SECONDS), "Cancel must attempt stream close")
            repeat(5) {
                controller.recreate()
                val replacement = controller.get()
                click(replacement, R.string.acts_import)
                assertNull(shadowOf(replacement).nextStartedActivity)
                assertTrue(texts(replacement).contains(replacement.getString(R.string.acts_import_busy)))
                assertTrue(LocalActDocumentReader.isBusy())
                assertTrue(Thread.getAllStackTraces().keys.count {
                    it.isAlive && it.name.startsWith("local-act-")
                } <= 2)
            }
        } finally {
            release.countDown()
            awaitReaderIdle()
        }
        val replacement = controller.get()
        assertFalse(texts(replacement).contains(imported.template))
        assertFalse(texts(replacement).contains(replacement.getString(R.string.acts_import_confirm)))
        pick(replacement, LocalActTransfer.encode(imported).toByteArray())
        awaitRead(replacement)
        assertTrue(texts(replacement).contains(imported.template))
        assertEquals(0, updates)
        assertEquals(1, saved.size)
    }

    private fun awaitReaderIdle() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (LocalActDocumentReader.isBusy() && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse(LocalActDocumentReader.isBusy())
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun destroyDuringBlockedReadClosesStreamAndDropsPreview() = withActivity { activity ->
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val stream = object : ByteArrayInputStream(LocalActTransfer.encode(imported).toByteArray()) {
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                started.countDown()
                while (closed.count > 0) {
                    try { closed.await() } catch (_: InterruptedException) { }
                }
                return super.read(bytes, offset, count)
            }
            override fun close() { closed.countDown() }
        }
        click(activity, R.string.acts_import)
        val picker = shadowOf(activity).nextStartedActivityForResult
        val uri = Uri.parse("content://local/destroy-document")
        shadowOf(activity.contentResolver).registerInputStream(uri, stream)
        shadowOf(activity).receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(uri))
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val read = activity.javaClass.getDeclaredField("documentRead").apply { isAccessible = true }
            .get(activity) as LocalActDocumentReader.Read
        controller.recreate()
        synchronized(LocalActDocumentReader) { assertNull(read.callback, "Destroyed Activity must be detached") }
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        awaitReaderIdle()
        assertFalse(texts(controller.get()).contains(imported.template))
        assertFalse(texts(controller.get()).contains(activity.getString(R.string.acts_import_confirm)))
        assertEquals(0, updates)
    }

    @Test fun deadlineShowsExplicitErrorAndNeverPreviewsLateDocument() = withActivity { activity ->
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val stream = object : ByteArrayInputStream(LocalActTransfer.encode(imported).toByteArray()) {
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                started.countDown()
                while (closed.count > 0) {
                    try { closed.await() } catch (_: InterruptedException) { }
                }
                return super.read(bytes, offset, count)
            }
            override fun close() { closed.countDown() }
        }
        click(activity, R.string.acts_import)
        val picker = shadowOf(activity).nextStartedActivityForResult
        val uri = Uri.parse("content://local/timeout-document")
        shadowOf(activity.contentResolver).registerInputStream(uri, stream)
        shadowOf(activity).receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(uri))
        assertTrue(started.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idleFor(LocalActDocumentReader.DEADLINE_MS, TimeUnit.MILLISECONDS)
        assertTrue(texts(activity).contains(activity.getString(R.string.acts_import_timeout)))
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        awaitReaderIdle()
        assertFalse(texts(activity).contains(imported.template))
        assertEquals(0, updates)
    }

    @Test fun recreatedActivityDropsPendingPickerRatherThanRestoringPrivateState() = withActivity { activity ->
        click(activity, R.string.acts_import)
        val picker = shadowOf(activity).nextStartedActivityForResult
        val state = Bundle()
        activity.javaClass.getDeclaredMethod("onSaveInstanceState", Bundle::class.java)
            .apply { isAccessible = true }.invoke(activity, state)
        assertTrue(state.isEmpty)
        val replacement = Robolectric.buildActivity(LocalActsActivity::class.java).create(state).start().resume()
        try {
            val callback = replacement.get().javaClass.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
            callback.invoke(replacement.get(), picker.requestCode, Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://local/stale-document")))
            assertFalse(texts(replacement.get()).contains(replacement.get().getString(R.string.acts_import_reading)))
            assertEquals(0, updates)
        } finally { replacement.pause().stop().destroy() }
    }

    @Test fun canceledPickerAndUnsolicitedCallbackDoNotReadOrWrite() = withActivity { activity ->
        click(activity, R.string.acts_import)
        val picker = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(picker.intent, Activity.RESULT_CANCELED, null)
        val callback = activity.javaClass.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
        callback.invoke(activity, picker.requestCode, Activity.RESULT_OK, Intent().setData(Uri.parse("content://local/unsolicited")))
        assertFalse(texts(activity).contains(activity.getString(R.string.acts_import_reading)))
        click(activity, R.string.acts_import)
        val finishingPicker = shadowOf(activity).nextStartedActivityForResult
        activity.finish()
        shadowOf(activity).receiveResult(finishingPicker.intent, Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://local/late-document")))
        assertFalse(texts(activity).contains(activity.getString(R.string.acts_import_reading)))
        assertEquals(0, updates)
        assertEquals(1, saved.size)
    }
}
