package com.alex.touchpad.scroll

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alex.touchpad.TouchpadApplication
import com.alex.touchpad.backend.CursorGroundTruth
import com.alex.touchpad.core.AppContainer
import com.alex.touchpad.ui.TouchOrientationProbeActivity
import android.view.InputDevice
import android.view.MotionEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class CursorOrientationMappingProbeIntegrationTest {

    @Ignore("Debug-only probe kept off by default while touch event injection is validated.")
    @Test
    fun probeCornerRelativesToScreenTapCoordinates() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TouchpadApplication>()
        val container = app.appContainer
        container.autoConnectEnabled.value = true
        container.overlayDesired.value = false
        container.settingsRepository.setOneFingerEdgeScrollEnabled(false)
        container.settingsRepository.setMouseAccelerationEnabled(false)
        container.settingsRepository.setHidMoveChunkSize(1)
        container.settingsRepository.setSpeedMultiplier(1f)
        container.settingsRepository.setPointerMoveDeadbandPx(0f)

        ensureConnected(container)
        assertTrue(waitUntil(4_000L) { container.sessionManager.isConnected.value })

        val rows = mutableListOf<MappingRow>()
        rows += collectForOrientation(
            app = app,
            container = container,
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            modeName = "portrait",
            expectLandscape = false,
        )
        rows += collectForOrientation(
            app = app,
            container = container,
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            modeName = "landscape",
            expectLandscape = true,
        )

        assertTrue("Expected probe rows for portrait + landscape", rows.size == 8)
        println(buildReport(rows))
    }

    private suspend fun collectForOrientation(
        app: TouchpadApplication,
        container: AppContainer,
        requestedOrientation: Int,
        modeName: String,
        expectLandscape: Boolean,
    ): List<MappingRow> {
        val rows = mutableListOf<MappingRow>()
        val intent = Intent(app, TouchOrientationProbeActivity::class.java).apply {
            putExtra(TouchOrientationProbeActivity.EXTRA_REQUESTED_ORIENTATION, requestedOrientation)
        }

        ActivityScenario.launch<TouchOrientationProbeActivity>(intent).use { scenario ->
            val ok = waitUntil(5_000L) { isExpectedDisplayLandscape() == expectLandscape }
            assertTrue("Probe activity did not enter $modeName orientation.", ok)
            scenario.onActivity { it.clearLatestTap() }

            val corners = listOf(
                "top-left" to (0 to 0),
                "top-right" to (CORNER_STEP to -CORNER_STEP),
                "bottom-right" to (CORNER_STEP to CORNER_STEP),
                "bottom-left" to (-CORNER_STEP to CORNER_STEP),
            )

        for ((cornerName, command) in corners) {
            assertTrue(
                "Could not position baseline for $cornerName in $modeName.",
                container.debugMoveCursorToTopLeftForCalibration(),
            )
            val baseline = waitForCursor(container)
            assertNotNull("Expected baseline cursor sample for $modeName", baseline)
            if (command.first != 0 || command.second != 0) {
                assertTrue(
                    "Failed to send command $command for $cornerName in $modeName.",
                    container.debugSendLogicalMove(command.first, command.second),
                )
            }
            val sample = waitForCursorDelta(container, baseline!!) ?: baseline

            val (injectedX, injectedY) = sample.safeTapCoords()
            scenario.onActivity { it.clearLatestTap() }
            injectTouchEvent(injectedX, injectedY)
            delay(180)
            val tap = waitForTap(scenario) ?: throw AssertionError("No tap captured for $cornerName in $modeName")
            val queryOrientation = container.debugCurrentInputViewportOrientation()
            val expectedMoveDx = sample.x - baseline.x
            val expectedMoveDy = sample.y - baseline.y

            rows += MappingRow(
                modeName = modeName,
                requestedOrientation = requestedOrientation,
                queryOrientation = queryOrientation,
                cornerName = cornerName,
                cursorX = sample.x,
                cursorY = sample.y,
                displayWidthPx = sample.widthPx,
                displayHeightPx = sample.heightPx,
                tapRawX = tap.rawX,
                tapRawY = tap.rawY,
                tapLocalX = tap.localX,
                tapLocalY = tap.localY,
                injectedX = injectedX.toFloat(),
                injectedY = injectedY.toFloat(),
                movedByX = expectedMoveDx,
                movedByY = expectedMoveDy,
            )
        }
        }
        return rows
    }

    private suspend fun waitForTap(
        scenario: ActivityScenario<TouchOrientationProbeActivity>,
    ): TouchOrientationProbeActivity.TapSample? {
        val deadline = System.currentTimeMillis() + 1_200L
        while (System.currentTimeMillis() < deadline) {
            var sample: TouchOrientationProbeActivity.TapSample? = null
            scenario.onActivity {
                sample = it.consumeLatestTap()
            }
            if (sample != null) {
                return sample
            }
            delay(25)
        }
        return null
    }

    private suspend fun waitForCursor(container: AppContainer): CursorGroundTruth? {
        val deadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadline) {
            val sample = container.debugQueryCursorGroundTruth()
            if (sample != null) {
                return sample
            }
            delay(30)
        }
        return null
    }

    private suspend fun waitForCursorDelta(container: AppContainer, before: CursorGroundTruth): CursorGroundTruth? {
        val deadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadline) {
            val sample = container.debugQueryCursorGroundTruth()
            if (sample != null && (abs(sample.x - before.x) > MOVEMENT_EPSILON || abs(sample.y - before.y) > MOVEMENT_EPSILON)) {
                return sample
            }
            delay(25)
        }
        return null
    }

    private suspend fun ensureConnected(container: AppContainer) {
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

    private fun isExpectedDisplayLandscape(): Boolean {
        val metrics = ApplicationProvider.getApplicationContext<TouchpadApplication>().resources.displayMetrics
        return metrics.widthPixels > metrics.heightPixels
    }

    private fun buildReport(rows: List<MappingRow>): String {
        val header = "Mode | ReqOri | QueryOri | Corner | Cursor(x,y) | Delta(x,y) | Display(WxH) | TapRaw(x,y) | TapLocal(x,y) | Inject(x,y)"
        val divider = "-".repeat(header.length)
        val body = rows.joinToString("\n") { row ->
            "${row.modeName} | " +
                "${row.requestedOrientation} | " +
                "${row.queryOrientation} | " +
                row.cornerName.padEnd(11) +
                " | " +
                "${row.cursorX.format(1)},${row.cursorY.format(1)} | " +
                "${row.movedByX.format(1)},${row.movedByY.format(1)} | " +
                "${row.displayWidthPx}x${row.displayHeightPx} | " +
                "${row.tapRawX.format(1)},${row.tapRawY.format(1)} | " +
                "${row.tapLocalX.format(1)},${row.tapLocalY.format(1)} | " +
                "${row.injectedX.toInt()},${row.injectedY.toInt()}"
        }
        return "\n$header\n$divider\n$body\n"
    }

    private fun Float.format(digits: Int): String {
        return "%.${digits}f".format(this)
    }

    private fun injectTouchEvent(x: Int, y: Int) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            now,
            now,
            MotionEvent.ACTION_DOWN,
            x.toFloat(),
            y.toFloat(),
            0,
        ).apply {
            setSource(InputDevice.SOURCE_TOUCHSCREEN)
        }
        val up = MotionEvent.obtain(
            now + 10,
            now + 10,
            MotionEvent.ACTION_UP,
            x.toFloat(),
            y.toFloat(),
            0,
        ).apply {
            setSource(InputDevice.SOURCE_TOUCHSCREEN)
        }
        automation.injectInputEvent(down, true)
        automation.injectInputEvent(up, true)
        down.recycle()
        up.recycle()
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

    private fun CursorGroundTruth.safeTapCoords(): Pair<Int, Int> {
        val xMin = 24
        val yMin = 24
        val xMax = (widthPx - 1 - xMin).coerceAtLeast(xMin)
        val yMax = (heightPx - 1 - yMin).coerceAtLeast(yMin)
        val safeX = x.roundToInt().coerceIn(xMin, xMax)
        val safeY = y.roundToInt().coerceIn(yMin, yMax)
        return safeX to safeY
    }

    private data class MappingRow(
        val modeName: String,
        val requestedOrientation: Int,
        val queryOrientation: Int,
        val cornerName: String,
        val cursorX: Float,
        val cursorY: Float,
        val displayWidthPx: Int,
        val displayHeightPx: Int,
        val tapRawX: Float,
        val tapRawY: Float,
        val tapLocalX: Float,
        val tapLocalY: Float,
        val injectedX: Float,
        val injectedY: Float,
        val movedByX: Float,
        val movedByY: Float,
    )

    private companion object {
        const val CORNER_STEP = 20_000
        const val MOVEMENT_EPSILON = 0.2f
    }
}
