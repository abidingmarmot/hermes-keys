// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.app.AlertDialog
import android.app.Application
import android.app.Dialog
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import helium314.keyboard.latin.LatinIME
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AgentControllerTest {
    private fun field(name: String) = AgentController::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun display(controller: AgentController, dialog: AlertDialog): Boolean =
        AgentController::class.java.getDeclaredMethod("display", AlertDialog::class.java)
            .apply { isAccessible = true }.invoke(controller, dialog) as Boolean

    @Test fun unattachedOrHiddenWindowDoesNotShowDialog() {
        val ime = mock(LatinIME::class.java)
        val controller = AgentController(ime)
        val dialog = mock(AlertDialog::class.java)
        assertFalse(display(controller, dialog))
        verify(dialog, never()).show()
        assertNull(field("dialog").get(controller))
    }

    @Test fun disappearingWindowTokenIsHandledAndWorkInvalidated() {
        val ime = mock(LatinIME::class.java)
        val imeDialog = mock(Dialog::class.java)
        val imeWindow = mock(Window::class.java)
        val decor = mock(View::class.java)
        `when`(ime.window).thenReturn(imeDialog)
        `when`(imeDialog.window).thenReturn(imeWindow)
        `when`(imeWindow.decorView).thenReturn(decor)
        `when`(decor.windowToken).thenReturn(Binder())
        `when`(ime.isInputViewShown).thenReturn(true)
        val dialog = mock(AlertDialog::class.java)
        val window = mock(Window::class.java)
        `when`(dialog.window).thenReturn(window)
        `when`(window.attributes).thenReturn(WindowManager.LayoutParams())
        doThrow(WindowManager.BadTokenException("test detached window")).`when`(dialog).show()
        val controller = AgentController(ime)
        assertFalse(display(controller, dialog))
        assertNull(field("dialog").get(controller))
        verify(dialog).dismiss()
    }

    @Test fun movedSelectionDisablesInsertButKeepsResultForCopy() {
        val controller = AgentController(mock(LatinIME::class.java))
        val dialog = mock(AlertDialog::class.java)
        val button = mock(Button::class.java)
        `when`(dialog.getButton(AlertDialog.BUTTON_POSITIVE)).thenReturn(button)
        field("dialog").set(controller, dialog)
        field("hasResult").setBoolean(controller, true)
        field("selectionStart").setInt(controller, 3)
        field("selectionEnd").setInt(controller, 3)
        controller.selectionChanged(4, 4)
        verify(button).setEnabled(false)
        verify(dialog, never()).dismiss()
        assertSame(dialog, field("dialog").get(controller))
    }

    @Test fun invalidateDetachesEvenCompletedWorkerBeforeQueuedCallbackDelivery() {
        val controller = AgentController(mock(LatinIME::class.java))
        val config = AgentCredentials.Config("https://example.invalid", "unit-test-placeholder", true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        var delivered = false
        mockConstruction(AgentClient::class.java) { client, _ ->
            `when`(client.complete(any<AgentCredentials.Config>() ?: config, anyString())).thenAnswer {
                worker.set(Thread.currentThread())
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                AgentProtocol.Result("discarded private response", "stop")
            }
        }.use { clients ->
            val run = requireNotNull(AgentEditorRequest.start(config, "private prompt") { _, _ -> delivered = true })
            field("requestRun").set(controller, run)
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                release.countDown()
                worker.get().join(5000)
                assertFalse(worker.get().isAlive)
                assertFalse(AgentEditorRequest.isBusy())
                synchronized(AgentEditorRequest) { assertNotNull(run.callback) }
                controller.invalidate()
                synchronized(AgentEditorRequest) { assertNull(run.callback) }
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(delivered)
                verify(clients.constructed().single(), never()).cancelAndDisconnect()
            } finally {
                release.countDown()
                worker.get()?.join(5000)
                controller.invalidate()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    @Test fun cancellationDropsPendingCallbacksAndSurvivesDetachedDialog() {
        val controller = AgentController(mock(LatinIME::class.java))
        val dialog = mock(AlertDialog::class.java)
        doThrow(IllegalArgumentException("test already detached")).`when`(dialog).dismiss()
        field("dialog").set(controller, dialog)
        val handler = field("handler").get(controller) as Handler
        var delivered = false
        handler.post { delivered = true }
        val generation = field("generation").getInt(controller)
        controller.invalidate()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(delivered)
        assertTrue(field("generation").getInt(controller) > generation)
        assertNull(field("dialog").get(controller))
    }
}
