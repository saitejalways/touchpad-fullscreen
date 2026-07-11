package com.alex.daemonspike

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

object DaemonSocketClient {
    suspend fun ping(port: Int, token: String): String {
        return exchange(port = port, token = token, command = "PING")
    }

    suspend fun stop(port: Int, token: String): String {
        return exchange(port = port, token = token, command = "STOP")
    }

    private suspend fun exchange(port: Int, token: String, command: String): String = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = IO_TIMEOUT_MS
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
    private const val IO_TIMEOUT_MS = 1_000
}
