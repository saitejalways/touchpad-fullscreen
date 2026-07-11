package com.alex.touchpad.backend

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sign

data class ScrollAccumulatorSnapshot(
    val horizontalAccumulator: Float,
    val verticalAccumulator: Float,
    val horizontalHasStepped: Boolean,
    val verticalHasStepped: Boolean,
)

class ScrollAccumulator(
    private val minAxisThreshold: Float,
    private val maxFollowupFactor: Float,
    private val clampScrollStepsPerDispatch: Boolean,
    private val horizontalHidSign: Float,
) {
    private var verticalStepAccumulator = 0f
    private var horizontalStepAccumulator = 0f
    private var verticalHasSteppedInGesture = false
    private var horizontalHasSteppedInGesture = false

    fun consumeScrollSteps(
        dx: Int,
        dy: Int,
        verticalUnitsPerStep: Float,
        horizontalUnitsPerStep: Float,
        verticalFollowupMultiplier: Float,
        horizontalFollowupMultiplier: Float,
    ): Pair<Int, Int> {
        val commandDxUnits = dx.toFloat()
        val commandDyUnits = dy.toFloat()
        val vWheel = consumeVerticalDiscreteWheel(
            // Preserve the engine/edge-router vertical sign convention as-is.
            rawDelta = commandDyUnits,
            baseThreshold = verticalUnitsPerStep,
            followupMultiplier = verticalFollowupMultiplier,
        )
        val hWheel = consumeHorizontalDiscreteWheel(
            rawDelta = commandDxUnits * horizontalHidSign,
            baseThreshold = horizontalUnitsPerStep,
            followupMultiplier = horizontalFollowupMultiplier,
        )
        return vWheel to hWheel
    }

    fun primeEdgeEntryForSingleStep(
        scrollDx: Int,
        scrollDy: Int,
        horizontalUnitsPerStep: Float,
        verticalUnitsPerStep: Float,
    ) {
        if (scrollDx != 0) {
            val horizontalThreshold = ceil(abs(horizontalUnitsPerStep).toDouble()).toFloat()
            val horizontalSign = if ((scrollDx.toFloat() * horizontalHidSign) >= 0f) 1f else -1f
            horizontalStepAccumulator = horizontalSign * horizontalThreshold
            horizontalHasSteppedInGesture = false
        }
        if (scrollDy != 0) {
            val verticalThreshold = ceil(abs(verticalUnitsPerStep).toDouble()).toFloat()
            val verticalRawSign = if (scrollDy > 0) 1f else -1f
            verticalStepAccumulator = verticalRawSign * verticalThreshold
            verticalHasSteppedInGesture = false
        }
    }

    fun reset() {
        verticalStepAccumulator = 0f
        horizontalStepAccumulator = 0f
        verticalHasSteppedInGesture = false
        horizontalHasSteppedInGesture = false
    }

    fun snapshot(): ScrollAccumulatorSnapshot {
        return ScrollAccumulatorSnapshot(
            horizontalAccumulator = horizontalStepAccumulator,
            verticalAccumulator = verticalStepAccumulator,
            horizontalHasStepped = horizontalHasSteppedInGesture,
            verticalHasStepped = verticalHasSteppedInGesture,
        )
    }

    private fun consumeHorizontalDiscreteWheel(
        rawDelta: Float,
        baseThreshold: Float,
        followupMultiplier: Float,
    ): Int {
        val result = consumeAxisDiscreteWheel(
            rawDelta = rawDelta,
            accumulator = horizontalStepAccumulator,
            baseThreshold = baseThreshold,
            followupMultiplier = followupMultiplier,
            hasSteppedInGesture = horizontalHasSteppedInGesture,
        )
        horizontalStepAccumulator = result.nextAccumulator
        horizontalHasSteppedInGesture = result.hasSteppedInGesture
        return result.steps
    }

    private fun consumeVerticalDiscreteWheel(
        rawDelta: Float,
        baseThreshold: Float,
        followupMultiplier: Float,
    ): Int {
        val result = consumeAxisDiscreteWheel(
            rawDelta = rawDelta,
            accumulator = verticalStepAccumulator,
            baseThreshold = baseThreshold,
            followupMultiplier = followupMultiplier,
            hasSteppedInGesture = verticalHasSteppedInGesture,
        )
        verticalStepAccumulator = result.nextAccumulator
        verticalHasSteppedInGesture = result.hasSteppedInGesture
        return result.steps
    }

    private fun consumeAxisDiscreteWheel(
        rawDelta: Float,
        accumulator: Float,
        baseThreshold: Float,
        followupMultiplier: Float,
        hasSteppedInGesture: Boolean,
    ): AxisScrollConsumeResult {
        val safeBaseThreshold = abs(baseThreshold).coerceAtLeast(minAxisThreshold)
        val safeFollowupMultiplier = normalizedFollowupMultiplier(followupMultiplier)
        val maxAbsStepsPerDispatch = if (clampScrollStepsPerDispatch) 1 else Int.MAX_VALUE

        var nextAccumulator = accumulator + rawDelta
        var steps = 0
        var hasStepped = hasSteppedInGesture
        val previousSign = accumulator.directionSign()
        val nextSign = nextAccumulator.directionSign()
        if (previousSign != 0 && nextSign != 0 && previousSign != nextSign) {
            hasStepped = false
        }

        while (true) {
            if (abs(steps) >= maxAbsStepsPerDispatch) {
                break
            }
            val activeThreshold = safeBaseThreshold * if (hasStepped) safeFollowupMultiplier else 1f
            when {
                nextAccumulator >= activeThreshold -> {
                    steps += 1
                    nextAccumulator -= activeThreshold
                    hasStepped = true
                }

                nextAccumulator <= -activeThreshold -> {
                    steps -= 1
                    nextAccumulator += activeThreshold
                    hasStepped = true
                }

                else -> break
            }
        }

        return AxisScrollConsumeResult(
            steps = steps,
            nextAccumulator = nextAccumulator,
            hasSteppedInGesture = hasStepped,
        )
    }

    private fun Float.directionSign(): Int {
        return sign.toInt()
    }

    private fun normalizedFollowupMultiplier(raw: Float): Float {
        return if (raw.isFinite() && raw > 0f) raw.coerceIn(1f, maxFollowupFactor) else 1f
    }

    private data class AxisScrollConsumeResult(
        val steps: Int,
        val nextAccumulator: Float,
        val hasSteppedInGesture: Boolean,
    )
}
