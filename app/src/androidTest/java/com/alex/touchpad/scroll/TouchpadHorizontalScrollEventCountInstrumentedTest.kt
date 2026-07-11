package com.alex.touchpad.scroll

import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alex.touchpad.adb.AdbCommand
import com.alex.touchpad.adb.AdbInjectionBackend
import com.alex.touchpad.core.CursorStateStore
import com.alex.touchpad.input.ActionRouter
import com.alex.touchpad.input.InputAction
import com.alex.touchpad.input.TouchpadEngine
import com.alex.touchpad.settings.SettingsRepository
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class TouchpadHorizontalScrollEventCountInstrumentedTest {

    @Test
    fun capturesHorizontalEventCountAndEstimatedHidSteps_perTwoFingerGesture() {
        val shortGesture = runHorizontalGestureWithOffsets(listOf(70f))
        val longGesture = runHorizontalGestureWithOffsets(
            listOf(30f, 60f, 90f, 120f, 150f, 180f),
        )

        assertTrue(
            "Expected short two-finger swipe to emit at least one horizontal scroll command; " +
                "shortCount=${shortGesture.horizontalNonZeroCount} details=${shortGesture.summary}",
            shortGesture.horizontalNonZeroCount >= 1,
        )
        assertTrue(
            "Expected long swipe to emit at least one horizontal scroll command; " +
                "longCount=${longGesture.horizontalNonZeroCount} longDetails=${longGesture.summary}",
            longGesture.horizontalNonZeroCount >= 1,
        )
        assertTrue(
            "Expected longer swipe to produce at least as much horizontal command magnitude as short swipe; " +
                "shortDxSum=${shortGesture.horizontalDxMagnitudeSum} longDxSum=${longGesture.horizontalDxMagnitudeSum} " +
                "shortDetails=${shortGesture.summary} longDetails=${longGesture.summary}",
            longGesture.horizontalDxMagnitudeSum >= shortGesture.horizontalDxMagnitudeSum,
        )
        assertTrue(
            "Expected longer swipe to produce at least as many estimated horizontal HID steps as short swipe; " +
                "shortSteps=${shortGesture.estimatedHorizontalHidStepCount} longSteps=${longGesture.estimatedHorizontalHidStepCount} " +
                "shortDetails=${shortGesture.summary} longDetails=${longGesture.summary}",
            longGesture.estimatedHorizontalHidStepCount >= shortGesture.estimatedHorizontalHidStepCount,
        )
    }

    private fun runHorizontalGestureWithOffsets(moveOffsets: List<Float>): ScrollCommandStats {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsRepository(context)
        settings.setTwoFingerScrollGraceMs(40)
        settings.setHorizontalScrollPixelsPerStep(40f)
        settings.setHorizontalScrollInverted(false)
        settings.setVerticalScrollPixelsPerStep(40f)
        settings.setFlingDurationMs(0)

        val cursorStore = CursorStateStore()
        cursorStore.updateBounds(1080, 2400)
        val engine = TouchpadEngine(cursorStore, settings)

        val actions = mutableListOf<InputAction>()
        val downTime = 21_000L
        val baseX1 = 340f
        val baseY1 = 1200f
        val baseX2 = 650f
        val baseY2 = 1200f

        fun dispatch(eventTime: Long, actionMasked: Int, actionIndex: Int, x1: Float, y1: Float, x2: Float? = null, y2: Float? = null) {
            val event = if (x2 == null || y2 == null) {
                obtainEvent(
                    downTime = downTime,
                    eventTime = eventTime,
                    actionMasked = actionMasked,
                    actionIndex = actionIndex,
                    pointers = listOf(x1 to y1),
                )
            } else {
                obtainEvent(
                    downTime = downTime,
                    eventTime = eventTime,
                    actionMasked = actionMasked,
                    actionIndex = actionIndex,
                    pointers = listOf(x1 to y1, x2 to y2),
                )
            }
            try {
                actions += engine.onTouchEvent(event)
            } finally {
                event.recycle()
            }
        }

        dispatch(
            eventTime = downTime,
            actionMasked = MotionEvent.ACTION_DOWN,
            actionIndex = 0,
            x1 = baseX1,
            y1 = baseY1,
        )
        dispatch(
            eventTime = downTime + 8L,
            actionMasked = MotionEvent.ACTION_POINTER_DOWN,
            actionIndex = 1,
            x1 = baseX1,
            y1 = baseY1,
            x2 = baseX2,
            y2 = baseY2,
        )

        var t = downTime + 70L
        moveOffsets.forEach { offset ->
            dispatch(
                eventTime = t,
                actionMasked = MotionEvent.ACTION_MOVE,
                actionIndex = 0,
                x1 = baseX1 + offset,
                y1 = baseY1,
                x2 = baseX2 + offset,
                y2 = baseY2,
            )
            t += 16L
        }

        val endOffset = moveOffsets.lastOrNull() ?: 0f
        dispatch(
            eventTime = t,
            actionMasked = MotionEvent.ACTION_POINTER_UP,
            actionIndex = 1,
            x1 = baseX1 + endOffset,
            y1 = baseY1,
            x2 = baseX2 + endOffset,
            y2 = baseY2,
        )
        dispatch(
            eventTime = t + 8L,
            actionMasked = MotionEvent.ACTION_UP,
            actionIndex = 0,
            x1 = baseX1 + endOffset,
            y1 = baseY1,
        )

        val backend = RecordingBackend()
        val router = ActionRouter(backend)
        actions.forEach(router::route)

        val scrollCommands = backend.snapshot().filterIsInstance<AdbCommand.Scroll>()
        val horizontalNonZero = scrollCommands.count { it.dx != 0 }
        val verticalNonZero = scrollCommands.count { it.dy != 0 }
        val stopMarkers = scrollCommands.count { it.dx == 0 && it.dy == 0 }
        val horizontalDxMagnitudeSum = scrollCommands.sumOf { abs(it.dx) }
        val estimatedHorizontalHidStepCount = estimateHorizontalHidStepCount(
            scrollCommands = scrollCommands,
            pixelsPerStep = settings.horizontalScrollPixelsPerStep.value,
        )
        val summary = scrollCommands.joinToString(
            prefix = "[",
            postfix = "]",
        ) { "(dx=${it.dx},dy=${it.dy},seq=${it.seq})" }

        return ScrollCommandStats(
            horizontalNonZeroCount = horizontalNonZero,
            verticalNonZeroCount = verticalNonZero,
            stopMarkerCount = stopMarkers,
            horizontalDxMagnitudeSum = horizontalDxMagnitudeSum,
            estimatedHorizontalHidStepCount = estimatedHorizontalHidStepCount,
            summary = summary,
        )
    }

    private fun estimateHorizontalHidStepCount(
        scrollCommands: List<AdbCommand.Scroll>,
        pixelsPerStep: Float,
    ): Int {
        val threshold = (pixelsPerStep * SCROLL_COMMAND_UNITS_PER_PIXEL).coerceAtLeast(MIN_SCROLL_UNITS_PER_STEP)
        var accumulator = 0f
        var hidStepCount = 0
        scrollCommands.forEach { command ->
            if (command.dx == 0 && command.dy == 0) {
                accumulator = 0f
                return@forEach
            }
            val rawDxUnits = command.dx.toFloat()
            val dxUnits = when {
                rawDxUnits > HORIZONTAL_NOISE_FLOOR_UNITS -> rawDxUnits - HORIZONTAL_NOISE_FLOOR_UNITS
                rawDxUnits < -HORIZONTAL_NOISE_FLOOR_UNITS -> rawDxUnits + HORIZONTAL_NOISE_FLOOR_UNITS
                else -> 0f
            }

            accumulator += dxUnits
            while (accumulator >= threshold) {
                hidStepCount += 1
                accumulator -= threshold
            }
            while (accumulator <= -threshold) {
                hidStepCount += 1
                accumulator += threshold
            }
            if (abs(accumulator) < SCROLL_ACCUMULATOR_EPSILON) {
                accumulator = 0f
            }
        }
        return hidStepCount
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

    private data class ScrollCommandStats(
        val horizontalNonZeroCount: Int,
        val verticalNonZeroCount: Int,
        val stopMarkerCount: Int,
        val horizontalDxMagnitudeSum: Int,
        val estimatedHorizontalHidStepCount: Int,
        val summary: String,
    )

    private class RecordingBackend : AdbInjectionBackend {
        private val commands = Collections.synchronizedList(mutableListOf<AdbCommand>())

        override fun enqueue(command: AdbCommand) {
            commands += command
        }

        fun snapshot(): List<AdbCommand> = synchronized(commands) { commands.toList() }
    }

    private companion object {
        const val SCROLL_COMMAND_UNITS_PER_PIXEL = 10f
        const val HORIZONTAL_NOISE_FLOOR_UNITS = 0f
        const val SCROLL_ACCUMULATOR_EPSILON = 0.0001f
        const val MIN_SCROLL_UNITS_PER_STEP = 0.001f
    }
}
