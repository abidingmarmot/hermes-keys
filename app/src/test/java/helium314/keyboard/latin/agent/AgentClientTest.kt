// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import javax.net.ssl.HttpsURLConnection
import kotlin.test.assertEquals
import kotlin.test.assertFails

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
        for (status in listOf(301, 302, 307, 308, 401, 403, 429, 500)) {
            val connection = mock(HttpsURLConnection::class.java)
            `when`(connection.outputStream).thenReturn(ByteArrayOutputStream())
            `when`(connection.responseCode).thenReturn(status)
            var calls = 0
            val client = AgentClient { calls++; connection }
            assertFails { client.complete(config, "Prompt") }
            assertEquals(1, calls)
            verify(connection, never()).inputStream
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
