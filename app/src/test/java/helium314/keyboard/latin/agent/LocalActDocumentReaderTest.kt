// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.app.Application
import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class LocalActDocumentReaderTest {
    private val document = """{"version":1,"name":"Synthetic","template":"Explain {input}"}""".toByteArray()

    private fun CountDownLatch.awaitIgnoringInterrupts() {
        while (count > 0) try { await() } catch (_: InterruptedException) { }
    }

    private fun awaitIdle() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (LocalActDocumentReader.isBusy() && System.nanoTime() < deadline) Thread.sleep(5)
        assertFalse(LocalActDocumentReader.isBusy(), "Read/cleanup did not finish")
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertRetryRefused() {
        repeat(5) {
            assertTrue(LocalActDocumentReader.isBusy())
            assertNull(LocalActDocumentReader.start({ error("Must not open another provider") }) {
                fail("Refused read must not call back")
            })
        }
    }

    private fun assertRetrySucceeds() {
        var result: Result<LocalAct>? = null
        val read = assertNotNull(LocalActDocumentReader.start({ document.inputStream() }) { result = it })
        read.worker.join(5000)
        assertFalse(read.worker.isAlive)
        awaitIdle()
        assertEquals("Synthetic", assertNotNull(result).getOrThrow().name)
    }

    @Test fun canceledBlockedOpenKeepsSlotAndClosesLateStreamOffMain() {
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closedOnMain = AtomicBoolean(false)
        var callbacks = 0
        val read = assertNotNull(LocalActDocumentReader.start({
            opened.countDown()
            release.awaitIgnoringInterrupts() // Ignores both cancellation and interruption.
            object : ByteArrayInputStream(document) {
                override fun close() {
                    closedOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                    closed.countDown()
                }
            }
        }) { callbacks++ })
        try {
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            read.cancel()
            synchronized(LocalActDocumentReader) { assertNull(read.callback) }
            assertRetryRefused()
        } finally {
            release.countDown()
            read.cancel()
            awaitIdle()
        }
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertFalse(closedOnMain.get())
        assertEquals(0, callbacks)
        assertRetrySucceeds()
    }

    @Test fun blockedCancellationKeepsSlotEvenAfterWorkerFinishesAndDetachesCallback() {
        val reading = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val canceling = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        val canceledOnMain = AtomicBoolean(false)
        val closedOnMain = AtomicBoolean(false)
        var callbacks = 0
        val read = assertNotNull(LocalActDocumentReader.start({ signal ->
            signal.setOnCancelListener {
                canceledOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                canceling.countDown()
                releaseCancel.awaitIgnoringInterrupts()
            }
            object : ByteArrayInputStream(document) {
                override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                    reading.countDown()
                    releaseRead.awaitIgnoringInterrupts()
                    return super.read(bytes, offset, count)
                }
                override fun close() {
                    if (Looper.myLooper() == Looper.getMainLooper()) closedOnMain.set(true)
                }
            }
        }) { callbacks++ })
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            read.cancel() // Must return even though the cancellation listener blocks.
            assertTrue(canceling.await(5, TimeUnit.SECONDS))
            synchronized(LocalActDocumentReader) { assertNull(read.callback) }
            releaseRead.countDown()
            read.worker.join(5000)
            assertFalse(read.worker.isAlive)
            shadowOf(Looper.getMainLooper()).idle()
            assertRetryRefused() // The read is done, but cleanup is still outstanding.
            repeat(5) { read.cancel() }
            assertEquals(1, Thread.getAllStackTraces().keys.count {
                it.isAlive && it.name == "local-act-close"
            })
        } finally {
            releaseRead.countDown()
            releaseCancel.countDown()
            read.cancel()
            awaitIdle()
        }
        assertFalse(canceledOnMain.get())
        assertFalse(closedOnMain.get())
        assertEquals(0, callbacks)
        assertRetrySucceeds()
    }

    @Test fun timedOutBlockedCloseFailsClosedAndHoldsSlotUntilBothThreadsFinish() {
        val closing = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closedOnMain = AtomicBoolean(false)
        val results = mutableListOf<Result<LocalAct>>()
        val read = assertNotNull(LocalActDocumentReader.start({
            object : ByteArrayInputStream(document) {
                override fun close() {
                    if (Looper.myLooper() == Looper.getMainLooper()) closedOnMain.set(true)
                    closing.countDown()
                    releaseClose.awaitIgnoringInterrupts()
                }
            }
        }) { results.add(it) })
        try {
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idleFor(LocalActDocumentReader.DEADLINE_MS, TimeUnit.MILLISECONDS)
            assertIs<LocalActDocumentReader.Timeout>(results.single().exceptionOrNull())
            synchronized(LocalActDocumentReader) { assertNull(read.callback) }
            assertRetryRefused()
            repeat(5) { read.cancel() }
            assertTrue(Thread.getAllStackTraces().keys.count {
                it.isAlive && it.name.startsWith("local-act-")
            } <= 2)
        } finally {
            releaseClose.countDown()
            read.cancel()
            awaitIdle()
        }
        assertFalse(closedOnMain.get())
        assertEquals(1, results.size)
        assertRetrySucceeds()
    }

    @Test fun completionDeadlineRaceNotifiesOnceOutsideReaderLock() {
        var callbacks = 0
        val read = assertNotNull(LocalActDocumentReader.start({ document.inputStream() }) { result ->
            callbacks++
            assertIs<LocalActDocumentReader.Timeout>(result.exceptionOrNull())
            // A callback must not hold the reader monitor, including the readDone timeout path.
            val acquired = CountDownLatch(1)
            val contender = Thread { LocalActDocumentReader.isBusy(); acquired.countDown() }
            contender.start()
            assertTrue(acquired.await(5, TimeUnit.SECONDS), "Callback held reader lock")
            contender.join(5000)
        })
        try {
            read.worker.join(5000)
            assertFalse(read.worker.isAlive)
            // Worker finished, but main has not delivered its queued result yet.
            read.deadline.run()
            awaitIdle()
            assertEquals(1, callbacks)
        } finally {
            read.cancel()
            awaitIdle()
        }
        assertRetrySucceeds()
    }

    @Test fun cancelAfterCompletionBeforeMainDeliveryDropsQueuedResult() {
        var callbacks = 0
        val read = assertNotNull(LocalActDocumentReader.start({ document.inputStream() }) { callbacks++ })
        read.worker.join(5000)
        assertFalse(read.worker.isAlive)
        read.cancel()
        awaitIdle()
        assertEquals(0, callbacks)
        assertRetrySucceeds()
    }
}
