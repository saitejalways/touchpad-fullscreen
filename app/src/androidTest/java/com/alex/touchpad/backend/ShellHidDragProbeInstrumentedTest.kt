package com.alex.touchpad.backend

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellHidDragProbeInstrumentedTest {

    @Test
    fun shellHidWriter_movesCursorWhileLeftButtonHeld() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = runInstrumentationShell(
            "sh -c \"CLASSPATH='${context.packageCodePath}' app_process /system/bin " +
                "com.alex.touchpad.backend.ShellHidDragProbeMain 2>&1; echo EXIT:\\$?\"",
        ).trim()

        assertTrue(
            "Expected shell drag probe to succeed, got: $output",
            output.contains("OK ") && output.contains("EXIT:0"),
        )
    }

    private fun runInstrumentationShell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return pfd.use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                reader.readText()
            }
        }
    }
}
