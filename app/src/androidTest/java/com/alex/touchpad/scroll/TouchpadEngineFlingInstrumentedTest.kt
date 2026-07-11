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
class TouchpadEngineFlingInstrumentedTest {

    @Test
    fun twoFingerScroll_releaseEmitsFling_whenInertiaEnabled() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsRepository(context)
        settings.setTwoFingerScrollGraceMs(0)
        settings.setFlingDurationMs(900)

        val cursorStore = CursorStateStore()
        cursorStore.updateBounds(1080, 2400)
        val engine = TouchpadEngine(cursorStore, settings)

        val actions = runTwoFingerScrollGesture(engine)
        val fling = actions.filterIsInstance<InputAction.ScrollFling>().firstOrNull()

        assertTrue("Expected fling action on release when inertia enabled", fling != null)
        assertTrue("Expected fling inertia to match setting", fling?.inertiaMs == 900)
        assertTrue(
            "Expected non-trivial fling velocity",
            fling != null && (kotlin.math.abs(fling.velocityYPerSecond) > 200f || kotlin.math.abs(fling.velocityXPerSecond) > 200f),
        )
    }

    @Test
    fun twoFingerScroll_releaseStopsImmediately_whenInertiaDisabled() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsRepository(context)
        settings.setTwoFingerScrollGraceMs(0)
        settings.setFlingDurationMs(0)

        val cursorStore = CursorStateStore()
        cursorStore.updateBounds(1080, 2400)
        val engine = TouchpadEngine(cursorStore, settings)

        val actions = runTwoFingerScrollGesture(engine)
        val hasFling = actions.any { it is InputAction.ScrollFling }
        val hasStopMarker = actions.any { it is InputAction.ScrollBy && it.dx == 0 && it.dy == 0 }

        assertFalse("Did not expect fling action when inertia is 0", hasFling)
        assertTrue("Expected explicit stop marker when inertia is 0", hasStopMarker)
    }

    private fun runTwoFingerScrollGesture(engine: TouchpadEngine): List<InputAction> {
        val allActions = mutableListOf<InputAction>()
        val downTime = 10_000L

        fun dispatchMulti(
            eventTime: Long,
            actionMasked: Int,
            actionIndex: Int,
            pointers: List<Pair<Float, Float>>,
        ) {
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
            try {
                allActions += engine.onTouchEvent(event)
            } finally {
                event.recycle()
            }
        }

        dispatchMulti(
            eventTime = downTime,
            actionMasked = MotionEvent.ACTION_DOWN,
            actionIndex = 0,
            pointers = listOf(300f to 1200f),
        )
        dispatchMulti(
            eventTime = downTime + 8L,
            actionMasked = MotionEvent.ACTION_POINTER_DOWN,
            actionIndex = 1,
            pointers = listOf(300f to 1200f, 700f to 1200f),
        )
        dispatchMulti(
            eventTime = downTime + 24L,
            actionMasked = MotionEvent.ACTION_MOVE,
            actionIndex = 0,
            pointers = listOf(300f to 1320f, 700f to 1320f),
        )
        dispatchMulti(
            eventTime = downTime + 40L,
            actionMasked = MotionEvent.ACTION_MOVE,
            actionIndex = 0,
            pointers = listOf(300f to 1470f, 700f to 1470f),
        )
        dispatchMulti(
            eventTime = downTime + 56L,
            actionMasked = MotionEvent.ACTION_MOVE,
            actionIndex = 0,
            pointers = listOf(300f to 1660f, 700f to 1660f),
        )
        dispatchMulti(
            eventTime = downTime + 72L,
            actionMasked = MotionEvent.ACTION_POINTER_UP,
            actionIndex = 1,
            pointers = listOf(300f to 1660f, 700f to 1660f),
        )
        dispatchMulti(
            eventTime = downTime + 88L,
            actionMasked = MotionEvent.ACTION_UP,
            actionIndex = 0,
            pointers = listOf(300f to 1660f),
        )

        return allActions
    }
}
