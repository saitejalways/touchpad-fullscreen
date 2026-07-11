package com.alex.touchpad.backend

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alex.touchpad.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnDeviceAdbCommandExecutorEdgeCooldownInstrumentedTest {

    @Test
    fun clampEdgeWheelDirection_enforcesAxisAndPolarityPerEdge() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(context)
        val executor = OnDeviceAdbCommandExecutor(context, settings)

        assertEquals(2 to 0, clampEdgeWheelDirection(executor, edgeName = "TOP", vWheel = 2, hWheel = 3))
        assertEquals(0 to 0, clampEdgeWheelDirection(executor, edgeName = "TOP", vWheel = -2, hWheel = -3))

        assertEquals(-2 to 0, clampEdgeWheelDirection(executor, edgeName = "BOTTOM", vWheel = -2, hWheel = 3))
        assertEquals(0 to 0, clampEdgeWheelDirection(executor, edgeName = "BOTTOM", vWheel = 2, hWheel = -3))

        assertEquals(0 to -3, clampEdgeWheelDirection(executor, edgeName = "LEFT", vWheel = 2, hWheel = -3))
        assertEquals(0 to 0, clampEdgeWheelDirection(executor, edgeName = "LEFT", vWheel = -2, hWheel = 3))

        assertEquals(0 to 3, clampEdgeWheelDirection(executor, edgeName = "RIGHT", vWheel = -2, hWheel = 3))
        assertEquals(0 to 0, clampEdgeWheelDirection(executor, edgeName = "RIGHT", vWheel = 2, hWheel = -3))
    }

    private fun clampEdgeWheelDirection(
        executor: OnDeviceAdbCommandExecutor,
        edgeName: String,
        vWheel: Int,
        hWheel: Int,
    ): Pair<Int, Int> {
        val edgeSideClass = Class.forName("com.alex.touchpad.backend.OnDeviceAdbCommandExecutor\$EdgeSide")
        val edge = edgeSideClass.enumConstants.first { (it as Enum<*>).name == edgeName }

        val method = OnDeviceAdbCommandExecutor::class.java.getDeclaredMethod(
            "clampEdgeWheelDirection",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            edgeSideClass,
        )
        method.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        return method.invoke(executor, vWheel, hWheel, edge) as Pair<Int, Int>
    }
}
