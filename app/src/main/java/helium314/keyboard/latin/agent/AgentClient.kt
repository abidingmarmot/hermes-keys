// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import javax.net.ssl.HttpsURLConnection

/** One explicitly invoked, non-streaming request. Never follows redirects or retries. */
class AgentClient(private val openConnection: (java.net.URL) -> HttpsURLConnection = {
    it.openConnection() as HttpsURLConnection
}) {
    @Volatile private var connection: HttpsURLConnection? = null
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        // Disconnect may block on some TLS stacks; do not do it on the IME thread.
        val active = connection
        Thread { active?.disconnect() }.start()
    }

    fun complete(config: AgentCredentials.Config, prompt: String): AgentProtocol.Result {
        check(config.enabled && AgentProtocol.validToken(config.token) && !cancelled)
        val body = AgentProtocol.request(prompt).toByteArray(Charsets.UTF_8)
        val conn = openConnection(AgentProtocol.endpoint(config.base).toURL())
        connection = conn
        try {
            check(!cancelled)
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${config.token}")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            check(conn.responseCode in 200..299)
            check(!cancelled)
            return conn.inputStream.use { AgentProtocol.parse(AgentProtocol.readBounded(it)) }
        } finally {
            conn.disconnect()
            connection = null
        }
    }
}
