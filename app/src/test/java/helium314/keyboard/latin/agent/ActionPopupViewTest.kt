// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.app.Application
import android.view.MotionEvent
import helium314.keyboard.accessibility.KeyCodeDescriptionMapper
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.KeyDetector
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardId
import helium314.keyboard.keyboard.PopupKeysKeyboard
import helium314.keyboard.keyboard.PopupKeysDetector
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsValues
import helium314.keyboard.keyboard.PopupKeysKeyboardView
import helium314.keyboard.keyboard.PopupKeysPanel
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

/** Exercises real popup hit/release logic, without claiming a rendered IME/window gesture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ActionPopupViewTest {
    private class Harness(action: Boolean = true) {
        val view = mock(PopupKeysKeyboardView::class.java, CALLS_REAL_METHODS)
        val detector = mock(KeyDetector::class.java, CALLS_REAL_METHODS)
        val keyboard = mock(Keyboard::class.java)
        val controller = mock(PopupKeysPanel.Controller::class.java)
        val key = Key("1 Rewrite", null, 1, null, null, 0, Key.BACKGROUND_TYPE_ACTION,
            0, 0, 120, 50, 0, 0)
        var selections = 0
        val selectedCodes = mutableListOf<Int>()
        init {
            fun set(name: String, value: Any) = PopupKeysKeyboardView::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.set(view, value)
            set("mKeyDetector", detector)
            set("mController", controller)
            set("mOnReleaseKey", { key: Key -> selections++; selectedCodes.add(key.code); Unit })
            `when`(keyboard.sortedKeys).thenReturn(listOf(key))
            doReturn(keyboard).`when`(view).keyboard
            detector.setKeyboard(keyboard, 0f, 0f)
            doReturn(0).`when`(view).paddingLeft
            doReturn(0).`when`(view).paddingTop
            doReturn(false).`when`(view).isShowingInParent
            doNothing().`when`(view).invalidateKey(any())
            // Deliberately return nearest key even well outside: Action mode must reject it.
            `when`(detector.detectHitKey(anyInt(), anyInt())).thenReturn(key)
            doAnswer { view.dismissPopupKeysPanel(); null }.`when`(controller).onCancelPopupKeysPanel()
            if (action) view.setActionPopupMode(null)
        }
    }
    @Test fun rectangularChoicesFitNarrowKeyboardAndHaveExactMapping() {
        mockStatic(Settings::class.java).use { settings ->
            settings.`when`<SettingsValues> { Settings.getValues() }.thenReturn(mock(SettingsValues::class.java))
            settings.`when`<Settings> { Settings.getInstance() }.thenReturn(mock(Settings::class.java))
            for (width in listOf(180, 240, 320)) {
                val id = mock(KeyboardId::class.java)
                `when`(id.width).thenReturn(width)
                `when`(id.height).thenReturn(200)
                `when`(id.element).thenReturn(KeyboardElement.ALPHABET)
                val parent = mock(Keyboard::class.java)
                Keyboard::class.java.getField("mId").apply { isAccessible = true }.set(parent, id)
                Keyboard::class.java.getField("mPopupKeysTemplate").apply { isAccessible = true }.setInt(parent, R.xml.kbd_popup_keys_keyboard_template)
                Keyboard::class.java.getField("mMostCommonKeyHeight").apply { isAccessible = true }.setInt(parent, 50)
                val labels = listOf("Agent", "1 Rewrite", "2 Pipe | !", "3 Lookup")
                val popup = PopupKeysKeyboard.Builder(RuntimeEnvironment.getApplication(), Harness().key, parent, labels).build()
                assertTrue(popup.mOccupiedWidth <= width)
                assertEquals(4, popup.sortedKeys.size)
                val detector = PopupKeysDetector(20f)
                detector.setKeyboard(popup, 0f, 0f)
                for (key in popup.sortedKeys) {
                    assertEquals(labels[key.code], key.label)
                    assertSame(key, detector.detectHitKey(key.x + key.width / 2, key.y + key.height / 2))
                    assertTrue(key.width > 0 && key.height > 0)
                }
            }
        }
    }
    @Test fun themedCorrectionDoesNotCreateDeadBandsAcrossVisibleRows() {
        val h = Harness(false)
        val attrs = RuntimeEnvironment.getApplication().obtainStyledAttributes(
            R.style.PopupKeysKeyboardView_LXX_Base, R.styleable.KeyboardView)
        val correction = attrs.getDimension(R.styleable.KeyboardView_verticalCorrection, 0f)
        attrs.recycle()
        assertTrue(correction < 0f)
        val lower = Key("Agent", null, 0, null, null, 0, Key.BACKGROUND_TYPE_ACTION,
            0, 50, 120, 50, 0, 0)
        `when`(h.keyboard.sortedKeys).thenReturn(listOf(h.key, lower))
        val detector = PopupKeysDetector(20f)
        detector.setKeyboard(h.keyboard, 0f, correction)
        PopupKeysKeyboardView::class.java.getDeclaredField("mKeyDetector")
            .apply { isAccessible = true }.set(h.view, detector)
        assertEquals(correction.toInt(), detector.getTouchY(0))
        h.view.setActionPopupMode(null)
        assertEquals(0, detector.getTouchY(0))
        h.view.onDownEvent(60, 110, 0, 0)
        // Visit every pixel through both rows, including the old raw/corrected disagreement.
        for (y in 99 downTo 0) h.view.onMoveEvent(60, y, 0, (100 - y).toLong())
        for (y in 0..99) h.view.onMoveEvent(60, y, 0, (200 + y).toLong())
        verify(h.controller, never()).onCancelPopupKeysPanel()
        h.view.onUpEvent(60, 75, 0, 300)
        assertEquals(1, h.selections)
        assertEquals(listOf(lower.code), h.selectedCodes)

        // Ordinary popups retain both themed correction and slide allowance.
        val ordinary = Harness(false)
        ordinary.detector.setKeyboard(ordinary.keyboard, 0f, correction)
        ordinary.view.onDownEvent(60, 70, 0, 0)
        ordinary.view.onUpEvent(60, 70, 0, 1)
        assertEquals(correction.toInt(), ordinary.detector.getTouchY(0))
        assertEquals(1, ordinary.selections)
    }
    @Test fun mustSlideIntoRealChoiceAndOnlyMatchingPointerCanRelease() {
        val h = Harness()
        h.view.onDownEvent(50, 100, 7, 0)
        h.view.onUpEvent(50, 20, 7, 1)
        assertEquals(0, h.selections)
        h.view.onMoveEvent(50, 20, 8, 2)
        h.view.onUpEvent(50, 20, 8, 3)
        assertEquals(0, h.selections)
        h.view.onMoveEvent(50, 20, 7, 4)
        h.view.onUpEvent(50, 20, 7, 5)
        assertEquals(1, h.selections)
    }
    @Test fun slideOutCancelsRatherThanNearestChoice() {
        val h = Harness()
        h.view.onDownEvent(50, 100, 0, 0)
        h.view.onMoveEvent(50, 20, 0, 1)
        h.view.onMoveEvent(160, 20, 0, 2)
        verify(h.controller).onCancelPopupKeysPanel()
        h.view.onMoveEvent(50, 20, 0, 3)
        h.view.onUpEvent(50, 20, 0, 4)
        assertEquals(0, h.selections)
    }
    @Test fun releaseOutsideOrCancelNeverSelects() {
        val outside = Harness()
        outside.view.onDownEvent(50, 100, 0, 0)
        outside.view.onMoveEvent(50, 20, 0, 1)
        outside.view.onUpEvent(50, 70, 0, 2)
        assertEquals(0, outside.selections)
        val cancelled = Harness()
        cancelled.view.onDownEvent(50, 100, 0, 0)
        cancelled.view.onMoveEvent(50, 20, 0, 1)
        val event = MotionEvent.obtain(0, 2, MotionEvent.ACTION_CANCEL, 50f, 20f, 0)
        cancelled.view.onTouchEvent(event); event.recycle()
        cancelled.view.onUpEvent(50, 20, 0, 3)
        assertEquals(0, cancelled.selections)
        verify(cancelled.controller).onCancelPopupKeysPanel()
    }
    @Test fun ordinaryPopupSlideAllowanceIsUnchangedAndActionLabelIsAccessible() {
        val h = Harness(false)
        h.view.onDownEvent(50, 70, 0, 0)
        h.view.onUpEvent(50, 70, 0, 1)
        assertEquals(1, h.selections)
        assertEquals("1 Rewrite", KeyCodeDescriptionMapper.instance.getDescriptionForKey(
            RuntimeEnvironment.getApplication(), null, h.key, false))
        val name = "😀".repeat(11)
        assertEquals("😀".repeat(10) + "…", ActionPopupSession.label(name))
    }
}
