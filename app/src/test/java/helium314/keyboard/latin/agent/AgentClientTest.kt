// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.app.Application
import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AgentClientTest {
    private val config = AgentCredentials.Config("https://example.invalid", "unit-test-placeholder", true)
    private val answer = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Final"}}]}"""

    @Test fun explicitPostUsesTlsAndDoesNotFollowRedirects() {
        val connection = mock(HttpsURLConnection::class.java)
        val output = ByteArrayOutputStream()
        `when`(connection.outputStream).thenReturn(output)
        `when`(connection.responseCode).thenReturn(200)
        `when`(connection.inputStream).thenReturn(answer.byteInputStream())
        val client = AgentClient {
            assertEquals("https://example.invalid/v1/chat/completions", it.toString())
            connection
        }
        assertEquals("Final", client.complete(config, "Explicit prompt").text)
        verify(connection).setInstanceFollowRedirects(false)
        verify(connection).setConnectTimeout(10000)
        verify(connection).setReadTimeout(30000)
        verify(connection).setRequestMethod("POST")
        verify(connection).setUseCaches(false)
        verify(connection).setRequestProperty("Authorization", "Bearer unit-test-placeholder")
        assertEquals(AgentProtocol.request("Explicit prompt"), output.toString("UTF-8"))
        verify(connection).disconnect()
    }

    @Test fun redirectsAuthAndServerErrorsNeverReadBodyOrRetry() {
        for (status in listOf(301, 302, 307, 308, 400, 401, 403, 404, 429, 500, 599)) {
            val connection = mock(HttpsURLConnection::class.java)
            `when`(connection.outputStream).thenReturn(ByteArrayOutputStream())
            `when`(connection.responseCode).thenReturn(status)
            var calls = 0
            val client = AgentClient { calls++; connection }
            val failure = assertFailsWith<AgentClient.HttpStatusException> { client.complete(config, "Prompt") }
            assertEquals(status, failure.status)
            assertEquals("HTTP request rejected", failure.message)
            assertEquals(1, calls)
            verify(connection, never()).inputStream
            verify(connection, never()).errorStream
            verify(connection, never()).responseMessage
            verify(connection).disconnect()
        }
    }

    @Test fun disabledInvalidOrCancelledRequestsNeverOpenConnection() {
        var calls = 0
        val client = AgentClient { calls++; error("Unexpected network") }
        assertFails { client.complete(AgentCredentials.Config(config.base, config.token, false), "Prompt") }
        assertFails { client.complete(AgentCredentials.Config("http://example.invalid", config.token, true), "Prompt") }
        assertFails { client.complete(AgentCredentials.Config(config.base, "bad\nheader", true), "Prompt") }
        assertFails { client.complete(config, "x".repeat(4001)) }
        client.cancel()
        assertFails { client.complete(config, "Prompt") }
        assertEquals(0, calls)
    }

    @Test fun publicCancelStillReturnsOffMainWhileRealDisconnectAndRequestAreBlocked() {
        val connection = mock(HttpsURLConnection::class.java)
        val requesting = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val disconnecting = CountDownLatch(1)
        val releaseDisconnect = CountDownLatch(1)
        val cleanup = AtomicReference<Thread>()
        val result = AtomicReference<Result<AgentProtocol.Result>>()
        `when`(connection.outputStream).thenReturn(ByteArrayOutputStream())
        `when`(connection.responseCode).thenAnswer {
            requesting.countDown()
            check(releaseRequest.await(5, TimeUnit.SECONDS))
            200
        }
        doAnswer {
            assertNotEquals(Looper.getMainLooper().thread, Thread.currentThread())
            cleanup.compareAndSet(null, Thread.currentThread())
            disconnecting.countDown()
            check(releaseDisconnect.await(5, TimeUnit.SECONDS))
            null
        }.`when`(connection).disconnect()
        val client = AgentClient { connection }
        val worker = Thread { result.set(runCatching { client.complete(config, "Prompt") }) }
        worker.start()
        try {
            assertTrue(requesting.await(5, TimeUnit.SECONDS))
            client.cancel() // The IME's existing API must not wait for disconnect.
            assertTrue(disconnecting.await(5, TimeUnit.SECONDS))
            assertTrue(worker.isAlive)
            assertTrue(cleanup.get().isAlive)
            releaseRequest.countDown()
            verify(connection, timeout(5000).times(2)).disconnect() // Request's finally also owns cleanup.
            assertTrue(worker.isAlive)
            verify(connection, never()).inputStream // Cancelled response must not be accepted.
        } finally {
            releaseRequest.countDown()
            releaseDisconnect.countDown()
            worker.join(5000)
            cleanup.get()?.join(5000)
        }
        assertFalse(worker.isAlive)
        assertFalse(cleanup.get().isAlive)
        assertTrue(result.get().isFailure)
    }

    @Test fun timeoutAndCancelledFinalAnswersNeverReturnSuccess() {
        val connection = mock(HttpsURLConnection::class.java)
        `when`(connection.outputStream).thenReturn(ByteArrayOutputStream())
        `when`(connection.responseCode).thenReturn(200)
        `when`(connection.inputStream).thenThrow(SocketTimeoutException())
        assertFails { AgentClient { connection }.complete(config, "Prompt") }
        verify(connection).disconnect()
        reset(connection)
        `when`(connection.outputStream).thenReturn(ByteArrayOutputStream())
        `when`(connection.responseCode).thenReturn(200)
        `when`(connection.inputStream).thenReturn(answer.replace("stop", "cancelled").byteInputStream())
        assertFails { AgentClient { connection }.complete(config, "Prompt") }
        verify(connection).disconnect()
    }
}
