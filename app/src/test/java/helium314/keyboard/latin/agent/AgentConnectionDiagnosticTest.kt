// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.app.AlertDialog
import android.app.Application
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import helium314.keyboard.latin.R
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AgentConnectionDiagnosticTest {
    private val saved = AgentCredentials.Config("https://example.invalid/profile", "saved-test-placeholder", true)
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun button(activity: AgentSettingsActivity, id: Int) = views(activity.window.decorView)
        .filterIsInstance<Button>().single { it.text == activity.getString(id) }
    private fun hasStatus(activity: AgentSettingsActivity, id: Int) = views(activity.window.decorView)
        .filterIsInstance<TextView>().any { it.text == activity.getString(id) }
    private fun confirmation() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    private fun withStore(load: () -> AgentCredentials.Config?, block: (ActivityController<AgentSettingsActivity>) -> Unit) {
        mockConstruction(AgentCredentials::class.java) { store, _ ->
            `when`(store.load()).thenAnswer { load() }
        }.use {
            val controller = Robolectric.buildActivity(AgentSettingsActivity::class.java).setup()
            try { block(controller) } finally {
                if (!controller.get().isDestroyed) controller.pause().stop().destroy()
            }
        }
    }
    private fun awaitWorker() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (Thread.getAllStackTraces().keys.any { it.name.startsWith("hermes-connection-") && it.isAlive }) {
            check(System.nanoTime() < deadline) { "Synthetic worker did not exit" }
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun noConfigurationDisabledOptInAndUnreadableStorageNeverSend() {
        val states: List<Pair<() -> AgentCredentials.Config?, Int>> = listOf(
            ({ null }) to R.string.agent_test_no_config,
            ({ AgentCredentials.Config(saved.base, saved.token, false) }) to R.string.agent_test_opt_in_needed,
            ({ throw IllegalStateException("private-storage-detail") }) to R.string.agent_test_storage_error
        )
        mockConstruction(AgentClient::class.java).use { clients ->
            for ((load, message) in states) withStore(load) { controller ->
                val activity = controller.get()
                val test = button(activity, R.string.agent_test_connection)
                assertFalse(test.isEnabled)
                assertTrue(hasStatus(activity, message))
                // Unsaved opt-in and even a programmatic click cannot bypass saved configuration.
                views(activity.window.decorView).filterIsInstance<CheckBox>().single().isChecked = true
                test.performClick()
                assertTrue(clients.constructed().isEmpty())
            }
        }
    }

    @Test fun preMarshmallowHasPreciseDisabledReason() {
        // Robolectric 4.16 has no API 22 runtime. Exercise the version gate in the API 28 sandbox;
        // this checks our branch, not platform compatibility on a real Android 5 device.
        val sdk = Build.VERSION.SDK_INT
        var loads = 0
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 22)
        try {
            mockConstruction(AgentClient::class.java).use { clients ->
                withStore({ loads++; error("Must not read credentials on API 22") }) { controller ->
                    val activity = controller.get()
                    val test = button(activity, R.string.agent_test_connection)
                    assertFalse(test.isEnabled)
                    assertTrue(hasStatus(activity, R.string.agent_requires_23))
                    test.performClick()
                    assertTrue(clients.constructed().isEmpty())
                    assertFalse(button(activity, R.string.agent_save).isEnabled)
                    assertFalse(button(activity, R.string.agent_forget).isEnabled)
                    assertEquals(0, loads) // A swallowed load exception must not make this test pass.
                }
            }
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)
        }
    }

    @Test fun openingSavingEditingAndCancellingConsentNeverSend() {
        mockConstruction(AgentClient::class.java).use { clients ->
            withStore({ saved }) { controller ->
                val activity = controller.get()
                assertTrue(button(activity, R.string.agent_test_connection).isEnabled)
                val fields = views(activity.window.decorView).filterIsInstance<EditText>()
                fields.last().setText("form-test-placeholder")
                button(activity, R.string.agent_save).performClick()
                fields.first().setText("https://unsaved.invalid")
                fields.last().setText("private-form-token")
                button(activity, R.string.agent_test_connection).performClick()
                val dialog = confirmation()
                assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                val message = dialog.findViewById<TextView>(android.R.id.message).text.toString()
                assertEquals(activity.getString(R.string.agent_test_confirm, AgentSettingsActivity.TEST_PROMPT), message)
                assertFalse(message.contains("unsaved.invalid"))
                assertFalse(message.contains("private-form-token"))
                assertTrue(clients.constructed().isEmpty())
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(button(activity, R.string.agent_test_connection).isEnabled)
                assertTrue(hasStatus(activity, R.string.agent_test_not_sent))
                assertFalse(hasStatus(activity, R.string.agent_test_ready))
                assertTrue(clients.constructed().isEmpty())
                button(activity, R.string.agent_test_connection).performClick()
                confirmation().cancel() // Back/outside dismissal also sends nothing.
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(hasStatus(activity, R.string.agent_test_not_sent))
                assertTrue(clients.constructed().isEmpty())
            }
        }
    }

    @Test fun confirmationUsesOnlySavedConfigurationAndFixedPromptAndDiscardsResponse() {
        mockConstruction(AgentClient::class.java) { client, _ ->
            `when`(client.complete(any<AgentCredentials.Config>() ?: saved, anyString())).thenAnswer {
                assertNotEquals(Looper.getMainLooper().thread, Thread.currentThread())
                AgentProtocol.Result("private-server-response", "stop")
            }
        }.use { clients ->
            withStore({ saved }) { controller ->
                val activity = controller.get()
                val fields = views(activity.window.decorView).filterIsInstance<EditText>()
                fields.first().setText("https://unsaved.invalid")
                fields.last().setText("private-form-token")
                views(activity.window.decorView).filterIsInstance<CheckBox>().single().isChecked = false
                val test = button(activity, R.string.agent_test_connection)
                test.performClick()
                val dialog = confirmation()
                test.performClick()
                assertSame(dialog, confirmation())
                val send = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                send.performClick()
                send.performClick()
                test.performClick()
                awaitWorker()
                assertEquals(1, clients.constructed().size)
                // Config has identity equality; raw arguments avoid Java matchers returning null in Kotlin.
                verify(clients.constructed().single(), times(1)).complete(saved, AgentSettingsActivity.TEST_PROMPT)
                assertTrue(hasStatus(activity, R.string.agent_test_success))
                val status = views(activity.window.decorView).filterIsInstance<TextView>()
                    .single { it.text == activity.getString(R.string.agent_test_success) }
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, status.accessibilityLiveRegion)
                assertFalse(views(activity.window.decorView).filterIsInstance<TextView>()
                    .any { it.text.contains("private-server-response") })
                assertEquals("private-form-token", fields.last().text.toString())
                assertTrue(test.isEnabled)
            }
        }
    }

    @Test fun savedConfigurationChangeOrUnreadabilityDuringConsentRefusesSend() {
        for (change in listOf("url", "token", "disabled", "forgotten", "unreadable")) {
            var changed = false
            mockConstruction(AgentClient::class.java).use { clients ->
                withStore({
                    if (!changed) saved else when (change) {
                        "unreadable" -> throw IllegalStateException("private-storage-detail")
                        "forgotten" -> null
                        "disabled" -> AgentCredentials.Config(saved.base, saved.token, false)
                        "token" -> AgentCredentials.Config(saved.base, "changed-test-placeholder", true)
                        else -> AgentCredentials.Config("https://changed.invalid", saved.token, true)
                    }
                }) { controller ->
                    button(controller.get(), R.string.agent_test_connection).performClick()
                    changed = true
                    confirmation().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertTrue(clients.constructed().isEmpty())
                    assertTrue(hasStatus(controller.get(), R.string.agent_test_config_changed))
                }
            }
        }
    }

    @Test fun cancellingConsentViaDisableForgetOrSaveNeverSendsAndInvalidatesOldDialog() {
        for (action in listOf("disable", "forget", "save")) {
            mockConstruction(AgentClient::class.java).use { clients ->
                withStore({ saved }) { controller ->
                    val activity = controller.get()
                    button(activity, R.string.agent_test_connection).performClick()
                    val dialog = confirmation()
                    when (action) {
                        "disable" -> views(activity.window.decorView).filterIsInstance<CheckBox>().single().isChecked = false
                        "forget" -> button(activity, R.string.agent_forget).performClick()
                        "save" -> button(activity, R.string.agent_save).performClick()
                    }
                    shadowOf(Looper.getMainLooper()).idle()
                    assertFalse(dialog.isShowing)
                    assertTrue(hasStatus(activity, R.string.agent_test_not_sent))
                    assertFalse(hasStatus(activity, R.string.agent_test_cancelled))
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertTrue(clients.constructed().isEmpty())
                }
            }
        }
    }

    @Test fun destroyDisableForgetAndSaveCancelAndIgnoreLateCompletion() {
        for (action in listOf("destroy", "close", "disable", "forget", "save")) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            mockConstruction(AgentClient::class.java) { client, _ ->
                `when`(client.complete(any<AgentCredentials.Config>() ?: saved, anyString())).thenAnswer {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    AgentProtocol.Result("late-private-response", "stop")
                }
            }.use { clients ->
                withStore({ saved }) { controller ->
                    val activity = controller.get()
                    button(activity, R.string.agent_test_connection).performClick()
                    confirmation().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    try {
                        assertTrue(entered.await(5, TimeUnit.SECONDS))
                        assertFalse(button(activity, R.string.agent_test_connection).isEnabled)
                        when (action) {
                            "destroy" -> controller.pause().stop().destroy()
                            "close" -> button(activity, R.string.agent_close).performClick()
                            "disable" -> views(activity.window.decorView).filterIsInstance<CheckBox>().single().isChecked = false
                            "forget" -> button(activity, R.string.agent_forget).performClick()
                            "save" -> button(activity, R.string.agent_save).performClick()
                        }
                        verify(clients.constructed().single(), timeout(5000)).cancelAndDisconnect()
                    } finally { release.countDown() }
                    awaitWorker()
                    assertFalse(hasStatus(activity, R.string.agent_test_success))
                    if (action !in listOf("destroy", "close")) {
                        assertTrue(hasStatus(activity, R.string.agent_test_cancelled))
                        assertFalse(hasStatus(activity, R.string.agent_test_ready))
                    }
                    assertFalse(confirmation().isShowing)
                    assertEquals(1, clients.constructed().size)
                }
            }
        }
    }

    @Test fun overallDeadlineCancelsOneRequestWithoutRetryOrLateSuccess() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        mockConstruction(AgentClient::class.java) { client, _ ->
            `when`(client.complete(any<AgentCredentials.Config>() ?: saved, anyString())).thenAnswer {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                AgentProtocol.Result("late-response", "stop")
            }
        }.use { clients ->
            withStore({ saved }) { controller ->
                val activity = controller.get()
                button(activity, R.string.agent_test_connection).performClick()
                confirmation().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(45))
                    verify(clients.constructed().single(), timeout(5000)).cancelAndDisconnect()
                    assertTrue(hasStatus(activity, R.string.agent_test_timeout))
                    assertFalse(button(activity, R.string.agent_test_connection).isEnabled)
                    assertTrue(hasStatus(activity, R.string.agent_test_busy))
                } finally { release.countDown() }
                awaitWorker()
                assertTrue(hasStatus(activity, R.string.agent_test_timeout))
                assertEquals(1, clients.constructed().size)
            }
        }
    }

    @Test fun destroyDuringConsentDismissesAndRequiresFreshConfirmationAfterRecreation() {
        mockConstruction(AgentClient::class.java).use { clients ->
            withStore({ saved }) { controller ->
                button(controller.get(), R.string.agent_test_connection).performClick()
                val dialog = confirmation()
                controller.pause().stop().destroy()
                assertFalse(dialog.isShowing)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                val recreated = Robolectric.buildActivity(AgentSettingsActivity::class.java).setup()
                try {
                    assertTrue(button(recreated.get(), R.string.agent_test_connection).isEnabled)
                    assertTrue(hasStatus(recreated.get(), R.string.agent_test_ready))
                } finally { recreated.pause().stop().destroy() }
                assertTrue(clients.constructed().isEmpty())
            }
        }
    }

    @Test fun blockedDisconnectHoldsSlotAfterRequestAcrossCancelTimeoutAndRecreation() {
        for (action in listOf("save", "timeout", "destroy")) boundedCancellation(action, requestFinishesFirst = true)
    }

    @Test fun blockedRequestHoldsSlotAfterDisconnectAcrossCancelTimeoutAndRecreation() {
        for (action in listOf("save", "timeout", "destroy")) boundedCancellation(action, requestFinishesFirst = false)
    }

    private fun boundedCancellation(action: String, requestFinishesFirst: Boolean) {
        val entered = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val disconnecting = CountDownLatch(1)
        val releaseDisconnect = CountDownLatch(1)
        mockConstruction(AgentClient::class.java) { client, _ ->
            `when`(client.complete(any<AgentCredentials.Config>() ?: saved, anyString())).thenAnswer {
                entered.countDown()
                check(releaseRequest.await(10, TimeUnit.SECONDS))
                AgentProtocol.Result("private-late-response", "stop")
            }
            doAnswer {
                assertNotEquals(Looper.getMainLooper().thread, Thread.currentThread())
                disconnecting.countDown()
                check(releaseDisconnect.await(10, TimeUnit.SECONDS))
                null
            }.`when`(client).cancelAndDisconnect()
        }.use { clients ->
            withStore({ saved }) { controller ->
                val activity = controller.get()
                button(activity, R.string.agent_test_connection).performClick()
                val oldConsent = confirmation()
                oldConsent.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                val run = ReflectionHelpers.getField<AgentConnectionDiagnostic.Run>(activity, "diagnosticRun")
                var recreated: ActivityController<AgentSettingsActivity>? = null
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    when (action) {
                        "save" -> button(activity, R.string.agent_save).performClick()
                        "timeout" -> shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(45))
                        else -> controller.pause().stop().destroy()
                    }
                    assertTrue(disconnecting.await(5, TimeUnit.SECONDS))
                    synchronized(AgentConnectionDiagnostic) { assertNull(run.callback) }
                    val worker = Thread.getAllStackTraces().keys.single {
                        it.isAlive && it.name == "hermes-connection-test"
                    }
                    val cleanup = Thread.getAllStackTraces().keys.single {
                        it.isAlive && it.name == "hermes-connection-close"
                    }
                    recreated = Robolectric.buildActivity(AgentSettingsActivity::class.java).setup()
                    val fresh = recreated.get()
                    repeat(5) { // Both request and disconnect are still blocked across recreation/taps.
                        run.cancel() // Repeated cancellation must not create another cleanup thread.
                        button(fresh, R.string.agent_test_connection).performClick()
                        button(fresh, R.string.agent_save).performClick()
                        oldConsent.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                        assertNull(AgentConnectionDiagnostic.start(saved) { fail("Busy run notified") })
                        assertEquals(1, clients.constructed().size)
                        assertEquals(2, Thread.getAllStackTraces().keys.count {
                            it.isAlive && it.name.startsWith("hermes-connection-")
                        })
                    }
                    if (requestFinishesFirst) {
                        releaseRequest.countDown()
                        worker.join(5000)
                        assertFalse(worker.isAlive)
                    } else {
                        releaseDisconnect.countDown()
                        cleanup.join(5000)
                        assertFalse(cleanup.isAlive)
                    }
                    shadowOf(Looper.getMainLooper()).idle()
                    assertFalse(hasStatus(activity, R.string.agent_test_success))
                    repeat(5) {
                        run.cancel()
                        assertTrue(AgentConnectionDiagnostic.isBusy())
                        assertFalse(button(fresh, R.string.agent_test_connection).isEnabled)
                        assertTrue(hasStatus(fresh, R.string.agent_test_busy))
                        button(fresh, R.string.agent_test_connection).performClick()
                        button(fresh, R.string.agent_save).performClick()
                        oldConsent.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                        assertNull(AgentConnectionDiagnostic.start(saved) { fail("Busy run notified") })
                    }
                    assertEquals(1, clients.constructed().size)
                    verify(clients.constructed().single(), times(1)).cancelAndDisconnect()
                    verify(clients.constructed().single(), times(1)).complete(saved, AgentSettingsActivity.TEST_PROMPT)
                    assertTrue(Thread.getAllStackTraces().keys.count {
                        it.isAlive && it.name.startsWith("hermes-connection-")
                    } <= 1) // Only the still-blocked half remains; no new workers/cleanup.
                    releaseRequest.countDown()
                    releaseDisconnect.countDown()
                    awaitWorker()
                    assertFalse(AgentConnectionDiagnostic.isBusy())
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
                    assertTrue(button(fresh, R.string.agent_test_connection).isEnabled)
                    assertFalse(hasStatus(fresh, R.string.agent_test_success))
                    button(fresh, R.string.agent_test_connection).performClick()
                    assertEquals(1, clients.constructed().size) // No automatic retry, fresh consent required.
                    confirmation().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    awaitWorker()
                    assertEquals(2, clients.constructed().size)
                    assertTrue(hasStatus(fresh, R.string.agent_test_success))
                } finally {
                    releaseRequest.countDown()
                    releaseDisconnect.countDown()
                    awaitWorker()
                    recreated?.pause()?.stop()?.destroy()
                }
            }
        }
    }

    @Test fun consentOpenedBeforeAnotherActivityStartsCannotBypassProcessSlot() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        mockConstruction(AgentClient::class.java) { client, _ ->
            `when`(client.complete(any<AgentCredentials.Config>() ?: saved, anyString())).thenAnswer {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                AgentProtocol.Result("discarded", "stop")
            }
        }.use { clients ->
            withStore({ saved }) { first ->
                button(first.get(), R.string.agent_test_connection).performClick()
                val firstConsent = confirmation()
                val second = Robolectric.buildActivity(AgentSettingsActivity::class.java).setup()
                try {
                    button(second.get(), R.string.agent_test_connection).performClick()
                    confirmation().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    firstConsent.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertEquals(1, clients.constructed().size)
                    assertTrue(hasStatus(first.get(), R.string.agent_test_busy))
                    assertFalse(button(first.get(), R.string.agent_test_connection).isEnabled)
                } finally {
                    release.countDown()
                    awaitWorker()
                    second.pause().stop().destroy()
                }
            }
        }
    }

    @Test fun failuresAreOnlySafeLocalCategories() {
        val failures = listOf(
            AgentClient.HttpStatusException(401) to R.string.agent_test_auth,
            AgentClient.HttpStatusException(403) to R.string.agent_test_auth,
            AgentClient.HttpStatusException(404) to R.string.agent_test_route,
            AgentClient.HttpStatusException(429) to R.string.agent_test_quota,
            AgentClient.HttpStatusException(500) to R.string.agent_test_server,
            AgentClient.HttpStatusException(599) to R.string.agent_test_server,
            AgentClient.HttpStatusException(302) to R.string.agent_test_http,
            AgentClient.HttpStatusException(400) to R.string.agent_test_http,
            SSLHandshakeException("private-host-token-body") to R.string.agent_test_tls,
            UnknownHostException("private-host-token-body") to R.string.agent_test_dns,
            SocketTimeoutException("private-host-token-body") to R.string.agent_test_timeout,
            IOException("private-host-token-body") to R.string.agent_test_network,
            IllegalArgumentException("private-host-token-body") to R.string.agent_test_unexpected,
            org.json.JSONException("private-host-token-body") to R.string.agent_test_unexpected
        )
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        for ((failure, expected) in failures) {
            val actual = AgentSettingsActivity.diagnosticMessage(failure)
            assertEquals(expected, actual)
            assertFalse(context.getString(actual).contains("private-host-token-body"))
        }
    }

    @Test fun failedRequestDisplaysSafeCategoryWithoutExceptionTextOrRetry() {
        mockConstruction(AgentClient::class.java) { client, _ ->
            `when`(client.complete(any<AgentCredentials.Config>() ?: saved, anyString()))
                .thenAnswer { throw UnknownHostException("private-host-token-body") }
        }.use { clients ->
            withStore({ saved }) { controller ->
                val activity = controller.get()
                button(activity, R.string.agent_test_connection).performClick()
                confirmation().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                awaitWorker()
                assertTrue(hasStatus(activity, R.string.agent_test_dns))
                assertFalse(views(activity.window.decorView).filterIsInstance<TextView>()
                    .any { it.text.contains("private-host-token-body") })
                verify(clients.constructed().single(), times(1)).complete(saved, AgentSettingsActivity.TEST_PROMPT)
                assertTrue(button(activity, R.string.agent_test_connection).isEnabled)
                button(activity, R.string.agent_test_connection).performClick()
                assertEquals(1, clients.constructed().size) // Another request requires fresh consent.
                confirmation().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                awaitWorker()
                assertEquals(2, clients.constructed().size)
                clients.constructed().forEach {
                    verify(it, times(1)).complete(saved, AgentSettingsActivity.TEST_PROMPT)
                }
                assertTrue(hasStatus(activity, R.string.agent_test_dns))
            }
        }
    }
}
