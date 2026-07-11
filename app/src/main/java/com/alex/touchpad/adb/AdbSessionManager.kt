package com.alex.touchpad.adb

import com.alex.touchpad.core.AppLog as Log
import com.alex.touchpad.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AdbSessionManager(
    private val scope: CoroutineScope,
    private val transport: AdbTransport,
    private val settingsRepository: SettingsRepository,
) {
    val isConnected: StateFlow<Boolean> = transport.isConnected

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var heartbeatJob: Job? = null

    suspend fun connect(): Boolean {
        val endpoint = settingsRepository.endpointConfig.value
        Log.i(TAG, "connect requested endpoint=${endpoint.host}:${endpoint.port}")
        val connected = transport.connect(endpoint.host, endpoint.port)
        if (connected) {
            _lastError.value = null
            Log.i(TAG, "connect success")
            startHeartbeat()
        } else {
            _lastError.value = "Connect failed (${endpoint.host}:${endpoint.port})"
            Log.w(TAG, "connect failed endpoint=${endpoint.host}:${endpoint.port}")
        }
        return connected
    }

    fun disconnect() {
        Log.i(TAG, "disconnect requested")
        heartbeatJob?.cancel()
        heartbeatJob = null
        transport.disconnect()
    }

    private fun startHeartbeat(intervalMs: Long = 2000L) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                if (!transport.ping()) {
                    _lastError.value = "Connection lost"
                    Log.w(TAG, "heartbeat ping failed; disconnecting")
                    transport.disconnect()
                    break
                }
            }
        }
    }

    private companion object {
        const val TAG = "AdbSessionManager"
    }
}
