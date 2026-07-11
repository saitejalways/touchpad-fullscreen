package com.alex.touchpad.adb

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface SafetyEvent {
    data object FaultEntered : SafetyEvent
    data object FaultRecovered : SafetyEvent
}

class SafetyController(private val failureThreshold: Int = 3) {
    private var consecutiveFailures = 0
    private var inFault = false

    private val _events = MutableSharedFlow<SafetyEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SafetyEvent> = _events.asSharedFlow()

    fun onDispatchResult(success: Boolean) {
        if (success) {
            consecutiveFailures = 0
            if (inFault) {
                inFault = false
                _events.tryEmit(SafetyEvent.FaultRecovered)
            }
            return
        }

        consecutiveFailures += 1
        if (!inFault && consecutiveFailures >= failureThreshold) {
            inFault = true
            _events.tryEmit(SafetyEvent.FaultEntered)
        }
    }
}
