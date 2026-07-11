package com.alex.touchpad.scroll

import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alex.touchpad.R
import com.alex.touchpad.TouchpadApplication
import com.alex.touchpad.input.InputAction
import com.alex.touchpad.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class InjectedScrollPositionIntegrationTest {

    @Test
    fun injectedSmallStepScroll_accumulatesIntoMovement() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TouchpadApplication>()
        val container = app.appContainer
        container.autoConnectEnabled.value = true
        container.overlayDesired.value = false
        container.settingsRepository.setFlingDurationMs(0)
        container.settingsRepository.setVerticalScrollPixelsPerStep(12f)

        ensureConnected(container)
        assertTrue(
            "Expected backend session connected for small-step scroll test",
            waitUntil(3_000L) { container.sessionManager.isConnected.value },
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue("Expected MainActivity settings to be scrollable", waitUntil(2_000L) { scenario.maxScrollY() > 0 })
            scenario.scrollToMiddle()
            scenario.anchorPointerToSettingsScrollView(container)

            val moved = scenario.dispatchInjectedSmallStepBurst(container = container, dy = 40)
            assertTrue("Expected repeated small-step scroll deltas to move ScrollView", moved)
        }
    }

    @Test
    fun verticalGestureStartThreshold_isConsistentAcrossConsecutiveGestures() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TouchpadApplication>()
        val container = app.appContainer
        container.autoConnectEnabled.value = true
        container.overlayDesired.value = false
        container.settingsRepository.setFlingDurationMs(0)
        container.settingsRepository.setVerticalScrollPixelsPerStep(20f)

        ensureConnected(container)
        assertTrue(
            "Expected backend session connected for vertical threshold consistency test",
            waitUntil(3_000L) { container.sessionManager.isConnected.value },
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue("Expected MainActivity settings to be scrollable", waitUntil(2_000L) { scenario.maxScrollY() > 0 })
            scenario.scrollToMiddle()
            scenario.anchorPointerToSettingsScrollView(container)

            val firstEvents = scenario.eventsUntilFirstVisibleMovement(
                container = container,
                dy = CONSISTENCY_TEST_DELTA_PER_EVENT,
                maxEvents = 60,
            )
            val secondEvents = scenario.eventsUntilFirstVisibleMovement(
                container = container,
                dy = CONSISTENCY_TEST_DELTA_PER_EVENT,
                maxEvents = 60,
            )

            assertFalse("Expected first gesture to trigger movement within event budget", firstEvents < 0)
            assertFalse("Expected second gesture to trigger movement within event budget", secondEvents < 0)

            val difference = abs(secondEvents - firstEvents)
            assertTrue(
                "Expected consecutive gesture start thresholds to be close. first=$firstEvents second=$secondEvents diff=$difference",
                difference <= MAX_START_THRESHOLD_EVENT_DIFF,
            )
        }
    }

    private suspend fun ensureConnected(container: com.alex.touchpad.core.AppContainer) {
        if (container.sessionManager.isConnected.value) {
            return
        }
        repeat(6) {
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

    private suspend fun ActivityScenario<MainActivity>.anchorPointerToSettingsScrollView(
        container: com.alex.touchpad.core.AppContainer,
    ) {
        val (centerX, centerY) = settingsScrollViewCenterOnScreen()
        // Rebase pointer to top-left first, then place it over the settings ScrollView.
        repeat(3) {
            container.actionRouter.route(InputAction.MoveBy(-6_000, -6_000))
            delay(12)
        }
        container.actionRouter.route(InputAction.MoveBy(centerX, centerY))
        focusSettingsScrollView()
        delay(120)
    }

    private suspend fun ActivityScenario<MainActivity>.dispatchInjectedSmallStepBurst(
        container: com.alex.touchpad.core.AppContainer,
        dy: Int,
    ): Boolean {
        val startY = readScrollY()
        repeat(36) {
            container.actionRouter.route(InputAction.ScrollBy(dx = 0, dy = dy))
            delay(14)
        }
        container.actionRouter.route(InputAction.ScrollBy(dx = 0, dy = 0))

        return waitUntil(2_000L) {
            kotlin.math.abs(readScrollY() - startY) >= SMALL_STEP_MIN_MOVED_DELTA_PX
        }
    }

    private suspend fun ActivityScenario<MainActivity>.eventsUntilFirstVisibleMovement(
        container: com.alex.touchpad.core.AppContainer,
        dy: Int,
        maxEvents: Int,
    ): Int {
        val startY = readScrollY()
        var sent = 0
        while (sent < maxEvents && abs(readScrollY() - startY) < 1) {
            container.actionRouter.route(InputAction.ScrollBy(dx = 0, dy = dy))
            sent += 1
            delay(14)
        }
        container.actionRouter.route(InputAction.ScrollBy(dx = 0, dy = 0))
        val moved = waitUntil(1_000L) { abs(readScrollY() - startY) >= 1 }
        return if (moved) sent else -1
    }

    private fun ActivityScenario<MainActivity>.readScrollY(): Int {
        val value = AtomicInteger()
        onActivity { activity ->
            value.set(activity.findViewById<ScrollView>(R.id.settingsScrollView).scrollY)
        }
        return value.get()
    }

    private fun ActivityScenario<MainActivity>.maxScrollY(): Int {
        val value = AtomicInteger()
        onActivity { activity ->
            val scrollView = activity.findViewById<ScrollView>(R.id.settingsScrollView)
            val child = scrollView.getChildAt(0)
            val max = if (child != null) (child.height - scrollView.height).coerceAtLeast(0) else 0
            value.set(max)
        }
        return value.get()
    }

    private fun ActivityScenario<MainActivity>.settingsScrollViewCenterOnScreen(): Pair<Int, Int> {
        val centerX = AtomicInteger()
        val centerY = AtomicInteger()
        onActivity { activity ->
            val scrollView = activity.findViewById<ScrollView>(R.id.settingsScrollView)
            val location = IntArray(2)
            scrollView.getLocationOnScreen(location)
            centerX.set(location[0] + (scrollView.width / 2))
            centerY.set(location[1] + (scrollView.height / 2))
        }
        return centerX.get() to centerY.get()
    }

    private fun ActivityScenario<MainActivity>.focusSettingsScrollView() {
        onActivity { activity ->
            val scrollView = activity.findViewById<ScrollView>(R.id.settingsScrollView)
            scrollView.isFocusable = true
            scrollView.isFocusableInTouchMode = true
            scrollView.requestFocus()
        }
    }

    private fun ActivityScenario<MainActivity>.scrollToMiddle() {
        onActivity { activity ->
            val scrollView = activity.findViewById<ScrollView>(R.id.settingsScrollView)
            val child = scrollView.getChildAt(0)
            val max = if (child != null) (child.height - scrollView.height).coerceAtLeast(0) else 0
            scrollView.scrollTo(0, max / 2)
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) {
                return true
            }
            Thread.sleep(40)
        }
        return false
    }

    private companion object {
        const val SMALL_STEP_MIN_MOVED_DELTA_PX = 30
        const val CONSISTENCY_TEST_DELTA_PER_EVENT = 100
        const val MAX_START_THRESHOLD_EVENT_DIFF = 6
    }
}
