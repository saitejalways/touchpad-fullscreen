package com.alex.touchpad.scroll

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alex.touchpad.TouchpadApplication
import com.alex.touchpad.input.InputAction
import com.alex.touchpad.service.TouchpadAccessibilityService
import com.alex.touchpad.ui.ScrollAnchorProbeActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class EdgeScrollFullCycleIntegrationTest {

    @Test
    fun touchInputTopAndBottomEdgePush_emitsVerticalScroll_andMovesScrollableView() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TouchpadApplication>()
        val container = app.appContainer
        container.autoConnectEnabled.value = true
        container.overlayDesired.value = true

        // Keep the path deterministic and sensitive enough for device-side verification.
        container.settingsRepository.setOneFingerEdgeScrollEnabled(true)
        container.settingsRepository.setEdgeCornerDeadzonePx(0f)
        container.settingsRepository.setEdgeScrollRepeatDelayMs(0)
        container.settingsRepository.setVerticalScrollPixelsPerStep(10f)
        container.settingsRepository.setFlingDurationMs(0)
        container.settingsRepository.setSpeedMultiplier(1f)
        container.settingsRepository.setMouseAccelerationEnabled(false)
        container.settingsRepository.setPointerMoveDeadbandPx(0f)

        ensureConnected(container)
        assertTrue(
            "Expected backend connected for edge-scroll full-cycle test",
            waitUntil(4_000L) { container.sessionManager.isConnected.value },
        )
        assertTrue(
            "Accessibility service must be enabled in secure settings for real-touch full-cycle test",
            waitUntil(2_000L) { isAccessibilityServiceEnabled() },
        )
        assumeTrue(
            "Skipping real-touch full-cycle instrumentation path because accessibility service is not bound " +
                "(this can happen when instrumentation runs in a separate process).",
            isAccessibilityServiceBound(),
        )
        TouchpadAccessibilityService.instance?.requestManualReconcile("edge_scroll_full_cycle_test")
        waitUntil(1_500L) { container.overlayAttached.value }

        clearLogcat()

        ActivityScenario.launch(ScrollAnchorProbeActivity::class.java).use { scenario ->
            scenario.focusLeft()
            scenario.scrollLeftToMiddle()
            val display = readDisplaySize()

            val topResult = runVerticalEdgePushCycle(
                scenario = scenario,
                edge = VerticalEdge.TOP,
                display = display,
            )
            scenario.scrollLeftToMiddle()
            val bottomResult = runVerticalEdgePushCycle(
                scenario = scenario,
                edge = VerticalEdge.BOTTOM,
                display = display,
            )

            assertNotNull("Expected cursor ground truth from top-edge cycle", topResult.edgeCursor)
            assertNotNull("Expected cursor ground truth from bottom-edge cycle", bottomResult.edgeCursor)
            assertTrue(
                "Expected top-edge cycle to move a scrollable pane. " +
                    "left=${topResult.leftDeltaY} right=${topResult.rightDeltaY} " +
                    "logs=${topResult.stepLogsBefore}->${topResult.stepLogsAfter}",
                abs(topResult.dominantDeltaY) >= MIN_SCROLL_DELTA_PX,
            )
            assertTrue(
                "Expected bottom-edge cycle to move a scrollable pane. " +
                    "left=${bottomResult.leftDeltaY} right=${bottomResult.rightDeltaY} " +
                    "logs=${bottomResult.stepLogsBefore}->${bottomResult.stepLogsAfter}",
                abs(bottomResult.dominantDeltaY) >= MIN_SCROLL_DELTA_PX,
            )
            assertTrue(
                "Expected top-edge cycle to emit scroll steps. before=${topResult.stepLogsBefore} after=${topResult.stepLogsAfter}",
                topResult.stepLogsAfter > topResult.stepLogsBefore,
            )
            assertTrue(
                "Expected bottom-edge cycle to emit scroll steps. before=${bottomResult.stepLogsBefore} after=${bottomResult.stepLogsAfter}",
                bottomResult.stepLogsAfter > bottomResult.stepLogsBefore,
            )
            assertTrue(
                "Expected top/bottom cycles to scroll in opposite directions. top=${topResult.dominantDeltaY} bottom=${bottomResult.dominantDeltaY}",
                topResult.dominantDeltaY * bottomResult.dominantDeltaY < 0,
            )
        }
    }

    private suspend fun runVerticalEdgePushCycle(
        scenario: ActivityScenario<ScrollAnchorProbeActivity>,
        edge: VerticalEdge,
        display: DisplaySize,
    ): EdgePushCycleResult {
        val strokeStartY = scenario.verticalCenterY().toFloat()
        val edgeTargetY = when (edge) {
            VerticalEdge.TOP -> STROKE_TOP_TARGET_Y
            VerticalEdge.BOTTOM -> (display.height - STROKE_BOTTOM_MARGIN_PX).toFloat()
        }
        val strokeX = scenario.leftCenterX().toFloat()

        // Move cursor into the selected vertical edge using touch input only.
        repeat(MOVE_TO_EDGE_STROKES) {
            injectOneFingerStroke(
                startX = strokeX,
                startY = strokeStartY,
                endX = strokeX,
                endY = edgeTargetY,
                steps = STROKE_MOVE_STEPS,
                frameMs = STROKE_FRAME_MS,
            )
            delay(STROKE_GAP_MS)
        }

        val edgeCursor = waitForCursor(timeoutMs = 2_500L) { cursor ->
            when (edge) {
                VerticalEdge.TOP -> cursor.y <= EDGE_Y_TOLERANCE_PX
                VerticalEdge.BOTTOM -> cursor.y >= (display.height - EDGE_Y_TOLERANCE_PX)
            }
        }
        assertNotNull("Expected cursor at ${edge.name.lowercase()} edge before push", edgeCursor)

        val beforeLeftY = scenario.leftScrollY()
        val beforeRightY = scenario.rightScrollY()
        val beforeSteps = countScrollStepLogs()

        repeat(PUSH_STROKES) {
            injectOneFingerStroke(
                startX = strokeX,
                startY = strokeStartY,
                endX = strokeX,
                endY = edgeTargetY,
                steps = STROKE_MOVE_STEPS,
                frameMs = STROKE_FRAME_MS,
            )
            delay(PUSH_GAP_MS)
        }
        delay(POST_PUSH_SETTLE_MS)

        val afterLeftY = scenario.leftScrollY()
        val afterRightY = scenario.rightScrollY()
        val afterSteps = countScrollStepLogs()
        val leftDelta = afterLeftY - beforeLeftY
        val rightDelta = afterRightY - beforeRightY
        val dominantDelta = if (abs(leftDelta) >= abs(rightDelta)) leftDelta else rightDelta
        return EdgePushCycleResult(
            edgeCursor = edgeCursor,
            dominantDeltaY = dominantDelta,
            leftDeltaY = leftDelta,
            rightDeltaY = rightDelta,
            stepLogsBefore = beforeSteps,
            stepLogsAfter = afterSteps,
        )
    }

    private suspend fun ensureConnected(container: com.alex.touchpad.core.AppContainer) {
        if (container.sessionManager.isConnected.value) {
            return
        }
        repeat(8) {
            if (container.sessionManager.isConnected.value) {
                return
            }
            container.sessionManager.connect()
            if (container.sessionManager.isConnected.value) {
                return
            }
            delay(250)
        }
    }

    private suspend fun ActivityScenario<ScrollAnchorProbeActivity>.anchorPointer(
        container: com.alex.touchpad.core.AppContainer,
        x: Int,
        y: Int,
    ) {
        repeat(4) {
            container.actionRouter.route(InputAction.MoveBy(-6_000, -6_000))
            delay(16)
        }
        container.actionRouter.route(InputAction.MoveBy(x, y))
        delay(160)
    }

    private suspend fun injectOneFingerStroke(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        steps: Int,
        frameMs: Long,
    ) {
        val safeSteps = steps.coerceAtLeast(1)
        val downTime = SystemClock.uptimeMillis()
        var eventTime = downTime

        injectMotionEvent(
            MotionEvent.obtain(
                downTime,
                eventTime,
                MotionEvent.ACTION_DOWN,
                startX,
                startY,
                0,
            )
        )

        for (step in 1..safeSteps) {
            eventTime += frameMs
            val progress = step.toFloat() / safeSteps.toFloat()
            val moveX = startX + ((endX - startX) * progress)
            val moveY = startY + ((endY - startY) * progress)
            injectMotionEvent(
                MotionEvent.obtain(
                    downTime,
                    eventTime,
                    MotionEvent.ACTION_MOVE,
                    moveX,
                    moveY,
                    0,
                )
            )
        }

        eventTime += frameMs
        injectMotionEvent(
            MotionEvent.obtain(
                downTime,
                eventTime,
                MotionEvent.ACTION_UP,
                endX,
                endY,
                0,
            )
        )
    }

    private fun injectMotionEvent(
        event: MotionEvent,
    ) {
        try {
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .injectInputEvent(event, true)
        } finally {
            event.recycle()
        }
    }

    private fun ActivityScenario<ScrollAnchorProbeActivity>.focusLeft() {
        onActivity { activity ->
            activity.leftScrollView.isFocusable = true
            activity.leftScrollView.isFocusableInTouchMode = true
            activity.leftScrollView.requestFocus()
        }
    }

    private fun ActivityScenario<ScrollAnchorProbeActivity>.leftSideCenter(): Pair<Int, Int> {
        val xValue = AtomicInteger()
        val yValue = AtomicInteger()
        onActivity { activity ->
            val location = IntArray(2)
            activity.leftScrollView.getLocationOnScreen(location)
            xValue.set(location[0] + (activity.leftScrollView.width / 2))
            yValue.set(location[1] + (activity.leftScrollView.height / 2))
        }
        return xValue.get() to yValue.get()
    }

    private fun ActivityScenario<ScrollAnchorProbeActivity>.leftCenterX(): Int {
        val xValue = AtomicInteger()
        onActivity { activity ->
            val location = IntArray(2)
            activity.leftScrollView.getLocationOnScreen(location)
            xValue.set(location[0] + (activity.leftScrollView.width / 2))
        }
        return xValue.get()
    }

    private fun ActivityScenario<ScrollAnchorProbeActivity>.verticalCenterY(): Int {
        val yValue = AtomicInteger()
        onActivity { activity ->
            val location = IntArray(2)
            activity.leftScrollView.getLocationOnScreen(location)
            yValue.set(location[1] + (activity.leftScrollView.height / 2))
        }
        return yValue.get()
    }

    private fun ActivityScenario<ScrollAnchorProbeActivity>.leftScrollY(): Int {
        val value = AtomicInteger()
        onActivity { activity ->
            value.set(activity.leftScrollView.scrollY)
        }
        return value.get()
    }

    private fun ActivityScenario<ScrollAnchorProbeActivity>.rightScrollY(): Int {
        val value = AtomicInteger()
        onActivity { activity ->
            value.set(activity.rightScrollView.scrollY)
        }
        return value.get()
    }

    private fun ActivityScenario<ScrollAnchorProbeActivity>.scrollLeftToMiddle() {
        onActivity { activity ->
            val scrollView = activity.leftScrollView
            val child = scrollView.getChildAt(0)
            val max = if (child != null) (child.height - scrollView.height).coerceAtLeast(0) else 0
            scrollView.scrollTo(0, max / 2)
        }
    }

    private fun clearLogcat() {
        readShellOutput("logcat -c")
    }

    private fun countScrollStepLogs(): Int {
        val output = readShellOutput("logcat -d -s OnDeviceAdbExecutor:I")
        return output.lineSequence().count {
            it.contains("scroll step emit") || it.contains("edge vertical swipe step emit")
        }
    }

    private fun readCursorGroundTruth(): CursorSample? {
        val output = readShellOutput(
            "dumpsys input | grep -m 1 -E 'hoveringPointers|Pointer\\(id=[0-9]+, *MOUSE\\)'",
        )
        val match = HOVER_POINTER_REGEX.find(output) ?: GENERIC_MOUSE_POINTER_REGEX.find(output) ?: return null
        val x = match.groupValues.getOrNull(1)?.toFloatOrNull() ?: return null
        val y = match.groupValues.getOrNull(2)?.toFloatOrNull() ?: return null
        return CursorSample(x = x, y = y)
    }

    private suspend fun waitForCursor(timeoutMs: Long, predicate: (CursorSample) -> Boolean): CursorSample? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest: CursorSample? = null
        while (System.currentTimeMillis() < deadline) {
            val cursor = readCursorGroundTruth()
            if (cursor != null) {
                latest = cursor
                if (predicate(cursor)) {
                    return cursor
                }
            }
            delay(45)
        }
        return latest
    }

    private fun readShellOutput(command: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val parcel = instrumentation.uiAutomation.executeShellCommand(command)
        FileInputStream(parcel.fileDescriptor).use { input ->
            return input.bufferedReader().readText()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = readShellOutput("settings get secure enabled_accessibility_services")
        return enabledServices.contains(
            "com.alex.touchpad/com.alex.touchpad.service.TouchpadAccessibilityService",
            ignoreCase = true,
        )
    }

    private fun isAccessibilityServiceBound(): Boolean {
        val dump = readShellOutput("dumpsys accessibility")
        return dump.contains("Bound services:{", ignoreCase = true) &&
            dump.contains("com.alex.touchpad/.service.TouchpadAccessibilityService", ignoreCase = true)
    }

    private fun readDisplaySize(): DisplaySize {
        val output = readShellOutput("wm size")
        val match = WM_SIZE_REGEX.findAll(output).lastOrNull()
        val width = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        val height = match?.groupValues?.getOrNull(2)?.toIntOrNull()
        if (width != null && width > 0 && height != null && height > 0) {
            return DisplaySize(width = width, height = height)
        }
        val metrics = ApplicationProvider.getApplicationContext<TouchpadApplication>().resources.displayMetrics
        return DisplaySize(
            width = metrics.widthPixels.coerceAtLeast(1),
            height = metrics.heightPixels.coerceAtLeast(1),
        )
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return true
            }
            Thread.sleep(40)
        }
        return false
    }

    private data class CursorSample(
        val x: Float,
        val y: Float,
    )

    private data class DisplaySize(
        val width: Int,
        val height: Int,
    )

    private data class EdgePushCycleResult(
        val edgeCursor: CursorSample?,
        val dominantDeltaY: Int,
        val leftDeltaY: Int,
        val rightDeltaY: Int,
        val stepLogsBefore: Int,
        val stepLogsAfter: Int,
    )

    private enum class VerticalEdge {
        TOP,
        BOTTOM,
    }

    private companion object {
        const val EDGE_Y_TOLERANCE_PX = 2f
        const val EDGE_STAY_TOLERANCE_PX = 10f
        const val MIN_SCROLL_DELTA_PX = 24
        const val MOVE_TO_EDGE_STROKES = 18
        const val PUSH_STROKES = 18
        const val STROKE_MOVE_STEPS = 10
        const val STROKE_FRAME_MS = 12L
        const val STROKE_GAP_MS = 14L
        const val PUSH_GAP_MS = 14L
        const val POST_PUSH_SETTLE_MS = 450L
        const val STROKE_TOP_TARGET_Y = 48f
        const val STROKE_BOTTOM_MARGIN_PX = 48
        val HOVER_POINTER_REGEX = Regex(
            "hoveringPointers=\\[Pointer\\(id=\\d+,\\s*MOUSE\\)\\s*at\\s*\\((-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)\\)",
        )
        val GENERIC_MOUSE_POINTER_REGEX = Regex(
            "Pointer\\(id=\\d+,\\s*MOUSE\\)\\s*at\\s*\\((-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)\\)",
        )
        val WM_SIZE_REGEX = Regex("(?:Physical size|Override size):\\s*(\\d+)x(\\d+)")
    }
}
