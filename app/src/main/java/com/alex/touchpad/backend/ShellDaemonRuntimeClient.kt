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

class ShellDaemonRuntimeClient {
    private val lock = Any()
    private var channel: Channel? = null

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
        )
        require(reply.startsWith("OK")) { "Unexpected scroll reply: $reply" }
        return reply
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            closeLocked()
        }
    }

    private suspend fun exchange(
        port: Int,
        token: String,
        command: String,
    ): String = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val activeChannel = ensureChannelLocked(port = port, token = token)
            try {
                activeChannel.writer.write(command)
                activeChannel.writer.newLine()
                activeChannel.writer.flush()
                val reply = activeChannel.reader.readLine()?.trim().orEmpty()
                require(reply.isNotBlank()) { "Empty reply for $command" }
                reply
            } catch (error: Throwable) {
                closeLocked()
                throw error
            }
        }
    }

    private fun ensureChannelLocked(port: Int, token: String): Channel {
        val existing = channel
        if (
            existing != null &&
            existing.port == port &&
            existing.token == token &&
            !existing.socket.isClosed &&
            existing.socket.isConnected
        ) {
            return existing
        }

        closeLocked()

        val socket = Socket()
        socket.connect(InetSocketAddress(LOOPBACK_HOST, port), CONNECT_TIMEOUT_MS)
        socket.soTimeout = IO_TIMEOUT_MS
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        writer.write("AUTH $token")
        writer.newLine()
        writer.flush()
        val authReply = reader.readLine()?.trim().orEmpty()
        require(authReply.startsWith("OK")) { "Auth failed: $authReply" }

        return Channel(
            port = port,
            token = token,
            socket = socket,
            reader = reader,
            writer = writer,
        ).also { channel = it }
    }

    private fun closeLocked() {
        val active = channel ?: return
        channel = null
        runCatching { active.writer.close() }
        runCatching { active.reader.close() }
        runCatching { active.socket.close() }
    }

    private data class Channel(
        val port: Int,
        val token: String,
        val socket: Socket,
        val reader: BufferedReader,
        val writer: BufferedWriter,
    )

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val CONNECT_TIMEOUT_MS = 800
        const val IO_TIMEOUT_MS = 1_000
    }
}
