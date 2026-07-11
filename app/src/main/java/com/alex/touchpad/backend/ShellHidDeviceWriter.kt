package com.alex.touchpad.backend

import com.alex.touchpad.core.AppLog as Log
import com.alex.touchpad.input.MouseButton
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import kotlin.concurrent.thread

class ShellHidDeviceWriter {
    private var hidSession: HidSession? = null
    private var hidButtonMask = 0

    @Synchronized
    fun executeMove(
        dx: Int,
        dy: Int,
        hidMoveChunkSize: Int,
        mouseAccelerationEnabled: Boolean,
    ): Boolean {
        if (dx == 0 && dy == 0) {
            return true
        }
        val session = ensureHidSessionLocked() ?: return false
        val success = writeHidRelativeLocked(
            session = session,
            dx = dx,
            dy = dy,
            wheel = 0,
            hWheel = 0,
            hidMoveChunkSize = hidMoveChunkSize,
            mouseAccelerationEnabled = mouseAccelerationEnabled,
        )
        return success
    }

    @Synchronized
    fun executeClick(button: MouseButton): Boolean {
        val bit = button.toHidBit() ?: return false
        val session = ensureHidSessionLocked() ?: return false
        val originalMask = hidButtonMask
        val downMask = originalMask or bit
        hidButtonMask = downMask
        if (!writeHidReportLocked(session, downMask, dx = 0, dy = 0, wheel = 0, hWheel = 0)) {
            closeHidSessionLocked("click down failed")
            return false
        }
        if (!writeHidDelayLocked(session, HidDeviceWriter.HID_CLICK_GAP_MS)) {
            closeHidSessionLocked("click gap failed")
            return false
        }
        val upMask = originalMask and bit.inv()
        hidButtonMask = upMask
        if (!writeHidReportLocked(session, upMask, dx = 0, dy = 0, wheel = 0, hWheel = 0)) {
            closeHidSessionLocked("click up failed")
            return false
        }
        return true
    }

    @Synchronized
    fun executeButton(button: MouseButton, isDown: Boolean): Boolean {
        val bit = button.toHidBit() ?: return false
        val session = ensureHidSessionLocked() ?: return false
        hidButtonMask = if (isDown) {
            hidButtonMask or bit
        } else {
            hidButtonMask and bit.inv()
        }
        if (!writeHidReportLocked(session, hidButtonMask, dx = 0, dy = 0, wheel = 0, hWheel = 0)) {
            closeHidSessionLocked("button state failed")
            return false
        }
        return true
    }

    @Synchronized
    fun executeScroll(vWheel: Int, hWheel: Int): Boolean {
        if (vWheel == 0 && hWheel == 0) {
            return true
        }
        val session = ensureHidSessionLocked() ?: return false
        if (!writeHidRelativeLocked(
                session = session,
                dx = 0,
                dy = 0,
                wheel = vWheel,
                hWheel = hWheel,
                hidMoveChunkSize = HidDeviceWriter.HID_REL_AXIS_MAX,
                mouseAccelerationEnabled = true,
            )
        ) {
            closeHidSessionLocked("scroll failed")
            return false
        }
        return true
    }

    @Synchronized
    private fun ensureHidSessionLocked(): HidSession? {
        val existing = hidSession
        if (existing != null) {
            if (existing.process.isAlive) {
                return existing
            }
            closeHidSessionLocked("stale hid process")
        }

        val process = runCatching {
            ProcessBuilder(listOf(SYSTEM_SH, "-c", "hid -"))
                .redirectErrorStream(true)
                .start()
        }.getOrElse { error ->
            Log.w(TAG, "Failed to start local hid process", error)
            return null
        }
        val session = HidSession(
            process = process,
            writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8)),
            logDrainer = startHidOutputDrainer(process),
        )
        hidSession = session
        hidButtonMask = 0

        val registered = writeHidRegisterLocked(session) && writeHidDelayLocked(session, HidDeviceWriter.HID_REGISTER_DELAY_MS)
        if (!registered) {
            closeHidSessionLocked("hid register failed")
            return null
        }

        Log.i(TAG, "Local HID mouse session started")
        return session
    }

    private fun writeHidRegisterLocked(session: HidSession): Boolean {
        val descriptor = HidDeviceWriter.HID_MOUSE_DESCRIPTOR.joinToString(",")
        val jsonLine = buildString {
            append("{\"id\":")
            append(HidDeviceWriter.HID_DEVICE_ID)
            append(",\"command\":\"register\",\"name\":\"")
            append(HidDeviceWriter.HID_DEVICE_NAME)
            append("\",\"vid\":")
            append(HidDeviceWriter.HID_VENDOR_ID)
            append(",\"pid\":")
            append(HidDeviceWriter.HID_PRODUCT_ID)
            append(",\"bus\":\"usb\",\"descriptor\":[")
            append(descriptor)
            append("]}")
        }
        return writeHidJsonLineLocked(session, jsonLine)
    }

    private fun writeHidRelativeLocked(
        session: HidSession,
        dx: Int,
        dy: Int,
        wheel: Int,
        hWheel: Int,
        hidMoveChunkSize: Int,
        mouseAccelerationEnabled: Boolean,
    ): Boolean {
        var remainingDx = dx
        var remainingDy = dy
        var remainingWheel = wheel
        var remainingHWheel = hWheel
        val moveChunkLimit = if (mouseAccelerationEnabled) {
            HidDeviceWriter.HID_REL_AXIS_MAX
        } else {
            hidMoveChunkSize
                .coerceAtLeast(1)
                .coerceAtMost(HidDeviceWriter.HID_REL_AXIS_MAX)
        }

        while (remainingDx != 0 || remainingDy != 0 || remainingWheel != 0 || remainingHWheel != 0) {
            val chunkDx = remainingDx.coerceIn(-moveChunkLimit, moveChunkLimit)
            val chunkDy = remainingDy.coerceIn(-moveChunkLimit, moveChunkLimit)
            val chunkWheel = remainingWheel.coerceIn(-HidDeviceWriter.HID_REL_AXIS_MAX, HidDeviceWriter.HID_REL_AXIS_MAX)
            val chunkHWheel = remainingHWheel.coerceIn(-HidDeviceWriter.HID_REL_AXIS_MAX, HidDeviceWriter.HID_REL_AXIS_MAX)

            val written = writeHidReportLocked(
                session = session,
                buttons = hidButtonMask,
                dx = chunkDx,
                dy = chunkDy,
                wheel = chunkWheel,
                hWheel = chunkHWheel,
            )
            if (!written) {
                return false
            }
            remainingDx -= chunkDx
            remainingDy -= chunkDy
            remainingWheel -= chunkWheel
            remainingHWheel -= chunkHWheel
        }
        return true
    }

    private fun writeHidReportLocked(
        session: HidSession,
        buttons: Int,
        dx: Int,
        dy: Int,
        wheel: Int,
        hWheel: Int,
    ): Boolean {
        val jsonLine = buildString {
            append("{\"id\":")
            append(HidDeviceWriter.HID_DEVICE_ID)
            append(",\"command\":\"report\",\"report\":[")
            append(toUnsignedByte(buttons))
            append(",")
            append(toUnsignedByte(dx))
            append(",")
            append(toUnsignedByte(dy))
            append(",")
            append(toUnsignedByte(wheel))
            append(",")
            append(toUnsignedByte(hWheel))
            append("]}")
        }
        return writeHidJsonLineLocked(session, jsonLine)
    }

    private fun writeHidDelayLocked(session: HidSession, durationMs: Int): Boolean {
        val jsonLine = buildString {
            append("{\"id\":")
            append(HidDeviceWriter.HID_DEVICE_ID)
            append(",\"command\":\"delay\",\"duration\":")
            append(durationMs)
            append("}")
        }
        val written = writeHidJsonLineLocked(session, jsonLine)
        if (written) {
            Thread.sleep(durationMs.toLong())
        }
        return written
    }

    private fun writeHidJsonLineLocked(session: HidSession, line: String): Boolean {
        if (!session.process.isAlive) {
            return false
        }
        return runCatching {
            session.writer.write(line)
            session.writer.write("\n")
            session.writer.flush()
            true
        }.getOrElse { error ->
            Log.w(TAG, "Local HID write failed", error)
            false
        }
    }

    private fun startHidOutputDrainer(process: Process): Thread {
        val thread = thread(
            start = true,
            isDaemon = true,
            name = "shell-hid-drainer",
        ) {
            runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) {
                            Log.v(TAG, "hid-out: $line")
                        }
                    }
                }
            }
            runCatching {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) {
                            Log.w(TAG, "hid-err: $line")
                        }
                    }
                }
            }
        }
        return thread
    }

    private fun closeHidSessionLocked(reason: String) {
        val session = hidSession ?: return
        Log.i(TAG, "Closing local HID session reason=$reason")
        runCatching { session.writer.close() }
        if (session.process.isAlive) {
            runCatching { session.process.destroy() }
        }
        hidSession = null
        hidButtonMask = 0
    }

    private fun toUnsignedByte(value: Int): Int {
        return value and 0xFF
    }

    private fun MouseButton.toHidBit(): Int? {
        return when (this) {
            MouseButton.LEFT -> 1 shl 0
            MouseButton.RIGHT -> 1 shl 1
            MouseButton.MIDDLE -> 1 shl 2
        }
    }

    private data class HidSession(
        val process: Process,
        val writer: BufferedWriter,
        val logDrainer: Thread,
    )

    private companion object {
        const val TAG = "ShellHidDeviceWriter"
        const val SYSTEM_SH = "/system/bin/sh"
    }
}
