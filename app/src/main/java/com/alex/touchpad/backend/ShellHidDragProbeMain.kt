package com.alex.touchpad.backend

import android.os.SystemClock
import com.alex.touchpad.input.MouseButton
import kotlin.math.abs
import kotlin.system.exitProcess

object ShellHidDragProbeMain {
    private const val SYSTEM_SH = "/system/bin/sh"
    private const val PROCESS_TIMEOUT_POINTER_DUMPSYS_MS = 500L
    private const val PROCESS_TIMEOUT_FULL_POINTER_DUMPSYS_MS = 1_500L
    private const val FULL_POINTER_DUMPSYS_COMMAND = "dumpsys input"

    @JvmStatic
    fun main(args: Array<String>) {
        val writer = ShellHidDeviceWriter()
        val before = sampleCursorGroundTruth() ?: run {
            println("ERR before_ground_truth_unavailable")
            exitProcess(1)
        }
        val plan = selectMovePlan(before)

        if (!writer.executeButton(MouseButton.LEFT, true)) {
            println("ERR button_down_failed")
            exitProcess(1)
        }

        try {
            repeat(plan.steps) {
                if (!writer.executeMove(plan.dxPerStep, plan.dyPerStep, hidMoveChunkSize = 8, mouseAccelerationEnabled = false)) {
                    println("ERR move_failed step=$it")
                    exitProcess(1)
                }
            }
            Thread.sleep(150L)
            val after = awaitShiftedGroundTruth(before = before, axis = plan.axis, minimumDeltaPx = 80f)
                ?: run {
                    println(
                        "ERR no_shift beforeX=${before.x} beforeY=${before.y} " +
                            "axis=${plan.axis} dx=${plan.dxPerStep} dy=${plan.dyPerStep} steps=${plan.steps}",
                    )
                    exitProcess(1)
                }
            println(
                "OK beforeX=${before.x} beforeY=${before.y} afterX=${after.x} afterY=${after.y} " +
                    "axis=${plan.axis} dx=${plan.dxPerStep} dy=${plan.dyPerStep} steps=${plan.steps}",
            )
            exitProcess(0)
        } finally {
            writer.executeButton(MouseButton.LEFT, false)
        }
    }

    private fun awaitShiftedGroundTruth(
        before: CursorGroundTruth,
        axis: Axis,
        minimumDeltaPx: Float,
    ): CursorGroundTruth? {
        repeat(20) {
            val current = sampleCursorGroundTruth() ?: return@repeat
            if (movedEnough(before, current, axis, minimumDeltaPx)) {
                return current
            }
            Thread.sleep(100L)
        }
        return null
    }

    private fun movedEnough(
        before: CursorGroundTruth,
        after: CursorGroundTruth,
        axis: Axis,
        minimumDeltaPx: Float,
    ): Boolean {
        return when (axis) {
            Axis.X -> abs(after.x - before.x) >= minimumDeltaPx
            Axis.Y -> abs(after.y - before.y) >= minimumDeltaPx
        }
    }

    private fun selectMovePlan(groundTruth: CursorGroundTruth): MovePlan {
        val leftRoom = groundTruth.x
        val rightRoom = groundTruth.widthPx - groundTruth.x
        val upRoom = groundTruth.y
        val downRoom = groundTruth.heightPx - groundTruth.y

        return when (maxOf(leftRoom, rightRoom, upRoom, downRoom)) {
            rightRoom -> MovePlan(axis = Axis.X, dxPerStep = 60, dyPerStep = 0, steps = 4)
            leftRoom -> MovePlan(axis = Axis.X, dxPerStep = -60, dyPerStep = 0, steps = 4)
            downRoom -> MovePlan(axis = Axis.Y, dxPerStep = 0, dyPerStep = 60, steps = 4)
            else -> MovePlan(axis = Axis.Y, dxPerStep = 0, dyPerStep = -60, steps = 4)
        }
    }

    private fun sampleCursorGroundTruth(): CursorGroundTruth? {
        val fastResult = runProcess(
            args = listOf(SYSTEM_SH, "-c", CursorGroundTruthParser.FAST_POINTER_DUMPSYS_COMMAND),
            timeoutMs = PROCESS_TIMEOUT_POINTER_DUMPSYS_MS,
        )
        if (fastResult.output.isNotBlank()) {
            CursorGroundTruthParser.parseGroundTruthFromDumpsys(
                dump = fastResult.output,
                sampledAtMs = SystemClock.elapsedRealtime(),
            )?.let { return it }
        }

        val fullResult = runProcess(
            args = listOf(SYSTEM_SH, "-c", FULL_POINTER_DUMPSYS_COMMAND),
            timeoutMs = PROCESS_TIMEOUT_FULL_POINTER_DUMPSYS_MS,
        )
        if (fullResult.output.isNotBlank()) {
            CursorGroundTruthParser.parseGroundTruthFromDumpsys(
                dump = fullResult.output,
                sampledAtMs = SystemClock.elapsedRealtime(),
            )?.let { return it }
        }
        return null
    }

    private fun runProcess(
        args: List<String>,
        timeoutMs: Long,
    ): ProcessResult {
        return runCatching {
            val process = ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val outputReader = kotlin.concurrent.thread(
                start = true,
                isDaemon = true,
                name = "shell-hid-drag-probe-output",
            ) {
                runCatching {
                    process.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            output.appendLine(line)
                        }
                    }
                }
            }
            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            outputReader.join(250L)
            ProcessResult(
                exitCode = if (finished) process.exitValue() else -1,
                output = output.toString(),
            )
        }.getOrDefault(ProcessResult(exitCode = -1, output = ""))
    }

    private data class ProcessResult(
        val exitCode: Int,
        val output: String,
    )

    private data class MovePlan(
        val axis: Axis,
        val dxPerStep: Int,
        val dyPerStep: Int,
        val steps: Int,
    )

    private enum class Axis {
        X,
        Y,
    }

}
