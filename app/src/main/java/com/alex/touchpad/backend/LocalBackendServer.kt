package com.alex.touchpad.backend

import com.alex.touchpad.core.AppLog as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

data class LocalBackendState(
    val running: Boolean,
    val port: Int?,
    val lastError: String?,
)

class LocalBackendServer(
    private val scope: CoroutineScope,
    private val executor: WireCommandExecutor,
) {
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null

    private val _state = MutableStateFlow(
        LocalBackendState(
            running = false,
            port = null,
            lastError = null,
        )
    )
    val state: StateFlow<LocalBackendState> = _state.asStateFlow()

    fun start(port: Int) {
        if (_state.value.running && _state.value.port == port) {
            return
        }

        Log.i(TAG, "start requested port=$port")
        stop()
        serverJob = scope.launch(Dispatchers.IO) {
            runCatching {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                serverSocket = socket
                _state.value = LocalBackendState(running = true, port = port, lastError = null)
                Log.i(TAG, "listening on 127.0.0.1:$port")

                while (isActive) {
                    val client = socket.accept()
                    Log.i(TAG, "client connected from=${client.inetAddress?.hostAddress}:${client.port}")
                    launch { handleClient(client) }
                }
            }.onFailure { error ->
                Log.w(TAG, "backend server failed", error)
                _state.value = LocalBackendState(
                    running = false,
                    port = port,
                    lastError = error.message ?: "Backend server error",
                )
            }

            closeServer()
        }
    }

    fun stop() {
        Log.i(TAG, "stop requested")
        serverJob?.cancel()
        serverJob = null
        closeServer()
        _state.value = _state.value.copy(running = false)
    }

    private fun closeServer() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun handleClient(client: Socket) {
        client.use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            val recentSeqs = LinkedHashSet<Long>()
            while (true) {
                val line = reader.readLine() ?: break
                val command = WireProtocolParser.parse(line)
                if (command == null) {
                    val error = "Invalid command: $line"
                    _state.value = _state.value.copy(lastError = error)
                    if (!writeAck(writer, success = false, detail = error)) {
                        break
                    }
                    continue
                }

                val seq = commandSeq(command)
                if (recentSeqs.contains(seq)) {
                    if (!writeAck(writer, success = true, detail = "DUP")) {
                        break
                    }
                    continue
                }

                val success = executor.execute(command)
                if (!success) {
                    val error = "Command failed: ${command::class.simpleName}"
                    _state.value = _state.value.copy(lastError = error)
                    if (!writeAck(writer, success = false, detail = error)) {
                        break
                    }
                    continue
                }

                rememberProcessedSeq(recentSeqs, seq)
                if (!writeAck(writer, success = true, detail = null)) {
                    break
                }
            }
        }
    }

    private fun rememberProcessedSeq(recentSeqs: LinkedHashSet<Long>, seq: Long) {
        recentSeqs.add(seq)
        while (recentSeqs.size > MAX_RECENT_SEQ_MEMORY) {
            val oldest = recentSeqs.iterator().next()
            recentSeqs.remove(oldest)
        }
    }

    private fun commandSeq(command: WireCommand): Long {
        return when (command) {
            is WireCommand.MoveRel -> command.seq
            is WireCommand.ButtonDown -> command.seq
            is WireCommand.ButtonUp -> command.seq
            is WireCommand.Click -> command.seq
            is WireCommand.Scroll -> command.seq
            is WireCommand.ScrollWheel -> command.seq
            is WireCommand.TouchContact -> command.seq
            is WireCommand.TouchDelta -> command.seq
            is WireCommand.Ping -> command.seq
            is WireCommand.Pair -> command.seq
        }
    }

    private fun writeAck(writer: BufferedWriter, success: Boolean, detail: String?): Boolean {
        val payload = if (success) {
            if (detail.isNullOrBlank()) "OK" else "OK $detail"
        } else {
            if (detail.isNullOrBlank()) "ERR" else "ERR $detail"
        }
        return runCatching {
            writer.write(payload)
            writer.newLine()
            writer.flush()
            true
        }.getOrElse { error ->
            Log.w(TAG, "Failed to write backend ack payload=$payload", error)
            false
        }
    }

    private companion object {
        const val TAG = "LocalBackendServer"
        const val MAX_RECENT_SEQ_MEMORY = 4_096
    }
}
