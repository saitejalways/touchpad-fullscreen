package com.alex.daemonspike

import android.content.Context
import java.net.Socket
import java.security.SecureRandom

data class BootstrapResult(
    val success: Boolean,
    val message: String,
)

class DaemonBootstrapper(context: Context) {
    private val adbProcessManager = SpikeAdbProcessManager(context)

    suspend fun inspectFixedTarget(): BootstrapResult {
        val details = adbProcessManager.inspectTarget(SpikeAdbProcessManager.FIXED_TARGET_SERIAL)
        return BootstrapResult(success = false, message = details)
    }

    suspend fun pairLocalhost(pairPort: Int, pairingCode: String): BootstrapResult {
        if (pairPort <= 0) {
            return BootstrapResult(false, "Invalid pairing port")
        }
        if (pairingCode.isBlank()) {
            return BootstrapResult(false, "Pairing code is required")
        }
        val result = adbProcessManager.pairLocalhost(pairPort = pairPort, pairingCode = pairingCode)
        if (result.exitCode == 0) {
            return BootstrapResult(true, result.output.ifBlank { "Pairing succeeded" })
        }
        return BootstrapResult(false, result.output.ifBlank { "Pairing failed" })
    }

    suspend fun launchDaemon(port: Int, token: String): BootstrapResult {
        val serial = adbProcessManager.resolveRuntimeSerial()
            ?: return BootstrapResult(false, "No connected wireless ADB runtime serial found")
        val apkPathResult = adbProcessManager.runShell(
            serial = serial,
            shellCommand = "pm path com.alex.daemonspike",
            timeoutMs = 1_500L,
        )
        val apkPath = apkPathResult.output
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("package:") }
            ?.removePrefix("package:")
            ?.trim()
            ?: return BootstrapResult(false, "Could not resolve APK path: ${apkPathResult.output}")

        val escapedToken = token.replace("'", "'\\''")
        val daemonClass = "com.alex.daemonspike.ShellProbeDaemonMain"
        val launchCommand =
            "CLASSPATH='$apkPath' nohup app_process /system/bin $daemonClass --port $port --token '$escapedToken' >/dev/null 2>&1 &"
        val launchResult = adbProcessManager.runShell(serial = serial, shellCommand = launchCommand, timeoutMs = 2_500L)
        if (launchResult.exitCode != 0) {
            return BootstrapResult(false, "Launch command failed: ${launchResult.output}")
        }
        repeat(15) {
            val ping = runCatching { DaemonSocketClient.ping(port = port, token = token) }.getOrNull()
            if (ping != null) {
                return BootstrapResult(true, "Daemon responded: $ping")
            }
            Thread.sleep(200)
        }
        return BootstrapResult(false, "Daemon launch did not become reachable")
    }

    suspend fun stopDaemon(port: Int, token: String): BootstrapResult {
        val reply = runCatching { DaemonSocketClient.stop(port = port, token = token) }
            .getOrElse { error -> return BootstrapResult(false, error.message ?: "stop failed") }
        return BootstrapResult(true, reply)
    }

    companion object {
        fun newToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
