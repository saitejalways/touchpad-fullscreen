package com.alex.touchpad.backend

import com.alex.touchpad.input.MouseButton

interface DaemonRuntimeBridge {
    fun isActive(): Boolean

    suspend fun sendMove(
        dx: Int,
        dy: Int,
        hidMoveChunkSize: Int,
        mouseAccelerationEnabled: Boolean,
    ): Boolean

    suspend fun sendClick(button: MouseButton): Boolean

    suspend fun sendButton(button: MouseButton, isDown: Boolean): Boolean

    suspend fun sendScrollWheel(vWheel: Int, hWheel: Int): Boolean
}
