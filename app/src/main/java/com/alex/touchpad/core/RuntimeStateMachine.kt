package com.alex.touchpad.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RuntimeStateMachine {
    private var adbConnected = false
    private var overlayCapturing = false
    private var faulted = false

    private val _mode = MutableStateFlow(AppMode.DISCONNECTED)
    val mode: StateFlow<AppMode> = _mode.asStateFlow()

    fun onAdbConnectionChanged(connected: Boolean) {
        adbConnected = connected
        if (!connected) {
            faulted = false
            overlayCapturing = false
        }
        recompute()
    }

    fun onOverlayCaptureChanged(active: Boolean) {
        overlayCapturing = active
        recompute()
    }

    fun onFaultDetected() {
        faulted = true
        recompute()
    }

    fun onFaultRecovered() {
        faulted = false
        recompute()
    }

    private fun recompute() {
        _mode.value = when {
            faulted -> AppMode.FAULT
            !adbConnected -> AppMode.DISCONNECTED
            overlayCapturing -> AppMode.ACTIVE
            else -> AppMode.ARMED
        }
    }
}
