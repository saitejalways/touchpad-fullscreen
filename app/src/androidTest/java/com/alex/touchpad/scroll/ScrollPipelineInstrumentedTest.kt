package com.alex.touchpad.scroll

import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alex.touchpad.adb.AdbCommand
import com.alex.touchpad.adb.AdbTransport
import com.alex.touchpad.adb.QueuedAdbInjectionBackend
import com.alex.touchpad.adb.SafetyController
import com.alex.touchpad.core.CursorStateStore
import com.alex.touchpad.input.ActionRouter
import com.alex.touchpad.input.InputAction
import com.alex.touchpad.input.TouchpadEngine
import com.alex.touchpad.settings.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.Random

@RunWith(AndroidJUnit4::class)
class ScrollPipelineInstrumentedTest {

    @Test
    fun stopMarkerPurgesTail_noFurtherScrollAfterStop() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = RecordingTransport(perSendDelayMs = 2L)
        val backend = QueuedAdbInjectionBackend(
            scope = scope,
            transport = transport,
            safetyController = SafetyController(),
        )

        val baseTs = SystemClock.elapsedRealtime()
        repeat(180) { i ->
            backend.enqueue(
                AdbCommand.Scroll(
                    dx = 0,
                    dy = (i % 7) + 1,
                    seq = i + 1L,
                    ts = baseTs + i,
                )
            )
        }
        backend.enqueue(AdbCommand.Scroll(dx = 0, dy = 0, seq = 9999L, ts = baseTs + 9999))

        val stopSeen = waitUntilTrue(timeoutMs = 3_000L) {
            transport.snapshot().any {
                it is AdbCommand.Scroll && it.dx == 0 && it.dy == 0
            }
        }

        val commands = transport.snapshot()
        val recentScrolls = commands
            .filterIsInstance<AdbCommand.Scroll>()
            .takeLast(8)
            .joinToString(prefix = "[", postfix = "]") { "(dx=${it.dx},dy=${it.dy},seq=${it.seq})" }
        assertTrue(
            "Expected stop-marker scroll to be dispatched; total=${commands.size} recent=$recentScrolls",
            stopSeen,
        )
        val stopIndex = commands.indexOfFirst {
            it is AdbCommand.Scroll && it.dx == 0 && it.dy == 0
        }

        assertTrue("Expected stop-marker scroll index >= 0", stopIndex >= 0)

        val hasNonZeroAfterStop = commands
            .drop(stopIndex + 1)
            .filterIsInstance<AdbCommand.Scroll>()
            .any { it.dx != 0 || it.dy != 0 }

        assertFalse("Found non-zero scroll after stop marker", hasNonZeroAfterStop)

        scope.cancel()
    }

    @Test
    fun stopMarkerPurgesTail_noFurtherHorizontalScrollAfterStop() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = RecordingTransport(perSendDelayMs = 2L)
        val backend = QueuedAdbInjectionBackend(
            scope = scope,
            transport = transport,
            safetyController = SafetyController(),
        )

        val baseTs = SystemClock.elapsedRealtime()
        repeat(180) { i ->
            backend.enqueue(
                AdbCommand.Scroll(
                    dx = (i % 7) + 1,
                    dy = 0,
                    seq = i + 1L,
                    ts = baseTs + i,
                )
            )
        }
        backend.enqueue(AdbCommand.Scroll(dx = 0, dy = 0, seq = 9999L, ts = baseTs + 9999))

        val stopSeen = waitUntilTrue(timeoutMs = 3_000L) {
            transport.snapshot().any {
                it is AdbCommand.Scroll && it.dx == 0 && it.dy == 0
            }
        }

        val commands = transport.snapshot()
        assertTrue("Expected horizontal stop-marker scroll to be dispatched; total=${commands.size}", stopSeen)
        val stopIndex = commands.indexOfFirst {
            it is AdbCommand.Scroll && it.dx == 0 && it.dy == 0
        }
        assertTrue("Expected stop-marker scroll index >= 0", stopIndex >= 0)

        val hasNonZeroAfterStop = commands
            .drop(stopIndex + 1)
            .filterIsInstance<AdbCommand.Scroll>()
            .any { it.dx != 0 || it.dy != 0 }
        assertFalse("Found non-zero horizontal scroll after stop marker", hasNonZeroAfterStop)

        scope.cancel()
    }

    @Test
    fun coalescing_accumulatesVerticalScrollMagnitude() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val releaseFirstSend = CompletableDeferred<Unit>()
        val transport = RecordingTransport(blockFirstSend = releaseFirstSend)
        val backend = QueuedAdbInjectionBackend(
            scope = scope,
            transport = transport,
            safetyController = SafetyController(),
        )

        val baseTs = SystemClock.elapsedRealtime()
        backend.enqueue(AdbCommand.Scroll(dx = 0, dy = 11, seq = 1L, ts = baseTs + 1))
        backend.enqueue(AdbCommand.Scroll(dx = 0, dy = 22, seq = 2L, ts = baseTs + 2))
        backend.enqueue(AdbCommand.Scroll(dx = 0, dy = 33, seq = 3L, ts = baseTs + 3))

        // Let queue accept and merge tail while first send is blocked.
        delay(120)
        releaseFirstSend.complete(Unit)

        val enoughScrolls = waitUntilTrue(timeoutMs = 2_000L) {
            transport.snapshot().any { it is AdbCommand.Scroll }
        }
        assertTrue("Expected at least one dispatched scroll command", enoughScrolls)

        val allMagnitudeSeen = waitUntilTrue(timeoutMs = 2_000L) {
            transport.snapshot()
                .filterIsInstance<AdbCommand.Scroll>()
                .sumOf { it.dy } >= 66
        }
        val scrolls = transport.snapshot().filterIsInstance<AdbCommand.Scroll>()
        val totalDy = scrolls.sumOf { it.dy }
        assertTrue(
            "Expected accumulated dy to include all enqueued values (66), got totalDy=$totalDy " +
                "sequence=${scrolls.joinToString { it.dy.toString() }}",
            allMagnitudeSeen,
        )

        scope.cancel()
    }

    @Test
    fun coalescing_accumulatesHorizontalScrollMagnitude() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val releaseFirstSend = CompletableDeferred<Unit>()
        val transport = RecordingTransport(blockFirstSend = releaseFirstSend)
        val backend = QueuedAdbInjectionBackend(
            scope = scope,
            transport = transport,
            safetyController = SafetyController(),
        )

        val baseTs = SystemClock.elapsedRealtime()
        backend.enqueue(AdbCommand.Scroll(dx = 11, dy = 0, seq = 1L, ts = baseTs + 1))
        backend.enqueue(AdbCommand.Scroll(dx = 22, dy = 0, seq = 2L, ts = baseTs + 2))
        backend.enqueue(AdbCommand.Scroll(dx = 33, dy = 0, seq = 3L, ts = baseTs + 3))

        delay(120)
        releaseFirstSend.complete(Unit)

        val enoughScrolls = waitUntilTrue(timeoutMs = 2_000L) {
            transport.snapshot().any { it is AdbCommand.Scroll }
        }
        assertTrue("Expected at least one dispatched horizontal scroll command", enoughScrolls)

        val allMagnitudeSeen = waitUntilTrue(timeoutMs = 2_000L) {
            transport.snapshot()
                .filterIsInstance<AdbCommand.Scroll>()
                .sumOf { it.dx } >= 66
        }
        val scrolls = transport.snapshot().filterIsInstance<AdbCommand.Scroll>()
        val totalDx = scrolls.sumOf { it.dx }
        assertTrue(
            "Expected accumulated dx to include all enqueued values (66), got totalDx=$totalDx " +
                "sequence=${scrolls.joinToString { it.dx.toString() }}",
            allMagnitudeSeen,
        )

        scope.cancel()
    }

    @Test
    fun moveRel_isNotCoalescedIntoSingleJump() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val releaseFirstSend = CompletableDeferred<Unit>()
        val transport = RecordingTransport(blockFirstSend = releaseFirstSend)
        val backend = QueuedAdbInjectionBackend(
            scope = scope,
            transport = transport,
            safetyController = SafetyController(),
        )

        val baseTs = SystemClock.elapsedRealtime()
        backend.enqueue(AdbCommand.MoveRel(dx = 3, dy = 0, seq = 1L, ts = baseTs + 1))
        backend.enqueue(AdbCommand.MoveRel(dx = 4, dy = 0, seq = 2L, ts = baseTs + 2))

        delay(120)
        releaseFirstSend.complete(Unit)

        val enoughMoves = waitUntilTrue(timeoutMs = 2_000L) {
            transport.snapshot().filterIsInstance<AdbCommand.MoveRel>().size >= 2
        }
        assertTrue("Expected separate move dispatches without coalescing", enoughMoves)

        val moves = transport.snapshot().filterIsInstance<AdbCommand.MoveRel>()
        assertTrue(
            "Expected first two move deltas to stay discrete (+3, +4); observed=$moves",
            moves.size >= 2 && moves[0].dx == 3 && moves[1].dx == 4,
        )

        scope.cancel()
    }

    @Test
    fun moveRel_queueOverflowEvictsOldest_moveNotNewest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val releaseFirstSend = CompletableDeferred<Unit>()
        val transport = RecordingTransport(blockFirstSend = releaseFirstSend)
        val backend = QueuedAdbInjectionBackend(
            scope = scope,
            transport = transport,
            safetyController = SafetyController(),
            maxQueueDepth = 3,
        )

        val baseTs = SystemClock.elapsedRealtime()
        backend.enqueue(AdbCommand.MoveRel(dx = 5, dy = 0, seq = 1L, ts = baseTs + 1))
        delay(40)
        backend.enqueue(AdbCommand.MoveRel(dx = 5, dy = 0, seq = 2L, ts = baseTs + 2))
        backend.enqueue(AdbCommand.MoveRel(dx = 5, dy = 0, seq = 3L, ts = baseTs + 3))
        backend.enqueue(AdbCommand.MoveRel(dx = 5, dy = 0, seq = 4L, ts = baseTs + 4))
        backend.enqueue(AdbCommand.MoveRel(dx = 5, dy = 0, seq = 5L, ts = baseTs + 5))

        delay(120)
        releaseFirstSend.complete(Unit)

        val enoughMoves = waitUntilTrue(timeoutMs = 2_000L) {
            transport.snapshot().filterIsInstance<AdbCommand.MoveRel>().size >= 3
        }
        assertTrue("Expected compacted move commands to dispatch", enoughMoves)

        val moveSeqs = transport.snapshot()
            .filterIsInstance<AdbCommand.MoveRel>()
            .map { it.seq }
        assertTrue(
            "Expected newest move seq=5 to be preserved under overflow; observed=$moveSeqs",
            moveSeqs.contains(5L),
        )
        assertFalse(
            "Expected oldest queued move seq=2 to be evicted under overflow; observed=$moveSeqs",
            moveSeqs.contains(2L),
        )

        scope.cancel()
    }

    @Test
    fun randomVerticalCenterRoundTrip_returnsCursorToCenter_whenTouchReturnsToCenter() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = RecordingTransport(perSendDelayMs = 1L)
        val backend = QueuedAdbInjectionBackend(
            scope = scope,
            transport = transport,
            safetyController = SafetyController(),
            maxQueueDepth = 256,
        )
        val actionRouter = ActionRouter(backend)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsRepository(context)
        settings.setSpeedMultiplier(1f)
        settings.setMouseAccelerationEnabled(false)
        settings.setOneFingerEdgeScrollEnabled(false)
        settings.setTapToDragEnabled(false)
        settings.setDragFlickKickMs(0)
        settings.setFlingDurationMs(0)
        settings.setDoubleClickGuardMs(0)
        settings.setHoldDragDelayMs(10_000)

        val width = 1080
        val height = 2400
        val centerX = width / 2f
        val centerY = height / 2f
        val cursorStore = CursorStateStore().apply {
            updateBounds(width, height)
            resetToCenter()
        }
        val engine = TouchpadEngine(cursorStore, settings)

        val downTime = 120_000L
        dispatchAndRoute(
            engine = engine,
            actionRouter = actionRouter,
            downTimeMs = downTime,
            eventTimeMs = downTime,
            action = MotionEvent.ACTION_DOWN,
            x = centerX,
            y = centerY,
        )

        val random = Random(7_133L)
        val intervalMs = 10L
        val maxOffsetFromCenterPx = 360
        val explorationSteps = 900
        val correctionStepsLimit = 500
        val fullGestureDeltas = mutableListOf<Int>()
        var runningOffsetY = 0

        // Long random walk around center, bounded to keep edge-free validity.
        repeat(explorationSteps) {
            var accepted = false
            var attempts = 0
            while (!accepted && attempts < 16) {
                var candidate = random.nextInt(25) - 12
                if (candidate == 0) {
                    candidate = if (random.nextBoolean()) 1 else -1
                }
                val nextOffset = runningOffsetY + candidate
                if (abs(nextOffset) <= maxOffsetFromCenterPx) {
                    runningOffsetY = nextOffset
                    fullGestureDeltas += candidate
                    accepted = true
                }
                attempts += 1
            }
            if (!accepted) {
                val candidate = if (runningOffsetY > 0) -1 else 1
                runningOffsetY += candidate
                fullGestureDeltas += candidate
            }
        }

        // Randomized correction back to center (not mirrored replay).
        var correctionSteps = 0
        while (runningOffsetY != 0 && correctionSteps < correctionStepsLimit) {
            val towardCenterSign = if (runningOffsetY > 0) -1 else 1
            val maxMagnitude = kotlin.math.min(12, abs(runningOffsetY)).coerceAtLeast(1)
            val magnitude = 1 + random.nextInt(maxMagnitude)
            val candidate = towardCenterSign * magnitude
            runningOffsetY += candidate
            fullGestureDeltas += candidate
            correctionSteps += 1
        }
        assertTrue(
            "Failed to synthesize long random walk returning to center; offset=$runningOffsetY steps=${fullGestureDeltas.size}",
            runningOffsetY == 0,
        )

        var touchY = centerY
        var eventTime = downTime
        var touchEdgeTouched = false
        var engineMoveDySum = 0
        var engineMoveCount = 0
        fullGestureDeltas.forEach { dy ->
            eventTime += intervalMs
            touchY += dy
            if (touchY <= 1f || touchY >= (height - 1).toFloat()) {
                touchEdgeTouched = true
            }
            val actions = dispatchAndRoute(
                engine = engine,
                actionRouter = actionRouter,
                downTimeMs = downTime,
                eventTimeMs = eventTime,
                action = MotionEvent.ACTION_MOVE,
                x = centerX,
                y = touchY,
            )
            actions.filterIsInstance<InputAction.MoveBy>().forEach { move ->
                engineMoveCount += 1
                engineMoveDySum += move.dy
            }
        }

        eventTime += intervalMs
        dispatchAndRoute(
            engine = engine,
            actionRouter = actionRouter,
            downTimeMs = downTime,
            eventTimeMs = eventTime,
            action = MotionEvent.ACTION_UP,
            x = centerX,
            y = touchY,
        )

        val stopMarkerSeen = waitUntilTrue(timeoutMs = 4_000L) {
            transport.snapshot().any {
                it is AdbCommand.Scroll && it.dx == 0 && it.dy == 0
            }
        }
        assertTrue("Expected stop marker after ACTION_UP to confirm queue drain", stopMarkerSeen)

        val moves = transport.snapshot().filterIsInstance<AdbCommand.MoveRel>()
        var hostX = centerX
        var hostY = centerY
        var hostEdgeTouched = false
        moves.forEach { move ->
            hostX = (hostX + move.dx).coerceIn(0f, width.toFloat())
            hostY = (hostY + move.dy).coerceIn(0f, height.toFloat())
            if (hostX <= 0f || hostX >= width.toFloat() || hostY <= 0f || hostY >= height.toFloat()) {
                hostEdgeTouched = true
            }
        }

        assertTrue("Touch trace unexpectedly hit edge; test is invalid", !touchEdgeTouched)
        assertTrue(
            "Cursor trace hit edge; center-coupling assertion is invalid. moves=${moves.size}",
            !hostEdgeTouched,
        )
        assertTrue(
            "Touch should finish at center. centerY=$centerY touchY=$touchY",
            abs(touchY - centerY) <= 0.001f,
        )
        assertTrue(
            "Engine move output should round-trip to zero; sumDy=$engineMoveDySum count=$engineMoveCount",
            engineMoveDySum == 0,
        )
        assertTrue(
            "Cursor should return near center after 3s random up/down round trip. " +
                "centerY=$centerY hostY=$hostY moveCount=${moves.size} " +
                "engineMoveCount=$engineMoveCount engineMoveDySum=$engineMoveDySum",
            abs(hostY - centerY) <= 1.5f,
        )

        scope.cancel()
    }

    @Test
    fun fastFlickThenSlowReturnFromCenter_staysCenteredAfterLongRun() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = RecordingTransport(perSendDelayMs = 1L)
        val backend = QueuedAdbInjectionBackend(
            scope = scope,
            transport = transport,
            safetyController = SafetyController(),
            maxQueueDepth = 256,
        )
        val actionRouter = ActionRouter(backend)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsRepository(context)
        settings.setSpeedMultiplier(1f)
        settings.setMouseAccelerationEnabled(false)
        settings.setOneFingerEdgeScrollEnabled(false)
        settings.setTapToDragEnabled(false)
        settings.setDragFlickKickMs(0)
        settings.setFlingDurationMs(0)
        settings.setDoubleClickGuardMs(0)
        settings.setHoldDragDelayMs(10_000)

        val width = 1080
        val height = 2400
        val centerX = width / 2f
        val centerY = height / 2f
        val cursorStore = CursorStateStore().apply {
            updateBounds(width, height)
            resetToCenter()
        }
        val engine = TouchpadEngine(cursorStore, settings)

        val downTime = 180_000L
        dispatchAndRoute(
            engine = engine,
            actionRouter = actionRouter,
            downTimeMs = downTime,
            eventTimeMs = downTime,
            action = MotionEvent.ACTION_DOWN,
            x = centerX,
            y = centerY,
        )

        val random = Random(9_031L)
        val cycles = 120
        val fastIntervalMs = 6L
        val slowIntervalMs = 18L
        var eventTime = downTime
        var touchY = centerY
        var touchEdgeTouched = false
        var engineMoveDySum = 0
        var engineMoveCount = 0

        repeat(cycles) { cycle ->
            val direction = if (random.nextBoolean()) 1 else -1
            val targetDistancePx = 90 + random.nextInt(141) // 90..230
            var movedOutPx = 0

            // Fast outward flick.
            while (movedOutPx < targetDistancePx) {
                val step = kotlin.math.min(12 + random.nextInt(19), targetDistancePx - movedOutPx)
                val dy = direction * step
                movedOutPx += step
                eventTime += fastIntervalMs
                touchY += dy.toFloat()
                if (touchY <= 1f || touchY >= (height - 1).toFloat()) {
                    touchEdgeTouched = true
                }
                val actions = dispatchAndRoute(
                    engine = engine,
                    actionRouter = actionRouter,
                    downTimeMs = downTime,
                    eventTimeMs = eventTime,
                    action = MotionEvent.ACTION_MOVE,
                    x = centerX,
                    y = touchY,
                )
                actions.filterIsInstance<InputAction.MoveBy>().forEach { move ->
                    engineMoveCount += 1
                    engineMoveDySum += move.dy
                }
            }

            // Slow return to center.
            var remainingBackPx = movedOutPx
            while (remainingBackPx > 0) {
                val step = kotlin.math.min(1 + random.nextInt(4), remainingBackPx)
                val dy = -direction * step
                remainingBackPx -= step
                eventTime += slowIntervalMs
                touchY += dy.toFloat()
                if (touchY <= 1f || touchY >= (height - 1).toFloat()) {
                    touchEdgeTouched = true
                }
                val actions = dispatchAndRoute(
                    engine = engine,
                    actionRouter = actionRouter,
                    downTimeMs = downTime,
                    eventTimeMs = eventTime,
                    action = MotionEvent.ACTION_MOVE,
                    x = centerX,
                    y = touchY,
                )
                actions.filterIsInstance<InputAction.MoveBy>().forEach { move ->
                    engineMoveCount += 1
                    engineMoveDySum += move.dy
                }
            }

            assertTrue(
                "Touch should be back at center after cycle=$cycle; centerY=$centerY touchY=$touchY",
                abs(touchY - centerY) <= 0.001f,
            )
        }

        eventTime += slowIntervalMs
        dispatchAndRoute(
            engine = engine,
            actionRouter = actionRouter,
            downTimeMs = downTime,
            eventTimeMs = eventTime,
            action = MotionEvent.ACTION_UP,
            x = centerX,
            y = touchY,
        )

        val stopMarkerSeen = waitUntilTrue(timeoutMs = 4_000L) {
            transport.snapshot().any {
                it is AdbCommand.Scroll && it.dx == 0 && it.dy == 0
            }
        }
        assertTrue("Expected stop marker after ACTION_UP to confirm queue drain", stopMarkerSeen)

        val moves = transport.snapshot().filterIsInstance<AdbCommand.MoveRel>()
        var hostY = centerY
        var hostEdgeTouched = false
        moves.forEach { move ->
            hostY = (hostY + move.dy).coerceIn(0f, height.toFloat())
            if (hostY <= 0f || hostY >= height.toFloat()) {
                hostEdgeTouched = true
            }
        }

        assertTrue("Touch trace unexpectedly hit edge; test is invalid", !touchEdgeTouched)
        assertTrue("Cursor trace hit edge; center assertion is invalid", !hostEdgeTouched)
        assertTrue(
            "Engine move output should round-trip to zero; sumDy=$engineMoveDySum count=$engineMoveCount",
            engineMoveDySum == 0,
        )
        assertTrue(
            "Cursor drift after fast-flick/slow-return cycles; centerY=$centerY hostY=$hostY " +
                "moveCount=${moves.size} engineMoveDySum=$engineMoveDySum engineMoveCount=$engineMoveCount",
            abs(hostY - centerY) <= 1.5f,
        )

        scope.cancel()
    }

    @Test
    fun fastFlickThenSlowReturn_withFingerLiftBetweenCycles_staysCentered() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = RecordingTransport(perSendDelayMs = 1L)
        val backend = QueuedAdbInjectionBackend(
            scope = scope,
            transport = transport,
            safetyController = SafetyController(),
            maxQueueDepth = 256,
        )
        val actionRouter = ActionRouter(backend)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsRepository(context)
        settings.setSpeedMultiplier(1f)
        settings.setMouseAccelerationEnabled(false)
        settings.setOneFingerEdgeScrollEnabled(false)
        settings.setTapToDragEnabled(false)
        settings.setDragFlickKickMs(0)
        settings.setFlingDurationMs(0)
        settings.setDoubleClickGuardMs(0)
        settings.setHoldDragDelayMs(10_000)

        val width = 1080
        val height = 2400
        val centerX = width / 2f
        val centerY = height / 2f
        val cursorStore = CursorStateStore().apply {
            updateBounds(width, height)
            resetToCenter()
        }
        val engine = TouchpadEngine(cursorStore, settings)

        val random = Random(5_177L)
        val cycles = 80
        val fastIntervalMs = 6L
        val slowIntervalMs = 20L
        val interGestureGapMs = 30L
        var touchEdgeTouched = false
        var engineMoveDySum = 0
        var engineMoveCount = 0
        var eventTime = 260_000L

        repeat(cycles) { cycle ->
            val direction = if (random.nextBoolean()) 1 else -1
            val targetDistancePx = 100 + random.nextInt(151) // 100..250
            var movedOutPx = 0
            var touchY = centerY
            val downTime = eventTime

            dispatchAndRoute(
                engine = engine,
                actionRouter = actionRouter,
                downTimeMs = downTime,
                eventTimeMs = downTime,
                action = MotionEvent.ACTION_DOWN,
                x = centerX,
                y = centerY,
            )

            // Fast outward flick.
            while (movedOutPx < targetDistancePx) {
                val step = kotlin.math.min(12 + random.nextInt(21), targetDistancePx - movedOutPx)
                val dy = direction * step
                movedOutPx += step
                eventTime += fastIntervalMs
                touchY += dy.toFloat()
                if (touchY <= 1f || touchY >= (height - 1).toFloat()) {
                    touchEdgeTouched = true
                }
                val actions = dispatchAndRoute(
                    engine = engine,
                    actionRouter = actionRouter,
                    downTimeMs = downTime,
                    eventTimeMs = eventTime,
                    action = MotionEvent.ACTION_MOVE,
                    x = centerX,
                    y = touchY,
                )
                actions.filterIsInstance<InputAction.MoveBy>().forEach { move ->
                    engineMoveCount += 1
                    engineMoveDySum += move.dy
                }
            }

            // Slow return to center.
            var remainingBackPx = movedOutPx
            while (remainingBackPx > 0) {
                val step = kotlin.math.min(1 + random.nextInt(4), remainingBackPx)
                val dy = -direction * step
                remainingBackPx -= step
                eventTime += slowIntervalMs
                touchY += dy.toFloat()
                if (touchY <= 1f || touchY >= (height - 1).toFloat()) {
                    touchEdgeTouched = true
                }
                val actions = dispatchAndRoute(
                    engine = engine,
                    actionRouter = actionRouter,
                    downTimeMs = downTime,
                    eventTimeMs = eventTime,
                    action = MotionEvent.ACTION_MOVE,
                    x = centerX,
                    y = touchY,
                )
                actions.filterIsInstance<InputAction.MoveBy>().forEach { move ->
                    engineMoveCount += 1
                    engineMoveDySum += move.dy
                }
            }

            assertTrue(
                "Touch should be back at center before lift cycle=$cycle; centerY=$centerY touchY=$touchY",
                abs(touchY - centerY) <= 0.001f,
            )
            eventTime += slowIntervalMs
            dispatchAndRoute(
                engine = engine,
                actionRouter = actionRouter,
                downTimeMs = downTime,
                eventTimeMs = eventTime,
                action = MotionEvent.ACTION_UP,
                x = centerX,
                y = touchY,
            )
            eventTime += interGestureGapMs
        }

        val drained = waitForTransportQuiescence(transport = transport, timeoutMs = 12_000L)
        assertTrue("Expected transport to quiesce after long multi-gesture run", drained)
        val stopMarkerSeen = transport.snapshot().any {
            it is AdbCommand.Scroll && it.dx == 0 && it.dy == 0
        }
        assertTrue("Expected at least one stop marker in multi-gesture run", stopMarkerSeen)

        val moves = transport.snapshot().filterIsInstance<AdbCommand.MoveRel>()
        var hostY = centerY
        var hostEdgeTouched = false
        moves.forEach { move ->
            hostY = (hostY + move.dy).coerceIn(0f, height.toFloat())
            if (hostY <= 0f || hostY >= height.toFloat()) {
                hostEdgeTouched = true
            }
        }

        assertTrue("Touch trace unexpectedly hit edge; test is invalid", !touchEdgeTouched)
        assertTrue("Cursor trace hit edge; center assertion is invalid", !hostEdgeTouched)
        assertTrue(
            "Engine move output should round-trip to zero; sumDy=$engineMoveDySum count=$engineMoveCount",
            engineMoveDySum == 0,
        )
        assertTrue(
            "Cursor drift after flick/return with lifts; centerY=$centerY hostY=$hostY " +
                "moveCount=${moves.size} engineMoveDySum=$engineMoveDySum engineMoveCount=$engineMoveCount",
            abs(hostY - centerY) <= 1.5f,
        )

        scope.cancel()
    }

    @Test
    fun fastFlickThenSlowReturn_withLift_transientMoveSendFailureDoesNotDrift() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = RecordingTransport(
            perSendDelayMs = 1L,
            failFirstMoveAttempt = true,
        )
        val backend = QueuedAdbInjectionBackend(
            scope = scope,
            transport = transport,
            safetyController = SafetyController(),
            maxQueueDepth = 256,
        )
        val actionRouter = ActionRouter(backend)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsRepository(context)
        settings.setSpeedMultiplier(1f)
        settings.setMouseAccelerationEnabled(false)
        settings.setOneFingerEdgeScrollEnabled(false)
        settings.setTapToDragEnabled(false)
        settings.setDragFlickKickMs(0)
        settings.setFlingDurationMs(0)
        settings.setDoubleClickGuardMs(0)
        settings.setHoldDragDelayMs(10_000)

        val width = 1080
        val height = 2400
        val centerX = width / 2f
        val centerY = height / 2f
        val cursorStore = CursorStateStore().apply {
            updateBounds(width, height)
            resetToCenter()
        }
        val engine = TouchpadEngine(cursorStore, settings)

        val random = Random(4_021L)
        val cycles = 36
        val fastIntervalMs = 6L
        val slowIntervalMs = 20L
        val interGestureGapMs = 26L
        var touchEdgeTouched = false
        var engineMoveDySum = 0
        var engineMoveCount = 0
        var eventTime = 390_000L

        repeat(cycles) { cycle ->
            val direction = if (random.nextBoolean()) 1 else -1
            val targetDistancePx = 100 + random.nextInt(151)
            var movedOutPx = 0
            var touchY = centerY
            val downTime = eventTime

            dispatchAndRoute(
                engine = engine,
                actionRouter = actionRouter,
                downTimeMs = downTime,
                eventTimeMs = downTime,
                action = MotionEvent.ACTION_DOWN,
                x = centerX,
                y = centerY,
            )

            while (movedOutPx < targetDistancePx) {
                val step = kotlin.math.min(12 + random.nextInt(21), targetDistancePx - movedOutPx)
                val dy = direction * step
                movedOutPx += step
                eventTime += fastIntervalMs
                touchY += dy.toFloat()
                if (touchY <= 1f || touchY >= (height - 1).toFloat()) {
                    touchEdgeTouched = true
                }
                val actions = dispatchAndRoute(
                    engine = engine,
                    actionRouter = actionRouter,
                    downTimeMs = downTime,
                    eventTimeMs = eventTime,
                    action = MotionEvent.ACTION_MOVE,
                    x = centerX,
                    y = touchY,
                )
                actions.filterIsInstance<InputAction.MoveBy>().forEach { move ->
                    engineMoveCount += 1
                    engineMoveDySum += move.dy
                }
            }

            var remainingBackPx = movedOutPx
            while (remainingBackPx > 0) {
                val step = kotlin.math.min(1 + random.nextInt(4), remainingBackPx)
                val dy = -direction * step
                remainingBackPx -= step
                eventTime += slowIntervalMs
                touchY += dy.toFloat()
                if (touchY <= 1f || touchY >= (height - 1).toFloat()) {
                    touchEdgeTouched = true
                }
                val actions = dispatchAndRoute(
                    engine = engine,
                    actionRouter = actionRouter,
                    downTimeMs = downTime,
                    eventTimeMs = eventTime,
                    action = MotionEvent.ACTION_MOVE,
                    x = centerX,
                    y = touchY,
                )
                actions.filterIsInstance<InputAction.MoveBy>().forEach { move ->
                    engineMoveCount += 1
                    engineMoveDySum += move.dy
                }
            }

            assertTrue(
                "Touch should be back at center before lift cycle=$cycle; centerY=$centerY touchY=$touchY",
                abs(touchY - centerY) <= 0.001f,
            )
            eventTime += slowIntervalMs
            dispatchAndRoute(
                engine = engine,
                actionRouter = actionRouter,
                downTimeMs = downTime,
                eventTimeMs = eventTime,
                action = MotionEvent.ACTION_UP,
                x = centerX,
                y = touchY,
            )
            eventTime += interGestureGapMs
        }

        val drained = waitForTransportQuiescence(transport = transport, timeoutMs = 12_000L)
        assertTrue("Expected transport to quiesce after transient-failure run", drained)

        val moves = transport.snapshot().filterIsInstance<AdbCommand.MoveRel>()
        var hostY = centerY
        var hostEdgeTouched = false
        moves.forEach { move ->
            hostY = (hostY + move.dy).coerceIn(0f, height.toFloat())
            if (hostY <= 0f || hostY >= height.toFloat()) {
                hostEdgeTouched = true
            }
        }

        assertTrue("Touch trace unexpectedly hit edge; test is invalid", !touchEdgeTouched)
        assertTrue("Cursor trace hit edge; center assertion is invalid", !hostEdgeTouched)
        assertTrue(
            "Engine move output should round-trip to zero; sumDy=$engineMoveDySum count=$engineMoveCount",
            engineMoveDySum == 0,
        )
        assertTrue(
            "Cursor drift under transient MOVE_REL send failures; centerY=$centerY hostY=$hostY " +
                "moveCount=${moves.size} engineMoveCount=$engineMoveCount",
            abs(hostY - centerY) <= 1.5f,
        )

        scope.cancel()
    }

    @Test
    fun stopMarker_keepsPendingVerticalScrollBeforeBarrier() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val releaseFirstSend = CompletableDeferred<Unit>()
        val transport = RecordingTransport(blockFirstSend = releaseFirstSend)
        val backend = QueuedAdbInjectionBackend(
            scope = scope,
            transport = transport,
            safetyController = SafetyController(),
        )

        val baseTs = SystemClock.elapsedRealtime()
        backend.enqueue(AdbCommand.Scroll(dx = 0, dy = 11, seq = 1L, ts = baseTs + 1))
        backend.enqueue(AdbCommand.Scroll(dx = 0, dy = 22, seq = 2L, ts = baseTs + 2))
        backend.enqueue(AdbCommand.Scroll(dx = 0, dy = 33, seq = 3L, ts = baseTs + 3))
        backend.enqueue(AdbCommand.Scroll(dx = 0, dy = 0, seq = 4L, ts = baseTs + 4))

        delay(120)
        releaseFirstSend.complete(Unit)

        val stopSeen = waitUntilTrue(timeoutMs = 2_000L) {
            transport.snapshot().any { it is AdbCommand.Scroll && it.dx == 0 && it.dy == 0 }
        }
        assertTrue("Expected stop marker to be dispatched", stopSeen)

        val scrolls = transport.snapshot().filterIsInstance<AdbCommand.Scroll>()
        val stopIndex = scrolls.indexOfFirst { it.dx == 0 && it.dy == 0 }
        assertTrue("Expected stop marker index >= 0", stopIndex >= 0)
        val totalBeforeStopDy = scrolls.take(stopIndex).sumOf { it.dy }
        assertTrue(
            "Expected pending scroll magnitude before stop barrier (>=66), got $totalBeforeStopDy sequence=$scrolls",
            totalBeforeStopDy >= 66,
        )

        scope.cancel()
    }

    @Test
    fun stopMarkerBarrier_preservedWhenNextGestureArrivesImmediately() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val releaseFirstSend = CompletableDeferred<Unit>()
        val transport = RecordingTransport(blockFirstSend = releaseFirstSend)
        val backend = QueuedAdbInjectionBackend(
            scope = scope,
            transport = transport,
            safetyController = SafetyController(),
        )

        val baseTs = SystemClock.elapsedRealtime()
        backend.enqueue(AdbCommand.Scroll(dx = 0, dy = 11, seq = 1L, ts = baseTs + 1))
        backend.enqueue(AdbCommand.Scroll(dx = 0, dy = 0, seq = 2L, ts = baseTs + 2))
        backend.enqueue(AdbCommand.Scroll(dx = 0, dy = 22, seq = 3L, ts = baseTs + 3))

        delay(120)
        releaseFirstSend.complete(Unit)

        val barrierSeen = waitUntilTrue(timeoutMs = 2_000L) {
            val scrolls = transport.snapshot().filterIsInstance<AdbCommand.Scroll>()
            val stopIndex = scrolls.indexOfFirst { it.dx == 0 && it.dy == 0 }
            stopIndex >= 0 && scrolls.drop(stopIndex + 1).any { it.dx != 0 || it.dy != 0 }
        }

        val scrolls = transport.snapshot().filterIsInstance<AdbCommand.Scroll>()
        val summary = scrolls.joinToString(prefix = "[", postfix = "]") { "(dx=${it.dx},dy=${it.dy},seq=${it.seq})" }
        assertTrue("Expected stop-marker barrier before next gesture scroll. Observed=$summary", barrierSeen)

        scope.cancel()
    }

    @Test
    fun stopMarker_isNotDroppedWhenMoveIsEnqueuedAfterScroll() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val releaseFirstSend = CompletableDeferred<Unit>()
        val transport = RecordingTransport(blockFirstSend = releaseFirstSend)
        val backend = QueuedAdbInjectionBackend(
            scope = scope,
            transport = transport,
            safetyController = SafetyController(),
        )

        val baseTs = SystemClock.elapsedRealtime()
        backend.enqueue(AdbCommand.Scroll(dx = 0, dy = 25, seq = 1L, ts = baseTs + 1))
        backend.enqueue(AdbCommand.Scroll(dx = 0, dy = 0, seq = 2L, ts = baseTs + 2))
        backend.enqueue(AdbCommand.MoveRel(dx = 8, dy = 0, seq = 3L, ts = baseTs + 3))

        delay(120)
        releaseFirstSend.complete(Unit)

        val moveSeen = waitUntilTrue(timeoutMs = 2_000L) {
            transport.snapshot().any { it is AdbCommand.MoveRel }
        }
        assertTrue("Expected move command to be dispatched", moveSeen)

        val commands = transport.snapshot()
        val moveIndex = commands.indexOfFirst { it is AdbCommand.MoveRel }
        assertTrue("Expected move index >= 0", moveIndex >= 0)

        val stopBeforeMove = commands.take(moveIndex).any {
            it is AdbCommand.Scroll && it.dx == 0 && it.dy == 0
        }
        assertTrue(
            "Expected stop marker to be preserved before move dispatch; observed=$commands",
            stopBeforeMove,
        )

        scope.cancel()
    }

    @Test
    fun horizontalSignFlip_isNotMergedIntoSingleScrollCommand() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val releaseFirstSend = CompletableDeferred<Unit>()
        val transport = RecordingTransport(blockFirstSend = releaseFirstSend)
        val backend = QueuedAdbInjectionBackend(
            scope = scope,
            transport = transport,
            safetyController = SafetyController(),
        )

        val baseTs = SystemClock.elapsedRealtime()
        backend.enqueue(AdbCommand.Scroll(dx = 12, dy = 0, seq = 1L, ts = baseTs + 1))
        backend.enqueue(AdbCommand.Scroll(dx = -12, dy = 0, seq = 2L, ts = baseTs + 2))

        delay(120)
        releaseFirstSend.complete(Unit)

        val seenTwo = waitUntilTrue(timeoutMs = 2_000L) {
            transport.snapshot().filterIsInstance<AdbCommand.Scroll>().size >= 2
        }
        assertTrue("Expected two horizontal scroll commands after sign flip", seenTwo)

        val scrolls = transport.snapshot().filterIsInstance<AdbCommand.Scroll>()
        val pairSeen = scrolls.windowed(size = 2, step = 1).any { pair ->
            pair[0].dx > 0 && pair[1].dx < 0
        }
        assertTrue(
            "Expected sign-flip sequence (+dx then -dx) without merge collapse; observed=$scrolls",
            pairSeen,
        )

        scope.cancel()
    }

    @Test
    fun dispatchLatency_staysBoundedUnderBurst() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = RecordingTransport(perSendDelayMs = 1L)
        val backend = QueuedAdbInjectionBackend(
            scope = scope,
            transport = transport,
            safetyController = SafetyController(),
        )

        val count = 240
        repeat(count) { i ->
            val now = SystemClock.elapsedRealtime()
            backend.enqueue(AdbCommand.Scroll(dx = 0, dy = 2, seq = i + 1L, ts = now))
        }
        backend.enqueue(
            AdbCommand.Scroll(
                dx = 0,
                dy = 0,
                seq = 100_000L,
                ts = SystemClock.elapsedRealtime(),
            )
        )

        val stopSeen = waitUntilTrue(timeoutMs = 3_000L) {
            transport.snapshot().any {
                it is AdbCommand.Scroll && it.dx == 0 && it.dy == 0
            }
        }
        assertTrue("Expected stop-marker to arrive in latency test", stopSeen)

        val latenciesMs = transport.scrollLatencySamplesMs()
        assertTrue("Expected scroll latency samples", latenciesMs.isNotEmpty())

        val sorted = latenciesMs.sorted()
        val p95 = sorted[((sorted.size - 1) * 95) / 100]
        // Keep this practical and non-flaky for physical devices.
        assertTrue("P95 scroll latency too high: ${p95}ms", p95 < 400L)

        scope.cancel()
    }

    private suspend fun waitUntilTrue(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) {
                return true
            }
            delay(20)
        }
        return false
    }

    private suspend fun waitForTransportQuiescence(
        transport: RecordingTransport,
        timeoutMs: Long,
        stableWindowMs: Long = 300L,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastCount = -1
        var lastChangeAt = SystemClock.elapsedRealtime()

        while (SystemClock.elapsedRealtime() < deadline) {
            val count = transport.snapshot().size
            if (count != lastCount) {
                lastCount = count
                lastChangeAt = SystemClock.elapsedRealtime()
            } else if ((SystemClock.elapsedRealtime() - lastChangeAt) >= stableWindowMs) {
                return true
            }
            delay(20L)
        }
        return false
    }

    private fun dispatchAndRoute(
        engine: TouchpadEngine,
        actionRouter: ActionRouter,
        downTimeMs: Long,
        eventTimeMs: Long,
        action: Int,
        x: Float,
        y: Float,
    ): List<InputAction> {
        val event = MotionEvent.obtain(downTimeMs, eventTimeMs, action, x, y, 0)
        try {
            val actions = engine.onTouchEvent(event)
            actionRouter.route(actions)
            return actions
        } finally {
            event.recycle()
        }
    }

    private class RecordingTransport(
        private val blockFirstSend: CompletableDeferred<Unit>? = null,
        private val perSendDelayMs: Long = 0L,
        private val failFirstMoveAttempt: Boolean = false,
    ) : AdbTransport {
        private val _isConnected = MutableStateFlow(true)
        override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val commands = Collections.synchronizedList(mutableListOf<AdbCommand>())
        private val scrollLatenciesMs = Collections.synchronizedList(mutableListOf<Long>())
        private val moveFailedOnceSeqs = Collections.synchronizedSet(mutableSetOf<Long>())

        private var firstSendBlocked = false

        override suspend fun connect(host: String, port: Int): Boolean {
            _isConnected.value = true
            return true
        }

        override fun disconnect() {
            _isConnected.value = false
        }

        override suspend fun ping(): Boolean = true

        override suspend fun send(command: AdbCommand): Boolean {
            if (!firstSendBlocked) {
                firstSendBlocked = true
                blockFirstSend?.await()
            }
            if (
                failFirstMoveAttempt &&
                command is AdbCommand.MoveRel &&
                moveFailedOnceSeqs.add(command.seq)
            ) {
                return false
            }
            if (perSendDelayMs > 0) {
                delay(perSendDelayMs)
            }
            commands += command
            if (command is AdbCommand.Scroll) {
                val latency = (SystemClock.elapsedRealtime() - command.ts).coerceAtLeast(0L)
                scrollLatenciesMs += latency
            }
            return true
        }

        fun snapshot(): List<AdbCommand> = commands.toList()

        fun scrollLatencySamplesMs(): List<Long> = scrollLatenciesMs.toList()
    }
}
