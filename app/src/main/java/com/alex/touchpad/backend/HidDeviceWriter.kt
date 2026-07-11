package com.alex.touchpad.backend

import com.alex.touchpad.core.AppLog as Log
import com.alex.touchpad.input.MouseButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedWriter
import java.io.OutputStreamWriter

class HidDeviceWriter(
    private val adbProcessManager: AdbProcessManager,
    private val hidMoveChunkSize: Int,
    private val mouseAccelerationEnabled: Boolean,
) {
    private val hidMutex = Mutex()
    private var hidSession: HidSession? = null
    private var hidButtonMask = 0

    suspend fun executeMove(dx: Int, dy: Int): Result<Unit> {
        if (dx == 0 && dy == 0) {
            return Result.success(Unit)
        }
        return withHidSession { session ->
            writeHidRelativeLocked(session, dx = dx, dy = dy, wheel = 0, hWheel = 0)
        }
    }

    suspend fun executeClick(button: MouseButton): Result<Unit> {
        val bit = button.toHidBit() ?: return Result.failure(Exception("Unsupported button $button"))
        return withHidSession { session ->
            val originalMask = hidButtonMask
            val downMask = originalMask or bit
            hidButtonMask = downMask
            if (!writeHidReportLocked(session, downMask, dx = 0, dy = 0, wheel = 0, hWheel = 0)) {
                return@withHidSession false
            }
            if (!writeHidDelayLocked(session, HID_CLICK_GAP_MS)) {
                return@withHidSession false
            }
            val upMask = originalMask and bit.inv()
            hidButtonMask = upMask
            writeHidReportLocked(session, upMask, dx = 0, dy = 0, wheel = 0, hWheel = 0)
        }
    }

    suspend fun executeButton(button: MouseButton, isDown: Boolean): Result<Unit> {
        val bit = button.toHidBit() ?: return Result.failure(Exception("Unsupported button $button"))
        return withHidSession { session ->
            hidButtonMask = if (isDown) {
                hidButtonMask or bit
            } else {
                hidButtonMask and bit.inv()
            }
            writeHidReportLocked(session, hidButtonMask, dx = 0, dy = 0, wheel = 0, hWheel = 0)
        }
    }

    suspend fun executeScroll(vWheel: Int, hWheel: Int): Result<Unit> {
        if (vWheel == 0 && hWheel == 0) {
            return Result.success(Unit)
        }
        return withHidSession { session ->
            writeHidRelativeLocked(session, dx = 0, dy = 0, wheel = vWheel, hWheel = hWheel)
        }
    }

    private suspend fun withHidSession(block: suspend (HidSession) -> Boolean): Result<Unit> {
        return hidMutex.withLock {
            val session = ensureHidSessionLocked() ?: return@withLock Result.failure(Exception("Failed to ensure HID session"))
            val success = block(session)
            if (!success) {
                closeHidSessionLocked("hid write failed")
                Result.failure(Exception("HID Write Failed"))
            } else {
                Result.success(Unit)
            }
        }
    }

    private suspend fun ensureHidSessionLocked(): HidSession? {
        val requestedSerial = FIXED_TARGET_SERIAL

        val existing = hidSession
        if (existing != null) {
            if (existing.requestedSerial == requestedSerial && existing.process.isAlive) {
                return existing
            }
            closeHidSessionLocked("stale hid process")
        }

        val adbBinary = adbProcessManager.ensureAdbBinary() ?: return null
        val commandSerial = adbProcessManager.ensureTargetConnected(
            requestedSerial = requestedSerial,
        ) ?: run {
            return null
        }

        val args = listOf(
            adbBinary.absolutePath,
            "-s",
            commandSerial,
            "shell",
            "hid",
            "-",
        )

        val process = adbProcessManager.startProcess(args) ?: run {
            return null
        }
        val writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
        val logDrainer = startHidOutputDrainer(process)
        val session = HidSession(
            requestedSerial = requestedSerial,
            commandSerial = commandSerial,
            process = process,
            writer = writer,
            logDrainer = logDrainer,
        )
        hidSession = session
        hidButtonMask = 0

        val registered = writeHidRegisterLocked(session) && writeHidDelayLocked(session, HID_REGISTER_DELAY_MS)
        if (!registered) {
            closeHidSessionLocked("hid register failed")
            return null
        }

        Log.i(TAG, "HID mouse session started requestedSerial=$requestedSerial commandSerial=$commandSerial")
        return session
    }

    private suspend fun writeHidRegisterLocked(session: HidSession): Boolean {
        val descriptor = HID_MOUSE_DESCRIPTOR.joinToString(",")
        val jsonLine = buildString {
            append("{\"id\":")
            append(HID_DEVICE_ID)
            append(",\"command\":\"register\",\"name\":\"")
            append(HID_DEVICE_NAME)
            append("\",\"vid\":")
            append(HID_VENDOR_ID)
            append(",\"pid\":")
            append(HID_PRODUCT_ID)
            append(",\"bus\":\"usb\",\"descriptor\":[")
            append(descriptor)
            append("]}")
        }
        return writeHidJsonLineLocked(session, jsonLine)
    }

    private suspend fun writeHidRelativeLocked(
        session: HidSession,
        dx: Int,
        dy: Int,
        wheel: Int,
        hWheel: Int,
    ): Boolean {
        var remainingDx = dx
        var remainingDy = dy
        var remainingWheel = wheel
        var remainingHWheel = hWheel
        val moveChunkLimit = if (mouseAccelerationEnabled) {
            HID_REL_AXIS_MAX
        } else {
            hidMoveChunkSize
                .coerceAtLeast(1)
                .coerceAtMost(HID_REL_AXIS_MAX)
        }

        while (remainingDx != 0 || remainingDy != 0 || remainingWheel != 0 || remainingHWheel != 0) {
            val chunkDx = remainingDx.coerceIn(-moveChunkLimit, moveChunkLimit)
            val chunkDy = remainingDy.coerceIn(-moveChunkLimit, moveChunkLimit)
            val chunkWheel = remainingWheel.coerceIn(-HID_REL_AXIS_MAX, HID_REL_AXIS_MAX)
            val chunkHWheel = remainingHWheel.coerceIn(-HID_REL_AXIS_MAX, HID_REL_AXIS_MAX)

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

    private suspend fun writeHidReportLocked(
        session: HidSession,
        buttons: Int,
        dx: Int,
        dy: Int,
        wheel: Int,
        hWheel: Int,
    ): Boolean {
        val jsonLine = buildString {
            append("{\"id\":")
            append(HID_DEVICE_ID)
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

    private suspend fun writeHidDelayLocked(session: HidSession, durationMs: Int): Boolean {
        val jsonLine = buildString {
            append("{\"id\":")
            append(HID_DEVICE_ID)
            append(",\"command\":\"delay\",\"duration\":")
            append(durationMs)
            append("}")
        }
        val written = writeHidJsonLineLocked(session, jsonLine)
        if (written) {
            delay(durationMs.toLong())
        }
        return written
    }

    private suspend fun writeHidJsonLineLocked(session: HidSession, line: String): Boolean {
        if (!session.process.isAlive) {
            return false
        }
        return runCatching {
            session.writer.write(line)
            session.writer.write("\n")
            session.writer.flush()
            true
        }.getOrElse { error ->
            Log.w(TAG, "HID write failed", error)
            false
        }
    }

    private fun startHidOutputDrainer(process: Process): Thread {
        val thread = Thread {
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
        thread.name = "HidDrainer"
        thread.isDaemon = true
        thread.start()
        return thread
    }

    private fun closeHidSessionLocked(reason: String) {
        val session = hidSession ?: return
        Log.i(TAG, "Closing HID session reason=$reason serial=${session.commandSerial}")
        runCatching { session.writer.close() }
        if (session.process.isAlive) {
            runCatching { session.process.destroy() }
        }
        hidSession = null
        hidButtonMask = 0
    }

    private fun MouseButton.toHidBit(): Int? {
        return when (this) {
            MouseButton.LEFT -> 1 shl 0
            MouseButton.RIGHT -> 1 shl 1
            MouseButton.MIDDLE -> 1 shl 2
            else -> null
        }
    }

    private fun toUnsignedByte(value: Int): Int {
        return value and 0xFF
    }

    fun getActiveCommandSerial(): String? {
        return hidSession?.takeIf { it.process.isAlive }?.commandSerial
    }

    private data class HidSession(
        val requestedSerial: String,
        val commandSerial: String,
        val process: Process,
        val writer: BufferedWriter,
        val logDrainer: Thread,
    )

    companion object {
        private const val TAG = "HidDeviceWriter"
        const val HID_DEVICE_ID = 1
        const val HID_VENDOR_ID = 6353
        const val HID_PRODUCT_ID = 20001
        const val HID_DEVICE_NAME = "Touchpad Virtual Mouse"
        const val HID_REL_AXIS_MAX = 127
        const val HID_REGISTER_DELAY_MS = 40
        const val HID_CLICK_GAP_MS = 8

        const val FIXED_TARGET_SERIAL = "127.0.0.1:5555"

        val HID_MOUSE_DESCRIPTOR = listOf(
            0x05, 0x01, // Usage Page (Generic Desktop)
            0x09, 0x02, // Usage (Mouse)
            0xA1, 0x01, // Collection (Application)
            0x09, 0x01, // Usage (Pointer)
            0xA1, 0x00, // Collection (Physical)

            0x05, 0x09, // Usage Page (Button)
            0x19, 0x01, // Usage Minimum (1)
            0x29, 0x03, // Usage Maximum (3)
            0x15, 0x00, // Logical Minimum (0)
            0x25, 0x01, // Logical Maximum (1)
            0x95, 0x03, // Report Count (3)
            0x75, 0x01, // Report Size (1)
            0x81, 0x02, // Input (Data,Var,Abs)

            0x95, 0x01, // Report Count (1)
            0x75, 0x05, // Report Size (5)
            0x81, 0x01, // Input (Const,Array,Abs)

            0x05, 0x01, // Usage Page (Generic Desktop)
            0x09, 0x30, // Usage (X)
            0x09, 0x31, // Usage (Y)
            0x09, 0x38, // Usage (Wheel)
            0x15, 0x81, // Logical Minimum (-127)
            0x25, 0x7F, // Logical Maximum (127)
            0x75, 0x08, // Report Size (8)
            0x95, 0x03, // Report Count (3)
            0x81, 0x06, // Input (Data,Var,Rel)

            0x05, 0x0C, // Usage Page (Consumer)
            0x0A, 0x38, 0x02, // Usage (AC Pan)
            0x15, 0x81, // Logical Minimum (-127)
            0x25, 0x7F, // Logical Maximum (127)
            0x75, 0x08, // Report Size (8)
            0x95, 0x01, // Report Count (1)
            0x81, 0x06, // Input (Data,Var,Rel)

            0xC0, // End Collection
            0xC0, // End Collection
        ).map { it and 0xFF }
    }
}
