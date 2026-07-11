package com.alex.touchpad.backend

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alex.touchpad.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnDeviceAdbCommandExecutorFollowupFactorInstrumentedTest {

    @Test
    fun horizontalSlowDelta_accumulatesWithoutPerFrameDeadband() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(context)
        settings.setHorizontalScrollPixelsPerStep(40f)
        settings.setHorizontalFollowupStepFactor(1f)

        val executor = OnDeviceAdbCommandExecutor(context, settings)
        resetHorizontalGestureState(executor)

        repeat(399) {
            assertEquals(0, consumeHorizontalWheel(executor, 1f))
        }
        assertEquals(1, consumeHorizontalWheel(executor, 1f))
    }

    @Test
    fun horizontalSingleDispatch_isClampedToSingleStep() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(context)
        settings.setHorizontalScrollPixelsPerStep(40f)
        settings.setHorizontalFollowupStepFactor(1f)

        val executor = OnDeviceAdbCommandExecutor(context, settings)
        resetHorizontalGestureState(executor)

        val threshold = 40f * SCROLL_COMMAND_UNITS_PER_PIXEL
        assertEquals(1, consumeHorizontalWheel(executor, threshold * 3.1f))
    }

    @Test
    fun verticalSingleDispatch_isClampedToSingleStep() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(context)
        settings.setVerticalScrollPixelsPerStep(40f)
        settings.setVerticalFollowupStepFactor(1f)

        val executor = OnDeviceAdbCommandExecutor(context, settings)
        resetVerticalGestureState(executor)

        val threshold = 40f * SCROLL_COMMAND_UNITS_PER_PIXEL
        assertEquals(-1, consumeVerticalWheel(executor, -(threshold * 3.1f)))
    }

    @Test
    fun horizontalRemainder_isPreservedAcrossEvents() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(context)
        settings.setHorizontalScrollPixelsPerStep(40f)
        settings.setHorizontalFollowupStepFactor(1f)

        val executor = OnDeviceAdbCommandExecutor(context, settings)
        resetHorizontalGestureState(executor)

        val threshold = 40f * SCROLL_COMMAND_UNITS_PER_PIXEL
        assertEquals(1, consumeHorizontalWheel(executor, threshold * 1.75f))
        assertEquals(1, consumeHorizontalWheel(executor, threshold * 0.90f))
    }

    @Test
    fun horizontalFollowupFactor_makesSecondStepHarder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(context)
        settings.setHorizontalScrollPixelsPerStep(40f)
        settings.setHorizontalFollowupStepFactor(2f)

        val executor = OnDeviceAdbCommandExecutor(context, settings)
        resetHorizontalGestureState(executor)

        assertEquals(1, consumeHorizontalWheel(executor, 400f))
        assertEquals(0, consumeHorizontalWheel(executor, 799f))
        assertEquals(1, consumeHorizontalWheel(executor, 1f))
    }

    @Test
    fun signFlip_clearsSteppedPhaseForAxis() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(context)
        val executor = OnDeviceAdbCommandExecutor(context, settings)

        setHorizontalAccumulator(executor, 100f)
        setHorizontalStepped(executor, true)

        assertEquals(0, consumeHorizontalWheel(executor, -200f))
        assertFalse(horizontalStepped(executor))
    }

    @Test
    fun stopMarker_resetsGestureState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(context)
        val executor = OnDeviceAdbCommandExecutor(context, settings)

        setHorizontalAccumulator(executor, 123.5f)
        setVerticalAccumulator(executor, -55.25f)
        setHorizontalStepped(executor, true)
        setVerticalStepped(executor, true)
        setLastNonZeroScrollInputAt(executor, 7777L)

        val ok = executor.execute(WireCommand.Scroll(dx = 0, dy = 0, seq = 1L, ts = 1L))
        assertTrue(ok)
        assertEquals(0f, horizontalAccumulator(executor), 0.0001f)
        assertEquals(0f, verticalAccumulator(executor), 0.0001f)
        assertFalse(horizontalStepped(executor))
        assertFalse(verticalStepped(executor))
        assertEquals(0L, lastNonZeroScrollInputAt(executor))
    }

    private fun consumeHorizontalWheel(executor: OnDeviceAdbCommandExecutor, rawDelta: Float): Int {
        val method = OnDeviceAdbCommandExecutor::class.java.getDeclaredMethod(
            "consumeHorizontalDiscreteWheel",
            Float::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(executor, rawDelta) as Int
    }

    private fun consumeVerticalWheel(executor: OnDeviceAdbCommandExecutor, rawDelta: Float): Int {
        val method = OnDeviceAdbCommandExecutor::class.java.getDeclaredMethod(
            "consumeVerticalDiscreteWheel",
            Float::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(executor, rawDelta) as Int
    }

    private fun resetHorizontalGestureState(executor: OnDeviceAdbCommandExecutor) {
        setHorizontalAccumulator(executor, 0f)
        setHorizontalStepped(executor, false)
    }

    private fun resetVerticalGestureState(executor: OnDeviceAdbCommandExecutor) {
        setVerticalAccumulator(executor, 0f)
        setVerticalStepped(executor, false)
    }

    private fun horizontalAccumulator(executor: OnDeviceAdbCommandExecutor): Float {
        val accumulatorField = OnDeviceAdbCommandExecutor::class.java.getDeclaredField("horizontalStepAccumulator")
        accumulatorField.isAccessible = true
        return accumulatorField.getFloat(executor)
    }

    private fun verticalAccumulator(executor: OnDeviceAdbCommandExecutor): Float {
        val accumulatorField = OnDeviceAdbCommandExecutor::class.java.getDeclaredField("verticalStepAccumulator")
        accumulatorField.isAccessible = true
        return accumulatorField.getFloat(executor)
    }

    private fun setHorizontalAccumulator(executor: OnDeviceAdbCommandExecutor, value: Float) {
        val accumulatorField = OnDeviceAdbCommandExecutor::class.java.getDeclaredField("horizontalStepAccumulator")
        accumulatorField.isAccessible = true
        accumulatorField.setFloat(executor, value)
    }

    private fun setVerticalAccumulator(executor: OnDeviceAdbCommandExecutor, value: Float) {
        val accumulatorField = OnDeviceAdbCommandExecutor::class.java.getDeclaredField("verticalStepAccumulator")
        accumulatorField.isAccessible = true
        accumulatorField.setFloat(executor, value)
    }

    private fun horizontalStepped(executor: OnDeviceAdbCommandExecutor): Boolean {
        val steppedField = OnDeviceAdbCommandExecutor::class.java.getDeclaredField("horizontalHasSteppedInGesture")
        steppedField.isAccessible = true
        return steppedField.getBoolean(executor)
    }

    private fun verticalStepped(executor: OnDeviceAdbCommandExecutor): Boolean {
        val steppedField = OnDeviceAdbCommandExecutor::class.java.getDeclaredField("verticalHasSteppedInGesture")
        steppedField.isAccessible = true
        return steppedField.getBoolean(executor)
    }

    private fun setHorizontalStepped(executor: OnDeviceAdbCommandExecutor, value: Boolean) {
        val steppedField = OnDeviceAdbCommandExecutor::class.java.getDeclaredField("horizontalHasSteppedInGesture")
        steppedField.isAccessible = true
        steppedField.setBoolean(executor, value)
    }

    private fun setVerticalStepped(executor: OnDeviceAdbCommandExecutor, value: Boolean) {
        val steppedField = OnDeviceAdbCommandExecutor::class.java.getDeclaredField("verticalHasSteppedInGesture")
        steppedField.isAccessible = true
        steppedField.setBoolean(executor, value)
    }

    private fun setLastNonZeroScrollInputAt(executor: OnDeviceAdbCommandExecutor, value: Long) {
        val field = OnDeviceAdbCommandExecutor::class.java.getDeclaredField("lastNonZeroScrollInputAtMs")
        field.isAccessible = true
        field.setLong(executor, value)
    }

    private fun lastNonZeroScrollInputAt(executor: OnDeviceAdbCommandExecutor): Long {
        val field = OnDeviceAdbCommandExecutor::class.java.getDeclaredField("lastNonZeroScrollInputAtMs")
        field.isAccessible = true
        return field.getLong(executor)
    }

    private companion object {
        const val SCROLL_COMMAND_UNITS_PER_PIXEL = 10f
    }
}
