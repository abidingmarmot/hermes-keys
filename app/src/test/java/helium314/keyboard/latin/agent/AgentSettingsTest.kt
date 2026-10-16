// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.app.Application
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.accessibility.AccessibilityEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import helium314.keyboard.latin.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AgentSettingsTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun noDefaultsTokenMaskAndNoSavedCredentials() {
        val controller = Robolectric.buildActivity(AgentSettingsActivity::class.java).setup()
        val activity = controller.get()
        val views = descendants(activity.window.decorView)
        val fields = views.filterIsInstance<EditText>()
        assertEquals(2, fields.size)
        assertTrue(fields.all { it.text.isEmpty() && !it.isSaveEnabled })
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            fields.last().inputType and InputType.TYPE_MASK_VARIATION)
        assertFalse(views.filterIsInstance<CheckBox>().single().isChecked)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        val token = fields.last()
        token.setText("unit-test-placeholder")
        assertTrue(token.transformationMethod is PasswordTransformationMethod)
        assertFalse(token.transformationMethod.getTransformation(token.text, token).toString()
            .contains("unit-test-placeholder"))
        val node = token.createAccessibilityNodeInfo()
        assertTrue(node.isPassword)
        assertTrue(token.isImportantForAccessibility)
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        token.onInitializeAccessibilityEvent(event)
        assertTrue(event.isPassword)
        val state = Bundle()
        controller.saveInstanceState(state)
        assertTrue(state.isEmpty)
        controller.pause().stop().destroy()
    }

    @Test fun disableWithoutTokenCannotMoveCredentialsOrEnable() {
        val old = AgentCredentials.Config("https://example.invalid", "unit-test-placeholder", true)
        val disabled = AgentCredentials.configurationForSave(old.base, "", false, old)
        assertFalse(disabled.enabled)
        assertEquals(old.token, disabled.token)
        kotlin.test.assertFails { AgentCredentials.configurationForSave(old.base, "", true, old) }
        kotlin.test.assertFails { AgentCredentials.configurationForSave("https://other.invalid", "", false, old) }
        kotlin.test.assertFails { AgentCredentials.configurationForSave(old.base + "/", "", false, old) }
        kotlin.test.assertFails { AgentCredentials.configurationForSave(old.base, "", false, null) }
        val changed = AgentCredentials.configurationForSave("https://other.invalid", "new-test-placeholder", true, old)
        assertEquals("new-test-placeholder", changed.token)
    }

    @Test fun atomicBackupIsRecoveredEvenWhenBaseIsMissing() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val file = java.io.File(context.noBackupFilesDir, "hermes-agent.enc")
        val backup = java.io.File(file.path + ".bak")
        file.delete()
        // Deliberately invalid encrypted data: load must recover/read it, not return no config.
        backup.writeBytes(byteArrayOf(1, 2, 3))
        try {
            kotlin.test.assertFails { AgentCredentials(context).load() }
            assertTrue(file.exists())
            assertFalse(backup.exists())
        } finally { file.delete(); backup.delete() }
    }

    @Test fun settingsTitleUsesForkNameAcrossLocaleOverrides() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        for (tag in listOf("en-GB", "en-CA", "de", "bn", "sr", "zh-TW")) {
            val config = android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag(tag))
            }
            val localized = context.createConfigurationContext(config)
            val title = localized.getString(R.string.hermes_keys_settings, localized.getString(R.string.english_ime_name))
            assertTrue(title.startsWith("Hermes Keys"), title)
            assertFalse(title.contains("HeliBoard"))
        }
    }

    @Test fun cannotEnableWithoutExplicitConfiguration() {
        val controller = Robolectric.buildActivity(AgentSettingsActivity::class.java).setup()
        val activity = controller.get()
        val save = descendants(activity.window.decorView).filterIsInstance<Button>()
            .single { it.text == activity.getString(R.string.agent_save) }
        descendants(activity.window.decorView).filterIsInstance<CheckBox>().single().isChecked = true
        save.performClick()
        assertTrue(descendants(activity.window.decorView).filterIsInstance<android.widget.TextView>()
            .any { it.text == activity.getString(R.string.agent_config_error) })
        assertEquals(null, AgentCredentials(activity).load())
        controller.pause().stop().destroy()
    }
}
