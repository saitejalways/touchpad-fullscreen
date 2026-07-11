package com.alex.daemonspike

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.system.exitProcess

object ShellProbeDaemonMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = parseArgs(args) ?: exitProcess(2)
        val serverSocket = ServerSocket(config.port, 8, InetAddress.getByName("127.0.0.1"))
        while (true) {
            val client = serverSocket.accept()
            if (!handleClient(client, config.token)) {
                serverSocket.close()
                exitProcess(0)
            }
        }
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
                when (reader.readLine()?.trim().orEmpty()) {
                    "PING" -> {
                        writer.write("OK PONG pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()}")
                        writer.newLine()
                        writer.flush()
                    }
                    "STOP" -> {
                        writer.write("OK stopping")
                        writer.newLine()
                        writer.flush()
                        return false
                    }
                    "" -> return true
                    else -> {
                        writer.write("ERR command")
                        writer.newLine()
                        writer.flush()
                    }
                }
            }
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
}
