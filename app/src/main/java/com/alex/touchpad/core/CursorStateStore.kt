package com.alex.touchpad.core

import kotlin.math.max
import kotlin.math.min

data class AppliedDelta(
    val dx: Int,
    val dy: Int,
    val logicalX: Float,
    val logicalY: Float,
)

class CursorStateStore {
    private var logicalX = 0f
    private var logicalY = 0f
    private var maxX = 1080f
    private var maxY = 2400f

    @Synchronized
    fun updateBounds(width: Int, height: Int) {
        maxX = max(1f, width.toFloat())
        maxY = max(1f, height.toFloat())
        logicalX = logicalX.coerceIn(0f, maxX)
        logicalY = logicalY.coerceIn(0f, maxY)
    }

    @Synchronized
    fun applyDelta(requestDx: Int, requestDy: Int): AppliedDelta {
        val nextX = (logicalX + requestDx).coerceIn(0f, maxX)
        val nextY = (logicalY + requestDy).coerceIn(0f, maxY)
        val appliedDx = (nextX - logicalX).toInt()
        val appliedDy = (nextY - logicalY).toInt()
        logicalX = nextX
        logicalY = nextY
        return AppliedDelta(appliedDx, appliedDy, logicalX, logicalY)
    }

    @Synchronized
    fun resetToCenter() {
        logicalX = maxX / 2f
        logicalY = maxY / 2f
    }

    @Synchronized
    fun snapshot(): Pair<Float, Float> = logicalX to logicalY

    @Synchronized
    fun boundsSnapshot(): Pair<Float, Float> = maxX to maxY
}
