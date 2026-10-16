// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.agent

import android.app.Application
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import helium314.keyboard.accessibility.AccessibilityUtils
import helium314.keyboard.accessibility.PopupKeysKeyboardAccessibilityDelegate
import org.robolectric.Shadows.shadowOf
import android.view.MotionEvent
import helium314.keyboard.keyboard.*
import helium314.keyboard.keyboard.internal.DrawingProxy
import helium314.keyboard.keyboard.internal.NonDistinctMultitouchHelper
import helium314.keyboard.keyboard.internal.PointerTrackerQueue
import helium314.keyboard.keyboard.internal.TimerProxy
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsValues
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

/** Real PointerTracker down/move/up/cancel paths with a mocked popup surface, not device E2E. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ActionPopupPointerTest {
    private class Harness(val enabled: Boolean) {
        val listener = mock(KeyboardActionListener::class.java)
        val drawing = mock(DrawingProxy::class.java)
        val panel = mock(PopupKeysPanel::class.java)
        val detector = mock(KeyDetector::class.java)
        val keyboard = mock(Keyboard::class.java)
        val settings = mock(SettingsValues::class.java)
        val tasks = mutableListOf<() -> Unit>()
        var previews = 0
        val selectedIndices = mutableListOf<Int>()
        val session = ActionPopupSession(listOf("Agent", "1 Rewrite"), { tasks.add(it) }) {
            previews++; selectedIndices.add(it)
        }
        val key = Key("Space", null, Constants.CODE_SPACE, null, null, 0, Key.BACKGROUND_TYPE_SPACEBAR,
            0, 100, 300, 50, 0, 0)
        val tracker: PointerTracker
        init {
            SettingsValues::class.java.getField("mSpaceActionPopup").apply { isAccessible = true }.setBoolean(settings, enabled)
            SettingsValues::class.java.getField("mSpaceForLangChange").apply { isAccessible = true }.setBoolean(settings, true)
            for (name in listOf("mSpaceSwipeHorizontal", "mSpaceSwipeVertical"))
                SettingsValues::class.java.getField(name).apply { isAccessible = true }.set(settings, KeyboardActionListener.SwipeAction.NONE)
            val context = RuntimeEnvironment.getApplication()
            val attrs = context.obtainStyledAttributes(null, R.styleable.MainKeyboardView,
                R.attr.mainKeyboardViewStyle, R.style.MainKeyboardView)
            PointerTracker.init(attrs, TimerProxy.Adapter(), drawing)
            attrs.recycle()
            PointerTracker.setGestureHandlingEnabledByUser(false)
            PointerTracker.setKeyboardActionListener(listener)
            `when`(detector.keyboard).thenReturn(keyboard)
            `when`(detector.detectHitKey(anyInt(), anyInt())).thenReturn(key)
            `when`(listener.prepareActionPopup()).thenReturn(session)
            `when`(listener.onCustomRequest(KeyboardActionListener.CustomAction.SHOW_INPUT_METHOD_PICKER)).thenReturn(true)
            `when`(drawing.showPopupKeysKeyboard(key, PointerTracker.getPointerTracker(0))).thenReturn(panel)
            tracker = PointerTracker.getPointerTracker(0)
            doAnswer {
                assertFalse(tracker.isInOperation) // Consumed before callback, not afterwards.
                assertNull(tracker.actionPopupSession)
                session.release(1)
                assertEquals(0, previews) // Must not show a dialog on the pointer stack.
                null
            }.`when`(panel).onUpEvent(anyInt(), anyInt(), anyInt(), anyLong())
        }
        fun event(action: Int, x: Float = 150f, y: Float = 125f) {
            val event = MotionEvent.obtain(1000, 1100, action, x, y, 0)
            tracker.processMotionEvent(event, detector)
            event.recycle()
        }
        fun finish() {
            PointerTracker.dismissAllPopupKeysPanels()
            PointerTracker.cancelAllPointerTrackers()
            val queue = PointerTracker::class.java.getDeclaredField("sPointerTrackerQueue").apply { isAccessible = true }
                .get(null) as PointerTrackerQueue
            queue.releaseAllPointers(1200)
        }
    }
    private fun run(enabled: Boolean = true, block: (Harness) -> Unit) {
        val h = Harness(enabled)
        mockStatic(Settings::class.java).use { static ->
            static.`when`<SettingsValues> { Settings.getValues() }.thenReturn(h.settings)
            try { block(h) } finally { h.finish() }
        }
    }

    private class AccessiblePopup(val h: Harness) {
        val view = mock(PopupKeysKeyboardView::class.java, CALLS_REAL_METHODS)
        val choices = listOf(
            Key("Agent", null, 0, null, null, 0, Key.BACKGROUND_TYPE_ACTION,
                0, 0, 120, 50, 0, 0),
            Key("1 Rewrite", null, 1, null, null, 0, Key.BACKGROUND_TYPE_ACTION,
                0, 50, 120, 50, 0, 0))
        val delegate: PopupKeysKeyboardAccessibilityDelegate
        init {
            val keyboard = mock(Keyboard::class.java)
            `when`(keyboard.sortedKeys).thenReturn(choices)
            doReturn(keyboard).`when`(view).keyboard
            doReturn(RuntimeEnvironment.getApplication()).`when`(view).context
            doReturn(120).`when`(view).width
            doReturn(100).`when`(view).height
            doReturn(0).`when`(view).paddingLeft
            doReturn(0).`when`(view).paddingTop
            doReturn(true).`when`(view).isShowingInParent
            doNothing().`when`(view).invalidateKey(any())
            doNothing().`when`(view).setAccessibilityDelegate(any())
            val detector = PopupKeysDetector(20f)
            fun set(name: String, value: Any) = PopupKeysKeyboardView::class.java
                .getDeclaredField(name).apply { isAccessible = true }.set(view, value)
            set("mKeyDetector", detector)
            val controller = mock(PopupKeysPanel.Controller::class.java)
            doAnswer { PointerTracker.dismissAllPopupKeysPanels(); null }
                .`when`(controller).onCancelPopupKeysPanel()
            set("mController", controller)
            set("mOriginX", 40)
            set("mOriginY", 10)
            set("mOnReleaseKey", { key: Key ->
                // Same owning-tracker guard as MainKeyboardView; do not relax it for accessibility.
                assertFalse(h.tracker.isInOperation)
                assertNull(h.tracker.actionPopupSession)
                h.session.release(key.code)
                assertEquals(0, h.previews)
                Unit
            })
            view.setActionPopupMode(h.tracker)
            delegate = PopupKeysKeyboardAccessibilityDelegate(view, detector)
            delegate.keyboard = keyboard
            `when`(h.drawing.showPopupKeysKeyboard(h.key, h.tracker)).thenReturn(view)
        }
        fun pressed(key: Key) = Key::class.java.getDeclaredField("mPressed")
            .apply { isAccessible = true }.getBoolean(key)
        fun hover(action: Int, x: Float = 60f, y: Float = 25f) {
            val event = MotionEvent.obtain(1000, 1150, action, x, y, 0)
            delegate.onHoverEvent(event)
            event.recycle()
        }
    }
    private fun accessible(block: (Harness, AccessiblePopup) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        AccessibilityUtils.init(context)
        val manager = shadowOf(context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
        manager.setEnabled(true)
        manager.setTouchExplorationEnabled(true)
        try {
            run { h ->
                val popup = AccessiblePopup(h)
                h.event(MotionEvent.ACTION_DOWN)
                h.tracker.onLongPressed()
                block(h, popup)
            }
        } finally {
            manager.setTouchExplorationEnabled(false)
            manager.setEnabled(false)
        }
    }
    @Test fun accessibilityLiftWithoutMoveAndAcrossRowsDefersExactlyOnePreview() {
        for (move in listOf(false, true)) accessible { h, popup ->
            popup.hover(MotionEvent.ACTION_HOVER_ENTER)
            assertTrue(popup.pressed(popup.choices[0]))
            if (move) popup.hover(MotionEvent.ACTION_HOVER_MOVE, y = 75f)
            popup.hover(MotionEvent.ACTION_HOVER_EXIT, y = if (move) 75f else 25f)
            assertFalse(h.tracker.isInOperation)
            assertNull(h.tracker.actionPopupSession)
            assertFalse(PointerTracker.hasActionPopupInteraction())
            assertEquals(0, h.previews)
            assertTrue(popup.choices.none { popup.pressed(it) })
            popup.hover(MotionEvent.ACTION_HOVER_EXIT)
            h.event(MotionEvent.ACTION_UP)
            h.tasks.forEach { it() }
            assertEquals(1, h.previews)
            assertEquals(listOf(if (move) 1 else 0), h.selectedIndices)
            verify(h.listener, never()).onCodeInput(anyInt(), anyInt(), anyInt(), anyBoolean())
        }
    }
    @Test fun accessibilityExplicitNodeClickSelectsWithoutSyntheticTouchHold() = accessible { h, popup ->
        val provider = popup.delegate.getAccessibilityNodeProvider(popup.view)
        assertTrue(provider.performAction(1, AccessibilityNodeInfoCompat.ACTION_CLICK, null))
        assertEquals(0, h.previews)
        h.tasks.forEach { it() }
        assertEquals(1, h.previews)
        assertEquals(listOf(1), h.selectedIndices)
        verify(h.listener, never()).onCodeInput(anyInt(), anyInt(), anyInt(), anyBoolean())
    }
    @Test fun accessibilityCancelPhantomAndDismissCannotBeRevivedByLiftOrClick() {
        for (cancel in 0..3) accessible { h, popup ->
            popup.hover(MotionEvent.ACTION_HOVER_ENTER)
            when (cancel) {
                0 -> h.event(MotionEvent.ACTION_CANCEL)
                1 -> h.tracker.onPhantomUpEvent(1200)
                2 -> {
                    val event = MotionEvent.obtain(1000, 1200, MotionEvent.ACTION_CANCEL, 60f, 25f, 0)
                    popup.view.onTouchEvent(event)
                    event.recycle()
                }
                else -> PointerTracker.dismissAllPopupKeysPanels()
            }
            popup.hover(MotionEvent.ACTION_HOVER_EXIT)
            popup.delegate.performClickOn(popup.choices[1])
            h.event(MotionEvent.ACTION_UP)
            h.tasks.forEach { it() }
            assertEquals(0, h.previews)
            verify(h.listener, never()).onCodeInput(anyInt(), anyInt(), anyInt(), anyBoolean())
        }
    }
    @Test fun accessibilityEdgeExitAndExitWithoutEnterCancelWithoutSelection() {
        for (enter in listOf(false, true)) accessible { h, popup ->
            if (enter) popup.hover(MotionEvent.ACTION_HOVER_ENTER)
            popup.hover(MotionEvent.ACTION_HOVER_EXIT, x = if (enter) 0f else 60f)
            popup.delegate.performClickOn(popup.choices[0])
            h.tasks.forEach { it() }
            assertEquals(0, h.previews)
        }
    }
    @Test fun accessibilityCannotSelectWithoutTouchExplorationOrWithWrongOwner() = accessible { h, popup ->
        val manager = shadowOf(RuntimeEnvironment.getApplication().getSystemService(
            Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
        manager.setTouchExplorationEnabled(false)
        popup.delegate.performClickOn(popup.choices[0])
        manager.setTouchExplorationEnabled(true)
        h.tracker.onAccessibilityActionPopupUp(h.panel, 60, 25, 1200)
        h.tasks.forEach { it() }
        assertEquals(0, h.previews)
        assertSame(h.session, h.tracker.actionPopupSession)
    }

    @Test fun ordinarySpaceBothSettings() {
        assertFalse(Defaults.PREF_SPACE_ACTION_POPUP)
        for (enabled in listOf(false, true)) run(enabled) { h ->
            h.event(MotionEvent.ACTION_DOWN); h.event(MotionEvent.ACTION_UP)
            verify(h.listener).onCodeInput(eq(Constants.CODE_SPACE), anyInt(), anyInt(), eq(false))
            verify(h.listener, never()).prepareActionPopup()
        }
    }
    @Test fun offStillOpensImePicker() = run(false) { h ->
        h.event(MotionEvent.ACTION_DOWN); h.tracker.onLongPressed(); h.event(MotionEvent.ACTION_UP)
        verify(h.listener).onCustomRequest(KeyboardActionListener.CustomAction.SHOW_INPUT_METHOD_PICKER)
        verify(h.listener, never()).prepareActionPopup()
        verify(h.listener, never()).onCodeInput(anyInt(), anyInt(), anyInt(), anyBoolean())
    }
    @Test fun heldReleaseUsesPopupAndDefersOnePreviewWithoutSpace() = run { h ->
        h.event(MotionEvent.ACTION_DOWN); h.tracker.onLongPressed()
        h.event(MotionEvent.ACTION_MOVE, 160f, 50f); h.event(MotionEvent.ACTION_UP, 160f, 50f)
        verify(h.panel).onMoveEvent(anyInt(), anyInt(), anyInt(), anyLong())
        verify(h.panel).onUpEvent(anyInt(), anyInt(), anyInt(), anyLong())
        verify(h.listener, never()).onCodeInput(anyInt(), anyInt(), anyInt(), anyBoolean())
        h.tasks.forEach { it() }; assertEquals(1, h.previews)
    }
    @Test fun preHoldSwipeRetainsExistingPath() = run { h ->
        SettingsValues::class.java.getField("mSpaceSwipeHorizontal").apply { isAccessible = true }.set(h.settings, KeyboardActionListener.SwipeAction.MOVE_CURSOR)
        h.event(MotionEvent.ACTION_DOWN); h.event(MotionEvent.ACTION_MOVE, 220f)
        h.tracker.onLongPressed(); h.event(MotionEvent.ACTION_UP, 220f)
        verify(h.listener).onHorizontalSpaceSwipe(anyInt())
        verify(h.listener).onEndSpaceSwipe()
        verify(h.listener, never()).prepareActionPopup()
        verify(h.listener, never()).onCodeInput(anyInt(), anyInt(), anyInt(), anyBoolean())
    }
    @Test fun actionCancelNeverSynthesizesSelectionOrSpace() = run { h ->
        h.event(MotionEvent.ACTION_DOWN); h.tracker.onLongPressed()
        h.event(MotionEvent.ACTION_CANCEL); h.event(MotionEvent.ACTION_UP)
        verify(h.panel, never()).onUpEvent(anyInt(), anyInt(), anyInt(), anyLong())
        verify(h.listener, never()).onCodeInput(anyInt(), anyInt(), anyInt(), anyBoolean())
        h.session.release(1); h.tasks.forEach { it() }; assertEquals(0, h.previews)
    }
    @Test fun slideOutDismissalConsumesRemainder() = run { h ->
        h.event(MotionEvent.ACTION_DOWN); h.tracker.onLongPressed()
        PointerTracker.dismissAllPopupKeysPanels() // PopupKeysKeyboardView's slide-out controller path.
        h.event(MotionEvent.ACTION_MOVE); h.event(MotionEvent.ACTION_UP)
        verify(h.panel, never()).onUpEvent(anyInt(), anyInt(), anyInt(), anyLong())
        verify(h.listener, never()).onCodeInput(anyInt(), anyInt(), anyInt(), anyBoolean())
        h.session.release(1); h.tasks.forEach { it() }; assertEquals(0, h.previews)
    }
    @Test fun phantomReleaseNeverChoosesAction() = run { h ->
        h.event(MotionEvent.ACTION_DOWN); h.tracker.onLongPressed(); h.tracker.onPhantomUpEvent(1200)
        h.event(MotionEvent.ACTION_UP)
        verify(h.panel, never()).onUpEvent(anyInt(), anyInt(), anyInt(), anyLong())
        verify(h.listener, never()).onCodeInput(anyInt(), anyInt(), anyInt(), anyBoolean())
        h.session.release(1); h.tasks.forEach { it() }; assertEquals(0, h.previews)
    }
    @Test fun unavailableHoldAndNextOrdinaryTapAreSafe() = run { h ->
        `when`(h.listener.prepareActionPopup()).thenReturn(null)
        h.event(MotionEvent.ACTION_DOWN); h.tracker.onLongPressed(); h.event(MotionEvent.ACTION_UP)
        verify(h.listener, never()).onCodeInput(anyInt(), anyInt(), anyInt(), anyBoolean())
        h.event(MotionEvent.ACTION_DOWN); h.event(MotionEvent.ACTION_UP)
        verify(h.listener).onCodeInput(eq(Constants.CODE_SPACE), anyInt(), anyInt(), eq(false))
    }
    @Test fun mainViewQuarantinesMultitouchIncludingNonDistinctSyntheticDowns() {
        for (nonDistinct in listOf(false, true)) run { h ->
            val view = mock(MainKeyboardView::class.java, CALLS_REAL_METHODS)
            doReturn(h.keyboard).`when`(view).keyboard
            MainKeyboardView::class.java.getDeclaredField("mKeyDetector").apply { isAccessible = true }.set(view, h.detector)
            if (nonDistinct) MainKeyboardView::class.java.getDeclaredField("mNonDistinctMultitouchHelper")
                .apply { isAccessible = true }.set(view, NonDistinctMultitouchHelper())
            h.event(MotionEvent.ACTION_DOWN); h.tracker.onLongPressed()
            val properties = Array(2) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(2) { i -> MotionEvent.PointerCoords().apply { x = 150f + i * 50; y = 125f } }
            val multi = MotionEvent.obtain(1000, 1150,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            assertTrue(view.onTouchEvent(multi)); multi.recycle()
            val up = MotionEvent.obtain(1000, 1200, MotionEvent.ACTION_UP, 150f, 125f, 0)
            assertTrue(view.onTouchEvent(up)); up.recycle()
            verify(h.panel, never()).onUpEvent(anyInt(), anyInt(), anyInt(), anyLong())
            verify(h.listener, never()).onCodeInput(anyInt(), anyInt(), anyInt(), anyBoolean())
            h.session.release(1); h.tasks.forEach { it() }; assertEquals(0, h.previews)
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val next = MotionEvent.obtain(1300, 1400, action, 150f, 125f, 0)
                view.onTouchEvent(next); next.recycle()
            }
            verify(h.listener).onCodeInput(eq(Constants.CODE_SPACE), anyInt(), anyInt(), eq(false))
        }
    }
    @Test fun secondPointerDisarmsAction() = run { h ->
        h.event(MotionEvent.ACTION_DOWN); h.tracker.onLongPressed(); PointerTracker.cancelActionPopups()
        h.event(MotionEvent.ACTION_UP)
        verify(h.panel, never()).onUpEvent(anyInt(), anyInt(), anyInt(), anyLong())
        verify(h.listener, never()).onCodeInput(anyInt(), anyInt(), anyInt(), anyBoolean())
        h.session.release(1); h.tasks.forEach { it() }; assertEquals(0, h.previews)
    }
}
