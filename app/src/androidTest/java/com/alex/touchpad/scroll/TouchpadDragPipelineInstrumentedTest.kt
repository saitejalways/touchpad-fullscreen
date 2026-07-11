package com.alex.touchpad.scroll

import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alex.touchpad.adb.AdbCommand
import com.alex.touchpad.adb.AdbInjectionBackend
import com.alex.touchpad.adb.AdbTransport
import com.alex.touchpad.adb.QueuedAdbInjectionBackend
import com.alex.touchpad.adb.SafetyController
import com.alex.touchpad.core.CursorStateStore
import com.alex.touchpad.input.ActionRouter
import com.alex.touchpad.input.MouseButton
import com.alex.touchpad.input.TouchpadEngine
import com.alex.touchpad.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class TouchpadDragPipelineInstrumentedTest {

    @Test
    fun holdDrag_continuedTouchscreenMoves_routeAsHeldMouseDragSequence() {
        val backend = RecordingBackend()
        val router = ActionRouter(backend)
        val engine = newEngine()
        val downTime = 32_000L

        route(engine, router, downTime, downTime, MotionEvent.ACTION_DOWN, x = 500f, y = 1100f)
        router.route(engine.onTick(downTime + 150L))
        route(engine, router, downTime, downTime + 166L, MotionEvent.ACTION_MOVE, x = 516f, y = 1100f)
        route(engine, router, downTime, downTime + 182L, MotionEvent.ACTION_MOVE, x = 548f, y = 1112f)
        route(engine, router, downTime, downTime + 198L, MotionEvent.ACTION_MOVE, x = 586f, y = 1130f)
        route(engine, router, downTime, downTime + 214L, MotionEvent.ACTION_UP, x = 586f, y = 1130f)

        val commands = backend.snapshot()
        val buttonDownIndex = commands.indexOfFirst {
            it is AdbCommand.ButtonDown && it.button == MouseButton.LEFT
        }
        val buttonUpIndex = commands.indexOfFirst {
            it is AdbCommand.ButtonUp && it.button == MouseButton.LEFT
        }
        val moveCommands = commands.filterIsInstance<AdbCommand.MoveRel>()

        assertTrue("Expected left-button press to start drag; commands=$commands", buttonDownIndex >= 0)
        assertTrue("Expected left-button release to end drag; commands=$commands", buttonUpIndex > buttonDownIndex)
        assertTrue(
            "Expected multiple routed move commands during held drag; moves=$moveCommands commands=$commands",
            moveCommands.size >= 3,
        )
        assertFalse(
            "Expected no click command in hold-drag path; commands=$commands",
            commands.any { it is AdbCommand.Click && it.button == MouseButton.LEFT },
        )
        assertTrue(
            "Expected first routed drag move after ButtonDown; commands=$commands",
            commands.indexOfFirst { it is AdbCommand.MoveRel } > buttonDownIndex,
        )
        assertTrue(
            "Expected no routed move commands after ButtonUp; commands=$commands",
            commands.drop(buttonUpIndex + 1).none { it is AdbCommand.MoveRel },
        )
        assertEquals(
            "Expected drag lifecycle BUTTON DOWN -> MOVE* -> BUTTON UP",
            listOf(
                AdbCommand.ButtonDown::class,
                AdbCommand.MoveRel::class,
                AdbCommand.MoveRel::class,
                AdbCommand.MoveRel::class,
                AdbCommand.ButtonUp::class,
            ),
            listOf(
                commands[buttonDownIndex]::class,
                commands[buttonDownIndex + 1]::class,
                commands[buttonDownIndex + 2]::class,
                commands[buttonDownIndex + 3]::class,
                commands[buttonUpIndex]::class,
            ),
        )
    }

    @Test
    fun holdDrag_continuedTouchscreenMoves_surviveQueuedBackendDispatch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = RecordingTransport(perSendDelayMs = 12L)
        val backend = QueuedAdbInjectionBackend(
            scope = scope,
            transport = transport,
            safetyController = SafetyController(),
            maxQueueDepth = 16,
        )
        val router = ActionRouter(backend)
        val engine = newEngine()
        val downTime = 33_000L

        try {
            route(engine, router, downTime, downTime, MotionEvent.ACTION_DOWN, x = 500f, y = 1100f)
            router.route(engine.onTick(downTime + 150L))
            route(engine, router, downTime, downTime + 166L, MotionEvent.ACTION_MOVE, x = 516f, y = 1100f)
            route(engine, router, downTime, downTime + 182L, MotionEvent.ACTION_MOVE, x = 548f, y = 1112f)
            route(engine, router, downTime, downTime + 198L, MotionEvent.ACTION_MOVE, x = 586f, y = 1130f)
            route(engine, router, downTime, downTime + 214L, MotionEvent.ACTION_MOVE, x = 620f, y = 1148f)
            route(engine, router, downTime, downTime + 230L, MotionEvent.ACTION_UP, x = 620f, y = 1148f)

            val dispatched = waitUntilTrue(timeoutMs = 2_000L) {
                transport.snapshot().any { it is AdbCommand.ButtonUp && it.button == MouseButton.LEFT }
            }
            assertTrue("Expected queued backend to dispatch full drag lifecycle", dispatched)

            val commands = transport.snapshot()
            val buttonDownIndex = commands.indexOfFirst {
                it is AdbCommand.ButtonDown && it.button == MouseButton.LEFT
            }
            val buttonUpIndex = commands.indexOfFirst {
                it is AdbCommand.ButtonUp && it.button == MouseButton.LEFT
            }
            val moveCommands = commands.filterIsInstance<AdbCommand.MoveRel>()

            assertTrue("Expected queued ButtonDown before drag motion; commands=$commands", buttonDownIndex >= 0)
            assertTrue("Expected queued ButtonUp after drag motion; commands=$commands", buttonUpIndex > buttonDownIndex)
            assertTrue(
                "Expected multiple queued MoveRel commands during drag; moves=$moveCommands commands=$commands",
                moveCommands.size >= 3,
            )
            assertFalse(
                "Expected no click command in queued hold-drag path; commands=$commands",
                commands.any { it is AdbCommand.Click && it.button == MouseButton.LEFT },
            )
            assertTrue(
                "Expected first queued drag move after ButtonDown; commands=$commands",
                commands.indexOfFirst { it is AdbCommand.MoveRel } > buttonDownIndex,
            )
        } finally {
            scope.cancel()
        }
    }

    private fun newEngine(): TouchpadEngine {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsRepository(context)
        settings.setOneFingerEdgeScrollEnabled(false)
        settings.setHoldDragDelayMs(120)
        settings.setTapToDragEnabled(false)
        settings.setDragStartSlopPx(8f)
        settings.setDragFlickKickMs(0)
        settings.setHoldDragSlopPx(8f)
        settings.setDoubleClickGuardMs(0)
        settings.setFlingDurationMs(0)
        settings.setSpeedMultiplier(1f)

        val cursorStore = CursorStateStore()
        cursorStore.updateBounds(1080, 2400)
        return TouchpadEngine(cursorStore, settings)
    }

    private fun route(
        engine: TouchpadEngine,
        router: ActionRouter,
        downTimeMs: Long,
        eventTimeMs: Long,
        action: Int,
        x: Float,
        y: Float,
    ) {
        val event = MotionEvent.obtain(downTimeMs, eventTimeMs, action, x, y, 0)
        try {
            router.route(engine.onTouchEvent(event))
        } finally {
            event.recycle()
        }
    }

    private class RecordingBackend : AdbInjectionBackend {
        private val commands = Collections.synchronizedList(mutableListOf<AdbCommand>())

        override fun enqueue(command: AdbCommand) {
            commands += command
        }

        fun snapshot(): List<AdbCommand> = synchronized(commands) { commands.toList() }
    }

    private class RecordingTransport(
        private val perSendDelayMs: Long = 0L,
    ) : AdbTransport {
        private val connected = MutableStateFlow(true)
        private val commands = Collections.synchronizedList(mutableListOf<AdbCommand>())

        override val isConnected: StateFlow<Boolean> = connected.asStateFlow()

        override suspend fun connect(host: String, port: Int): Boolean = true

        override fun disconnect() {
            connected.value = false
        }

        override suspend fun ping(): Boolean = true

        override suspend fun send(command: AdbCommand): Boolean {
            if (perSendDelayMs > 0L) {
                delay(perSendDelayMs)
            }
            commands += command
            return true
        }

        fun snapshot(): List<AdbCommand> = synchronized(commands) { commands.toList() }
    }

    private suspend fun waitUntilTrue(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) {
                return true
            }
            delay(20L)
        }
        return predicate()
    }
}
