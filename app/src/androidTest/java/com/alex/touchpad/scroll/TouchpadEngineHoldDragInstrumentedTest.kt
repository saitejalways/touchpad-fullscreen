package com.alex.touchpad.scroll

import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alex.touchpad.core.CursorStateStore
import com.alex.touchpad.input.HapticFeedbackKind
import com.alex.touchpad.input.InputAction
import com.alex.touchpad.input.MouseButton
import com.alex.touchpad.input.TouchpadEngine
import com.alex.touchpad.settings.SettingsRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TouchpadEngineHoldDragInstrumentedTest {

    @Test
    fun holdDragArmsOnTickWithoutMove() {
        val engine = newEngine()
        val downTime = 10_000L

        dispatchSingle(engine, downTime, downTime, MotionEvent.ACTION_DOWN, x = 600f, y = 1200f)
        val early = engine.onTick(downTime + 80L)
        val armed = engine.onTick(downTime + 140L)
        val duplicate = engine.onTick(downTime + 180L)

        assertTrue("Expected no hold-drag arm before delay", early.isEmpty())
        assertTrue(
            "Expected hold-drag arm haptic without move events; actions=$armed",
            armed.any { it is InputAction.Haptic && it.kind == HapticFeedbackKind.DRAG_START },
        )
        assertTrue("Expected single arm event only once; duplicate=$duplicate", duplicate.isEmpty())
    }

    @Test
    fun holdDragArmedThenReleaseWithoutMove_doesNotClick() {
        val engine = newEngine()
        val downTime = 20_000L

        dispatchSingle(engine, downTime, downTime, MotionEvent.ACTION_DOWN, x = 640f, y = 1280f)
        engine.onTick(downTime + 150L)
        val upActions = dispatchSingle(engine, downTime, downTime + 170L, MotionEvent.ACTION_UP, x = 640f, y = 1280f)

        assertFalse(
            "Armed release without drag should not emit click; actions=$upActions",
            upActions.any { it is InputAction.Click },
        )
        assertFalse(
            "Armed release without drag should not press mouse button; actions=$upActions",
            upActions.any { it is InputAction.ButtonDown || it is InputAction.ButtonUp },
        )
    }

    @Test
    fun actionUpDoesNotArmHoldDrag_whenNoTickRan() {
        val engine = newEngine()
        val downTime = 25_000L

        dispatchSingle(engine, downTime, downTime, MotionEvent.ACTION_DOWN, x = 640f, y = 1280f)
        val upActions = dispatchSingle(engine, downTime, downTime + 170L, MotionEvent.ACTION_UP, x = 640f, y = 1280f)

        assertFalse(
            "ACTION_UP must not arm hold-drag path by itself; actions=$upActions",
            upActions.any { it is InputAction.Haptic && it.kind == HapticFeedbackKind.DRAG_START },
        )
        assertTrue(
            "Expected tap click when lifted without prior arm tick; actions=$upActions",
            upActions.any { it is InputAction.Click && it.button == MouseButton.LEFT },
        )
    }

    @Test
    fun fingerLiftResetsHoldTimer_forNextTouch() {
        val engine = newEngine(tapToDragEnabled = true)
        val firstDownAt = 26_000L
        val secondDownAt = 26_110L

        dispatchSingle(engine, firstDownAt, firstDownAt, MotionEvent.ACTION_DOWN, x = 640f, y = 1280f)
        dispatchSingle(engine, firstDownAt, firstDownAt + 60L, MotionEvent.ACTION_UP, x = 640f, y = 1280f)

        dispatchSingle(engine, secondDownAt, secondDownAt, MotionEvent.ACTION_DOWN, x = 640f, y = 1280f)
        val tooEarly = engine.onTick(secondDownAt + 90L)
        val armed = engine.onTick(secondDownAt + 130L)

        assertTrue(
            "Hold-drag must not arm using stale pre-lift timer; actions=$tooEarly",
            tooEarly.none { it is InputAction.Haptic && it.kind == HapticFeedbackKind.DRAG_START },
        )
        assertTrue(
            "Hold-drag should arm after full delay from second touch-down; actions=$armed",
            armed.any { it is InputAction.Haptic && it.kind == HapticFeedbackKind.DRAG_START },
        )
    }

    @Test
    fun holdDragArmedThenMove_startsDragAndReleasesOnUp() {
        val engine = newEngine()
        val downTime = 30_000L

        dispatchSingle(engine, downTime, downTime, MotionEvent.ACTION_DOWN, x = 500f, y = 1100f)
        engine.onTick(downTime + 150L)

        val microMove = dispatchSingle(engine, downTime, downTime + 166L, MotionEvent.ACTION_MOVE, x = 501f, y = 1100f)
        val dragMove = dispatchSingle(engine, downTime, downTime + 182L, MotionEvent.ACTION_MOVE, x = 516f, y = 1100f)
        val upActions = dispatchSingle(engine, downTime, downTime + 198L, MotionEvent.ACTION_UP, x = 516f, y = 1100f)

        assertTrue(
            "Small post-arm jitter should not start drag; actions=$microMove",
            microMove.none { it is InputAction.ButtonDown },
        )
        assertTrue(
            "Expected drag start to emit left ButtonDown; actions=$dragMove",
            dragMove.any { it is InputAction.ButtonDown && it.button == MouseButton.LEFT },
        )
        assertTrue(
            "Expected drag move to emit pointer movement; actions=$dragMove",
            dragMove.any { it is InputAction.MoveBy },
        )
        assertTrue(
            "Expected ACTION_UP after dragging to emit ButtonUp; actions=$upActions",
            upActions.any { it is InputAction.ButtonUp && it.button == MouseButton.LEFT },
        )
    }

    @Test
    fun holdDrag_started_thenContinuedTouchscreenMoves_keepDraggingUntilFingerUp() {
        val engine = newEngine()
        val downTime = 31_000L

        dispatchSingle(engine, downTime, downTime, MotionEvent.ACTION_DOWN, x = 500f, y = 1100f)
        engine.onTick(downTime + 150L)

        val dragStart = dispatchSingle(
            engine,
            downTime,
            downTime + 166L,
            MotionEvent.ACTION_MOVE,
            x = 516f,
            y = 1100f,
        )
        val continuedMoveOne = dispatchSingle(
            engine,
            downTime,
            downTime + 182L,
            MotionEvent.ACTION_MOVE,
            x = 548f,
            y = 1112f,
        )
        val continuedMoveTwo = dispatchSingle(
            engine,
            downTime,
            downTime + 198L,
            MotionEvent.ACTION_MOVE,
            x = 586f,
            y = 1130f,
        )
        val upActions = dispatchSingle(
            engine,
            downTime,
            downTime + 214L,
            MotionEvent.ACTION_UP,
            x = 586f,
            y = 1130f,
        )

        assertTrue(
            "Expected drag start to press left button; actions=$dragStart",
            dragStart.any { it is InputAction.ButtonDown && it.button == MouseButton.LEFT },
        )
        assertTrue(
            "Expected drag start to emit pointer movement; actions=$dragStart",
            dragStart.any { it is InputAction.MoveBy },
        )
        assertTrue(
            "Expected continued touchscreen drag movement after drag start; actions=$continuedMoveOne",
            continuedMoveOne.any { it is InputAction.MoveBy },
        )
        assertTrue(
            "Expected later touchscreen drag movement to keep emitting pointer deltas; actions=$continuedMoveTwo",
            continuedMoveTwo.any { it is InputAction.MoveBy },
        )
        assertFalse(
            "Expected no premature ButtonUp during continued drag movement; actions=$continuedMoveOne",
            continuedMoveOne.any { it is InputAction.ButtonUp && it.button == MouseButton.LEFT },
        )
        assertFalse(
            "Expected no premature ButtonUp during later drag movement; actions=$continuedMoveTwo",
            continuedMoveTwo.any { it is InputAction.ButtonUp && it.button == MouseButton.LEFT },
        )
        assertTrue(
            "Expected ButtonUp only when finger lifts; actions=$upActions",
            upActions.any { it is InputAction.ButtonUp && it.button == MouseButton.LEFT },
        )
    }

    @Test
    fun secondTapDrag_moveBeforeHoldDelay_startsDragImmediately() {
        val engine = newEngine(tapToDragEnabled = true)
        val downTime = 40_000L
        val x = 540f
        val y = 1180f

        // First tap registers click and primes second-tap drag arming window.
        dispatchSingle(engine, downTime, downTime, MotionEvent.ACTION_DOWN, x = x, y = y)
        dispatchSingle(engine, downTime, downTime + 40L, MotionEvent.ACTION_UP, x = x, y = y)

        // Second tap begins within drag arm timeout; move before hold delay elapses.
        dispatchSingle(engine, downTime + 120L, downTime + 120L, MotionEvent.ACTION_DOWN, x = x, y = y)
        val earlyMoveActions = dispatchSingle(
            engine,
            downTime + 120L,
            downTime + 136L,
            MotionEvent.ACTION_MOVE,
            x = x + 30f,
            y = y,
        )
        val releaseActions = dispatchSingle(
            engine,
            downTime + 120L,
            downTime + 152L,
            MotionEvent.ACTION_UP,
            x = x + 30f,
            y = y,
        )

        assertTrue(
            "Expected second-tap early move to start drag immediately; actions=$earlyMoveActions",
            earlyMoveActions.any { it is InputAction.ButtonDown && it.button == MouseButton.LEFT },
        )
        assertTrue(
            "Expected second-tap early move to include drag haptic; actions=$earlyMoveActions",
            earlyMoveActions.any { it is InputAction.Haptic && it.kind == HapticFeedbackKind.DRAG_START },
        )
        assertTrue(
            "Expected second-tap early move to include pointer delta; actions=$earlyMoveActions",
            earlyMoveActions.any { it is InputAction.MoveBy },
        )
        assertTrue(
            "Expected drag release to send ButtonUp; actions=$releaseActions",
            releaseActions.any { it is InputAction.ButtonUp && it.button == MouseButton.LEFT },
        )
    }

    @Test
    fun secondTapDrag_disabled_doesNotStartDrag() {
        val engine = newEngine(tapToDragEnabled = false)
        val downTime = 45_000L
        val x = 540f
        val y = 1180f

        dispatchSingle(engine, downTime, downTime, MotionEvent.ACTION_DOWN, x = x, y = y)
        dispatchSingle(engine, downTime, downTime + 40L, MotionEvent.ACTION_UP, x = x, y = y)

        dispatchSingle(engine, downTime + 120L, downTime + 120L, MotionEvent.ACTION_DOWN, x = x, y = y)
        val earlyMoveActions = dispatchSingle(
            engine,
            downTime + 120L,
            downTime + 136L,
            MotionEvent.ACTION_MOVE,
            x = x + 12f,
            y = y,
        )

        assertFalse(
            "Expected no drag button-down when tap-to-drag is disabled; actions=$earlyMoveActions",
            earlyMoveActions.any { it is InputAction.ButtonDown && it.button == MouseButton.LEFT },
        )
    }

    @Test
    fun dragReleaseAppliesFlickKick_whenEnabled() {
        val engine = newEngine(dragFlickKickMs = 50)
        val downTime = 50_000L

        dispatchSingle(engine, downTime, downTime, MotionEvent.ACTION_DOWN, x = 520f, y = 1180f)
        engine.onTick(downTime + 150L)
        dispatchSingle(engine, downTime, downTime + 166L, MotionEvent.ACTION_MOVE, x = 532f, y = 1180f)
        dispatchSingle(engine, downTime, downTime + 182L, MotionEvent.ACTION_MOVE, x = 612f, y = 1180f)
        val upActions = dispatchSingle(engine, downTime, downTime + 198L, MotionEvent.ACTION_UP, x = 612f, y = 1180f)

        val kickMoveIndex = upActions.indexOfFirst { it is InputAction.MoveBy }
        val buttonUpIndex = upActions.indexOfFirst { it is InputAction.ButtonUp && it.button == MouseButton.LEFT }
        val kickMove = upActions.filterIsInstance<InputAction.MoveBy>().firstOrNull()

        assertTrue("Expected a release flick-kick move; actions=$upActions", kickMoveIndex >= 0)
        assertTrue(
            "Expected release flick-kick to be emitted before ButtonUp; actions=$upActions",
            kickMoveIndex >= 0 && buttonUpIndex > kickMoveIndex,
        )
        assertTrue(
            "Expected positive horizontal kick after rightward drag; move=$kickMove actions=$upActions",
            (kickMove?.dx ?: 0) > 0,
        )
    }

    @Test
    fun dragReleaseDoesNotApplyFlickKick_whenDisabled() {
        val engine = newEngine(dragFlickKickMs = 0)
        val downTime = 60_000L

        dispatchSingle(engine, downTime, downTime, MotionEvent.ACTION_DOWN, x = 520f, y = 1180f)
        engine.onTick(downTime + 150L)
        dispatchSingle(engine, downTime, downTime + 166L, MotionEvent.ACTION_MOVE, x = 532f, y = 1180f)
        dispatchSingle(engine, downTime, downTime + 182L, MotionEvent.ACTION_MOVE, x = 612f, y = 1180f)
        val upActions = dispatchSingle(engine, downTime, downTime + 198L, MotionEvent.ACTION_UP, x = 612f, y = 1180f)

        assertFalse(
            "Expected no release flick-kick move when disabled; actions=$upActions",
            upActions.any { it is InputAction.MoveBy },
        )
        assertTrue(
            "Expected ButtonUp on drag release; actions=$upActions",
            upActions.any { it is InputAction.ButtonUp && it.button == MouseButton.LEFT },
        )
        assertTrue(
            "Expected scroll-stop marker on drag release; actions=$upActions",
            upActions.any { it is InputAction.ScrollBy && it.dx == 0 && it.dy == 0 },
        )
    }

    private fun newEngine(
        dragFlickKickMs: Int = 0,
        tapToDragEnabled: Boolean = false,
        dragStartSlopPx: Float = 8f,
    ): TouchpadEngine {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsRepository(context)
        settings.setOneFingerEdgeScrollEnabled(false)
        settings.setHoldDragDelayMs(120)
        settings.setTapToDragEnabled(tapToDragEnabled)
        settings.setDragStartSlopPx(dragStartSlopPx)
        settings.setDragFlickKickMs(dragFlickKickMs)
        settings.setHoldDragSlopPx(8f)
        settings.setDoubleClickGuardMs(0)
        settings.setFlingDurationMs(0)
        settings.setSpeedMultiplier(1f)

        val cursorStore = CursorStateStore()
        cursorStore.updateBounds(1080, 2400)
        return TouchpadEngine(cursorStore, settings)
    }

    private fun dispatchSingle(
        engine: TouchpadEngine,
        downTimeMs: Long,
        eventTimeMs: Long,
        action: Int,
        x: Float,
        y: Float,
    ): List<InputAction> {
        val event = MotionEvent.obtain(downTimeMs, eventTimeMs, action, x, y, 0)
        return try {
            engine.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
