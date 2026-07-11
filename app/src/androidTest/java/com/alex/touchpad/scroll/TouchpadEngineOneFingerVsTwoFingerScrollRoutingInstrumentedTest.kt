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
class TouchpadEngineOneFingerVsTwoFingerScrollRoutingInstrumentedTest {

    @Test
    fun oneFingerMove_doesNotEmitScrollBy() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsRepository(context)
        settings.setOneFingerEdgeScrollEnabled(true)
        settings.setTwoFingerScrollGraceMs(0)
        settings.setFlingDurationMs(0)

        val cursorStore = CursorStateStore()
        cursorStore.updateBounds(1080, 2400)
        val engine = TouchpadEngine(cursorStore, settings)

        val actions = mutableListOf<InputAction>()
        val downTime = 110_000L
        actions += dispatchSingle(engine, downTime, downTime, MotionEvent.ACTION_DOWN, 2f, 1200f)
        actions += dispatchSingle(engine, downTime, downTime + 16L, MotionEvent.ACTION_MOVE, 2f, 1120f)
        actions += dispatchSingle(engine, downTime, downTime + 32L, MotionEvent.ACTION_MOVE, 2f, 1000f)
        actions += dispatchSingle(engine, downTime, downTime + 48L, MotionEvent.ACTION_UP, 2f, 1000f)

        val hasNonZeroScroll = actions.any { it is InputAction.ScrollBy && (it.dx != 0 || it.dy != 0) }
        assertFalse(
            "One-finger engine path must not emit scroll deltas; edge scrolling is executor-routed now: $actions",
            hasNonZeroScroll,
        )
    }

    @Test
    fun twoFingerMove_stillEmitsScrollBy() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsRepository(context)
        settings.setTwoFingerScrollGraceMs(0)
        settings.setFlingDurationMs(0)

        val cursorStore = CursorStateStore()
        cursorStore.updateBounds(1080, 2400)
        val engine = TouchpadEngine(cursorStore, settings)

        val actions = mutableListOf<InputAction>()
        val downTime = 120_000L
        actions += dispatchMulti(
            engine = engine,
            downTime = downTime,
            eventTime = downTime,
            actionMasked = MotionEvent.ACTION_DOWN,
            actionIndex = 0,
            pointers = listOf(300f to 1200f),
        )
        actions += dispatchMulti(
            engine = engine,
            downTime = downTime,
            eventTime = downTime + 8L,
            actionMasked = MotionEvent.ACTION_POINTER_DOWN,
            actionIndex = 1,
            pointers = listOf(300f to 1200f, 700f to 1200f),
        )
        actions += dispatchMulti(
            engine = engine,
            downTime = downTime,
            eventTime = downTime + 24L,
            actionMasked = MotionEvent.ACTION_MOVE,
            actionIndex = 0,
            pointers = listOf(300f to 1120f, 700f to 1120f),
        )

        val hasNonZeroScroll = actions.any { it is InputAction.ScrollBy && (it.dx != 0 || it.dy != 0) }
        assertTrue("Two-finger move should emit non-zero scroll deltas", hasNonZeroScroll)
    }

    private fun dispatchSingle(
        engine: TouchpadEngine,
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): List<InputAction> {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        return try {
            engine.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun dispatchMulti(
        engine: TouchpadEngine,
        downTime: Long,
        eventTime: Long,
        actionMasked: Int,
        actionIndex: Int,
        pointers: List<Pair<Float, Float>>,
    ): List<InputAction> {
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
        val event = MotionEvent.obtain(
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
        return try {
            engine.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
