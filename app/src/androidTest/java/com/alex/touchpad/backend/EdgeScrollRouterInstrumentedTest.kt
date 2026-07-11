package com.alex.touchpad.backend

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EdgeScrollRouterInstrumentedTest {
    private val router = EdgeScrollRouter(scrollUnitsPerPixel = 10f)

    @Test
    fun topEdgePushUp_routesVerticalScrollAndKeepsMove() {
        val groundTruth = cursor(x = 540f, y = 1f)
        val routed = router.route(
            dx = 0,
            dy = -6,
            groundTruth = groundTruth,
            edgeThicknessPx = 10f,
            cornerDeadzonePx = 0f,
            edgeScrollEnabled = true,
            horizontalInverted = false,
        )

        assertEquals("Move should still pass through", -6, routed.moveDy)
        assertTrue("Vertical scroll should trigger at top edge when pushing up", routed.scrollDy > 0)
        assertEquals(0, routed.scrollDx)
    }

    @Test
    fun bottomEdgePushDown_routesVerticalScrollNegativeAndKeepsMove() {
        val groundTruth = cursor(x = 540f, y = 2399f)
        val routed = router.route(
            dx = 0,
            dy = 7,
            groundTruth = groundTruth,
            edgeThicknessPx = 10f,
            cornerDeadzonePx = 0f,
            edgeScrollEnabled = true,
            horizontalInverted = false,
        )

        assertEquals("Move should still pass through", 7, routed.moveDy)
        assertTrue("Vertical scroll should trigger at bottom edge when pushing down", routed.scrollDy < 0)
        assertEquals(0, routed.scrollDx)
    }

    @Test
    fun leftEdgePushRight_doesNotScroll_preventsEdgeStuck() {
        val groundTruth = cursor(x = 1f, y = 1200f)
        val routed = router.route(
            dx = 8,
            dy = 0,
            groundTruth = groundTruth,
            edgeThicknessPx = 10f,
            cornerDeadzonePx = 0f,
            edgeScrollEnabled = true,
            horizontalInverted = false,
        )

        assertEquals(8, routed.moveDx)
        assertEquals(0, routed.scrollDx)
        assertEquals(0, routed.scrollDy)
    }

    @Test
    fun leftEdgePushLeft_scrollsOnlyHorizontalAxis() {
        val groundTruth = cursor(x = 0f, y = 1000f)
        val routed = router.route(
            dx = -4,
            dy = -7,
            groundTruth = groundTruth,
            edgeThicknessPx = 10f,
            cornerDeadzonePx = 0f,
            edgeScrollEnabled = true,
            horizontalInverted = false,
        )

        assertTrue("Horizontal scroll should trigger on left-edge push", routed.scrollDx < 0)
        assertEquals(
            "Vertical scroll must not trigger unless top/bottom edge is pressed",
            0,
            routed.scrollDy,
        )
        assertEquals(-7, routed.moveDy)
    }

    @Test
    fun rightEdgePushRight_scrollsOnlyHorizontalAxis() {
        val groundTruth = cursor(x = 1079f, y = 1000f)
        val routed = router.route(
            dx = 6,
            dy = 5,
            groundTruth = groundTruth,
            edgeThicknessPx = 10f,
            cornerDeadzonePx = 0f,
            edgeScrollEnabled = true,
            horizontalInverted = false,
        )

        assertTrue("Horizontal scroll should trigger on right-edge push", routed.scrollDx > 0)
        assertEquals(
            "Vertical scroll must not trigger unless top/bottom edge is pressed",
            0,
            routed.scrollDy,
        )
        assertEquals(5, routed.moveDy)
    }

    @Test
    fun topEdgePushDown_doesNotScroll_directionMismatch() {
        val groundTruth = cursor(x = 540f, y = 1f)
        val routed = router.route(
            dx = 0,
            dy = 9,
            groundTruth = groundTruth,
            edgeThicknessPx = 10f,
            cornerDeadzonePx = 0f,
            edgeScrollEnabled = true,
            horizontalInverted = false,
        )

        assertEquals(0, routed.scrollDy)
        assertEquals(0, routed.scrollDx)
        assertEquals(9, routed.moveDy)
    }

    @Test
    fun bottomEdgePushUp_doesNotScroll_directionMismatch() {
        val groundTruth = cursor(x = 540f, y = 2399f)
        val routed = router.route(
            dx = 0,
            dy = -10,
            groundTruth = groundTruth,
            edgeThicknessPx = 10f,
            cornerDeadzonePx = 0f,
            edgeScrollEnabled = true,
            horizontalInverted = false,
        )

        assertEquals(0, routed.scrollDy)
        assertEquals(0, routed.scrollDx)
        assertEquals(-10, routed.moveDy)
    }

    @Test
    fun centerMovement_doesNotScroll() {
        val groundTruth = cursor(x = 500f, y = 900f)
        val routed = router.route(
            dx = 5,
            dy = -5,
            groundTruth = groundTruth,
            edgeThicknessPx = 10f,
            cornerDeadzonePx = 0f,
            edgeScrollEnabled = true,
            horizontalInverted = false,
        )

        assertEquals(5, routed.moveDx)
        assertEquals(-5, routed.moveDy)
        assertEquals(0, routed.scrollDx)
        assertEquals(0, routed.scrollDy)
    }

    @Test
    fun nearButNotAtEdge_doesNotScroll_withStrictOnePixelTrigger() {
        val groundTruth = cursor(x = 3f, y = 1200f)
        val routed = router.route(
            dx = -7,
            dy = 0,
            groundTruth = groundTruth,
            edgeThicknessPx = 50f,
            cornerDeadzonePx = 0f,
            edgeScrollEnabled = true,
            horizontalInverted = false,
        )

        assertEquals(0, routed.scrollDx)
        assertEquals(0, routed.scrollDy)
        assertEquals(-7, routed.moveDx)
    }

    @Test
    fun topEdgeInsideCornerDeadzone_doesNotScroll() {
        val groundTruth = cursor(x = 12f, y = 1f)
        val routed = router.route(
            dx = 0,
            dy = -8,
            groundTruth = groundTruth,
            edgeThicknessPx = 50f,
            cornerDeadzonePx = 32f,
            edgeScrollEnabled = true,
            horizontalInverted = false,
        )

        assertEquals(0, routed.scrollDx)
        assertEquals(0, routed.scrollDy)
        assertEquals(-8, routed.moveDy)
    }

    @Test
    fun highlightState_strictTopEdge_highlightsOnlyTop() {
        val state = router.highlightState(
            groundTruth = cursor(x = 540f, y = 1f),
            edgeThicknessPx = 120f,
            cornerDeadzonePx = 0f,
        )

        assertTrue(state.top)
        assertFalse(state.bottom)
        assertFalse(state.left)
        assertFalse(state.right)
    }

    @Test
    fun highlightState_topSuppressedInCornerDeadzone() {
        val state = router.highlightState(
            groundTruth = cursor(x = 8f, y = 1f),
            edgeThicknessPx = 120f,
            cornerDeadzonePx = 32f,
        )

        assertFalse(state.top)
    }

    private fun cursor(x: Float, y: Float): CursorGroundTruth {
        return CursorGroundTruth(
            x = x,
            y = y,
            widthPx = 1080,
            heightPx = 2400,
            sampledAtMs = 0L,
        )
    }
}
