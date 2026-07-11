package com.alex.touchpad.backend

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alex.touchpad.input.MouseButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ShellDaemonDragIntegrationInstrumentedTest {

    @Test
    @Ignore("Bootstrap path depends on on-device ADB reachability; use ShellHidDragProbeInstrumentedTest for USB-driven drag diagnosis")
    fun persistentRuntimeChannel_movesCursorWhileLeftButtonHeld() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bootstrapper = ShellDaemonBootstrapper(context)
        val runtimeClient = ShellDaemonRuntimeClient()
        val token = ShellDaemonBootstrapper.newToken()

        try {
            val launch = launchDaemonViaInstrumentationShell(
                token = token,
                apkPath = context.packageCodePath,
            )
            assertTrue("Daemon bootstrap failed: ${launch.message}", launch.success)

            val before = bootstrapper.queryCursorGroundTruth(token = token)
            val movePlan = selectMovePlan(before)

            runtimeClient.executeButton(
                port = ShellDaemonBootstrapper.DEFAULT_PORT,
                token = token,
                button = MouseButton.LEFT,
                isDown = true,
            )
            repeat(movePlan.steps) {
                runtimeClient.executeMove(
                    port = ShellDaemonBootstrapper.DEFAULT_PORT,
                    token = token,
                    dx = movePlan.dxPerStep,
                    dy = movePlan.dyPerStep,
                    hidMoveChunkSize = 8,
                    mouseAccelerationEnabled = false,
                )
            }

            val pingReply = bootstrapper.pingDaemon(token = token)
            assertTrue("Expected daemon to remain reachable during held drag: $pingReply", pingReply.startsWith("OK PONG"))

            val after = awaitShiftedGroundTruth(
                bootstrapper = bootstrapper,
                token = token,
                before = before,
                expectedAxis = movePlan.axis,
                minimumDeltaPx = 80f,
            )
            assertTrue(
                "Expected cursor to move while left button was held. before=$before after=$after plan=$movePlan",
                movedEnough(before, after, movePlan.axis, 80f),
            )
        } finally {
            runCatching {
                runtimeClient.executeButton(
                    port = ShellDaemonBootstrapper.DEFAULT_PORT,
                    token = token,
                    button = MouseButton.LEFT,
                    isDown = false,
                )
            }
            runtimeClient.close()
            runCatching { bootstrapper.stopDaemon(token = token) }
        }
    }

    private suspend fun launchDaemonViaInstrumentationShell(token: String, apkPath: String): ShellDaemonResult {
        runInstrumentationShell("pkill -f $DAEMON_CLASS || true")
        if (apkPath.isBlank()) {
            return ShellDaemonResult(false, "Could not resolve APK path from context")
        }

        val escapedToken = token.replace("'", "'\\''")
        val launchOutput = runInstrumentationShell(
            "CLASSPATH='$apkPath' nohup app_process /system/bin $DAEMON_CLASS " +
                "--port ${ShellDaemonBootstrapper.DEFAULT_PORT} --token '$escapedToken' >/dev/null 2>&1 &",
        )
        repeat(15) {
            val ping = runCatching {
                ShellDaemonSocketClient.ping(
                    port = ShellDaemonBootstrapper.DEFAULT_PORT,
                    token = token,
                )
            }.getOrNull()
            if (ping != null) {
                return ShellDaemonResult(true, "Daemon responded: $ping")
            }
            delay(200L)
        }
        return ShellDaemonResult(false, "Daemon launch did not become reachable. shell=$launchOutput")
    }

    private suspend fun awaitShiftedGroundTruth(
        bootstrapper: ShellDaemonBootstrapper,
        token: String,
        before: CursorGroundTruth,
        expectedAxis: Axis,
        minimumDeltaPx: Float,
    ): CursorGroundTruth {
        repeat(20) {
            val current = bootstrapper.queryCursorGroundTruth(token = token)
            if (movedEnough(before, current, expectedAxis, minimumDeltaPx)) {
                return current
            }
            delay(100L)
        }
        return bootstrapper.queryCursorGroundTruth(token = token)
    }

    private fun movedEnough(
        before: CursorGroundTruth,
        after: CursorGroundTruth,
        axis: Axis,
        minimumDeltaPx: Float,
    ): Boolean {
        return when (axis) {
            Axis.X -> abs(after.x - before.x) >= minimumDeltaPx
            Axis.Y -> abs(after.y - before.y) >= minimumDeltaPx
        }
    }

    private fun selectMovePlan(groundTruth: CursorGroundTruth): MovePlan {
        val leftRoom = groundTruth.x
        val rightRoom = groundTruth.widthPx - groundTruth.x
        val upRoom = groundTruth.y
        val downRoom = groundTruth.heightPx - groundTruth.y

        return when (maxOf(leftRoom, rightRoom, upRoom, downRoom)) {
            rightRoom -> MovePlan(axis = Axis.X, dxPerStep = 60, dyPerStep = 0, steps = 4)
            leftRoom -> MovePlan(axis = Axis.X, dxPerStep = -60, dyPerStep = 0, steps = 4)
            downRoom -> MovePlan(axis = Axis.Y, dxPerStep = 0, dyPerStep = 60, steps = 4)
            else -> MovePlan(axis = Axis.Y, dxPerStep = 0, dyPerStep = -60, steps = 4)
        }
    }

    private fun runInstrumentationShell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return pfd.use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                reader.readText()
            }
        }
    }

    private data class MovePlan(
        val axis: Axis,
        val dxPerStep: Int,
        val dyPerStep: Int,
        val steps: Int,
    )

    private enum class Axis {
        X,
        Y,
    }

    private companion object {
        const val DAEMON_CLASS = "com.alex.touchpad.backend.TouchpadShellDaemonMain"
    }
}
