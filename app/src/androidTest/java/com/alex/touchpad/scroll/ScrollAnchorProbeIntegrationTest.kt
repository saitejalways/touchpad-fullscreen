package com.alex.touchpad.scroll

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alex.touchpad.TouchpadApplication
import com.alex.touchpad.input.InputAction
import com.alex.touchpad.ui.ScrollAnchorProbeActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ScrollAnchorProbeIntegrationTest {

    @Test
    fun hidScrollAnchor_followsCursorSide() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TouchpadApplication>()
        val container = app.appContainer
        container.autoConnectEnabled.value = true
        container.overlayDesired.value = false
        container.settingsRepository.setFlingDurationMs(0)
        container.settingsRepository.setOneFingerEdgeScrollEnabled(false)

        ensureConnected(container)
        assertTrue(
            "Expected backend connected for anchor probe",
            waitUntil(3_000L) { container.sessionManager.isConnected.value },
        )

        ActivityScenario.launch(ScrollAnchorProbeActivity::class.java).use { scenario ->
            val (leftX, anchorY) = scenario.leftSideCenter()
            val (rightX, _) = scenario.rightSideCenter()

            scenario.anchorPointer(container, leftX, anchorY)
            scenario.focusLeft()
            val leftBeforeLeft = scenario.leftScrollY()
            val rightBeforeLeft = scenario.rightScrollY()
            scenario.dispatchScrollBurst(container, dy = 160)
            val leftDeltaWhenLeft = scenario.leftScrollY() - leftBeforeLeft
            val rightDeltaWhenLeft = scenario.rightScrollY() - rightBeforeLeft
            assumeTrue(
                "Probe activity did not receive any wheel scroll; cannot determine anchor mode on this device build",
                kotlin.math.abs(leftDeltaWhenLeft) + kotlin.math.abs(rightDeltaWhenLeft) > 0,
            )

            scenario.anchorPointer(container, rightX, anchorY)
            scenario.focusRight()
            val leftBeforeRight = scenario.leftScrollY()
            val rightBeforeRight = scenario.rightScrollY()
            scenario.dispatchScrollBurst(container, dy = 160)
            val leftDeltaWhenRight = scenario.leftScrollY() - leftBeforeRight
            val rightDeltaWhenRight = scenario.rightScrollY() - rightBeforeRight

            assertTrue(
                "Expected left side to scroll more when cursor is on left. left=$leftDeltaWhenLeft right=$rightDeltaWhenLeft",
                kotlin.math.abs(leftDeltaWhenLeft) > kotlin.math.abs(rightDeltaWhenLeft),
            )
            assertTrue(
                "Expected right side to scroll more when cursor is on right. left=$leftDeltaWhenRight right=$rightDeltaWhenRight",
                kotlin.math.abs(rightDeltaWhenRight) > kotlin.math.abs(leftDeltaWhenRight),
            )
        }
    }

    @Test
    fun injectedVerticalScroll_movesInBothDirections() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TouchpadApplication>()
        val container = app.appContainer
        container.autoConnectEnabled.value = true
        container.overlayDesired.value = false
        container.settingsRepository.setFlingDurationMs(0)
        container.settingsRepository.setOneFingerEdgeScrollEnabled(false)

        ensureConnected(container)
        assertTrue(
            "Expected backend connected for bidirectional injected scroll test",
            waitUntil(3_000L) { container.sessionManager.isConnected.value },
        )

        ActivityScenario.launch(ScrollAnchorProbeActivity::class.java).use { scenario ->
            val (leftX, anchorY) = scenario.leftSideCenter()
            scenario.anchorPointer(container, leftX, anchorY)
            scenario.focusLeft()
            scenario.scrollLeftToMiddle()

            val beforePositive = scenario.leftScrollY()
            scenario.dispatchScrollBurst(container, dy = 160)
            val positiveDelta = scenario.leftScrollY() - beforePositive

            scenario.scrollLeftToMiddle()
            val beforeNegative = scenario.leftScrollY()
            scenario.dispatchScrollBurst(container, dy = -160)
            val negativeDelta = scenario.leftScrollY() - beforeNegative

            assertTrue(
                "Expected positive injected vertical scroll burst to move the left pane. delta=$positiveDelta",
                abs(positiveDelta) > 0,
            )
            assertTrue(
                "Expected negative injected vertical scroll burst to move the left pane. delta=$negativeDelta",
                abs(negativeDelta) > 0,
            )
            assertTrue(
                "Expected opposite movement directions for opposite wheel command signs. " +
                    "positiveDelta=$positiveDelta negativeDelta=$negativeDelta",
                positiveDelta * negativeDelta < 0,
            )
        }
    }

    private suspend fun ensureConnected(container: com.alex.touchpad.core.AppContainer) {
        if (container.sessionManager.isConnected.value) {
            return
        }
        repeat(8) {
            if (container.sessionManager.isConnected.value) {
                return
            }
            container.sessionManager.connect()
            if (container.sessionManager.isConnected.value) {
                return
            }
            delay(200)
        }
    }

    private suspend fun ActivityScenario<ScrollAnchorProbeActivity>.anchorPointer(
        container: com.alex.touchpad.core.AppContainer,
        x: Int,
        y: Int,
    ) {
        repeat(3) {
            container.actionRouter.route(InputAction.MoveBy(-6_000, -6_000))
            delay(12)
        }
        container.actionRouter.route(InputAction.MoveBy(x, y))
        delay(140)
    }

    private suspend fun ActivityScenario<ScrollAnchorProbeActivity>.dispatchScrollBurst(
        container: com.alex.touchpad.core.AppContainer,
        dy: Int,
    ) {
        repeat(30) {
            container.actionRouter.route(InputAction.ScrollBy(dx = 0, dy = dy))
            delay(14)
        }
        container.actionRouter.route(InputAction.ScrollBy(dx = 0, dy = 0))
        delay(240)
    }

    private fun ActivityScenario<ScrollAnchorProbeActivity>.focusLeft() {
        onActivity { activity ->
            activity.leftScrollView.requestFocus()
        }
    }

    private fun ActivityScenario<ScrollAnchorProbeActivity>.focusRight() {
        onActivity { activity ->
            activity.rightScrollView.requestFocus()
        }
    }

    private fun ActivityScenario<ScrollAnchorProbeActivity>.leftScrollY(): Int {
        val value = AtomicInteger()
        onActivity { activity ->
            value.set(activity.leftScrollView.scrollY)
        }
        return value.get()
    }

    private fun ActivityScenario<ScrollAnchorProbeActivity>.scrollLeftToMiddle() {
        onActivity { activity ->
            val scrollView = activity.leftScrollView
            val child = scrollView.getChildAt(0)
            val max = if (child != null) (child.height - scrollView.height).coerceAtLeast(0) else 0
            scrollView.scrollTo(0, max / 2)
        }
    }

    private fun ActivityScenario<ScrollAnchorProbeActivity>.rightScrollY(): Int {
        val value = AtomicInteger()
        onActivity { activity ->
            value.set(activity.rightScrollView.scrollY)
        }
        return value.get()
    }

    private fun ActivityScenario<ScrollAnchorProbeActivity>.leftSideCenter(): Pair<Int, Int> {
        val xValue = AtomicInteger()
        val yValue = AtomicInteger()
        onActivity { activity ->
            val location = IntArray(2)
            activity.leftScrollView.getLocationOnScreen(location)
            xValue.set(location[0] + (activity.leftScrollView.width / 2))
            yValue.set(location[1] + (activity.leftScrollView.height / 2))
        }
        return xValue.get() to yValue.get()
    }

    private fun ActivityScenario<ScrollAnchorProbeActivity>.rightSideCenter(): Pair<Int, Int> {
        val xValue = AtomicInteger()
        val yValue = AtomicInteger()
        onActivity { activity ->
            val location = IntArray(2)
            activity.rightScrollView.getLocationOnScreen(location)
            xValue.set(location[0] + (activity.rightScrollView.width / 2))
            yValue.set(location[1] + (activity.rightScrollView.height / 2))
        }
        return xValue.get() to yValue.get()
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return true
            }
            Thread.sleep(40)
        }
        return false
    }
}
