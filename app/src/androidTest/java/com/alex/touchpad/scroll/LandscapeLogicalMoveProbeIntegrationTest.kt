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

@RunWith(AndroidJUnit4::class)
class LandscapeLogicalMoveProbeIntegrationTest {

    @Test
    @Ignore("Debug-only probe kept off by default once landscape mapping is validated.")
    fun probeContinuousSmallLogicalMovesInLandscape(): Unit = runBlocking {
        ActivityScenario.launch(LandscapeTestActivity::class.java).use {
            waitForLandscapeOrientation()

            val app = ApplicationProvider.getApplicationContext<TouchpadApplication>()
            val container = app.appContainer
            container.autoConnectEnabled.value = true
            container.overlayDesired.value = false
            container.settingsRepository.setMouseAccelerationEnabled(false)
            container.settingsRepository.setPointerMoveDeadbandPx(0f)
            container.settingsRepository.setSpeedMultiplier(1f)
            container.settingsRepository.setHidMoveChunkSize(32)

            ensureConnected(container)
            assertTrue(waitUntil(4_000L) { container.sessionManager.isConnected.value })

            val orientation = container.debugCurrentInputViewportOrientation()
            val startRaw = readRawCursorSample()
            val startLogical = container.debugQueryCursorGroundTruth()

            val blocks = listOf(
                ProbeBlock("R", 160, 0),
                ProbeBlock("D", 0, 160),
                ProbeBlock("L", -160, 0),
                ProbeBlock("U", 0, -160),
            )
            val samples = mutableListOf<String>()

            for (block in blocks) {
                val beforeRaw = readRawCursorSample()
                val beforeLogical = container.debugQueryCursorGroundTruth()
                repeat(BLOCK_STEPS) { step ->
                    val ok = container.debugSendLogicalMove(block.dx, block.dy)
                    assertTrue("Expected logical move block ${block.label} step=${step + 1} to succeed", ok)
                    delay(STEP_DELAY_MS)
                }
                delay(250)
                val afterRaw = readRawCursorSample()
                val afterLogical = container.debugQueryCursorGroundTruth()
                samples += buildString {
                    append(block.label)
                    append(" logical=(")
                    append(block.dx)
                    append(",")
                    append(block.dy)
                    append(")")
                    append(" beforeRaw=")
                    append(beforeRaw)
                    append(" afterRaw=")
                    append(afterRaw)
                    append(" beforeLogical=")
                    append(beforeLogical)
                    append(" afterLogical=")
                    append(afterLogical)
                }
            }

            throw AssertionError(
                "Landscape continuous logical move probe: " +
                    "orientation=$orientation startRaw=$startRaw startLogical=$startLogical samples=$samples",
            )
        }
    }

    private suspend fun ensureConnected(container: com.alex.touchpad.core.AppContainer) {
        if (container.sessionManager.isConnected.value) return
        repeat(8) {
            if (container.sessionManager.isConnected.value) return
            container.sessionManager.connect()
            if (container.sessionManager.isConnected.value) return
            delay(250)
        }
    }

    private suspend fun waitForLandscapeOrientation() {
        val ok = waitUntil(4_000L) {
            val metrics = ApplicationProvider.getApplicationContext<TouchpadApplication>().resources.displayMetrics
            metrics.widthPixels > metrics.heightPixels
        }
        assertTrue("Expected temporary test activity to pin landscape orientation", ok)
        delay(300)
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(40)
        }
        return false
    }

    private fun readRawCursorSample(): RawCursorSample? {
        val output = readShellOutput("dumpsys input")
        val viewport = VIEWPORT_REGEX.findAll(output).lastOrNull()
        val pointer = HOVER_POINTER_REGEX.find(output) ?: GENERIC_MOUSE_POINTER_REGEX.find(output) ?: return null
        return RawCursorSample(
            rawX = pointer.groupValues.getOrNull(1)?.toFloatOrNull() ?: return null,
            rawY = pointer.groupValues.getOrNull(2)?.toFloatOrNull() ?: return null,
            orientation = viewport?.groupValues?.getOrNull(1)?.toIntOrNull(),
            logicalWidth = viewport?.groupValues?.getOrNull(4)?.toIntOrNull(),
            logicalHeight = viewport?.groupValues?.getOrNull(5)?.toIntOrNull(),
        )
    }

    private fun readShellOutput(command: String): String {
        val parcel = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        FileInputStream(parcel.fileDescriptor).use { input ->
            return input.bufferedReader().readText()
        }
    }

    private data class ProbeBlock(val label: String, val dx: Int, val dy: Int)
    private data class RawCursorSample(
        val rawX: Float,
        val rawY: Float,
        val orientation: Int?,
        val logicalWidth: Int?,
        val logicalHeight: Int?,
    )

    private companion object {
        const val BLOCK_STEPS = 18
        const val STEP_DELAY_MS = 60L
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
