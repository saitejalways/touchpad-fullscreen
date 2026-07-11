package com.alex.touchpad.adb

import android.os.SystemClock
import com.alex.touchpad.core.AppLog as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

interface AdbInjectionBackend {
    fun enqueue(command: AdbCommand)
}

class QueuedAdbInjectionBackend(
    private val scope: CoroutineScope,
    private val transport: AdbTransport,
    private val safetyController: SafetyController,
    private val maxQueueDepth: Int = 256,
) : AdbInjectionBackend {
    private val queue = mutableListOf<AdbCommand>()
    private val lock = Any()
    private val wakeSignal = Channel<Unit>(capacity = Channel.CONFLATED)
    private val moveEnqueuedCount = AtomicLong(0L)
    private val moveEnqueuedDxSum = AtomicLong(0L)
    private val moveEnqueuedDySum = AtomicLong(0L)
    private val moveSentCount = AtomicLong(0L)
    private val moveSentDxSum = AtomicLong(0L)
    private val moveSentDySum = AtomicLong(0L)
    private val moveSendFailedCount = AtomicLong(0L)
    private val moveRetrySuccessCount = AtomicLong(0L)
    private val moveRetryAttemptCount = AtomicLong(0L)
    private val moveCompactionCount = AtomicLong(0L)
    private val moveCompactionDxSum = AtomicLong(0L)
    private val moveCompactionDySum = AtomicLong(0L)
    private val stopMarkerDropCount = AtomicLong(0L)
    private val lastStatsLogAtMs = AtomicLong(0L)

    init {
        scope.launch {
            for (ignored in wakeSignal) {
                while (true) {
                    val next = popNext() ?: break
                    val success = sendWithRetry(next)
                    safetyController.onDispatchResult(success)
                    if (next is AdbCommand.MoveRel) {
                        if (success) {
                            moveSentCount.incrementAndGet()
                            moveSentDxSum.addAndGet(next.dx.toLong())
                            moveSentDySum.addAndGet(next.dy.toLong())
                        } else {
                            moveSendFailedCount.incrementAndGet()
                        }
                        maybeLogMoveStats()
                    }
                }
            }
        }
    }

    override fun enqueue(command: AdbCommand) {
        synchronized(lock) {
            if (command is AdbCommand.MoveRel) {
                moveEnqueuedCount.incrementAndGet()
                moveEnqueuedDxSum.addAndGet(command.dx.toLong())
                moveEnqueuedDySum.addAndGet(command.dy.toLong())
                maybeLogMoveStats()
            }
            if (command is AdbCommand.ScrollWheel && command.vWheel == 0 && command.hWheel == 0) {
                val lastScroll = queue.lastOrNull() as? AdbCommand.ScrollWheel
                if (lastScroll != null && isStopMarker(lastScroll)) {
                    return@synchronized
                }
                if (queue.size >= maxQueueDepth) {
                    if (compactOldestMovePair()) {
                        queue.add(command)
                        return@synchronized
                    }

                    val staleScrollIndex = queue.indexOfFirst {
                        val scroll = it as? AdbCommand.ScrollWheel
                        scroll != null && !isStopMarker(scroll)
                    }
                    if (staleScrollIndex >= 0) {
                        queue.removeAt(staleScrollIndex)
                        queue.add(command)
                        return@synchronized
                    }

                    val existingStopIndex = queue.indexOfFirst {
                        val scroll = it as? AdbCommand.ScrollWheel
                        scroll != null && isStopMarker(scroll)
                    }
                    if (existingStopIndex >= 0) {
                        queue[existingStopIndex] = command
                        return@synchronized
                    }

                    // Queue is saturated with pointer/button commands. Don't drop movement to enqueue
                    // a stop marker: lost MoveRel deltas produce visible cursor drift.
                    stopMarkerDropCount.incrementAndGet()
                    maybeLogMoveStats(force = true)
                    return@synchronized
                }
                queue.add(command)
            } else {
                if (
                    command is AdbCommand.MoveRel ||
                    command is AdbCommand.ButtonDown ||
                    command is AdbCommand.ButtonUp ||
                    command is AdbCommand.Click ||
                    command is AdbCommand.TouchContact
                ) {
                    queue.removeAll { queued ->
                        val scroll = queued as? AdbCommand.ScrollWheel ?: return@removeAll false
                        !isStopMarker(scroll)
                    }
                }
                if (mergeWithTail(command)) {
                    return@synchronized
                }
                if (command is AdbCommand.MoveRel && compactMoveQueueForOverflow(command)) {
                    return@synchronized
                }

                if (queue.size >= maxQueueDepth && shouldDrop(command)) {
                    return@synchronized
                }

                if (queue.size >= maxQueueDepth) {
                    val staleIndex = queue.indexOfFirst {
                        it is AdbCommand.MoveRel || (it is AdbCommand.ScrollWheel && !isStopMarker(it))
                    }
                    if (staleIndex >= 0) {
                        queue.removeAt(staleIndex)
                    } else {
                        queue.removeAt(0)
                    }
                }

                queue.add(command)
            }
        }
        wakeSignal.trySend(Unit)
    }

    private fun shouldDrop(command: AdbCommand): Boolean {
        // Never drop fresh pointer deltas: dropping newest move commands keeps stale motion alive
        // and is perceived as acceleration/kick. Under pressure, prefer evicting older stale items.
        return command is AdbCommand.ScrollWheel
    }

    private fun compactMoveQueueForOverflow(incoming: AdbCommand.MoveRel): Boolean {
        if (queue.size < maxQueueDepth) {
            return false
        }

        if (compactOldestMovePair()) {
            queue.add(incoming)
            return true
        }

        val onlyIndex = queue.indexOfFirst { it is AdbCommand.MoveRel }
        if (onlyIndex < 0) {
            return false
        }
        val only = queue[onlyIndex] as AdbCommand.MoveRel
        queue[onlyIndex] = AdbCommand.MoveRel(
            dx = only.dx + incoming.dx,
            dy = only.dy + incoming.dy,
            seq = incoming.seq,
            ts = incoming.ts,
        )
        return true
    }

    private fun compactOldestMovePair(): Boolean {
        var firstIndex = -1
        var secondIndex = -1
        for (index in queue.indices) {
            if (queue[index] is AdbCommand.MoveRel) {
                if (firstIndex < 0) {
                    firstIndex = index
                } else {
                    secondIndex = index
                    break
                }
            }
        }
        if (firstIndex < 0 || secondIndex < 0) {
            return false
        }

        val first = queue[firstIndex] as AdbCommand.MoveRel
        val second = queue[secondIndex] as AdbCommand.MoveRel
        moveCompactionCount.incrementAndGet()
        moveCompactionDxSum.addAndGet(second.dx.toLong())
        moveCompactionDySum.addAndGet(second.dy.toLong())
        queue[firstIndex] = AdbCommand.MoveRel(
            dx = first.dx + second.dx,
            dy = first.dy + second.dy,
            seq = second.seq,
            ts = second.ts,
        )
        queue.removeAt(secondIndex)
        return true
    }

    private fun popNext(): AdbCommand? {
        return synchronized(lock) {
            if (queue.isEmpty()) {
                return@synchronized null
            }

            var command = queue.removeAt(0)
            if (command is AdbCommand.ScrollWheel) {
                if (isStopMarker(command)) {
                    return@synchronized command
                }
                var aggregateVWheel = command.vWheel
                var aggregateHWheel = command.hWheel
                var lastMergedVWheel = command.vWheel
                var lastMergedHWheel = command.hWheel
                var latestSeq = command.seq
                var latestTs = command.ts
                while (true) {
                    val next = queue.firstOrNull() as? AdbCommand.ScrollWheel ?: break
                    if (isStopMarker(next)) {
                        break
                    }
                    if (!canMergeScroll(lastMergedVWheel, lastMergedHWheel, next.vWheel, next.hWheel)) {
                        break
                    }
                    queue.removeAt(0)
                    aggregateVWheel += next.vWheel
                    aggregateHWheel += next.hWheel
                    lastMergedVWheel = next.vWheel
                    lastMergedHWheel = next.hWheel
                    latestSeq = next.seq
                    latestTs = next.ts
                }
                command = AdbCommand.ScrollWheel(
                    vWheel = aggregateVWheel,
                    hWheel = aggregateHWheel,
                    seq = latestSeq,
                    ts = latestTs,
                )
            }
            command
        }
    }

    private fun mergeWithTail(command: AdbCommand): Boolean {
        val lastIndex = queue.lastIndex
        if (lastIndex < 0) {
            return false
        }
        return when (command) {
            // Keep cursor movement frame-like; summing move deltas can feel like acceleration/kick.
            is AdbCommand.MoveRel -> false

            is AdbCommand.ScrollWheel -> {
                val last = queue[lastIndex] as? AdbCommand.ScrollWheel ?: return false
                if (isStopMarker(last)) {
                    return false
                }
                if (!canMergeScroll(last.vWheel, last.hWheel, command.vWheel, command.hWheel)) {
                    return false
                }
                queue[lastIndex] = AdbCommand.ScrollWheel(
                    vWheel = last.vWheel + command.vWheel,
                    hWheel = last.hWheel + command.hWheel,
                    seq = command.seq,
                    ts = command.ts,
                )
                true
            }

            else -> false
        }
    }

    private fun isStopMarker(command: AdbCommand.ScrollWheel): Boolean {
        return command.vWheel == 0 && command.hWheel == 0
    }

    private fun canMergeScroll(prevDx: Int, prevDy: Int, nextDx: Int, nextDy: Int): Boolean {
        return canMergeAxis(prevDx, nextDx) && canMergeAxis(prevDy, nextDy)
    }

    private fun canMergeAxis(previous: Int, next: Int): Boolean {
        if (previous == 0 || next == 0) {
            return true
        }
        return (previous > 0 && next > 0) || (previous < 0 && next < 0)
    }

    private suspend fun sendWithRetry(command: AdbCommand): Boolean {
        val maxAttempts = when (command) {
            is AdbCommand.MoveRel -> MOVE_RETRY_ATTEMPTS
            else -> 1
        }
        var attempt = 0
        while (attempt < maxAttempts) {
            if (transport.send(command)) {
                if (command is AdbCommand.MoveRel && attempt > 0) {
                    moveRetrySuccessCount.incrementAndGet()
                    moveRetryAttemptCount.addAndGet(attempt.toLong())
                }
                return true
            }
            attempt += 1
        }
        if (command is AdbCommand.MoveRel && attempt > 1) {
            moveRetryAttemptCount.addAndGet((attempt - 1).toLong())
        }
        return false
    }

    private fun maybeLogMoveStats(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastStatsLogAtMs.get()
        if (!force && now - previous < MOVE_STATS_LOG_INTERVAL_MS) {
            return
        }
        if (!lastStatsLogAtMs.compareAndSet(previous, now)) {
            return
        }
        Log.i(
            TAG,
            "move_stats enq=${moveEnqueuedCount.get()} sent=${moveSentCount.get()} fail=${moveSendFailedCount.get()} " +
                "sumEnq=(${moveEnqueuedDxSum.get()},${moveEnqueuedDySum.get()}) " +
                "sumSent=(${moveSentDxSum.get()},${moveSentDySum.get()}) " +
                "retryOk=${moveRetrySuccessCount.get()} retryAttempts=${moveRetryAttemptCount.get()} " +
                "compactions=${moveCompactionCount.get()} compactionDelta=(${moveCompactionDxSum.get()},${moveCompactionDySum.get()}) " +
                "stopDrop=${stopMarkerDropCount.get()} qSize=${synchronized(lock) { queue.size }}",
        )
    }

    private companion object {
        const val TAG = "QueuedAdbBackend"
        const val MOVE_STATS_LOG_INTERVAL_MS = 1500L
        // Recover from transient write/process hiccups without dropping pointer deltas.
        const val MOVE_RETRY_ATTEMPTS = 2
    }
}
