// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.app.AlertDialog
import android.app.Application
import android.graphics.Insets
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.ScrollView
import helium314.keyboard.latin.R
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LocalActsSettingsTest {
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun click(activity: LocalActsActivity, text: String) {
        views(activity.window.decorView).filterIsInstance<Button>().single { it.text == text }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun anyChange(): (List<LocalAct>) -> List<LocalAct> =
        any<((List<LocalAct>) -> List<LocalAct>)>() ?: { it }

    @Test fun accessibleCRUDPinAndConfirmedDeleteWithoutSavedPrivateDrafts() {
        var saved = emptyList<LocalAct>()
        mockConstruction(LocalActStore::class.java) { store, _ ->
            `when`(store.load()).thenAnswer { saved }
            `when`(store.update(anyChange())).thenAnswer { invocation ->
                val change = invocation.getArgument<(List<LocalAct>) -> List<LocalAct>>(0)
                change(saved).also { LocalActs.validateAll(it); saved = it }
            }
        }.use {
            val controller = Robolectric.buildActivity(LocalActsActivity::class.java).setup()
            val activity = controller.get()
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            click(activity, activity.getString(R.string.acts_add))
            val fields = views(activity.window.decorView).filterIsInstance<EditText>()
            assertEquals(2, fields.size)
            for (field in fields) {
                assertFalse(field.isSaveEnabled)
                assertTrue(field.isImportantForAccessibility)
                assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any { it.labelFor == field.id })
            }
            fields[0].setText("Private Act")
            fields[1].setText("Explain {input}")
            val state = Bundle()
            controller.saveInstanceState(state)
            assertTrue(state.isEmpty)
            click(activity, activity.getString(R.string.acts_save))
            assertEquals("Private Act", saved.single().name)
            val id = saved.single().id
            views(activity.window.decorView).filterIsInstance<CheckBox>().single().performClick()
            assertTrue(saved.single().pinned)
            click(activity, activity.getString(R.string.acts_edit_named, "Private Act"))
            views(activity.window.decorView).filterIsInstance<EditText>()[0].setText("Renamed")
            click(activity, activity.getString(R.string.acts_save))
            assertEquals(id, saved.single().id)
            assertEquals("Renamed", saved.single().name)
            click(activity, activity.getString(R.string.acts_delete_named, "Renamed"))
            var confirmation = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(texts(confirmation.window!!.decorView).any { it.contains("cannot be undone") && it.contains("Renamed") })
            confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, saved.size)
            click(activity, activity.getString(R.string.acts_delete_named, "Renamed"))
            confirmation = ShadowAlertDialog.getLatestAlertDialog()
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(saved.isEmpty())
            controller.pause().stop().destroy()
        }
    }

    @Test @Config(sdk = [35], qualifiers = "port")
    fun portraitInsetsKeepListAndEditorInsideSystemBars() = checkInsets(480, 900, Insets.of(0, 24, 0, 48))

    @Test @Config(sdk = [35], qualifiers = "land")
    fun landscapeInsetsKeepListAndEditorInsideSideBarsAndCutout() = checkInsets(900, 480, Insets.of(44, 24, 48, 0))

    private fun checkInsets(width: Int, height: Int, safe: Insets) {
        mockConstruction(LocalActStore::class.java) { store, _ ->
            `when`(store.load()).thenReturn(emptyList())
        }.use {
            val controller = Robolectric.buildActivity(LocalActsActivity::class.java).setup()
            val activity = controller.get()
            // Exercise both pages: each replaces the root and must install a fresh listener.
            repeat(2) { page ->
                if (page == 1) click(activity, activity.getString(R.string.acts_add))
                val scroll = views(activity.window.decorView).filterIsInstance<ScrollView>().single()
                val insets = WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(), safe)
                    .setInsets(WindowInsets.Type.displayCutout(), Insets.of(safe.left, 0, 0, 0)).build()
                repeat(2) { scroll.dispatchApplyWindowInsets(insets) }
                assertEquals(listOf(safe.left, safe.top, safe.right, safe.bottom),
                    listOf(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, scroll.paddingBottom))
                scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                scroll.layout(0, 0, width, height)
                val form = scroll.getChildAt(0) as ViewGroup
                assertTrue(form.left >= safe.left && form.right <= width - safe.right)
                assertTrue(form.top >= safe.top)
                scroll.fullScroll(View.FOCUS_DOWN)
                val last = form.getChildAt(form.childCount - 1)
                assertTrue(form.top + last.bottom - scroll.scrollY <= height - safe.bottom)
                if (page == 1) {
                    val keyboard = WindowInsets.Builder(insets)
                        .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 220)).build()
                    scroll.dispatchApplyWindowInsets(keyboard)
                    assertEquals(220, scroll.paddingBottom)
                    scroll.dispatchApplyWindowInsets(insets)
                    assertEquals(safe.bottom, scroll.paddingBottom)
                }
            }
            controller.pause().stop().destroy()
        }
    }

    private fun texts(view: View) = views(view).filterIsInstance<TextView>().map { it.text.toString() }

    @Test fun invalidDefinitionDoesNotSaveAndStorageFailureDoesNotSeedOrExposeData() {
        mockConstruction(LocalActStore::class.java) { store, _ ->
            `when`(store.load()).thenReturn(emptyList())
            `when`(store.update(anyChange())).thenAnswer { invocation ->
                val change = invocation.getArgument<(List<LocalAct>) -> List<LocalAct>>(0)
                change(emptyList()).also { LocalActs.validateAll(it) }
            }
        }.use {
            val controller = Robolectric.buildActivity(LocalActsActivity::class.java).setup()
            val activity = controller.get()
            click(activity, activity.getString(R.string.acts_add))
            val fields = views(activity.window.decorView).filterIsInstance<EditText>()
            fields[0].setText("Bad")
            fields[1].setText("Missing placeholder")
            click(activity, activity.getString(R.string.acts_save))
            assertTrue(texts(activity.window.decorView).contains(activity.getString(R.string.acts_validation_error)))
            click(activity, activity.getString(android.R.string.cancel))
            controller.pause().stop().destroy()
        }
        mockConstruction(LocalActStore::class.java) { store, _ ->
            `when`(store.load()).thenThrow(IllegalStateException("test unavailable"))
        }.use { stores ->
            val controller = Robolectric.buildActivity(LocalActsActivity::class.java).setup()
            val activity = controller.get()
            assertTrue(texts(activity.window.decorView).contains(activity.getString(R.string.acts_storage_error)))
            assertFalse(views(activity.window.decorView).filterIsInstance<Button>().any { it.text == activity.getString(R.string.acts_add) })
            verify(stores.constructed().single(), never()).update(anyChange())
            controller.pause().stop().destroy()
        }
    }
}
