package com.alex.touchpad.scroll

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alex.touchpad.R
import com.alex.touchpad.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MainActivityScrollSmokeTest {

    @Test
    fun settingsScreen_scrollPositionChangesOnSwipe() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val initial = it.readScrollY()

            onView(withId(R.id.settingsScrollView)).perform(swipeUp())
            val movedDown = waitUntil(2_000L) { it.readScrollY() > initial + MIN_SCROLL_DELTA_PX }
            assertTrue("Expected swipeUp to increase scrollY; initial=$initial current=${it.readScrollY()}", movedDown)

            val afterSwipeUp = it.readScrollY()
            onView(withId(R.id.settingsScrollView)).perform(swipeDown())
            val movedBack = waitUntil(2_000L) { it.readScrollY() < afterSwipeUp - MIN_SCROLL_DELTA_PX }
            assertTrue(
                "Expected swipeDown to reduce scrollY; afterUp=$afterSwipeUp current=${it.readScrollY()}",
                movedBack,
            )
        }
    }

    private fun ActivityScenario<MainActivity>.readScrollY(): Int {
        val value = AtomicInteger()
        onActivity { activity ->
            value.set(activity.findViewById<android.widget.ScrollView>(R.id.settingsScrollView).scrollY)
        }
        return value.get()
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
        const val MIN_SCROLL_DELTA_PX = 40
    }
}
