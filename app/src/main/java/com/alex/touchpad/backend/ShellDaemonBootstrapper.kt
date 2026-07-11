package com.alex.touchpad.backend

import android.content.Context
import com.alex.touchpad.input.MouseButton
import kotlinx.coroutines.delay
import java.security.SecureRandom

data class ShellDaemonResult(
    val success: Boolean,
    val message: String,
)

class ShellDaemonBootstrapper(context: Context) {
    private val adbProcessManager = AdbProcessManager(context)
    private val packageCodePath = context.packageCodePath

    suspend fun launchDaemon(token: String, port: Int = DEFAULT_PORT): ShellDaemonResult {
        val serial = adbProcessManager.ensureTargetConnected(FIXED_TARGET_SERIAL)
            ?: return ShellDaemonResult(false, "No reachable on-device ADB target found")
        runShell(
            serial = serial,
            shellCommand = "pkill -f $DAEMON_CLASS || true",
            timeoutMs = 1_500L,
        )
        val apkPath = packageCodePath.takeIf { it.isNotBlank() }
            ?: return ShellDaemonResult(false, "Could not resolve APK path from context")

        val escapedToken = token.replace("'", "'\\''")
        val launchCommand =
            "CLASSPATH='$apkPath' nohup app_process /system/bin $DAEMON_CLASS --port $port --token '$escapedToken' >/dev/null 2>&1 &"
        val launchResult = runShell(
            serial = serial,
            shellCommand = launchCommand,
            timeoutMs = 2_500L,
        )
        if (launchResult.exitCode != 0) {
            return ShellDaemonResult(false, "Launch command failed: ${launchResult.output}")
        }
        repeat(15) {
            val ping = runCatching { ShellDaemonSocketClient.ping(port = port, token = token) }.getOrNull()
            if (ping != null) {
                return ShellDaemonResult(true, "Daemon responded: $ping")
            }
            delay(200L)
        }
        return ShellDaemonResult(false, "Daemon launch did not become reachable")
    }

    suspend fun pingDaemon(token: String, port: Int = DEFAULT_PORT): String {
        return ShellDaemonSocketClient.ping(port = port, token = token)
    }

    suspend fun executeMove(
        token: String,
        dx: Int,
        dy: Int,
        hidMoveChunkSize: Int,
        mouseAccelerationEnabled: Boolean,
        port: Int = DEFAULT_PORT,
    ): String {
        return ShellDaemonSocketClient.executeMove(
            port = port,
            token = token,
            dx = dx,
            dy = dy,
            hidMoveChunkSize = hidMoveChunkSize,
            mouseAccelerationEnabled = mouseAccelerationEnabled,
        )
    }

    suspend fun executeButton(
        token: String,
        button: MouseButton,
        isDown: Boolean,
        port: Int = DEFAULT_PORT,
    ): String {
        return ShellDaemonSocketClient.executeButton(
            port = port,
            token = token,
            button = button,
            isDown = isDown,
        )
    }

    suspend fun executeClick(
        token: String,
        button: MouseButton,
        port: Int = DEFAULT_PORT,
    ): String {
        return ShellDaemonSocketClient.executeClick(
            port = port,
            token = token,
            button = button,
        )
    }

    suspend fun executeScrollWheel(
        token: String,
        vWheel: Int,
        hWheel: Int,
        port: Int = DEFAULT_PORT,
    ): String {
        return ShellDaemonSocketClient.executeScrollWheel(
            port = port,
            token = token,
            vWheel = vWheel,
            hWheel = hWheel,
        )
    }

    suspend fun queryCursorGroundTruth(token: String, port: Int = DEFAULT_PORT): CursorGroundTruth {
        return ShellDaemonSocketClient.queryCursorGroundTruth(port = port, token = token)
    }

    suspend fun queryAutoRotateEnabled(token: String, port: Int = DEFAULT_PORT): Boolean {
        return ShellDaemonSocketClient.queryAutoRotateEnabled(port = port, token = token)
    }

    suspend fun setAutoRotateEnabled(token: String, enabled: Boolean, port: Int = DEFAULT_PORT): Boolean {
        return ShellDaemonSocketClient.setAutoRotateEnabled(port = port, token = token, enabled = enabled)
    }

    suspend fun stopDaemon(token: String, port: Int = DEFAULT_PORT): ShellDaemonResult {
        val stopReply = runCatching {
            ShellDaemonSocketClient.stop(port = port, token = token)
        }.getOrNull()
        if (stopReply != null) {
            return ShellDaemonResult(true, stopReply)
        }
        val serial = adbProcessManager.ensureTargetConnected(FIXED_TARGET_SERIAL)
            ?: return ShellDaemonResult(false, "Shell daemon unreachable and no reachable on-device ADB target found")
        runShell(
            serial = serial,
            shellCommand = "pkill -f $DAEMON_CLASS || true",
            timeoutMs = 1_500L,
        )
        return ShellDaemonResult(true, "Shell daemon process terminated")
    }

    private suspend fun runShell(
        serial: String,
        shellCommand: String,
        timeoutMs: Long,
    ): AdbProcessResult {
        val adbBinary = adbProcessManager.ensureAdbBinary()
            ?: return AdbProcessResult(exitCode = -1, output = "adb missing")
        return adbProcessManager.runProcess(
            args = listOf(adbBinary.absolutePath, "-s", serial, "shell", shellCommand),
            timeoutMs = timeoutMs,
        )
    }

    companion object {
        const val DEFAULT_PORT = 53_536
        private const val FIXED_TARGET_SERIAL = "127.0.0.1:5555"
        private const val DAEMON_CLASS = "com.alex.touchpad.backend.TouchpadShellDaemonMain"

        fun newToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
