package com.alex.touchpad.scroll

import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alex.touchpad.core.CursorStateStore
import com.alex.touchpad.input.InputAction
import com.alex.touchpad.input.TouchpadEngine
import com.alex.touchpad.settings.SettingsRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TouchpadEngineTwoFingerRoutingInstrumentedTest {

    @Test
    fun threeFingerMove_isNotHandledAsTwoFingerScroll() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsRepository(context)
        settings.setTwoFingerScrollGraceMs(0)
        settings.setFlingDurationMs(0)

        val cursorStore = CursorStateStore()
        cursorStore.updateBounds(1080, 2400)
        val engine = TouchpadEngine(cursorStore, settings)

        val downTime = 30_000L
        dispatch(
            engine = engine,
            downTime = downTime,
            eventTime = downTime,
            actionMasked = MotionEvent.ACTION_DOWN,
            actionIndex = 0,
            pointers = listOf(300f to 1200f),
        )
        dispatch(
            engine = engine,
            downTime = downTime,
            eventTime = downTime + 8L,
            actionMasked = MotionEvent.ACTION_POINTER_DOWN,
            actionIndex = 1,
            pointers = listOf(300f to 1200f, 700f to 1200f),
        )
        // Start two-finger scrolling.
        dispatch(
            engine = engine,
            downTime = downTime,
            eventTime = downTime + 24L,
            actionMasked = MotionEvent.ACTION_MOVE,
            actionIndex = 0,
            pointers = listOf(390f to 1200f, 790f to 1200f),
        )

        // Add third finger.
        dispatch(
            engine = engine,
            downTime = downTime,
            eventTime = downTime + 32L,
            actionMasked = MotionEvent.ACTION_POINTER_DOWN,
            actionIndex = 2,
            pointers = listOf(390f to 1200f, 790f to 1200f, 950f to 1250f),
        )

        val move3Actions = dispatch(
            engine = engine,
            downTime = downTime,
            eventTime = downTime + 48L,
            actionMasked = MotionEvent.ACTION_MOVE,
            actionIndex = 0,
            pointers = listOf(520f to 1200f, 920f to 1200f, 980f to 1250f),
        )

        val hasNonZeroScroll = move3Actions.any {
            it is InputAction.ScrollBy && (it.dx != 0 || it.dy != 0)
        }
        assertFalse(
            "Three-finger move must not emit non-zero two-finger scroll deltas: $move3Actions",
            hasNonZeroScroll,
        )
    }

    @Test
    fun reenteringFromThreeToTwo_reanchorsBeforeScrollEmission() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsRepository(context)
        settings.setTwoFingerScrollGraceMs(0)
        settings.setFlingDurationMs(0)

        val cursorStore = CursorStateStore()
        cursorStore.updateBounds(1080, 2400)
        val engine = TouchpadEngine(cursorStore, settings)

        val downTime = 40_000L
        dispatch(
            engine = engine,
            downTime = downTime,
            eventTime = downTime,
            actionMasked = MotionEvent.ACTION_DOWN,
            actionIndex = 0,
            pointers = listOf(300f to 1200f),
        )
        dispatch(
            engine = engine,
            downTime = downTime,
            eventTime = downTime + 8L,
            actionMasked = MotionEvent.ACTION_POINTER_DOWN,
            actionIndex = 1,
            pointers = listOf(300f to 1200f, 700f to 1200f),
        )
        dispatch(
            engine = engine,
            downTime = downTime,
            eventTime = downTime + 24L,
            actionMasked = MotionEvent.ACTION_MOVE,
            actionIndex = 0,
            pointers = listOf(390f to 1200f, 790f to 1200f),
        )
        dispatch(
            engine = engine,
            downTime = downTime,
            eventTime = downTime + 32L,
            actionMasked = MotionEvent.ACTION_POINTER_DOWN,
            actionIndex = 2,
            pointers = listOf(390f to 1200f, 790f to 1200f, 950f to 1250f),
        )

        // Drop back from 3 to 2.
        dispatch(
            engine = engine,
            downTime = downTime,
            eventTime = downTime + 40L,
            actionMasked = MotionEvent.ACTION_POINTER_UP,
            actionIndex = 2,
            pointers = listOf(390f to 1200f, 790f to 1200f, 950f to 1250f),
        )

        // First move after 3->2 transition should only re-anchor.
        val reentryActions = dispatch(
            engine = engine,
            downTime = downTime,
            eventTime = downTime + 56L,
            actionMasked = MotionEvent.ACTION_MOVE,
            actionIndex = 0,
            pointers = listOf(430f to 1200f, 830f to 1200f),
        )
        val reentryNonZeroScroll = reentryActions.any {
            it is InputAction.ScrollBy && (it.dx != 0 || it.dy != 0)
        }
        assertFalse(
            "Expected re-entry frame to re-anchor without non-zero scroll output: $reentryActions",
            reentryNonZeroScroll,
        )

        // Next deliberate movement may scroll again.
        val resumedActions = dispatch(
            engine = engine,
            downTime = downTime,
            eventTime = downTime + 72L,
            actionMasked = MotionEvent.ACTION_MOVE,
            actionIndex = 0,
            pointers = listOf(520f to 1200f, 920f to 1200f),
        )
        val resumedNonZeroScroll = resumedActions.any {
            it is InputAction.ScrollBy && (it.dx != 0 || it.dy != 0)
        }
        assertTrue("Expected scrolling to resume after re-anchor", resumedNonZeroScroll)
    }

    private fun dispatch(
        engine: TouchpadEngine,
        downTime: Long,
        eventTime: Long,
        actionMasked: Int,
        actionIndex: Int,
        pointers: List<Pair<Float, Float>>,
    ): List<InputAction> {
        val event = obtainEvent(downTime, eventTime, actionMasked, actionIndex, pointers)
        return try {
            engine.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun obtainEvent(
        downTime: Long,
        eventTime: Long,
        actionMasked: Int,
        actionIndex: Int,
        pointers: List<Pair<Float, Float>>,
    ): MotionEvent {
        val properties = Array(pointers.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(pointers.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = pointers[index].first
                y = pointers[index].second
                pressure = 1f
                size = 1f
            }
        }
        val action = when (actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP,
            -> actionMasked or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

            else -> actionMasked
        }
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            pointers.size,
            properties,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            0,
            0,
        )
    }
}
