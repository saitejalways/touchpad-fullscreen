package com.alex.touchpad.backend

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ShellDaemonRuntimeClientInstrumentedTest {

    @Test
    fun executeMove_reusesSingleAuthenticatedConnectionAcrossSequentialMoves() = runBlocking {
        val server = FakeShellDaemonServer(token = TEST_TOKEN)
        val client = ShellDaemonRuntimeClient()

        try {
            client.executeMove(
                port = server.port,
                token = TEST_TOKEN,
                dx = 12,
                dy = -3,
                hidMoveChunkSize = 8,
                mouseAccelerationEnabled = false,
            )
            client.executeMove(
                port = server.port,
                token = TEST_TOKEN,
                dx = -7,
                dy = 5,
                hidMoveChunkSize = 8,
                mouseAccelerationEnabled = true,
            )

            server.awaitCommands(count = 2)

            assertEquals(
                "Sequential drag/move traffic should stay on a single persistent daemon connection",
                1,
                server.acceptedConnectionCount.get(),
            )
            assertEquals(
                listOf(
                    "MOVE_REL 12 -3 8 0",
                    "MOVE_REL -7 5 8 1",
                ),
                server.receivedCommands.take(2),
            )
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun persistentMoveConnection_doesNotBlockSeparatePingClient() = runBlocking {
        val server = FakeShellDaemonServer(token = TEST_TOKEN)
        val client = ShellDaemonRuntimeClient()

        try {
            client.executeMove(
                port = server.port,
                token = TEST_TOKEN,
                dx = 5,
                dy = 0,
                hidMoveChunkSize = 8,
                mouseAccelerationEnabled = false,
            )
            server.awaitCommands(count = 1)

            val pingReply = ShellDaemonSocketClient.ping(
                port = server.port,
                token = TEST_TOKEN,
            )

            assertTrue(pingReply.startsWith("OK PONG"))
            assertTrue(
                "The daemon must accept a second client while the runtime move channel remains connected",
                server.acceptedConnectionCount.get() >= 2,
            )
        } finally {
            client.close()
            server.close()
        }
    }

    private class FakeShellDaemonServer(
        private val token: String,
    ) {
        private val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val port: Int = serverSocket.localPort
        val acceptedConnectionCount = AtomicInteger(0)
        val receivedCommands = CopyOnWriteArrayList<String>()
        private val commandLatch = CountDownLatch(1)

        private val acceptThread = thread(
            start = true,
            isDaemon = true,
            name = "fake-shell-daemon-accept",
        ) {
            while (running.get()) {
                val client = try {
                    serverSocket.accept()
                } catch (_: SocketException) {
                    break
                }
                acceptedConnectionCount.incrementAndGet()
                thread(
                    start = true,
                    isDaemon = true,
                    name = "fake-shell-daemon-client",
                ) {
                    handleClient(client)
                }
            }
        }

        fun awaitCommands(count: Int) {
            val deadlineMs = System.currentTimeMillis() + 2_000L
            while (receivedCommands.size < count && System.currentTimeMillis() < deadlineMs) {
                commandLatch.await(50, TimeUnit.MILLISECONDS)
            }
            assertTrue(
                "Timed out waiting for $count commands, got ${receivedCommands.size}",
                receivedCommands.size >= count,
            )
        }

        fun close() {
            running.set(false)
            runCatching { serverSocket.close() }
            acceptThread.join(500L)
        }

        private fun handleClient(socket: Socket) {
            socket.use { client ->
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8))
                val authLine = reader.readLine()?.trim().orEmpty()
                if (authLine != "AUTH $token") {
                    writer.write("ERR auth")
                    writer.newLine()
                    writer.flush()
                    return
                }
                writer.write("OK auth")
                writer.newLine()
                writer.flush()

                while (true) {
                    val command = reader.readLine() ?: return
                    val trimmed = command.trim()
                    receivedCommands += trimmed
                    commandLatch.countDown()
                    when {
                        trimmed == "PING" -> writer.write("OK PONG pid=1 uid=2000 versionCode=2")
                        trimmed.startsWith("MOVE_REL ") -> writer.write("OK move")
                        trimmed.startsWith("BUTTON ") -> writer.write("OK button")
                        trimmed.startsWith("CLICK ") -> writer.write("OK click")
                        trimmed.startsWith("SCROLL_WHEEL ") -> writer.write("OK scroll")
                        trimmed == "QUERY_CURSOR_GROUND_TRUTH" -> {
                            writer.write("OK CURSOR 100.0 200.0 1440 3088 123456")
                        }
                        trimmed == "STOP" -> writer.write("OK stopping")
                        else -> writer.write("ERR command")
                    }
                    writer.newLine()
                    writer.flush()
                }
            }
        }
    }

    private companion object {
        const val TEST_TOKEN = "daemon-test-token"
    }
}
