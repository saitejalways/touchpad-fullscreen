package com.alex.touchpad.backend

import android.os.SystemClock
import android.util.Log
import com.alex.touchpad.BuildConfig
import com.alex.touchpad.input.MouseButton
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

object TouchpadShellDaemonMain {
    private val hidWriter = ShellHidDeviceWriter()
    @Volatile
    private var running = true
    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var lastGroundTruth: CursorGroundTruth? = null
    @Volatile
    private var samplerDemandUntilMs: Long = 0L

    @JvmStatic
    fun main(args: Array<String>) {
        val config = parseArgs(args) ?: exitProcess(2)
        running = true
        val boundServerSocket = ServerSocket(config.port, 8, InetAddress.getByName("127.0.0.1"))
        serverSocket = boundServerSocket
        startSamplerThread()
        while (running) {
            val client = runCatching { boundServerSocket.accept() }.getOrNull() ?: break
            thread(
                start = true,
                isDaemon = true,
                name = "touchpad-shell-daemon-client",
            ) {
                if (!handleClient(client, config.token)) {
                    shutdownDaemon()
                }
            }
        }
        runCatching { boundServerSocket.close() }
        exitProcess(0)
    }

    private fun handleClient(socket: Socket, token: String): Boolean {
        socket.use { client ->
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8))
            val authLine = reader.readLine()?.trim().orEmpty()
            if (authLine != "AUTH $token") {
                writer.write("ERR auth")
                writer.newLine()
                writer.flush()
                return true
            }
            writer.write("OK auth")
            writer.newLine()
            writer.flush()
            while (true) {
                val command = reader.readLine()?.trim().orEmpty()
                when {
                    command == "PING" -> {
                        writer.write(
                            "OK PONG pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()} " +
                                "versionCode=${BuildConfig.VERSION_CODE}",
                        )
                        writer.newLine()
                        writer.flush()
                    }
                    command == "QUERY_CURSOR_GROUND_TRUTH" -> {
                        val groundTruth = queryCursorGroundTruth()
                        if (groundTruth == null) {
                            writer.write("ERR no_cursor_ground_truth")
                        } else {
                            writer.write(
                                "OK CURSOR ${groundTruth.x} ${groundTruth.y} " +
                                    "${groundTruth.widthPx} ${groundTruth.heightPx} ${groundTruth.sampledAtMs}",
                            )
                        }
                        writer.newLine()
                        writer.flush()
                    }
                    command == "QUERY_AUTO_ROTATE" -> {
                        val enabled = queryAutoRotateEnabled()
                        writer.write(
                            if (enabled == null) {
                                "ERR auto_rotate_query_failed"
                            } else {
                                "OK AUTO_ROTATE ${if (enabled) 1 else 0}"
                            },
                        )
                        writer.newLine()
                        writer.flush()
                    }
                    command == "STOP" -> {
                        writer.write("OK stopping")
                        writer.newLine()
                        writer.flush()
                        return false
                    }
                    command.startsWith("MOVE_REL ") -> {
                        val parts = command.split(' ')
                        val dx = parts.getOrNull(1)?.toIntOrNull()
                        val dy = parts.getOrNull(2)?.toIntOrNull()
                        val hidMoveChunkSize = parts.getOrNull(3)?.toIntOrNull()
                        val mouseAccelerationEnabled = parts.getOrNull(4) == "1"
                        val success =
                            dx != null &&
                                dy != null &&
                                hidMoveChunkSize != null &&
                                hidWriter.executeMove(
                                    dx = dx,
                                    dy = dy,
                                    hidMoveChunkSize = hidMoveChunkSize,
                                    mouseAccelerationEnabled = mouseAccelerationEnabled,
                                )
                        writer.write(if (success) "OK move" else "ERR move_failed")
                        writer.newLine()
                        writer.flush()
                    }
                    command.startsWith("BUTTON ") -> {
                        val parts = command.split(' ')
                        val button = parts.getOrNull(1)?.toMouseButton()
                        val isDown = when (parts.getOrNull(2)) {
                            "1" -> true
                            "0" -> false
                            else -> null
                        }
                        val success =
                            button != null &&
                                isDown != null &&
                                hidWriter.executeButton(button = button, isDown = isDown)
                        writer.write(if (success) "OK button" else "ERR button_failed")
                        writer.newLine()
                        writer.flush()
                    }
                    command.startsWith("SET_AUTO_ROTATE ") -> {
                        val enabled = when (command.substringAfter("SET_AUTO_ROTATE ").trim()) {
                            "1" -> true
                            "0" -> false
                            else -> null
                        }
                        val success = enabled != null && setAutoRotateEnabled(enabled)
                        writer.write(
                            if (success && enabled != null) {
                                "OK AUTO_ROTATE ${if (enabled) 1 else 0}"
                            } else {
                                "ERR auto_rotate_set_failed"
                            },
                        )
                        writer.newLine()
                        writer.flush()
                    }
                    command.startsWith("CLICK ") -> {
                        val parts = command.split(' ')
                        val button = parts.getOrNull(1)?.toMouseButton()
                        val success = button != null && hidWriter.executeClick(button)
                        writer.write(if (success) "OK click" else "ERR click_failed")
                        writer.newLine()
                        writer.flush()
                    }
                    command.startsWith("SCROLL_WHEEL ") -> {
                        val parts = command.split(' ')
                        val vWheel = parts.getOrNull(1)?.toIntOrNull()
                        val hWheel = parts.getOrNull(2)?.toIntOrNull()
                        val success = vWheel != null && hWheel != null && hidWriter.executeScroll(vWheel, hWheel)
                        writer.write(if (success) "OK scroll" else "ERR scroll_failed")
                        writer.newLine()
                        writer.flush()
                    }
                    command.isEmpty() -> return true
                    else -> {
                        writer.write("ERR command")
                        writer.newLine()
                        writer.flush()
                    }
                }
            }
        }
    }

    private fun shutdownDaemon() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        exitProcess(0)
    }

    private fun startSamplerThread() {
        thread(
            start = true,
            isDaemon = true,
            name = "touchpad-cursor-sampler",
        ) {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val active = now <= samplerDemandUntilMs
                if (active || lastGroundTruth == null) {
                    sampleCursorGroundTruth()?.let { sample ->
                        lastGroundTruth = sample
                    }
                    Thread.sleep(if (active) ACTIVE_SAMPLER_INTERVAL_MS else IDLE_SAMPLER_RETRY_MS)
                } else {
                    Thread.sleep(IDLE_SAMPLER_SLEEP_MS)
                }
            }
        }
    }

    private fun markSamplerDemand(nowMs: Long = SystemClock.elapsedRealtime()) {
        val nextUntil = nowMs + SAMPLER_ACTIVE_WINDOW_MS
        if (nextUntil > samplerDemandUntilMs) {
            samplerDemandUntilMs = nextUntil
        }
    }

    private fun queryCursorGroundTruth(): CursorGroundTruth? {
        markSamplerDemand()
        freshCachedGroundTruth()?.let { return it }
        sampleCursorGroundTruth()?.let { sample ->
            lastGroundTruth = sample
            return sample
        }
        return lastGroundTruth
    }

    private fun queryAutoRotateEnabled(): Boolean? {
        val result = runProcess(
            args = listOf(SYSTEM_SH, "-c", QUERY_AUTO_ROTATE_COMMAND),
            timeoutMs = PROCESS_TIMEOUT_SYSTEM_SETTING_MS,
        )
        val normalized = result.output
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it == "0" || it == "1" }
        return when (normalized) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }

    private fun setAutoRotateEnabled(enabled: Boolean): Boolean {
        val result = runProcess(
            args = listOf(SYSTEM_SH, "-c", "settings put system accelerometer_rotation ${if (enabled) 1 else 0}"),
            timeoutMs = PROCESS_TIMEOUT_SYSTEM_SETTING_MS,
        )
        if (result.exitCode != 0) {
            return false
        }
        return queryAutoRotateEnabled() == enabled
    }

    private fun freshCachedGroundTruth(nowMs: Long = SystemClock.elapsedRealtime()): CursorGroundTruth? {
        val cached = lastGroundTruth ?: return null
        val ageMs = nowMs - cached.sampledAtMs
        return if (ageMs in 0..FRESH_GROUND_TRUTH_MAX_AGE_MS) cached else null
    }

    private fun sampleCursorGroundTruth(): CursorGroundTruth? {
        val fastResult = runProcess(
            args = listOf(SYSTEM_SH, "-c", CursorGroundTruthParser.FAST_POINTER_DUMPSYS_COMMAND),
            timeoutMs = PROCESS_TIMEOUT_POINTER_DUMPSYS_MS,
        )
        if (fastResult.output.isNotBlank()) {
            CursorGroundTruthParser.parseGroundTruthFromDumpsys(fastResult.output)?.let { groundTruth ->
                return groundTruth
            }
            Log.w(TAG, "fast dumpsys parse failed")
        }

        val fullResult = runProcess(
            args = listOf(SYSTEM_SH, "-c", FULL_POINTER_DUMPSYS_COMMAND),
            timeoutMs = PROCESS_TIMEOUT_FULL_POINTER_DUMPSYS_MS,
        )
        if (fullResult.output.isNotBlank()) {
            CursorGroundTruthParser.parseGroundTruthFromDumpsys(fullResult.output)?.let { groundTruth ->
                return groundTruth
            }
            Log.w(TAG, "full dumpsys parse failed")
        }
        return null
    }

    private fun runProcess(
        args: List<String>,
        timeoutMs: Long,
    ): ProcessResult {
        return runCatching {
            val process = ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val outputReader = thread(
                start = true,
                isDaemon = true,
                name = "touchpad-shell-daemon-process-output",
            ) {
                runCatching {
                    process.inputStream.bufferedReader().use { reader ->
                        val buffer = CharArray(PROCESS_READ_BUFFER_CHARS)
                        while (true) {
                            val read = reader.read(buffer)
                            if (read <= 0) {
                                break
                            }
                            synchronized(output) {
                                output.append(buffer, 0, read)
                            }
                        }
                    }
                }
            }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { process.destroy() }
                process.waitFor(150L, TimeUnit.MILLISECONDS)
                if (process.isAlive) {
                    runCatching { process.destroyForcibly() }
                }
                outputReader.join(PROCESS_READER_JOIN_MS)
                return@runCatching ProcessResult(exitCode = -2, output = "timeout")
            }
            outputReader.join(PROCESS_READER_JOIN_MS)
            ProcessResult(
                exitCode = process.exitValue(),
                output = synchronized(output) { output.toString().trim() },
            )
        }.getOrElse { error ->
            ProcessResult(exitCode = -1, output = error.message.orEmpty())
        }
    }

    private fun parseArgs(args: Array<String>): Config? {
        var port: Int? = null
        var token: String? = null
        var index = 0
        while (index < args.size) {
            when (args[index]) {
                "--port" -> port = args.getOrNull(index + 1)?.toIntOrNull()
                "--token" -> token = args.getOrNull(index + 1)
            }
            index += 2
        }
        val finalPort = port ?: return null
        val finalToken = token?.takeIf { it.isNotBlank() } ?: return null
        return Config(port = finalPort, token = finalToken)
    }

    private data class Config(
        val port: Int,
        val token: String,
    )

    private data class ProcessResult(
        val exitCode: Int,
        val output: String,
    )

    private fun String.toMouseButton(): MouseButton? {
        return when (this) {
            "LEFT" -> MouseButton.LEFT
            "RIGHT" -> MouseButton.RIGHT
            "MIDDLE" -> MouseButton.MIDDLE
            else -> null
        }
    }

    private const val PROCESS_TIMEOUT_POINTER_DUMPSYS_MS = 1_500L
    private const val PROCESS_TIMEOUT_FULL_POINTER_DUMPSYS_MS = 3_000L
    private const val PROCESS_TIMEOUT_SYSTEM_SETTING_MS = 1_500L
    private const val FULL_POINTER_DUMPSYS_COMMAND = "dumpsys input"
    private const val QUERY_AUTO_ROTATE_COMMAND = "settings get system accelerometer_rotation"
    private const val SYSTEM_SH = "/system/bin/sh"
    private const val ACTIVE_SAMPLER_INTERVAL_MS = 40L
    private const val IDLE_SAMPLER_RETRY_MS = 250L
    private const val IDLE_SAMPLER_SLEEP_MS = 750L
    private const val SAMPLER_ACTIVE_WINDOW_MS = 2_000L
    private const val FRESH_GROUND_TRUTH_MAX_AGE_MS = 300L
    private const val PROCESS_READ_BUFFER_CHARS = 4_096
    private const val PROCESS_READER_JOIN_MS = 200L
    private const val TAG = "TouchpadShellDaemon"
}
