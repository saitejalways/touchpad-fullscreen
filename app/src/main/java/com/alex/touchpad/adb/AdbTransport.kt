package com.alex.touchpad.adb

import com.alex.touchpad.core.AppLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

interface AdbTransport {
    val isConnected: StateFlow<Boolean>

    suspend fun connect(host: String, port: Int): Boolean
    fun disconnect()
    suspend fun ping(): Boolean
    suspend fun send(command: AdbCommand): Boolean
}

class PlainTextSocketAdbTransport : AdbTransport {
    private val mutex = Mutex()

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    override suspend fun connect(host: String, port: Int): Boolean = mutex.withLock {
        if (_isConnected.value) {
            Log.i(TAG, "connect skipped; already connected")
            return@withLock true
        }

        return@withLock withContext(Dispatchers.IO) {
            runCatching {
                val newSocket = Socket()
                newSocket.tcpNoDelay = true
                newSocket.keepAlive = true
                newSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                newSocket.soTimeout = ACK_TIMEOUT_MS
                val newReader = BufferedReader(InputStreamReader(newSocket.getInputStream(), Charsets.UTF_8))
                val newWriter = BufferedWriter(OutputStreamWriter(newSocket.getOutputStream(), Charsets.UTF_8))

                socket = newSocket
                reader = newReader
                writer = newWriter
                _isConnected.value = true
                Log.i(TAG, "socket connected host=$host port=$port")
                true
            }.getOrElse { error ->
                Log.w(TAG, "connect failed host=$host port=$port", error)
                closeResourcesLocked()
                false
            }
        }
    }

    override fun disconnect() {
        Log.i(TAG, "disconnect called")
        closeResourcesLocked()
    }

    override suspend fun ping(): Boolean {
        return writeLine("PING 0 ${System.currentTimeMillis()}")
    }

    override suspend fun send(command: AdbCommand): Boolean {
        val line = command.toProtocolLine()
        return writeLine(line)
    }

    private suspend fun writeLine(line: String): Boolean = mutex.withLock {
        if (!_isConnected.value) {
            return@withLock false
        }

        return@withLock withContext(Dispatchers.IO) {
            runCatching {
                val currentReader = reader ?: return@runCatching false
                val currentWriter = writer ?: return@runCatching false
                currentWriter.write(line)
                currentWriter.newLine()
                currentWriter.flush()
                val ack = currentReader.readLine()?.trim()
                val ok = ack?.startsWith("OK") == true
                if (!ok) {
                    Log.w(TAG, "command ack failure line=$line ack=$ack")
                }
                ok
            }.getOrElse { error ->
                Log.w(TAG, "write failed line=$line", error)
                closeResourcesLocked()
                false
            }
        }
    }

    private fun closeResourcesLocked() {
        runCatching { reader?.close() }
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        reader = null
        writer = null
        socket = null
        _isConnected.value = false
    }

    private fun AdbCommand.toProtocolLine(): String {
        return when (this) {
            is AdbCommand.MoveRel -> "MOVE_REL $dx $dy $seq $ts"
            is AdbCommand.ButtonDown -> "BUTTON_DOWN ${button.name} $seq $ts"
            is AdbCommand.ButtonUp -> "BUTTON_UP ${button.name} $seq $ts"
            is AdbCommand.Click -> "CLICK ${button.name} $seq $ts"
            is AdbCommand.Scroll -> "SCROLL $dy $dx $seq $ts"
            is AdbCommand.ScrollWheel -> "SCROLL $vWheel $hWheel $seq $ts"
            is AdbCommand.TouchContact -> "TOUCH_CONTACT ${if (active) 1 else 0} $seq $ts"
            is AdbCommand.TouchDelta -> "TOUCH_DELTA $dx $dy $seq $ts"
            is AdbCommand.Ping -> "PING $seq $ts"
            is AdbCommand.Pair -> "PAIR $host $port $code $seq $ts"
        }
    }

    private companion object {
        const val TAG = "AdbTransport"
        const val CONNECT_TIMEOUT_MS = 1500
        const val ACK_TIMEOUT_MS = 2000
    }
}
