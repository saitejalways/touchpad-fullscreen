package com.alex.touchpad.scroll

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alex.touchpad.TouchpadApplication
import com.alex.touchpad.input.InputAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class CursorCenterCalibrationFeasibilityIntegrationTest {

    @Test
    fun relativeMoveCalibration_fromTopLeftToCenter_isStableEnough() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TouchpadApplication>()
        val container = app.appContainer
        container.autoConnectEnabled.value = true
        container.overlayDesired.value = false
        container.settingsRepository.setOneFingerEdgeScrollEnabled(false)
        container.settingsRepository.setSpeedMultiplier(1f)
        container.settingsRepository.setMouseAccelerationEnabled(false)
        container.settingsRepository.setPointerMoveDeadbandPx(0f)

        ensureConnected(container)
        assertTrue(
            "Expected backend connected for cursor calibration feasibility test",
            waitUntil(4_000L) { container.sessionManager.isConnected.value },
        )

        val display = readDisplaySize()
        val targetX = display.width / 2f
        val targetY = display.height / 2f

        moveCursorToTopLeft(container)
        val corner = waitForCursor(2_000L) { it.x <= CORNER_TOLERANCE_PX && it.y <= CORNER_TOLERANCE_PX }
        assumeTrue("Could not reliably drive cursor to top-left corner", corner != null)

        val increasingX = sampleIncreasingAxis(container, horizontal = true, target = targetX)
        val increasingY = sampleIncreasingAxis(container, horizontal = false, target = targetY)

        assertTrue(
            "Expected increasing horizontal relative moves to increase cursor X monotonically. samples=$increasingX",
            increasingX.zipWithNext().all { (a, b) -> b >= a },
        )
        assertTrue(
            "Expected increasing vertical relative moves to increase cursor Y monotonically. samples=$increasingY",
            increasingY.zipWithNext().all { (a, b) -> b >= a },
        )

        val calibratedDx = calibrateAxis(container, horizontal = true, target = targetX)
        val calibratedDy = calibrateAxis(container, horizontal = false, target = targetY)

        val trialErrors = mutableListOf<Float>()
        repeat(3) {
            moveCursorToTopLeft(container)
            sendMove(container, calibratedDx, calibratedDy)
            val sample = waitForCursor(2_000L) { true }
            assertNotNull("Expected cursor sample after calibrated center move", sample)
            val cursor = sample!!
            val errorX = abs(cursor.x - targetX)
            val errorY = abs(cursor.y - targetY)
            trialErrors += maxOf(errorX, errorY)
        }

        val maxError = trialErrors.maxOrNull() ?: Float.MAX_VALUE
        val minError = trialErrors.minOrNull() ?: Float.MAX_VALUE
        val spread = maxError - minError

        assertTrue(
            "Expected calibrated relative move to land near screen center consistently. " +
                "dx=$calibratedDx dy=$calibratedDy errors=$trialErrors display=$display",
            maxError <= MAX_CENTER_ERROR_PX,
        )
        assertTrue(
            "Expected calibration repeatability to stay tight enough for practical use. " +
                "dx=$calibratedDx dy=$calibratedDy errors=$trialErrors",
            spread <= MAX_REPEATABILITY_SPREAD_PX,
        )
    }

    private suspend fun sampleIncreasingAxis(
        container: com.alex.touchpad.core.AppContainer,
        horizontal: Boolean,
        target: Float,
    ): List<Float> {
        val values = listOf(0.25f, 0.5f, 0.75f, 1f).map { (target * it).toInt().coerceAtLeast(1) }
        val samples = mutableListOf<Float>()
        for (value in values) {
            moveCursorToTopLeft(container)
            if (horizontal) {
                sendMove(container, value, 0)
            } else {
                sendMove(container, 0, value)
            }
            val sample = waitForCursor(2_000L) { true }
            assertNotNull("Expected cursor sample for monotonic calibration probe", sample)
            samples += if (horizontal) sample!!.x else sample!!.y
        }
        return samples
    }

    private suspend fun calibrateAxis(
        container: com.alex.touchpad.core.AppContainer,
        horizontal: Boolean,
        target: Float,
    ): Int {
        var low = 0
        var high = (target * 4f).toInt().coerceAtLeast(512)
        repeat(12) {
            val candidate = (low + high) / 2
            moveCursorToTopLeft(container)
            if (horizontal) {
                sendMove(container, candidate, 0)
            } else {
                sendMove(container, 0, candidate)
            }
            val sample = waitForCursor(2_000L) { true }
            assertNotNull("Expected cursor sample during calibration search", sample)
            val actual = if (horizontal) sample!!.x else sample!!.y
            if (actual < target) {
                low = candidate + 1
            } else {
                high = candidate
            }
        }
        return high
    }

    private suspend fun moveCursorToTopLeft(container: com.alex.touchpad.core.AppContainer) {
        repeat(6) {
            sendMove(container, -8_000, -8_000)
        }
        waitForCursor(2_000L) { it.x <= CORNER_TOLERANCE_PX && it.y <= CORNER_TOLERANCE_PX }
    }

    private suspend fun sendMove(
        container: com.alex.touchpad.core.AppContainer,
        dx: Int,
        dy: Int,
    ) {
        container.actionRouter.route(InputAction.MoveBy(dx, dy))
        delay(MOVE_SETTLE_MS)
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

    private fun readCursorGroundTruth(): CursorSample? {
        val output = readShellOutput(
            "dumpsys input | grep -m 1 -E 'hoveringPointers|Pointer\\(id=[0-9]+, *MOUSE\\)'",
        )
        val match = HOVER_POINTER_REGEX.find(output) ?: GENERIC_MOUSE_POINTER_REGEX.find(output)
        if (match != null) {
            val x = match.groupValues.getOrNull(1)?.toFloatOrNull() ?: return null
            val y = match.groupValues.getOrNull(2)?.toFloatOrNull() ?: return null
            return CursorSample(x = x, y = y)
        }
        val liveSample = ApplicationProvider.getApplicationContext<TouchpadApplication>()
            .appContainer
            .cursorGroundTruth
            .value
        return liveSample?.let { CursorSample(x = it.x, y = it.y) }
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
        return DisplaySize(width = metrics.widthPixels.coerceAtLeast(1), height = metrics.heightPixels.coerceAtLeast(1))
    }

    private fun readShellOutput(command: String): String {
        val parcel = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        FileInputStream(parcel.fileDescriptor).use { input ->
            return input.bufferedReader().readText()
        }
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

    private data class CursorSample(val x: Float, val y: Float)

    private data class DisplaySize(val width: Int, val height: Int)

    private companion object {
        const val MOVE_SETTLE_MS = 180L
        const val CORNER_TOLERANCE_PX = 3f
        const val MAX_CENTER_ERROR_PX = 80f
        const val MAX_REPEATABILITY_SPREAD_PX = 50f
        val HOVER_POINTER_REGEX = Regex(
            "hoveringPointers=\\[Pointer\\(id=\\d+,\\s*MOUSE\\)\\s*at\\s*\\((-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)\\)",
        )
        val GENERIC_MOUSE_POINTER_REGEX = Regex(
            "Pointer\\(id=\\d+,\\s*MOUSE\\)\\s*at\\s*\\((-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)\\)",
        )
        val WM_SIZE_REGEX = Regex("(?:Physical size|Override size):\\s*(\\d+)x(\\d+)")
    }
}
