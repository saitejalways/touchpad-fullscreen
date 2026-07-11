package com.alex.touchpad.backend

import android.os.SystemClock

object CursorGroundTruthParser {
    const val FAST_POINTER_DUMPSYS_COMMAND =
        "dumpsys input | grep -E 'Viewport INTERNAL|hoveringPointers|Pointer\\(id=[0-9]+, *MOUSE\\)'"

    private val hoverPointerRegex = Regex(
        "hoveringPointers=\\[Pointer\\(id=\\d+,\\s*MOUSE\\)\\s*at\\s*\\((-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)\\)",
    )
    private val genericMousePointerRegex = Regex(
        "Pointer\\(id=\\d+,\\s*MOUSE\\)\\s*at\\s*\\((-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)\\)",
    )
    private val internalViewportRegex = Regex(
        "Viewport\\s+INTERNAL.*orientation=(\\d+),.*logicalFrame=\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\]",
    )

    fun parseGroundTruthFromDumpsys(
        dump: String,
        fallbackWidthPx: Int = 0,
        fallbackHeightPx: Int = 0,
        sampledAtMs: Long = SystemClock.elapsedRealtime(),
    ): CursorGroundTruth? {
        val viewport = parseInputViewport(dump)
        val preferredPointerLine = dump.lineSequence().firstOrNull { line ->
            !line.contains("mDeviceStates=-1") &&
                !line.contains("SecondaryLauncher") &&
                (hoverPointerRegex.containsMatchIn(line) || genericMousePointerRegex.containsMatchIn(line))
        }
        val pointerMatch = preferredPointerLine?.let { line ->
            hoverPointerRegex.find(line) ?: genericMousePointerRegex.find(line)
        } ?: hoverPointerRegex.findAll(dump).lastOrNull()
            ?: genericMousePointerRegex.findAll(dump).lastOrNull()
            ?: return null
        val rawX = pointerMatch.groupValues.getOrNull(1)?.toFloatOrNull() ?: return null
        val rawY = pointerMatch.groupValues.getOrNull(2)?.toFloatOrNull() ?: return null

        val width = viewport?.logicalWidthPx ?: fallbackWidthPx.takeIf { it > 0 } ?: return null
        val height = viewport?.logicalHeightPx ?: fallbackHeightPx.takeIf { it > 0 } ?: return null
        if (width <= 0 || height <= 0) {
            return null
        }

        val (x, y) = normalizePointerCoordinates(
            rawX = rawX,
            rawY = rawY,
            logicalWidthPx = width,
            logicalHeightPx = height,
            orientation = viewport?.orientation ?: 0,
        )

        return CursorGroundTruth(
            x = x,
            y = y,
            widthPx = width,
            heightPx = height,
            sampledAtMs = sampledAtMs,
        )
    }

    private fun parseInputViewport(dump: String): InputViewport? {
        val match = internalViewportRegex.findAll(dump).lastOrNull() ?: return null
        val orientation = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val left = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        val top = match.groupValues.getOrNull(3)?.toIntOrNull() ?: return null
        val right = match.groupValues.getOrNull(4)?.toIntOrNull() ?: return null
        val bottom = match.groupValues.getOrNull(5)?.toIntOrNull() ?: return null
        return InputViewport(
            orientation = orientation,
            logicalWidthPx = (right - left).coerceAtLeast(1),
            logicalHeightPx = (bottom - top).coerceAtLeast(1),
        )
    }

    private fun normalizePointerCoordinates(
        rawX: Float,
        rawY: Float,
        logicalWidthPx: Int,
        logicalHeightPx: Int,
        orientation: Int,
    ): Pair<Float, Float> {
        val maxX = logicalWidthPx.toFloat().coerceAtLeast(0f)
        val maxY = logicalHeightPx.toFloat().coerceAtLeast(0f)
        val normalizedOrientation = ((orientation % 4) + 4) % 4
        val (logicalX, logicalY) = when (normalizedOrientation) {
            1 -> rawY to (maxY - rawX)
            2 -> (maxX - rawX) to (maxY - rawY)
            3 -> (maxY - rawY) to (maxX - rawX)
            else -> rawX to rawY
        }
        return logicalX.coerceIn(0f, maxX) to logicalY.coerceIn(0f, maxY)
    }

    private data class InputViewport(
        val orientation: Int,
        val logicalWidthPx: Int,
        val logicalHeightPx: Int,
    )
}
