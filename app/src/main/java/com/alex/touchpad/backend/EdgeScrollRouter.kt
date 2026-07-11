package com.alex.touchpad.backend

data class CursorGroundTruth(
    val x: Float,
    val y: Float,
    val widthPx: Int,
    val heightPx: Int,
    val sampledAtMs: Long,
)

data class EdgeHighlightState(
    val left: Boolean,
    val right: Boolean,
    val top: Boolean,
    val bottom: Boolean,
) {
    companion object {
        val NONE = EdgeHighlightState(
            left = false,
            right = false,
            top = false,
            bottom = false,
        )
    }
}

data class EdgeScrollRoutingResult(
    val moveDx: Int,
    val moveDy: Int,
    val scrollDx: Int,
    val scrollDy: Int,
) {
    val hasMove: Boolean get() = moveDx != 0 || moveDy != 0
    val hasScroll: Boolean get() = scrollDx != 0 || scrollDy != 0
}

class EdgeScrollRouter(
    private val scrollUnitsPerPixel: Float,
) {
    private enum class EdgeSide {
        LEFT,
        RIGHT,
        TOP,
        BOTTOM,
    }

    fun route(
        dx: Int,
        dy: Int,
        groundTruth: CursorGroundTruth?,
        edgeThicknessPx: Float,
        cornerDeadzonePx: Float,
        edgeScrollEnabled: Boolean,
        horizontalInverted: Boolean,
    ): EdgeScrollRoutingResult {
        if (dx == 0 && dy == 0) {
            return EdgeScrollRoutingResult(moveDx = 0, moveDy = 0, scrollDx = 0, scrollDy = 0)
        }
        if (!edgeScrollEnabled || groundTruth == null) {
            return EdgeScrollRoutingResult(moveDx = dx, moveDy = dy, scrollDx = 0, scrollDy = 0)
        }
        @Suppress("UNUSED_VARIABLE")
        val _overlayThicknessPx = edgeThicknessPx

        val side = resolvePriorityEdgeSide(
            groundTruth = groundTruth,
            cornerDeadzonePx = cornerDeadzonePx,
        ) ?: return EdgeScrollRoutingResult(moveDx = dx, moveDy = dy, scrollDx = 0, scrollDy = 0)

        val isPushingIntoResolvedEdge = when (side) {
            EdgeSide.LEFT -> dx < 0
            EdgeSide.RIGHT -> dx > 0
            EdgeSide.TOP -> dy < 0
            EdgeSide.BOTTOM -> dy > 0
        }
        if (!isPushingIntoResolvedEdge) {
            return EdgeScrollRoutingResult(moveDx = dx, moveDy = dy, scrollDx = 0, scrollDy = 0)
        }

        val horizontalDirection = if (horizontalInverted) -1f else 1f
        val rawScrollDx = when (side) {
            EdgeSide.LEFT, EdgeSide.RIGHT -> dx
            EdgeSide.TOP, EdgeSide.BOTTOM -> 0
        }
        val rawScrollDy = when (side) {
            EdgeSide.LEFT, EdgeSide.RIGHT -> 0
            EdgeSide.TOP, EdgeSide.BOTTOM -> dy
        }

        val scrollDx = (rawScrollDx.toFloat() * scrollUnitsPerPixel * horizontalDirection).toInt()
        // Match engine convention: finger-up means positive scroll output.
        val scrollDy = ((-rawScrollDy.toFloat()) * scrollUnitsPerPixel).toInt()

        return EdgeScrollRoutingResult(
            moveDx = dx,
            moveDy = dy,
            scrollDx = scrollDx,
            scrollDy = scrollDy,
        )
    }

    fun highlightState(
        groundTruth: CursorGroundTruth?,
        edgeThicknessPx: Float,
        cornerDeadzonePx: Float,
    ): EdgeHighlightState {
        if (groundTruth == null) {
            return EdgeHighlightState.NONE
        }
        @Suppress("UNUSED_VARIABLE")
        val _overlayThicknessPx = edgeThicknessPx
        return when (resolvePriorityEdgeSide(groundTruth = groundTruth, cornerDeadzonePx = cornerDeadzonePx)) {
            EdgeSide.LEFT -> EdgeHighlightState(left = true, right = false, top = false, bottom = false)
            EdgeSide.RIGHT -> EdgeHighlightState(left = false, right = true, top = false, bottom = false)
            EdgeSide.TOP -> EdgeHighlightState(left = false, right = false, top = true, bottom = false)
            EdgeSide.BOTTOM -> EdgeHighlightState(left = false, right = false, top = false, bottom = true)
            null -> EdgeHighlightState.NONE
        }
    }

    private fun resolvePriorityEdgeSide(
        groundTruth: CursorGroundTruth,
        cornerDeadzonePx: Float,
    ): EdgeSide? {
        val widthPx = groundTruth.widthPx.coerceAtLeast(1).toFloat()
        val heightPx = groundTruth.heightPx.coerceAtLeast(1).toFloat()
        val cornerPriorityPx = 3f
        val cornerDeadzone = cornerDeadzonePx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val hasCornerDeadzone = cornerDeadzone > 0f

        val inTopCornerPrioritySquare =
            groundTruth.y <= cornerPriorityPx &&
                (groundTruth.x <= cornerPriorityPx || groundTruth.x >= (widthPx - cornerPriorityPx))
        if (inTopCornerPrioritySquare) {
            return EdgeSide.TOP
        }

        val inBottomCornerPrioritySquare =
            groundTruth.y >= (heightPx - cornerPriorityPx) &&
                (groundTruth.x <= cornerPriorityPx || groundTruth.x >= (widthPx - cornerPriorityPx))
        if (inBottomCornerPrioritySquare) {
            return EdgeSide.BOTTOM
        }

        val atLeft = groundTruth.x <= STRICT_EDGE_TRIGGER_PX
        val atRight = groundTruth.x >= (widthPx - STRICT_EDGE_TRIGGER_PX)
        val atTop = groundTruth.y <= STRICT_EDGE_TRIGGER_PX
        val atBottom = groundTruth.y >= (heightPx - STRICT_EDGE_TRIGGER_PX)
        val inVerticalCornerDeadzone =
            hasCornerDeadzone &&
                (groundTruth.y <= cornerDeadzone || groundTruth.y >= (heightPx - cornerDeadzone))
        val inHorizontalCornerDeadzone =
            hasCornerDeadzone &&
                (groundTruth.x <= cornerDeadzone || groundTruth.x >= (widthPx - cornerDeadzone))

        if (atTop && !inHorizontalCornerDeadzone) {
            return EdgeSide.TOP
        }
        if (atBottom && !inHorizontalCornerDeadzone) {
            return EdgeSide.BOTTOM
        }
        if (atLeft && !inVerticalCornerDeadzone) {
            return EdgeSide.LEFT
        }
        if (atRight && !inVerticalCornerDeadzone) {
            return EdgeSide.RIGHT
        }
        return null
    }

    private companion object {
        const val STRICT_EDGE_TRIGGER_PX = 1f
    }
}
