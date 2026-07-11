package com.alex.touchpad.backend

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alex.touchpad.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnDeviceAdbCommandExecutorRegressionInstrumentedTest {

    @Test
    fun parseGroundTruthFromDumpsys_landscapeLeftTopLeftSample_mapsToLogicalUpperLeft() {
        val executor = newExecutor()
        setIntField(executor, "targetDisplayWidthPx", 3088)
        setIntField(executor, "targetDisplayHeightPx", 1440)

        val groundTruth = parseGroundTruth(executor, LANDSCAPE_LEFT_TOP_LEFT_SAMPLE)

        assertNotNull("Expected dumpsys sample to parse", groundTruth)
        assertEquals(0f, groundTruth!!.x, 0.5f)
        assertEquals(
            "Observed logical top-left raw sample should normalize to logical top edge in landscape-left",
            0f,
            groundTruth.y,
            1.5f,
        )
        assertEquals(3088, groundTruth.widthPx)
        assertEquals(1440, groundTruth.heightPx)
    }

    @Test
    fun zeroMoveWhileStillAtEdge_doesNotResetAccumulatedPushDistance() = runBlocking {
        val executor = newExecutor().apply {
            execute(WireCommand.TouchContact(active = true, seq = 1L, ts = 1L))
        }
        val now = SystemClock.elapsedRealtime()
        setObjectField(
            executor,
            "lastGroundTruthCursor",
            CursorGroundTruth(
                x = 200f,
                y = 0f,
                widthPx = 1440,
                heightPx = 3088,
                sampledAtMs = now,
            ),
        )
        setEdgeSideField(executor, "lastEdgeAtSide", "TOP")
        setEdgeSideField(executor, "lastEdgePushSide", "TOP")
        setFloatField(executor, "edgeActivationAccumulatedPx", 57f)

        val ok = executor.execute(WireCommand.MoveRel(dx = 0, dy = 0, seq = 2L, ts = now))

        assertTrue(ok)
        assertEquals(
            "A zero move tick while the cursor is still parked on the edge should not wipe accumulated push distance",
            57f,
            getFloatField(executor, "edgeActivationAccumulatedPx"),
            0.001f,
        )
    }

    @Test
    fun staleGroundTruthWhileStillLatchedToEdge_doesNotResetAccumulatedPushDistance() = runBlocking {
        val executor = newExecutor().apply {
            execute(WireCommand.TouchContact(active = true, seq = 1L, ts = 1L))
        }
        val now = SystemClock.elapsedRealtime()
        setObjectField(
            executor,
            "lastGroundTruthCursor",
            CursorGroundTruth(
                x = 200f,
                y = 0f,
                widthPx = 1440,
                heightPx = 3088,
                sampledAtMs = now - 1_000L,
            ),
        )
        setEdgeSideField(executor, "lastEdgeAtSide", "TOP")
        setEdgeSideField(executor, "lastEdgePushSide", "TOP")
        setFloatField(executor, "edgeActivationAccumulatedPx", 57f)

        val ok = executor.execute(WireCommand.MoveRel(dx = 0, dy = 0, seq = 2L, ts = now))

        assertTrue(ok)
        assertEquals(
            "A stale or missing cursor sample should not wipe the latched edge-push distance on its own",
            57f,
            getFloatField(executor, "edgeActivationAccumulatedPx"),
            0.001f,
        )
    }

    @Test
    fun touchDeltaAtTopEdge_accumulatesSlowPushDistance() = runBlocking {
        val executor = newExecutor().apply {
            execute(WireCommand.TouchContact(active = true, seq = 1L, ts = 1L))
        }
        setEdgeSideField(executor, "lastEdgeAtSide", "TOP")

        repeat(7) { index ->
            executor.execute(
                WireCommand.TouchDelta(
                    dx = 0,
                    dy = -1,
                    seq = (10 + index).toLong(),
                    ts = SystemClock.elapsedRealtime(),
                ),
            )
        }

        assertEquals(7f, getFloatField(executor, "edgeActivationAccumulatedPx"), 0.001f)
    }

    @Test
    fun currentCursorCalibration_landscapeFallsBackToSwappedPortraitAxes() {
        val executor = newExecutor()
        val settings = getObjectField(executor, "settingsRepository") as SettingsRepository
        settings.clearCursorCalibration()
        settings.setCursorCalibrationUnitsPerPx(xUnitsPerPx = 1.25f, yUnitsPerPx = 2.5f, isLandscape = false)
        setIntField(executor, "targetDisplayWidthPx", 3088)
        setIntField(executor, "targetDisplayHeightPx", 1440)

        val calibration = currentCursorCalibration(executor)

        assertNotNull("Expected portrait calibration to translate into a landscape fallback", calibration)
        assertEquals(2.5f, getFloatField(calibration!!, "unitsPerPxX"), 0.0001f)
        assertEquals(1.25f, getFloatField(calibration, "unitsPerPxY"), 0.0001f)
    }

    private fun newExecutor(): OnDeviceAdbCommandExecutor {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(context)
        settings.clearCursorCalibration()
        settings.setOneFingerEdgeScrollEnabled(true)
        settings.setMouseAccelerationEnabled(false)
        settings.setPointerMoveDeadbandPx(0f)
        return OnDeviceAdbCommandExecutor(context, settings)
    }

    private fun parseGroundTruth(
        executor: OnDeviceAdbCommandExecutor,
        dump: String,
    ): CursorGroundTruth? {
        val method = OnDeviceAdbCommandExecutor::class.java.getDeclaredMethod(
            "parseGroundTruthFromDumpsys",
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(executor, dump) as CursorGroundTruth?
    }

    private fun currentCursorCalibration(executor: OnDeviceAdbCommandExecutor): Any? {
        val method = OnDeviceAdbCommandExecutor::class.java.getDeclaredMethod("currentCursorCalibration")
        method.isAccessible = true
        return method.invoke(executor)
    }

    private fun setEdgeSideField(executor: OnDeviceAdbCommandExecutor, fieldName: String, edgeName: String) {
        val field = OnDeviceAdbCommandExecutor::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        val edgeSideClass = Class.forName("com.alex.touchpad.backend.OnDeviceAdbCommandExecutor\$EdgeSide")
        val edge = edgeSideClass.enumConstants.first { (it as Enum<*>).name == edgeName }
        field.set(executor, edge)
    }

    private fun setObjectField(executor: OnDeviceAdbCommandExecutor, fieldName: String, value: Any?) {
        val field = OnDeviceAdbCommandExecutor::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(executor, value)
    }

    private fun getObjectField(executor: OnDeviceAdbCommandExecutor, fieldName: String): Any? {
        val field = OnDeviceAdbCommandExecutor::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(executor)
    }

    private fun setIntField(executor: OnDeviceAdbCommandExecutor, fieldName: String, value: Int) {
        val field = OnDeviceAdbCommandExecutor::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.setInt(executor, value)
    }

    private fun setFloatField(executor: OnDeviceAdbCommandExecutor, fieldName: String, value: Float) {
        val field = OnDeviceAdbCommandExecutor::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.setFloat(executor, value)
    }

    private fun getFloatField(executor: OnDeviceAdbCommandExecutor, fieldName: String): Float {
        val field = OnDeviceAdbCommandExecutor::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.getFloat(executor)
    }

    private fun getFloatField(target: Any, fieldName: String): Float {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.getFloat(target)
    }

    private companion object {
        const val LANDSCAPE_LEFT_TOP_LEFT_SAMPLE = """
            Viewport INTERNAL: displayId=0, uniqueId=local:4630947093241269891, port=131, orientation=1, logicalFrame=[0, 0, 3088, 1440], physicalFrame=[0, 0, 3088, 1440], deviceSize=[3088, 1440], isActive=[1]
            1 : name='[Gesture Monitor] secinputdev', targetFlags=0x0, forwardingWindowToken=0x0, mDeviceStates=234:[touchingPointers=[], downTimeInTarget=<not set>, hoveringPointers=[Pointer(id=0, MOUSE) at (1440, 0)], pilferingPointerIds=<none>]
        """
    }
}
