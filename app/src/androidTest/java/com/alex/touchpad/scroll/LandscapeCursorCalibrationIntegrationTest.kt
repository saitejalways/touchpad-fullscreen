package com.alex.touchpad.scroll

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alex.touchpad.TouchpadApplication
import com.alex.touchpad.ui.LandscapeTestActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import kotlin.math.abs
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class LandscapeCursorCalibrationIntegrationTest {

    @Test
    fun calibrateCursorCenterMapping_inLandscape_storesLandscapeCalibrationAndCentersReasonably() = runBlocking {
        ActivityScenario.launch(LandscapeTestActivity::class.java).use {
            waitForLandscapeOrientation()

            val app = ApplicationProvider.getApplicationContext<TouchpadApplication>()
            val container = app.appContainer
            container.autoConnectEnabled.value = true
            container.overlayDesired.value = false
            container.settingsRepository.setOneFingerEdgeScrollEnabled(false)

            ensureConnected(container)
            assertTrue(waitUntil(4_000L) { container.sessionManager.isConnected.value })

            performCursorPrefightJiggle(container, "center calibration")

            val displayForCheck = readDisplaySizeFromInputViewport()
            assertTrue("Device must already be in landscape for this test. display=$displayForCheck", displayForCheck.width > displayForCheck.height)

            val result = container.calibrateCursorCenterMapping()
            if (result == null) {
                throw AssertionError(container.debugProbeCalibration())
            }
            assertTrue("Expected calibration result to be tagged as landscape", result.isLandscape)

            val live = readCursorGroundTruth()
            assertNotNull("Expected ground-truth cursor sample after calibration", live)

            val display = readDisplaySizeFromInputViewport()
            val centerX = display.width / 2f
            val centerY = display.height / 2f
            val errorX = abs(live!!.x - centerX)
            val errorY = abs(live.y - centerY)

            assertTrue(
                "Expected landscape calibration to store a usable center snap. " +
                    "cursor=$live center=($centerX,$centerY) result=$result",
                maxOf(errorX, errorY) <= MAX_CENTER_ERROR_PX,
            )
        }
    }

    @Ignore("Debug probe kept for targeted diagnosis only")
    @Test
    fun probeLandscapeCalibrationStages(): Unit = runBlocking {
        ActivityScenario.launch(LandscapeTestActivity::class.java).use {
            waitForLandscapeOrientation()

            val app = ApplicationProvider.getApplicationContext<TouchpadApplication>()
            val container = app.appContainer
            container.autoConnectEnabled.value = true
            container.overlayDesired.value = false
            container.settingsRepository.setOneFingerEdgeScrollEnabled(false)

            ensureConnected(container)
            assertTrue(waitUntil(4_000L) { container.sessionManager.isConnected.value })

            throw AssertionError(container.debugProbeCalibration())
        }
    }

    @Test
    fun calibrationPrelude_inLandscape_reachesLogicalTopLeft() = runBlocking {
        ActivityScenario.launch(LandscapeTestActivity::class.java).use {
            waitForLandscapeOrientation()

            val app = ApplicationProvider.getApplicationContext<TouchpadApplication>()
            val container = app.appContainer
            container.autoConnectEnabled.value = true
            container.overlayDesired.value = false
            container.settingsRepository.setOneFingerEdgeScrollEnabled(false)
            container.settingsRepository.setSpeedMultiplier(1f)
            container.settingsRepository.setMouseAccelerationEnabled(false)
            container.settingsRepository.setPointerMoveDeadbandPx(0f)

            ensureConnected(container)
            assertTrue(waitUntil(4_000L) { container.sessionManager.isConnected.value })
            val displayForCheck = readDisplaySizeFromInputViewport()
            assertTrue("Device must already be in landscape for this test. display=$displayForCheck", displayForCheck.width > displayForCheck.height)

            performCursorPrefightJiggle(container, "top-left prelude")

            val moved = container.debugMoveCursorToTopLeftForCalibration()
            val orientationBefore = container.debugCurrentInputViewportOrientation()
            val sampleBefore = container.debugQueryCursorGroundTruth()
            assertTrue("Expected calibration corner sweep command to succeed", moved)
            delay(600)
            val sample = readCursorGroundTruth()
            assertNotNull("Expected cursor sample after landscape corner sweep", sample)
            assertTrue(
                "Expected logical cursor to reach top-left after calibration sweep in landscape. " +
                    "beforeOrientation=$orientationBefore before=$sampleBefore sample=$sample",
                sample!!.x <= 12f && sample.y <= 12f,
            )
        }
    }

    private suspend fun performCursorPrefightJiggle(container: com.alex.touchpad.core.AppContainer, label: String) {
        val rng = Random(0xCAFEF00DL)
        val before = readCursorGroundTruth()
        val debugBefore = container.debugQueryCursorGroundTruth()
        val orientationBefore = container.debugCurrentInputViewportOrientation()

        val moves = buildList {
            repeat(5) {
                val mag = 4000 + rng.nextInt(15000)
                val dx = if (rng.nextBoolean()) mag else -mag
                val dy = if (rng.nextBoolean()) mag else -mag
                add(dx to dy)
            }
            add(999999 to 999999)
            add(-999999 to -999999)
        }

        val accepted = moves.map { (dx, dy) ->
            container.debugSendLogicalMove(dx, dy)
        }.all { it }

        delay(350)
        val after = readCursorGroundTruth()
        val debugAfter = container.debugQueryCursorGroundTruth()

        val movedDeltaX = if (before != null && after != null) after.x - before.x else Float.NaN
        val movedDeltaY = if (before != null && after != null) after.y - before.y else Float.NaN
        val debugDeltaX = if (debugBefore != null && debugAfter != null) debugAfter.x - debugBefore.x else Float.NaN
        val debugDeltaY = if (debugBefore != null && debugAfter != null) debugAfter.y - debugBefore.y else Float.NaN
        val movementMessage = buildString {
            append("prefight($label):")
            append(" orientationBefore=$orientationBefore")
            append(" before=$before")
            append(" after=$after")
            append(" delta=(${if (movedDeltaX.isNaN()) "n/a" else movedDeltaX},${if (movedDeltaY.isNaN()) "n/a" else movedDeltaY})")
            append(" debugDelta=(${if (debugDeltaX.isNaN()) "n/a" else debugDeltaX},${if (debugDeltaY.isNaN()) "n/a" else debugDeltaY})")
            append(" debugBefore=$debugBefore")
            append(" debugAfter=$debugAfter")
            append(" allMovesAccepted=$accepted")
            append(" moves=$moves")
        }
        println(movementMessage)

        assertNotNull("Expected cursor ground-truth after pre-flight jiggle. $movementMessage", after)
        val movedByDump = !movedDeltaX.isNaN() && !movedDeltaY.isNaN() && (abs(movedDeltaX) > 0.5f || abs(movedDeltaY) > 0.5f)
        val movedByDebug = !debugDeltaX.isNaN() && !debugDeltaY.isNaN() && (abs(debugDeltaX) > 0.5f || abs(debugDeltaY) > 0.5f)
        if (before != null || debugBefore != null) {
            assertTrue("Expected at least one observable movement during pre-flight jiggle. $movementMessage", movedByDump || movedByDebug)
        }
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

    private fun readCursorGroundTruth(): CursorSample? {
        val output = readShellOutput("dumpsys input")
        val viewport = VIEWPORT_REGEX.findAll(output).lastOrNull()
        val pointer = HOVER_POINTER_REGEX.find(output) ?: GENERIC_MOUSE_POINTER_REGEX.find(output) ?: return null
        val rawX = pointer.groupValues.getOrNull(1)?.toFloatOrNull() ?: return null
        val rawY = pointer.groupValues.getOrNull(2)?.toFloatOrNull() ?: return null
        val width = viewport?.groupValues?.getOrNull(4)?.toIntOrNull()?.minus(viewport.groupValues.getOrNull(2)?.toIntOrNull() ?: 0)
        val height = viewport?.groupValues?.getOrNull(5)?.toIntOrNull()?.minus(viewport.groupValues.getOrNull(3)?.toIntOrNull() ?: 0)
        val orientation = viewport?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        if (width == null || height == null || width <= 0 || height <= 0) {
            return CursorSample(rawX, rawY)
        }
        val maxX = width.toFloat()
        val maxY = height.toFloat()
        val normalized = when (((orientation % 4) + 4) % 4) {
            1 -> rawY to (maxY - rawX)
            2 -> (maxX - rawX) to (maxY - rawY)
            3 -> (maxY - rawY) to (maxX - rawX)
            else -> rawX to rawY
        }
        return CursorSample(normalized.first, normalized.second)
    }

    private fun readDisplaySizeFromInputViewport(): DisplaySize {
        val output = readShellOutput("dumpsys input")
        val viewport = VIEWPORT_REGEX.findAll(output).lastOrNull()
        val left = viewport?.groupValues?.getOrNull(2)?.toIntOrNull()
        val top = viewport?.groupValues?.getOrNull(3)?.toIntOrNull()
        val right = viewport?.groupValues?.getOrNull(4)?.toIntOrNull()
        val bottom = viewport?.groupValues?.getOrNull(5)?.toIntOrNull()
        if (left != null && top != null && right != null && bottom != null) {
            return DisplaySize(width = right - left, height = bottom - top)
        }
        val metrics = ApplicationProvider.getApplicationContext<TouchpadApplication>().resources.displayMetrics
        return DisplaySize(metrics.widthPixels, metrics.heightPixels)
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

    private suspend fun waitForLandscapeOrientation() {
        val ok = waitUntil(4_000L) {
            val size = readDisplaySizeFromInputViewport()
            size.width > size.height
        }
        assertTrue("Expected temporary test activity to pin landscape orientation", ok)
        delay(300)
    }

    private data class CursorSample(val x: Float, val y: Float)
    private data class DisplaySize(val width: Int, val height: Int)

    private companion object {
        const val MAX_CENTER_ERROR_PX = 140f
        val VIEWPORT_REGEX = Regex(
            "Viewport\\s+INTERNAL.*orientation=(\\d+),.*logicalFrame=\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\]",
        )
        val HOVER_POINTER_REGEX = Regex(
            "hoveringPointers=\\[Pointer\\(id=\\d+,\\s*MOUSE\\)\\s*at\\s*\\((-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)\\)",
        )
        val GENERIC_MOUSE_POINTER_REGEX = Regex(
            "Pointer\\(id=\\d+,\\s*MOUSE\\)\\s*at\\s*\\((-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)\\)",
        )
    }
}
