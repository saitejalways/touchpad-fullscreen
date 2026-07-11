package com.alex.touchpad.backend

import com.alex.touchpad.input.MouseButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

object ShellDaemonSocketClient {
    suspend fun ping(port: Int, token: String): String {
        return exchange(port = port, token = token, command = "PING", ioTimeoutMs = DEFAULT_IO_TIMEOUT_MS)
    }

    suspend fun executeMove(
        port: Int,
        token: String,
        dx: Int,
        dy: Int,
        hidMoveChunkSize: Int,
        mouseAccelerationEnabled: Boolean,
    ): String {
        val reply = exchange(
            port = port,
            token = token,
            command = "MOVE_REL $dx $dy $hidMoveChunkSize ${if (mouseAccelerationEnabled) 1 else 0}",
            ioTimeoutMs = DEFAULT_IO_TIMEOUT_MS,
        )
        require(reply.startsWith("OK")) { "Unexpected move reply: $reply" }
        return reply
    }

    suspend fun executeButton(
        port: Int,
        token: String,
        button: MouseButton,
        isDown: Boolean,
    ): String {
        val reply = exchange(
            port = port,
            token = token,
            command = "BUTTON ${button.name} ${if (isDown) 1 else 0}",
            ioTimeoutMs = DEFAULT_IO_TIMEOUT_MS,
        )
        require(reply.startsWith("OK")) { "Unexpected button reply: $reply" }
        return reply
    }

    suspend fun executeClick(
        port: Int,
        token: String,
        button: MouseButton,
    ): String {
        val reply = exchange(
            port = port,
            token = token,
            command = "CLICK ${button.name}",
            ioTimeoutMs = DEFAULT_IO_TIMEOUT_MS,
        )
        require(reply.startsWith("OK")) { "Unexpected click reply: $reply" }
        return reply
    }

    suspend fun executeScrollWheel(
        port: Int,
        token: String,
        vWheel: Int,
        hWheel: Int,
    ): String {
        val reply = exchange(
            port = port,
            token = token,
            command = "SCROLL_WHEEL $vWheel $hWheel",
            ioTimeoutMs = DEFAULT_IO_TIMEOUT_MS,
        )
        require(reply.startsWith("OK")) { "Unexpected scroll reply: $reply" }
        return reply
    }

    suspend fun queryCursorGroundTruth(port: Int, token: String): CursorGroundTruth {
        val reply = exchange(
            port = port,
            token = token,
            command = "QUERY_CURSOR_GROUND_TRUTH",
            ioTimeoutMs = CURSOR_QUERY_IO_TIMEOUT_MS,
        )
        val parts = reply.split(' ')
        require(parts.size >= 7 && parts[0] == "OK" && parts[1] == "CURSOR") { "Unexpected cursor reply: $reply" }
        return CursorGroundTruth(
            x = parts[2].toFloat(),
            y = parts[3].toFloat(),
            widthPx = parts[4].toInt(),
            heightPx = parts[5].toInt(),
            sampledAtMs = parts[6].toLong(),
        )
    }

    suspend fun queryAutoRotateEnabled(port: Int, token: String): Boolean {
        val reply = exchange(
            port = port,
            token = token,
            command = "QUERY_AUTO_ROTATE",
            ioTimeoutMs = DEFAULT_IO_TIMEOUT_MS,
        )
        val parts = reply.split(' ')
        require(parts.size >= 3 && parts[0] == "OK" && parts[1] == "AUTO_ROTATE") { "Unexpected auto-rotate reply: $reply" }
        return when (parts[2]) {
            "1" -> true
            "0" -> false
            else -> error("Unexpected auto-rotate value: $reply")
        }
    }

    suspend fun setAutoRotateEnabled(port: Int, token: String, enabled: Boolean): Boolean {
        val reply = exchange(
            port = port,
            token = token,
            command = "SET_AUTO_ROTATE ${if (enabled) 1 else 0}",
            ioTimeoutMs = DEFAULT_IO_TIMEOUT_MS,
        )
        val parts = reply.split(' ')
        require(parts.size >= 3 && parts[0] == "OK" && parts[1] == "AUTO_ROTATE") { "Unexpected auto-rotate set reply: $reply" }
        return when (parts[2]) {
            "1" -> true
            "0" -> false
            else -> error("Unexpected auto-rotate set value: $reply")
        }
    }

    suspend fun stop(port: Int, token: String): String {
        return exchange(port = port, token = token, command = "STOP", ioTimeoutMs = DEFAULT_IO_TIMEOUT_MS)
    }

    private suspend fun exchange(
        port: Int,
        token: String,
        command: String,
        ioTimeoutMs: Int,
    ): String = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = ioTimeoutMs
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            writer.write("AUTH $token")
            writer.newLine()
            writer.flush()
            val authReply = reader.readLine()?.trim().orEmpty()
            require(authReply.startsWith("OK")) { "Auth failed: $authReply" }
            writer.write(command)
            writer.newLine()
            writer.flush()
            val reply = reader.readLine()?.trim().orEmpty()
            require(reply.isNotBlank()) { "Empty reply for $command" }
            reply
        }
    }

    private const val CONNECT_TIMEOUT_MS = 800
    private const val DEFAULT_IO_TIMEOUT_MS = 1_000
    private const val CURSOR_QUERY_IO_TIMEOUT_MS = 4_500
}
