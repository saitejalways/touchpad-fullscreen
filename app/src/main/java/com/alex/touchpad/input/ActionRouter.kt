package com.alex.touchpad.input

import android.os.SystemClock
import com.alex.touchpad.core.AppLog as Log
import com.alex.touchpad.adb.AdbCommand
import com.alex.touchpad.adb.AdbInjectionBackend
import java.util.concurrent.atomic.AtomicLong

class ActionRouter(
    private val backend: AdbInjectionBackend,
) {
    private val seq = AtomicLong(0L)
    private val moveProducedCount = AtomicLong(0L)
    private val moveProducedDxSum = AtomicLong(0L)
    private val moveProducedDySum = AtomicLong(0L)
    private val lastStatsLogAtMs = AtomicLong(0L)

    fun route(actions: List<InputAction>) {
        actions.forEach(::route)
    }

    fun route(action: InputAction) {
        val nextSeq = seq.incrementAndGet()
        val now = SystemClock.elapsedRealtime()
        if (action is InputAction.MoveBy) {
            moveProducedCount.incrementAndGet()
            moveProducedDxSum.addAndGet(action.dx.toLong())
            moveProducedDySum.addAndGet(action.dy.toLong())
            maybeLogMoveStats(now)
        }
        val command = when (action) {
            is InputAction.MoveBy -> AdbCommand.MoveRel(action.dx, action.dy, nextSeq, now)
            is InputAction.ScrollBy -> AdbCommand.ScrollWheel(action.dy, action.dx, nextSeq, now)
            is InputAction.ScrollFling -> null
            is InputAction.TouchContact -> AdbCommand.TouchContact(action.active, nextSeq, now)
            is InputAction.TouchDelta -> AdbCommand.TouchDelta(action.dx, action.dy, nextSeq, now)
            is InputAction.ButtonDown -> AdbCommand.ButtonDown(action.button, nextSeq, now)
            is InputAction.ButtonUp -> AdbCommand.ButtonUp(action.button, nextSeq, now)
            is InputAction.Click -> AdbCommand.Click(action.button, nextSeq, now)
            is InputAction.Haptic -> null
        }
        if (command != null) {
            backend.enqueue(command)
        }
    }

    private fun maybeLogMoveStats(nowMs: Long) {
        val previous = lastStatsLogAtMs.get()
        if (nowMs - previous < MOVE_STATS_LOG_INTERVAL_MS) {
            return
        }
        if (!lastStatsLogAtMs.compareAndSet(previous, nowMs)) {
            return
        }
        Log.i(
            TAG,
            "move_produced count=${moveProducedCount.get()} " +
                "sumDx=${moveProducedDxSum.get()} sumDy=${moveProducedDySum.get()} seq=${seq.get()}",
        )
    }

    private companion object {
        const val TAG = "ActionRouter"
        const val MOVE_STATS_LOG_INTERVAL_MS = 1500L
    }
}
