package com.alex.touchpad.backend

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScrollAccumulatorInstrumentedTest {

    @Test
    fun consumeScrollSteps_emitsSingleVerticalStep_whenThresholdReached() {
        val accumulator = newAccumulator()

        val (vWheel, hWheel) = accumulator.consumeScrollSteps(
            dx = 0,
            dy = -400,
            verticalUnitsPerStep = 400f,
            horizontalUnitsPerStep = 400f,
            verticalFollowupMultiplier = 1.2f,
            horizontalFollowupMultiplier = 2f,
        )

        assertEquals(-1, vWheel)
        assertEquals(0, hWheel)
    }

    @Test
    fun consumeScrollSteps_clampsToOneStepPerDispatch_whenClampEnabled() {
        val accumulator = newAccumulator()

        val (vWheel, _) = accumulator.consumeScrollSteps(
            dx = 0,
            dy = -4000,
            verticalUnitsPerStep = 400f,
            horizontalUnitsPerStep = 400f,
            verticalFollowupMultiplier = 1.2f,
            horizontalFollowupMultiplier = 2f,
        )

        assertEquals(-1, vWheel)
    }

    @Test
    fun primeEdgeEntryForSingleStep_forcesImmediateStepThroughSharedPath_topDirection() {
        val accumulator = newAccumulator()

        accumulator.primeEdgeEntryForSingleStep(
            scrollDx = 0,
            scrollDy = 120,
            horizontalUnitsPerStep = 400f,
            verticalUnitsPerStep = 400f,
        )

        val (vWheel, hWheel) = accumulator.consumeScrollSteps(
            dx = 0,
            dy = 1,
            verticalUnitsPerStep = 400f,
            horizontalUnitsPerStep = 400f,
            verticalFollowupMultiplier = 1.2f,
            horizontalFollowupMultiplier = 2f,
        )

        assertEquals(1, vWheel)
        assertEquals(0, hWheel)
        assertTrue(accumulator.snapshot().verticalHasStepped)
    }

    @Test
    fun primeEdgeEntryForSingleStep_forcesImmediateStepThroughSharedPath_bottomDirection() {
        val accumulator = newAccumulator()

        accumulator.primeEdgeEntryForSingleStep(
            scrollDx = 0,
            scrollDy = -120,
            horizontalUnitsPerStep = 400f,
            verticalUnitsPerStep = 400f,
        )

        val (vWheel, hWheel) = accumulator.consumeScrollSteps(
            dx = 0,
            dy = -1,
            verticalUnitsPerStep = 400f,
            horizontalUnitsPerStep = 400f,
            verticalFollowupMultiplier = 1.2f,
            horizontalFollowupMultiplier = 2f,
        )

        assertEquals(-1, vWheel)
        assertEquals(0, hWheel)
        assertTrue(accumulator.snapshot().verticalHasStepped)
    }

    @Test
    fun verticalSignFlip_resetsSteppedPhaseWithinGesture() {
        val accumulator = newAccumulator()

        accumulator.consumeScrollSteps(
            dx = 0,
            dy = 450,
            verticalUnitsPerStep = 400f,
            horizontalUnitsPerStep = 400f,
            verticalFollowupMultiplier = 1.5f,
            horizontalFollowupMultiplier = 1f,
        )
        assertTrue(accumulator.snapshot().verticalHasStepped)

        val (vWheel, _) = accumulator.consumeScrollSteps(
            dx = 0,
            dy = -100,
            verticalUnitsPerStep = 400f,
            horizontalUnitsPerStep = 400f,
            verticalFollowupMultiplier = 1.5f,
            horizontalFollowupMultiplier = 1f,
        )

        assertEquals(0, vWheel)
        assertFalse(accumulator.snapshot().verticalHasStepped)
    }

    @Test
    fun equivalentCommandStreams_shareAccumulatorSemanticsForEdgeAndTwoFingerPaths() {
        val commandStream = listOf(180, 220, 90, -140, -280, -260, 120, 120, -120)

        val twoFingerAccumulator = newAccumulator()
        val edgeAccumulator = newAccumulator()

        val twoFingerWheels = commandStream.map { dy ->
            twoFingerAccumulator.consumeScrollSteps(
                dx = 0,
                dy = dy,
                verticalUnitsPerStep = 400f,
                horizontalUnitsPerStep = 400f,
                verticalFollowupMultiplier = 1.3f,
                horizontalFollowupMultiplier = 1f,
            ).first
        }
        val edgeWheels = commandStream.map { dy ->
            edgeAccumulator.consumeScrollSteps(
                dx = 0,
                dy = dy,
                verticalUnitsPerStep = 400f,
                horizontalUnitsPerStep = 400f,
                verticalFollowupMultiplier = 1.3f,
                horizontalFollowupMultiplier = 1f,
            ).first
        }

        assertEquals(twoFingerWheels, edgeWheels)
        assertEquals(twoFingerAccumulator.snapshot(), edgeAccumulator.snapshot())
    }

    private fun newAccumulator(): ScrollAccumulator {
        return ScrollAccumulator(
            minAxisThreshold = 0.0001f,
            maxFollowupFactor = 8f,
            clampScrollStepsPerDispatch = true,
            horizontalHidSign = 1f,
        )
    }
}
