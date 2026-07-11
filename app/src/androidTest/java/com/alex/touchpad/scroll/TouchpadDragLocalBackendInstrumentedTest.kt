package com.alex.touchpad.scroll

import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alex.touchpad.adb.PlainTextSocketAdbTransport
import com.alex.touchpad.adb.QueuedAdbInjectionBackend
import com.alex.touchpad.adb.SafetyController
import com.alex.touchpad.backend.ExecutorStatus
import com.alex.touchpad.backend.LocalBackendServer
import com.alex.touchpad.backend.WireCommand
import com.alex.touchpad.backend.WireCommandExecutor
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class TouchpadDragLocalBackendInstrumentedTest {

    @Test
    fun holdDrag_continuedTouchscreenMoves_surviveLocalSocketBackendRoundTrip() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val executor = RecordingWireExecutor()
        val server = LocalBackendServer(scope, executor)
        val transport = PlainTextSocketAdbTransport()
        val backend = QueuedAdbInjectionBackend(
            scope = scope,
            transport = transport,
            safetyController = SafetyController(),
            maxQueueDepth = 16,
        )
        val router = ActionRouter(backend)
        val engine = newEngine()
        val port = 53539
        val downTime = 34_000L

        try {
            server.start(port)
            val serverReady = waitUntilTrue(timeoutMs = 1_500L) { server.state.value.running }
            assertTrue("Expected local backend server to start", serverReady)

            val connected = transport.connect("127.0.0.1", port)
            assertTrue("Expected transport to connect to local backend server", connected)

            route(engine, router, downTime, downTime, MotionEvent.ACTION_DOWN, x = 500f, y = 1100f)
            router.route(engine.onTick(downTime + 150L))
            route(engine, router, downTime, downTime + 166L, MotionEvent.ACTION_MOVE, x = 516f, y = 1100f)
            route(engine, router, downTime, downTime + 182L, MotionEvent.ACTION_MOVE, x = 548f, y = 1112f)
            route(engine, router, downTime, downTime + 198L, MotionEvent.ACTION_MOVE, x = 586f, y = 1130f)
            route(engine, router, downTime, downTime + 214L, MotionEvent.ACTION_MOVE, x = 620f, y = 1148f)
            route(engine, router, downTime, downTime + 230L, MotionEvent.ACTION_UP, x = 620f, y = 1148f)

            val dispatched = waitUntilTrue(timeoutMs = 2_000L) {
                executor.snapshot().any { it is WireCommand.ButtonUp && it.button == MouseButton.LEFT }
            }
            assertTrue("Expected full drag lifecycle to reach local backend executor", dispatched)

            val commands = executor.snapshot()
            val buttonDownIndex = commands.indexOfFirst {
                it is WireCommand.ButtonDown && it.button == MouseButton.LEFT
            }
            val buttonUpIndex = commands.indexOfFirst {
                it is WireCommand.ButtonUp && it.button == MouseButton.LEFT
            }
            val moveCommands = commands.filterIsInstance<WireCommand.MoveRel>()

            assertTrue("Expected ButtonDown before drag motion; commands=$commands", buttonDownIndex >= 0)
            assertTrue("Expected ButtonUp after drag motion; commands=$commands", buttonUpIndex > buttonDownIndex)
            assertTrue(
                "Expected multiple MoveRel commands during drag; moves=$moveCommands commands=$commands",
                moveCommands.size >= 3,
            )
            assertFalse(
                "Expected no Click command in hold-drag path; commands=$commands",
                commands.any { it is WireCommand.Click && it.button == MouseButton.LEFT },
            )
            assertTrue(
                "Expected first MoveRel after ButtonDown; commands=$commands",
                commands.indexOfFirst { it is WireCommand.MoveRel } > buttonDownIndex,
            )
        } finally {
            transport.disconnect()
            server.stop()
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

    private class RecordingWireExecutor : WireCommandExecutor {
        private val commands = Collections.synchronizedList(mutableListOf<WireCommand>())
        private val _status = MutableStateFlow(
            ExecutorStatus(
                adbBinaryReady = true,
                adbBinaryPath = null,
                lastError = null,
            ),
        )

        override val status: StateFlow<ExecutorStatus> = _status.asStateFlow()

        override suspend fun execute(command: WireCommand): Boolean {
            commands += command
            return true
        }

        fun snapshot(): List<WireCommand> = synchronized(commands) { commands.toList() }
    }
}
