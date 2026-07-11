package com.alex.touchpad.backend

import com.alex.touchpad.input.MouseButton
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ShellDaemonRuntimeClientTest {

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

            assertEquals(1, server.acceptedConnectionCount.get())
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
            assertTrue(server.acceptedConnectionCount.get() >= 2)
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun dragSequence_reusesSingleAuthenticatedConnectionAndPreservesCommandOrder() = runBlocking {
        val server = FakeShellDaemonServer(token = TEST_TOKEN)
        val client = ShellDaemonRuntimeClient()

        try {
            client.executeButton(
                port = server.port,
                token = TEST_TOKEN,
                button = MouseButton.LEFT,
                isDown = true,
            )
            client.executeMove(
                port = server.port,
                token = TEST_TOKEN,
                dx = 14,
                dy = 6,
                hidMoveChunkSize = 8,
                mouseAccelerationEnabled = false,
            )
            client.executeMove(
                port = server.port,
                token = TEST_TOKEN,
                dx = -9,
                dy = 3,
                hidMoveChunkSize = 8,
                mouseAccelerationEnabled = false,
            )
            client.executeButton(
                port = server.port,
                token = TEST_TOKEN,
                button = MouseButton.LEFT,
                isDown = false,
            )

            server.awaitCommands(count = 4)

            assertEquals(1, server.acceptedConnectionCount.get())
            assertEquals(
                listOf(
                    "BUTTON LEFT 1",
                    "MOVE_REL 14 6 8 0",
                    "MOVE_REL -9 3 8 0",
                    "BUTTON LEFT 0",
                ),
                server.receivedCommands.take(4),
            )
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun dragSequence_doesNotBlockSeparatePingClientWhileButtonHeld() = runBlocking {
        val server = FakeShellDaemonServer(token = TEST_TOKEN)
        val client = ShellDaemonRuntimeClient()

        try {
            client.executeButton(
                port = server.port,
                token = TEST_TOKEN,
                button = MouseButton.LEFT,
                isDown = true,
            )
            client.executeMove(
                port = server.port,
                token = TEST_TOKEN,
                dx = 20,
                dy = 0,
                hidMoveChunkSize = 8,
                mouseAccelerationEnabled = false,
            )
            server.awaitCommands(count = 2)

            val pingReply = ShellDaemonSocketClient.ping(
                port = server.port,
                token = TEST_TOKEN,
            )

            client.executeButton(
                port = server.port,
                token = TEST_TOKEN,
                button = MouseButton.LEFT,
                isDown = false,
            )
            server.awaitCommands(count = 3)

            assertTrue(pingReply.startsWith("OK PONG"))
            assertTrue(server.acceptedConnectionCount.get() >= 2)
            assertEquals(
                listOf(
                    "BUTTON LEFT 1",
                    "MOVE_REL 20 0 8 0",
                    "BUTTON LEFT 0",
                ),
                server.receivedCommands.filter { it != "PING" }.take(3),
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
        private val running = AtomicBoolean(true)
        val port: Int = serverSocket.localPort
        val acceptedConnectionCount = AtomicInteger(0)
        val receivedCommands = CopyOnWriteArrayList<String>()
        private val commandSignal = CountDownLatch(1)

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
                commandSignal.await(50, TimeUnit.MILLISECONDS)
            }
            assertTrue("Timed out waiting for $count commands, got ${receivedCommands.size}", receivedCommands.size >= count)
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
                    commandSignal.countDown()
                    when {
                        trimmed == "PING" -> writer.write("OK PONG pid=1 uid=2000 versionCode=2")
                        trimmed.startsWith("MOVE_REL ") -> writer.write("OK move")
                        trimmed.startsWith("BUTTON ") -> writer.write("OK button")
                        trimmed.startsWith("CLICK ") -> writer.write("OK click")
                        trimmed.startsWith("SCROLL_WHEEL ") -> writer.write("OK scroll")
                        trimmed == "QUERY_CURSOR_GROUND_TRUTH" -> writer.write("OK CURSOR 100.0 200.0 1440 3088 123456")
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
