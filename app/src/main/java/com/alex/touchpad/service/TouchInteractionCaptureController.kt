package com.alex.touchpad.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.TouchInteractionController
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import com.alex.touchpad.core.AppLog as Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.OverScroller
import com.alex.touchpad.backend.EdgeHighlightState
import com.alex.touchpad.backend.CursorGroundTruth
import com.alex.touchpad.backend.EdgeSwipeDebugEvent
import com.alex.touchpad.backend.ExecutorStatus
import com.alex.touchpad.backend.HidScrollDebugEvent
import com.alex.touchpad.backend.LocalBackendState
import com.alex.touchpad.core.AppLogEntry
import com.alex.touchpad.core.AppMode
import com.alex.touchpad.core.CursorStateStore
import com.alex.touchpad.core.FeedbackModule
import com.alex.touchpad.input.ActionRouter
import com.alex.touchpad.input.HapticFeedbackKind
import com.alex.touchpad.input.InputAction
import com.alex.touchpad.input.TouchpadEngine
import com.alex.touchpad.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

class TouchInteractionCaptureController(
    private val service: AccessibilityService,
    private val touchpadEngine: TouchpadEngine,
    private val actionRouter: ActionRouter,
    private val cursorStateStore: CursorStateStore,
    private val settingsRepository: SettingsRepository,
    private val runtimeModeState: StateFlow<AppMode>,
    private val sessionConnectedState: StateFlow<Boolean>,
    private val overlayDesiredState: StateFlow<Boolean>,
    private val localBackendState: StateFlow<LocalBackendState>,
    private val executorStatusState: StateFlow<ExecutorStatus>,
    private val edgeHighlightState: StateFlow<EdgeHighlightState>,
    private val cursorGroundTruthState: StateFlow<CursorGroundTruth?>,
    private val shellDaemonActiveState: StateFlow<Boolean>,
    private val hidScrollDebugHistoryState: StateFlow<List<HidScrollDebugEvent>>,
    private val edgeSwipeDebugHistoryState: StateFlow<List<EdgeSwipeDebugEvent>>,
    private val overlayAttachedState: MutableStateFlow<Boolean>,
    private val feedbackModule: FeedbackModule,
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val overScroller = OverScroller(service)
    private var controller: TouchInteractionController? = null
    private var enabled = false
    private var lastBoundsWidth = -1
    private var lastBoundsHeight = -1
    private var scrollDispatchJob: Job? = null
    private var scrollFlingJob: Job? = null
    private val scrollLock = Any()
    private var pendingScrollDx = 0
    private var pendingScrollDy = 0
    private var stopRequested = false
    private var flingActive = false
    private var edgeHighlightView: EdgeHighlightView? = null
    private var edgeHighlightCollectJob: Job? = null
    private var edgeHighlightAttached = false
    private var debugOverlayView: DebugOverlayView? = null
    private var debugOverlayAttached = false
    private var debugToggleCollectJob: Job? = null
    private var debugStatusCollectJob: Job? = null
    private var debugConsoleCollectJob: Job? = null
    private var debugCursorCollectJob: Job? = null
    private var debugDaemonCollectJob: Job? = null
    private var debugHidCollectJob: Job? = null
    private var debugSwipeCollectJob: Job? = null
    @Volatile
    private var lastFingerX = Float.NaN
    @Volatile
    private var lastFingerY = Float.NaN
    @Volatile
    private var lastScrollDx = 0
    @Volatile
    private var lastScrollDy = 0
    private var touchDeltaResidualX = 0f
    private var touchDeltaResidualY = 0f
    @Volatile
    private var imeBoundsInScreen: Rect? = null

    private val callback = object : TouchInteractionController.Callback {
        override fun onMotionEvent(event: MotionEvent) {
            if (!enabled) {
                return
            }
            updateBoundsFromDisplay()
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                if (shouldDelegateImeTouch(event)) {
                    delegateCurrentGestureToSystem(event, reason = "ime")
                    return
                }
                if (shouldDelegateNavigationTouch(event)) {
                    delegateCurrentGestureToSystem(event, reason = "navigation")
                    return
                }
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                resetTouchDeltaResiduals()
                actionRouter.route(InputAction.TouchContact(active = true))
            }
            val previousFingerX = lastFingerX
            val previousFingerY = lastFingerY
            lastFingerX = event.getX(0)
            lastFingerY = event.getY(0)
            debugOverlayView?.updateFinger(lastFingerX, lastFingerY)
            val actions = touchpadEngine.onTouchEvent(event)
            routeActions(actions)
            if (
                event.actionMasked == MotionEvent.ACTION_MOVE &&
                event.pointerCount == 1 &&
                !previousFingerX.isNaN() &&
                !previousFingerY.isNaN()
            ) {
                val touchDelta = consumeTouchDelta(
                    rawDx = lastFingerX - previousFingerX,
                    rawDy = lastFingerY - previousFingerY,
                )
                if (touchDelta != null) {
                    actionRouter.route(touchDelta)
                }
            } else if (event.pointerCount != 1 || event.actionMasked != MotionEvent.ACTION_MOVE) {
                resetTouchDeltaResiduals()
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                resetTouchDeltaResiduals()
                actionRouter.route(InputAction.TouchContact(active = false))
            }
        }

        override fun onStateChanged(state: Int) {
            Log.i(TAG, "touch controller state=${TouchInteractionController.stateToString(state)}")
        }
    }

    fun enable(): Boolean {
        if (enabled) {
            return true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return false
        }

        return runCatching {
            val touchController = service.getTouchInteractionController(Display.DEFAULT_DISPLAY)
            touchController.registerCallback(null, callback)
            controller = touchController
            enabled = true
            updateBoundsFromDisplay()
            cursorStateStore.resetToCenter()
            overlayAttachedState.value = true
            resetTouchDeltaResiduals()
            ensureScrollDispatcher()
            ensureEdgeHighlightOverlay()
            ensureDebugOverlay()
            feedbackModule.onOverlayToggle(true)
            Log.i(TAG, "TouchInteraction capture enabled")
            true
        }.getOrElse { error ->
            Log.w(TAG, "Failed to enable TouchInteraction capture", error)
            overlayAttachedState.value = false
            false
        }
    }

    fun disable() {
        val currentController = controller
        if (currentController != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { currentController.unregisterCallback(callback) }
                .onFailure { error -> Log.w(TAG, "Failed to unregister TouchInteraction callback", error) }
        }
        clearScrollPipeline(sendStopMarker = true)
        resetTouchDeltaResiduals()
        lastFingerX = Float.NaN
        lastFingerY = Float.NaN
        actionRouter.route(InputAction.TouchContact(active = false))
        detachEdgeHighlightOverlay()
        detachDebugOverlay()
        controller = null
        if (enabled) {
            enabled = false
            overlayAttachedState.value = false
            feedbackModule.onOverlayToggle(false)
            Log.i(TAG, "TouchInteraction capture disabled")
        }
    }

    fun isEnabled(): Boolean = enabled

    fun updateImeBounds(bounds: Rect?) {
        imeBoundsInScreen = bounds?.let { Rect(it) }
    }

    private fun routeActions(actions: List<InputAction>) {
        actions.forEach { action ->
            when (action) {
                is InputAction.Haptic -> when (action.kind) {
                    HapticFeedbackKind.TOUCH_CONTACT -> feedbackModule.onTouchContact()
                    HapticFeedbackKind.CLICK -> feedbackModule.onClick()
                    HapticFeedbackKind.RIGHT_CLICK -> feedbackModule.onRightClick()
                    HapticFeedbackKind.DRAG_START -> feedbackModule.onDragStart()
                }

                is InputAction.ScrollBy -> onScrollAction(action)
                is InputAction.ScrollFling -> onScrollFlingAction(action)
                else -> {
                    cancelFling()
                    actionRouter.route(action)
                }
            }
        }
    }

    private fun ensureScrollDispatcher() {
        if (scrollDispatchJob?.isActive == true) {
            return
        }
        scrollDispatchJob = dispatchScope.launch {
            while (isActive) {
                dispatchScrollTick()
                delay(currentScrollDispatchIntervalMs())
            }
        }
    }

    private fun onScrollAction(action: InputAction.ScrollBy) {
        ensureScrollDispatcher()
        lastScrollDx = action.dx
        lastScrollDy = action.dy
        debugOverlayView?.updateScrollDelta(lastScrollDx, lastScrollDy)
        if (action.dx == 0 && action.dy == 0) {
            synchronized(scrollLock) {
                if (flingActive) {
                    return
                }
                pendingScrollDx = 0
                pendingScrollDy = 0
                stopRequested = true
            }
            return
        }

        cancelFling()
        synchronized(scrollLock) {
            pendingScrollDx = (pendingScrollDx + action.dx).coerceIn(-MAX_PENDING_SCROLL, MAX_PENDING_SCROLL)
            pendingScrollDy = (pendingScrollDy + action.dy).coerceIn(-MAX_PENDING_SCROLL, MAX_PENDING_SCROLL)
            stopRequested = false
        }
    }

    private fun onScrollFlingAction(action: InputAction.ScrollFling) {
        ensureScrollDispatcher()
        cancelFling()
        scrollFlingJob = dispatchScope.launch {
            val inertiaMs = action.inertiaMs
            val friction = frictionForInertia(inertiaMs)
            val velocityX = action.velocityXPerSecond.roundToInt().coerceIn(-MAX_FLING_VELOCITY, MAX_FLING_VELOCITY)
            val velocityY = action.velocityYPerSecond.roundToInt().coerceIn(-MAX_FLING_VELOCITY, MAX_FLING_VELOCITY)
            if (velocityX == 0 && velocityY == 0) {
                synchronized(scrollLock) {
                    stopRequested = true
                }
                return@launch
            }

            synchronized(scrollLock) {
                flingActive = true
                stopRequested = false
            }

            overScroller.forceFinished(true)
            overScroller.setFriction(friction)
            overScroller.fling(
                0,
                0,
                velocityX,
                velocityY,
                Int.MIN_VALUE,
                Int.MAX_VALUE,
                Int.MIN_VALUE,
                Int.MAX_VALUE,
            )

            var previousX = 0
            var previousY = 0
            while (isActive && !overScroller.isFinished) {
                val hasOffset = overScroller.computeScrollOffset()
                if (!hasOffset) {
                    break
                }
                val currentX = overScroller.currX
                val currentY = overScroller.currY
                val stepDx = currentX - previousX
                val stepDy = currentY - previousY
                previousX = currentX
                previousY = currentY
                if (stepDx != 0 || stepDy != 0) {
                    synchronized(scrollLock) {
                        pendingScrollDx = (pendingScrollDx + stepDx).coerceIn(-MAX_PENDING_SCROLL, MAX_PENDING_SCROLL)
                        pendingScrollDy = (pendingScrollDy + stepDy).coerceIn(-MAX_PENDING_SCROLL, MAX_PENDING_SCROLL)
                    }
                }
                delay(currentScrollDispatchIntervalMs())
            }

            synchronized(scrollLock) {
                flingActive = false
                stopRequested = true
            }
        }
    }

    private fun cancelFling() {
        val jobToCancel = synchronized(scrollLock) {
            flingActive = false
            overScroller.forceFinished(true)
            val job = scrollFlingJob
            scrollFlingJob = null
            job
        }
        jobToCancel?.cancel()
    }

    private fun dispatchScrollTick() {
        val timedActions = touchpadEngine.onTick(SystemClock.uptimeMillis())
        routeActions(timedActions)

        val dispatchDx: Int
        val dispatchDy: Int
        val sendStop: Boolean
        synchronized(scrollLock) {
            dispatchDx = pendingScrollDx.coerceIn(-MAX_SCROLL_CHUNK_PER_TICK, MAX_SCROLL_CHUNK_PER_TICK)
            dispatchDy = pendingScrollDy.coerceIn(-MAX_SCROLL_CHUNK_PER_TICK, MAX_SCROLL_CHUNK_PER_TICK)
            pendingScrollDx -= dispatchDx
            pendingScrollDy -= dispatchDy
            val noPending = pendingScrollDx == 0 && pendingScrollDy == 0
            sendStop = stopRequested && noPending && !flingActive
            if (sendStop) {
                stopRequested = false
            }
        }

        if (dispatchDx != 0 || dispatchDy != 0) {
            actionRouter.route(InputAction.ScrollBy(dispatchDx, dispatchDy))
        }
        if (sendStop) {
            actionRouter.route(InputAction.ScrollBy(0, 0))
        }
    }

    private fun clearScrollPipeline(sendStopMarker: Boolean) {
        cancelFling()
        scrollDispatchJob?.cancel()
        scrollDispatchJob = null
        synchronized(scrollLock) {
            pendingScrollDx = 0
            pendingScrollDy = 0
            stopRequested = false
        }
        if (sendStopMarker) {
            actionRouter.route(InputAction.ScrollBy(0, 0))
        }
    }

    private fun ensureEdgeHighlightOverlay() {
        if (edgeHighlightView == null) {
            edgeHighlightView = EdgeHighlightView(service)
        }
        if (!edgeHighlightAttached) {
            val params = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                title = EDGE_HIGHLIGHT_OVERLAY_TITLE
            }
            runCatching {
                windowManager.addView(edgeHighlightView, params)
                edgeHighlightAttached = true
            }.onFailure { error ->
                Log.w(TAG, "Failed to add edge highlight overlay", error)
            }
        }

        if (edgeHighlightCollectJob?.isActive == true) {
            return
        }
        edgeHighlightCollectJob = uiScope.launch {
            combine(edgeHighlightState, settingsRepository.debugOverlayEnabled) { state, debugEnabled ->
                state to debugEnabled
            }.collect { (state, debugEnabled) ->
                val thicknessPx = 28f
                edgeHighlightView?.update(if (debugEnabled) state else EdgeHighlightState.NONE, thicknessPx)
            }
        }
    }

    private fun detachEdgeHighlightOverlay() {
        edgeHighlightCollectJob?.cancel()
        edgeHighlightCollectJob = null
        edgeHighlightView?.update(EdgeHighlightState.NONE, 0f)
        if (edgeHighlightAttached) {
            runCatching {
                windowManager.removeView(edgeHighlightView)
            }.onFailure { error ->
                Log.w(TAG, "Failed to remove edge highlight overlay", error)
            }
            edgeHighlightAttached = false
        }
        edgeHighlightView = null
    }

    private fun resetTouchDeltaResiduals() {
        touchDeltaResidualX = 0f
        touchDeltaResidualY = 0f
    }

    private fun consumeTouchDelta(rawDx: Float, rawDy: Float): InputAction.TouchDelta? {
        val totalDx = rawDx + touchDeltaResidualX
        val totalDy = rawDy + touchDeltaResidualY
        val dx = quantizeTowardZero(totalDx)
        val dy = quantizeTowardZero(totalDy)
        touchDeltaResidualX = totalDx - dx
        touchDeltaResidualY = totalDy - dy
        return if (dx == 0 && dy == 0) {
            null
        } else {
            InputAction.TouchDelta(dx = dx, dy = dy)
        }
    }

    private fun quantizeTowardZero(value: Float): Int {
        return if (value >= 0f) {
            floor(value).toInt()
        } else {
            ceil(value).toInt()
        }
    }

    private fun ensureDebugOverlay() {
        if (debugOverlayView == null) {
            debugOverlayView = DebugOverlayView(service)
        }
        if (!debugOverlayAttached) {
            val params = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                title = DEBUG_OVERLAY_TITLE
            }
            runCatching {
                windowManager.addView(debugOverlayView, params)
                debugOverlayAttached = true
            }.onFailure { error ->
                Log.w(TAG, "Failed to add debug overlay", error)
            }
        }

        if (debugToggleCollectJob?.isActive != true) {
            debugToggleCollectJob = uiScope.launch {
                settingsRepository.debugOverlayEnabled.collect { enabled ->
                    debugOverlayView?.setDebugEnabled(enabled)
                }
            }
        }
        if (debugStatusCollectJob?.isActive != true) {
            debugStatusCollectJob = uiScope.launch {
                combine(
                    runtimeModeState,
                    sessionConnectedState,
                    overlayDesiredState,
                    overlayAttachedState,
                    localBackendState,
                    executorStatusState,
                ) { values ->
                    val mode = values[0] as AppMode
                    val connected = values[1] as Boolean
                    val desired = values[2] as Boolean
                    val attached = values[3] as Boolean
                    val backend = values[4] as LocalBackendState
                    val executor = values[5] as ExecutorStatus
                    buildStatusLines(
                        mode = mode,
                        connected = connected,
                        desired = desired,
                        attached = attached,
                        backend = backend,
                        executor = executor,
                    )
                }.collect { lines ->
                    debugOverlayView?.updateStatusLines(lines)
                }
            }
        }
        if (debugConsoleCollectJob?.isActive != true) {
            debugConsoleCollectJob = uiScope.launch {
                com.alex.touchpad.core.AppLog.recentIssues.collect { issues ->
                    debugOverlayView?.updateConsoleEntries(issues.takeLast(DebugOverlayView.MAX_CONSOLE_LINES))
                }
            }
        }
        if (debugCursorCollectJob?.isActive != true) {
            debugCursorCollectJob = uiScope.launch {
                cursorGroundTruthState.collect { cursor ->
                    debugOverlayView?.updateCursor(cursor)
                }
            }
        }
        if (debugDaemonCollectJob?.isActive != true) {
            debugDaemonCollectJob = uiScope.launch {
                shellDaemonActiveState.collect { active ->
                    debugOverlayView?.updateShellDaemonActive(active)
                }
            }
        }
        if (debugHidCollectJob?.isActive != true) {
            debugHidCollectJob = uiScope.launch {
                hidScrollDebugHistoryState.collect { history ->
                    debugOverlayView?.updateHidHistory(history)
                }
            }
        }
        if (debugSwipeCollectJob?.isActive != true) {
            debugSwipeCollectJob = uiScope.launch {
                edgeSwipeDebugHistoryState.collect { history ->
                    debugOverlayView?.updateEdgeSwipeHistory(history)
                }
            }
        }
    }

    private fun detachDebugOverlay() {
        debugToggleCollectJob?.cancel()
        debugToggleCollectJob = null
        debugStatusCollectJob?.cancel()
        debugStatusCollectJob = null
        debugConsoleCollectJob?.cancel()
        debugConsoleCollectJob = null
        debugCursorCollectJob?.cancel()
        debugCursorCollectJob = null
        debugDaemonCollectJob?.cancel()
        debugDaemonCollectJob = null
        debugHidCollectJob?.cancel()
        debugHidCollectJob = null
        debugSwipeCollectJob?.cancel()
        debugSwipeCollectJob = null
        if (debugOverlayAttached) {
            runCatching {
                windowManager.removeView(debugOverlayView)
            }.onFailure { error ->
                Log.w(TAG, "Failed to remove debug overlay", error)
            }
            debugOverlayAttached = false
        }
        debugOverlayView = null
    }

    private fun buildStatusLines(
        mode: AppMode,
        connected: Boolean,
        desired: Boolean,
        attached: Boolean,
        backend: LocalBackendState,
        executor: ExecutorStatus,
    ): List<String> {
        val captureStatus = when {
            attached -> "ACTIVE"
            desired -> "PENDING"
            else -> "DISABLED"
        }
        val backendStatus = if (backend.running) {
            "RUNNING${backend.port?.let { " ($it)" } ?: ""}"
        } else {
            "STOPPED"
        }
        return listOf(
            "Mode: ${mode.name}",
            "ADB session: ${if (connected) "ONLINE" else "OFFLINE"}",
            "Local backend: $backendStatus",
            "Touch capture: $captureStatus",
            "Target: $DEBUG_TARGET_SERIAL",
            "ADB binary: ${if (executor.adbBinaryReady) "READY" else "MISSING"}",
        )
    }

    private fun frictionForInertia(inertiaMs: Int): Float {
        val defaultFriction = ViewConfiguration.getScrollFriction()
        val normalized = (inertiaMs.toFloat() / FLING_INERTIA_REFERENCE_MS.toFloat())
        val frictionScale = MAX_FRICTION_SCALE - ((MAX_FRICTION_SCALE - MIN_FRICTION_SCALE) * normalized)
        return (defaultFriction * frictionScale).coerceAtLeast(MIN_ABSOLUTE_FRICTION)
    }

    private fun currentScrollDispatchIntervalMs(): Long {
        return settingsRepository.scrollDispatchIntervalMs.value.toLong()
    }

    private fun updateBoundsFromDisplay() {
        val realSize = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(realSize)
        if (realSize.x == lastBoundsWidth && realSize.y == lastBoundsHeight) {
            return
        }
        lastBoundsWidth = realSize.x
        lastBoundsHeight = realSize.y
        cursorStateStore.updateBounds(realSize.x, realSize.y)
        Log.i(TAG, "TouchInteraction bounds=${realSize.x}x${realSize.y}")
    }

    private fun shouldDelegateImeTouch(event: MotionEvent): Boolean {
        val imeBounds = imeBoundsInScreen ?: return false
        val touchX = event.getX(0).toInt()
        val touchY = event.getY(0).toInt()
        return imeBounds.contains(touchX, touchY)
    }

    private fun shouldDelegateNavigationTouch(event: MotionEvent): Boolean {
        if (!settingsRepository.bottomNavBarPassthroughEnabled.value) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false
        }
        val windowInsets = runCatching { windowManager.currentWindowMetrics.windowInsets }.getOrNull() ?: return false
        if (!windowInsets.isVisible(WindowInsets.Type.navigationBars())) {
            return false
        }
        val navInsets = windowInsets.getInsets(WindowInsets.Type.navigationBars())
        val displayWidth = lastBoundsWidth.coerceAtLeast(0)
        val displayHeight = lastBoundsHeight.coerceAtLeast(0)
        if (displayWidth <= 0 || displayHeight <= 0) {
            return false
        }
        val touchX = event.getX(0).toInt()
        val touchY = event.getY(0).toInt()

        // Intentionally ignore status bar/top inset; pass through nav bar only.
        val inBottomNav = navInsets.bottom > 0 && touchY >= (displayHeight - navInsets.bottom)
        val inLeftNav = navInsets.left > 0 && touchX <= navInsets.left
        val inRightNav = navInsets.right > 0 && touchX >= (displayWidth - navInsets.right)
        return inBottomNav || inLeftNav || inRightNav
    }

    private fun delegateCurrentGestureToSystem(downEvent: MotionEvent, reason: String) {
        val cancelEvent = MotionEvent.obtain(downEvent)
        try {
            cancelEvent.action = MotionEvent.ACTION_CANCEL
            val cancelActions = touchpadEngine.onTouchEvent(cancelEvent)
            routeActions(cancelActions)
        } finally {
            cancelEvent.recycle()
        }
        runCatching {
            controller?.requestDelegating()
            Log.i(TAG, "Delegating touch reason=$reason x=${downEvent.getX(0)} y=${downEvent.getY(0)}")
        }.onFailure { error ->
            Log.w(TAG, "Failed to request delegating reason=$reason", error)
        }
    }

    private class EdgeHighlightView(
        context: Context,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(88, 76, 175, 80)
        }
        private var state = EdgeHighlightState.NONE
        private var thicknessPx = 0f

        fun update(nextState: EdgeHighlightState, nextThicknessPx: Float) {
            val normalizedThickness = nextThicknessPx.coerceAtLeast(0f)
            if (state == nextState && thicknessPx == normalizedThickness) {
                return
            }
            state = nextState
            thicknessPx = normalizedThickness
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (thicknessPx <= 0f) {
                return
            }
            val width = width.toFloat()
            val height = height.toFloat()
            if (width <= 0f || height <= 0f) {
                return
            }
            val edge = thicknessPx.coerceAtMost(width / 2f).coerceAtMost(height / 2f)
            if (edge <= 0f) {
                return
            }
            if (state.left) {
                canvas.drawRect(0f, 0f, edge, height, paint)
            }
            if (state.right) {
                canvas.drawRect(width - edge, 0f, width, height, paint)
            }
            if (state.top) {
                canvas.drawRect(0f, 0f, width, edge, paint)
            }
            if (state.bottom) {
                canvas.drawRect(0f, height - edge, width, height, paint)
            }
        }
    }

    private class DebugOverlayView(
        context: Context,
    ) : View(context) {
        private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(89, 0, 0, 0)
        }
        private val panelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(110, 255, 255, 255)
        }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
            isFakeBoldText = true
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
        }
        private val consoleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 255, 224, 178)
            textSize = 22f
        }
        private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 80, 255, 120)
            style = Paint.Style.FILL
        }
        private val fingerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 80, 80)
            style = Paint.Style.FILL
        }
        private val swipeTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 0, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val swipeStartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 0, 255, 0)
            style = Paint.Style.FILL
        }
        private val swipeEndPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 0, 255)
            style = Paint.Style.FILL
        }
        private val cornerPriorityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(89, 255, 0, 0)
            style = Paint.Style.FILL
        }
        private val reusableRect = RectF()
        private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

        private var enabled = false
        private var cursor: CursorGroundTruth? = null
        private var shellDaemonActive = false
        private var fingerX = Float.NaN
        private var fingerY = Float.NaN
        private var scrollDx = 0
        private var scrollDy = 0
        private var statusLines: List<String> = emptyList()
        private var consoleLines: List<String> = emptyList()
        private var hidHistory: List<HidScrollDebugEvent> = emptyList()
        private var edgeSwipeHistory: List<EdgeSwipeDebugEvent> = emptyList()

        fun setDebugEnabled(value: Boolean) {
            if (enabled == value) return
            enabled = value
            postInvalidateOnAnimation()
        }

        fun updateStatusLines(lines: List<String>) {
            statusLines = lines
            postInvalidateOnAnimation()
        }

        fun updateConsoleEntries(entries: List<AppLogEntry>) {
            consoleLines = entries.takeLast(MAX_CONSOLE_LINES).map { entry ->
                val level = when (entry.level) {
                    com.alex.touchpad.core.AppLogLevel.ERROR -> "E"
                    com.alex.touchpad.core.AppLogLevel.WARNING -> "W"
                }
                val time = timeFormatter.format(Date(entry.timestampMs))
                "$time [$level] ${entry.tag}: ${entry.message}"
            }
            postInvalidateOnAnimation()
        }

        fun updateCursor(value: CursorGroundTruth?) {
            cursor = value
            postInvalidateOnAnimation()
        }

        fun updateShellDaemonActive(value: Boolean) {
            shellDaemonActive = value
            postInvalidateOnAnimation()
        }

        fun updateFinger(x: Float, y: Float) {
            fingerX = x
            fingerY = y
            postInvalidateOnAnimation()
        }

        fun updateScrollDelta(dx: Int, dy: Int) {
            scrollDx = dx
            scrollDy = dy
            postInvalidateOnAnimation()
        }

        fun updateHidHistory(history: List<HidScrollDebugEvent>) {
            hidHistory = history.takeLast(MAX_HID_HISTORY_LINES)
            postInvalidateOnAnimation()
        }

        fun updateEdgeSwipeHistory(history: List<EdgeSwipeDebugEvent>) {
            edgeSwipeHistory = history.takeLast(20)
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!enabled) return

            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            if (viewWidth <= 0f || viewHeight <= 0f) {
                return
            }

            drawCornerPriorityMarkers(canvas, viewWidth, viewHeight)
            drawPointersAndTrails(canvas)

            val panelMargin = 20f
            val panelGap = 16f
            val halfWidth = ((viewWidth - (panelMargin * 2f) - panelGap) / 2f).coerceAtLeast(220f)
            val topRowTop = panelMargin
            val statusHeight = panelHeightForLines(statusLines.size, BODY_LINE_HEIGHT)
            val consoleHeight = panelHeightForLines(consoleLines.size.coerceAtLeast(1), CONSOLE_LINE_HEIGHT)
            val topRowHeight = max(statusHeight, consoleHeight)

            reusableRect.set(panelMargin, topRowTop, panelMargin + halfWidth, topRowTop + statusHeight)
            drawPanel(canvas, reusableRect, "Status", statusLines, textPaint, BODY_LINE_HEIGHT)

            reusableRect.set(
                panelMargin + halfWidth + panelGap,
                topRowTop,
                viewWidth - panelMargin,
                topRowTop + consoleHeight,
            )
            drawPanel(
                canvas,
                reusableRect,
                "Console",
                consoleLines.ifEmpty { listOf("No recent issues") },
                consoleTextPaint,
                CONSOLE_LINE_HEIGHT,
            )

            val diagnosticsTop = topRowTop + topRowHeight + panelGap
            val diagnosticsLines = buildDiagnosticsLines()
            reusableRect.set(
                panelMargin,
                diagnosticsTop,
                viewWidth - panelMargin,
                diagnosticsTop + panelHeightForLines(diagnosticsLines.size, BODY_LINE_HEIGHT),
            )
            drawPanel(canvas, reusableRect, "Touch Input", diagnosticsLines, textPaint, BODY_LINE_HEIGHT)
        }

        private fun drawCornerPriorityMarkers(canvas: Canvas, viewWidth: Float, viewHeight: Float) {
            val cornerPrioritySizePx = 3f
            canvas.drawRect(0f, 0f, cornerPrioritySizePx, cornerPrioritySizePx, cornerPriorityPaint)
            canvas.drawRect(
                viewWidth - cornerPrioritySizePx,
                0f,
                viewWidth,
                cornerPrioritySizePx,
                cornerPriorityPaint,
            )
            canvas.drawRect(
                0f,
                viewHeight - cornerPrioritySizePx,
                cornerPrioritySizePx,
                viewHeight,
                cornerPriorityPaint,
            )
            canvas.drawRect(
                viewWidth - cornerPrioritySizePx,
                viewHeight - cornerPrioritySizePx,
                viewWidth,
                viewHeight,
                cornerPriorityPaint,
            )
        }

        private fun drawPointersAndTrails(canvas: Canvas) {
            cursor?.let {
                canvas.drawCircle(it.x, it.y, 10f, cursorPaint)
            }
            if (!fingerX.isNaN() && !fingerY.isNaN()) {
                canvas.drawCircle(fingerX, fingerY, 10f, fingerPaint)
            }
            edgeSwipeHistory.forEach { swipe ->
                canvas.drawLine(swipe.startX, swipe.startY, swipe.endX, swipe.endY, swipeTrailPaint)
                canvas.drawCircle(swipe.startX, swipe.startY, 8f, swipeStartPaint)
                canvas.drawCircle(swipe.endX, swipe.endY, 8f, swipeEndPaint)
            }
        }

        private fun drawPanel(
            canvas: Canvas,
            bounds: RectF,
            title: String,
            lines: List<String>,
            bodyPaint: Paint,
            lineHeight: Float,
        ) {
            canvas.drawRoundRect(bounds, PANEL_RADIUS, PANEL_RADIUS, panelPaint)
            canvas.drawRoundRect(bounds, PANEL_RADIUS, PANEL_RADIUS, panelStrokePaint)

            val contentLeft = bounds.left + PANEL_PADDING
            val maxTextWidth = (bounds.width() - (PANEL_PADDING * 2f)).coerceAtLeast(0f)
            var baseline = bounds.top + PANEL_PADDING + TITLE_BASELINE_OFFSET
            canvas.drawText(title, contentLeft, baseline, titlePaint)
            baseline += lineHeight

            lines.forEach { line ->
                canvas.drawText(ellipsize(line, maxTextWidth, bodyPaint), contentLeft, baseline, bodyPaint)
                baseline += lineHeight
            }
        }

        private fun buildDiagnosticsLines(): List<String> {
            val lines = mutableListOf<String>()
            val cursorSource = if (shellDaemonActive) "daemon" else "adb"
            val cursorText = cursor?.let {
                "Cursor($cursorSource): x=${"%.1f".format(it.x)} y=${"%.1f".format(it.y)}"
            } ?: "Cursor($cursorSource): n/a"
            val fingerText = if (fingerX.isNaN() || fingerY.isNaN()) {
                "Finger: n/a"
            } else {
                "Finger: x=${"%.1f".format(fingerX)} y=${"%.1f".format(fingerY)}"
            }
            lines += "Daemon: ${if (shellDaemonActive) "ACTIVE" else "INACTIVE"}"
            lines += cursorText
            lines += fingerText
            lines += "Scroll delta: dx=$scrollDx dy=$scrollDy"
            lines += "Edge swipes: ${edgeSwipeHistory.size}"
            lines += "HID scroll history:"
            hidHistory.takeLast(MAX_HID_HISTORY_LINES).forEach { event ->
                val seconds = event.elapsedRealtimeMs / 1000.0
                lines += "${"%.3f".format(seconds)}  v=${event.vWheel} h=${event.hWheel}"
            }
            return lines
        }

        private fun panelHeightForLines(lineCount: Int, lineHeight: Float): Float {
            val contentLines = lineCount.coerceAtLeast(1)
            return (PANEL_PADDING * 2f) + TITLE_BASELINE_OFFSET + (contentLines * lineHeight)
        }

        private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
            if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) {
                return text
            }
            val ellipsis = "..."
            val availableWidth = (maxWidth - paint.measureText(ellipsis)).coerceAtLeast(0f)
            val count = paint.breakText(text, true, availableWidth, null).coerceAtLeast(0)
            if (count <= 0) {
                return ellipsis
            }
            return text.take(count).trimEnd() + ellipsis
        }

        companion object {
            const val MAX_HID_HISTORY_LINES = 8
            const val MAX_CONSOLE_LINES = 8
            private const val PANEL_PADDING = 18f
            private const val PANEL_RADIUS = 18f
            private const val TITLE_BASELINE_OFFSET = 28f
            private const val BODY_LINE_HEIGHT = 30f
            private const val CONSOLE_LINE_HEIGHT = 26f
        }
    }

    private companion object {
        const val TAG = "TouchInteractionCapture"
        const val EDGE_HIGHLIGHT_OVERLAY_TITLE = "TouchpadEdgeHighlight"
        const val DEBUG_OVERLAY_TITLE = "TouchpadDebugOverlay"
        const val DEBUG_TARGET_SERIAL = "127.0.0.1:5555"
        const val MAX_SCROLL_CHUNK_PER_TICK = 240
        const val MAX_PENDING_SCROLL = 24_000
        const val FLING_INERTIA_REFERENCE_MS = 2_500
        const val MAX_FLING_VELOCITY = 32_000
        const val MAX_FRICTION_SCALE = 1.35f
        const val MIN_FRICTION_SCALE = 0.16f
        const val MIN_ABSOLUTE_FRICTION = 0.0015f
    }
}
