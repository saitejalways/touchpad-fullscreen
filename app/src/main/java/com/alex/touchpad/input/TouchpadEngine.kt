package com.alex.touchpad.input

import android.view.MotionEvent
import com.alex.touchpad.core.CursorStateStore
import com.alex.touchpad.settings.SettingsRepository
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

class TouchpadEngine(
    private val cursorStateStore: CursorStateStore,
    private val settingsRepository: SettingsRepository,
) {
    private val stateLock = Any()

    private var lastX = 0f
    private var lastY = 0f

    private var residualDx = 0f
    private var residualDy = 0f
    private var scrollResidualDx = 0f
    private var scrollResidualDy = 0f
    private var scrollVelocityDxPerMs = 0f
    private var scrollVelocityDyPerMs = 0f
    private var lastScrollSampleAtMs = 0L
    private var pointerVelocityDxPerMs = 0f
    private var pointerVelocityDyPerMs = 0f
    private var lastPointerSampleAtMs = 0L
    private var dragReleaseInertia: DragReleaseInertia? = null

    private var primaryState: PrimaryPointerState = PrimaryPointerState.Idle

    private var pendingLeftTapX = 0f
    private var pendingLeftTapY = 0f
    private var pendingLeftTapAtMs = 0L
    private var lastLeftClickAtMs = -1L

    private var twoFingerActive = false
    private var twoFingerScrolling = false
    private var lastCentroidX = 0f
    private var lastCentroidY = 0f
    private var twoFingerDownCentroidX = 0f
    private var twoFingerDownCentroidY = 0f
    private var twoFingerDownAtMs = 0L
    private var twoFingerTapMoved = false
    private var scrollAxisLock = ScrollAxisLock.NONE
    private var axisLockAccumDx = 0f
    private var axisLockAccumDy = 0f

    fun onTouchEvent(event: MotionEvent): List<InputAction> = synchronized(stateLock) {
        expirePendingGestures(event.eventTime)
        val timedActions = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            -> armHoldDragIfDue(event.eventTime)

            else -> emptyList()
        }
        val eventActions = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleActionDown(event)

            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(event)

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 2) {
                    val hadTwoFingerScroll = twoFingerScrolling
                    twoFingerActive = false
                    twoFingerScrolling = false
                    twoFingerTapMoved = true
                    resetScrollMotionState()
                    primaryState = PrimaryPointerState.Idle
                    if (hadTwoFingerScroll) {
                        listOf(InputAction.ScrollBy(0, 0))
                    } else {
                        emptyList()
                    }
                } else if (event.pointerCount == 2) {
                    if (!twoFingerActive) {
                        beginTwoFingerGesture(event)
                        emptyList()
                    } else {
                        handleTwoFingerMove(event)
                    }
                } else if (twoFingerActive) {
                    handleTwoFingerMove(event)
                } else {
                    when (primaryState) {
                        is PrimaryPointerState.Dragging -> handlePointerMove(event)
                        is PrimaryPointerState.HoldArmed -> handleHoldArmedMove(event)
                        is PrimaryPointerState.Tracking -> handleTrackingMove(event)
                        PrimaryPointerState.Idle -> emptyList()
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(event)

            MotionEvent.ACTION_UP -> handleActionUp(event)

            MotionEvent.ACTION_CANCEL -> handleCancel()

            else -> emptyList()
        }
        if (timedActions.isEmpty()) {
            eventActions
        } else if (eventActions.isEmpty()) {
            timedActions
        } else {
            timedActions + eventActions
        }
    }

    fun onTick(nowMs: Long): List<InputAction> = synchronized(stateLock) {
        expirePendingGestures(nowMs)
        val timedActions = armHoldDragIfDue(nowMs)
        val inertiaActions = advanceDragReleaseInertia(nowMs)
        when {
            timedActions.isEmpty() -> inertiaActions
            inertiaActions.isEmpty() -> timedActions
            else -> timedActions + inertiaActions
        }
    }

    private fun handleActionDown(event: MotionEvent): List<InputAction> {
        val actions = mutableListOf<InputAction>()
        actions += cancelDragReleaseInertiaIfNeeded()
        val armSecondTapDrag = shouldArmSecondTapDrag(event)
        val holdAllowed = pendingLeftTapAtMs <= 0L || armSecondTapDrag
        val holdArmAtMs = event.eventTime + settingsRepository.holdDragDelayMs.value.toLong()

        lastX = event.x
        lastY = event.y

        twoFingerActive = false
        twoFingerScrolling = false
        twoFingerTapMoved = false
        residualDx = 0f
        residualDy = 0f
        resetScrollMotionState()
        resetPointerMotionState()
        primaryState = PrimaryPointerState.Tracking(
            downX = event.x,
            downY = event.y,
            downAtMs = event.eventTime,
            holdArmAtMs = holdArmAtMs,
            holdEligible = holdAllowed,
            secondTapDragArmed = armSecondTapDrag,
            tapEligible = true,
            filteredX = event.x,
            filteredY = event.y,
        )
        actions += InputAction.Haptic(HapticFeedbackKind.TOUCH_CONTACT)
        return actions
    }

    private fun handlePointerDown(event: MotionEvent): List<InputAction> {
        val actions = mutableListOf<InputAction>()
        when {
            event.pointerCount == 2 -> {
                if (primaryState is PrimaryPointerState.Dragging) {
                    actions += InputAction.ButtonUp(MouseButton.LEFT)
                }
                beginTwoFingerGesture(event)
            }

            event.pointerCount > 2 -> {
                val hadTwoFingerScroll = twoFingerScrolling
                primaryState = PrimaryPointerState.Idle
                twoFingerActive = false
                twoFingerScrolling = false
                twoFingerTapMoved = true
                resetScrollMotionState()
                if (hadTwoFingerScroll) {
                    actions += InputAction.ScrollBy(0, 0)
                }
            }
        }
        return actions
    }

    private fun handlePointerUp(event: MotionEvent): List<InputAction> {
        val actions = mutableListOf<InputAction>()
        val wasTwoFinger = twoFingerActive && event.pointerCount == 2
        if (wasTwoFinger) {
            val (centroidX, centroidY) = twoFingerCentroid(event)
            val duration = event.eventTime - twoFingerDownAtMs
            val moveFromDownX = abs(centroidX - twoFingerDownCentroidX)
            val moveFromDownY = abs(centroidY - twoFingerDownCentroidY)
            val isTwoFingerTap = !twoFingerScrolling &&
                !twoFingerTapMoved &&
                duration <= TWO_FINGER_TAP_TIMEOUT_MS &&
                moveFromDownX < TWO_FINGER_TAP_SLOP_PX &&
                moveFromDownY < TWO_FINGER_TAP_SLOP_PX

            if (isTwoFingerTap) {
                actions += InputAction.Click(MouseButton.RIGHT)
                actions += InputAction.Haptic(HapticFeedbackKind.RIGHT_CLICK)
            }
        }

        // If one finger remains, anchor pointer-delta tracking to the remaining pointer.
        if (event.pointerCount - 1 == 1) {
            val upIndex = event.actionIndex
            val remainingIndex = if (upIndex == 0) 1 else 0
            lastX = event.getX(remainingIndex)
            lastY = event.getY(remainingIndex)
        }

        if (event.pointerCount - 1 < 2) {
            val shouldFling = wasTwoFinger && twoFingerScrolling
            val flingAction = if (shouldFling) buildScrollFlingAction() else null
            twoFingerActive = false
            twoFingerScrolling = false
            twoFingerTapMoved = false
            resetPointerMotionState()
            primaryState = if (event.pointerCount - 1 == 1) {
                PrimaryPointerState.Tracking(
                    downX = lastX,
                    downY = lastY,
                    downAtMs = event.eventTime,
                    holdArmAtMs = Long.MAX_VALUE,
                    holdEligible = false,
                    secondTapDragArmed = false,
                    tapEligible = false,
                    filteredX = lastX,
                    filteredY = lastY,
                )
            } else {
                PrimaryPointerState.Idle
            }
            resetScrollMotionState()
            if (flingAction != null) {
                actions += flingAction
            } else {
                actions += InputAction.ScrollBy(0, 0)
            }
        }

        return actions
    }

    private fun handleActionUp(event: MotionEvent): List<InputAction> {
        val stateBeforeUp = primaryState

        if (stateBeforeUp is PrimaryPointerState.Dragging) {
            primaryState = PrimaryPointerState.Idle
            clearPendingLeftTap()
            val inertiaStarted = startDragReleaseInertia(event.eventTime)
            resetPointerMotionState()
            return if (inertiaStarted) {
                emptyList()
            } else {
                listOf(
                    InputAction.ScrollBy(0, 0),
                    InputAction.ButtonUp(MouseButton.LEFT),
                )
            }
        }

        // Explicit edge-case behavior: if hold-drag armed but no drag started, consume release with no click.
        if (stateBeforeUp is PrimaryPointerState.HoldArmed) {
            primaryState = PrimaryPointerState.Idle
            clearPendingLeftTap()
            resetScrollMotionState()
            return listOf(InputAction.ScrollBy(0, 0))
        }

        val tracking = stateBeforeUp as? PrimaryPointerState.Tracking
        val isTap = if (tracking != null && !twoFingerActive) {
            val tapDuration = event.eventTime - tracking.downAtMs
            val distance = hypot(
                (event.x - tracking.downX).toDouble(),
                (event.y - tracking.downY).toDouble(),
            ).toFloat()
            tracking.tapEligible &&
                tapDuration <= TAP_TIMEOUT_MS &&
                distance < TAP_SLOP_PX
        } else {
            false
        }

        twoFingerActive = false
        twoFingerScrolling = false
        twoFingerTapMoved = false
        resetPointerMotionState()
        primaryState = PrimaryPointerState.Idle
        resetScrollMotionState()

        return if (isTap) {
            val guardMs = settingsRepository.doubleClickGuardMs.value.toLong()
            val blockedByGuard = guardMs > 0L &&
                lastLeftClickAtMs >= 0L &&
                (event.eventTime - lastLeftClickAtMs) < guardMs
            if (blockedByGuard) {
                clearPendingLeftTap()
                listOf(InputAction.ScrollBy(0, 0))
            } else {
                pendingLeftTapAtMs = event.eventTime
                pendingLeftTapX = event.x
                pendingLeftTapY = event.y
                lastLeftClickAtMs = event.eventTime
                listOf(
                    InputAction.ScrollBy(0, 0),
                    InputAction.Click(MouseButton.LEFT),
                    InputAction.Haptic(HapticFeedbackKind.CLICK),
                )
            }
        } else {
            clearPendingLeftTap()
            listOf(InputAction.ScrollBy(0, 0))
        }
    }

    private fun handleCancel(): List<InputAction> {
        val actions = mutableListOf<InputAction>()
        val hadScrolling = twoFingerScrolling
        if (primaryState is PrimaryPointerState.Dragging) {
            actions += InputAction.ButtonUp(MouseButton.LEFT)
        }

        primaryState = PrimaryPointerState.Idle
        twoFingerActive = false
        twoFingerScrolling = false
        twoFingerTapMoved = false
        resetPointerMotionState()
        resetScrollMotionState()
        clearPendingLeftTap()
        if (hadScrolling) {
            actions += InputAction.ScrollBy(0, 0)
        }
        return actions
    }

    private fun handlePointerMove(event: MotionEvent): List<InputAction> {
        val rawDx = event.x - lastX
        val rawDy = event.y - lastY

        lastX = event.x
        lastY = event.y

        val moveDeadbandPx = settingsRepository.pointerMoveDeadbandPx.value
        val filteredDx = applySoftDeadband(rawDx, moveDeadbandPx)
        val filteredDy = applySoftDeadband(rawDy, moveDeadbandPx)
        val speed = settingsRepository.speedMultiplier.value
        val accelerationEnabled = settingsRepository.mouseAccelerationEnabled.value
        val accelerationGain = if (accelerationEnabled) {
            accelerationGain(filteredDx, filteredDy)
        } else {
            1f
        }
        val totalDx = (filteredDx * speed * accelerationGain) + residualDx
        val totalDy = (filteredDy * speed * accelerationGain) + residualDy

        val scaledDx = if (totalDx >= 0f) floor(totalDx).toInt() else ceil(totalDx).toInt()
        val scaledDy = if (totalDy >= 0f) floor(totalDy).toInt() else ceil(totalDy).toInt()
        residualDx = totalDx - scaledDx
        residualDy = totalDy - scaledDy

        if (scaledDx == 0 && scaledDy == 0) {
            return emptyList()
        }

        updatePointerVelocity(scaledDx, scaledDy, event.eventTime)
        cursorStateStore.applyDelta(scaledDx, scaledDy)
        return listOf(InputAction.MoveBy(scaledDx, scaledDy))
    }

    private fun handleTrackingMove(event: MotionEvent): List<InputAction> {
        val tracking = primaryState as? PrimaryPointerState.Tracking ?: return emptyList()

        val nextFilteredX = tracking.filteredX + ((event.x - tracking.filteredX) * HOLD_ARM_FILTER_ALPHA)
        val nextFilteredY = tracking.filteredY + ((event.y - tracking.filteredY) * HOLD_ARM_FILTER_ALPHA)
        val holdDistancePx = hypot(
            (nextFilteredX - tracking.downX).toDouble(),
            (nextFilteredY - tracking.downY).toDouble(),
        ).toFloat()

        val dragStartSlopPx = settingsRepository.dragStartSlopPx.value
            .coerceAtLeast(MIN_DRAG_START_SLOP_PX)

        // Tap-drag (second tap) should not wait for hold timeout once deliberate movement starts.
        if (tracking.secondTapDragArmed && holdDistancePx >= dragStartSlopPx) {
            primaryState = PrimaryPointerState.Dragging(
                downX = tracking.downX,
                downY = tracking.downY,
                downAtMs = tracking.downAtMs,
            )
            clearPendingLeftTap()
            resetPointerMotionState()
            val moveActions = handlePointerMove(event)
            return buildList {
                add(InputAction.ButtonDown(MouseButton.LEFT))
                add(InputAction.Haptic(HapticFeedbackKind.DRAG_START))
                addAll(moveActions)
            }
        }

        val holdSlopPx = settingsRepository.holdDragSlopPx.value.coerceAtLeast(MIN_HOLD_SLOP_PX)
        val holdEligible = tracking.holdEligible && holdDistancePx <= holdSlopPx

        val tapDistancePx = hypot(
            (event.x - tracking.downX).toDouble(),
            (event.y - tracking.downY).toDouble(),
        ).toFloat()
        val tapEligible = tracking.tapEligible && tapDistancePx < TAP_SLOP_PX

        primaryState = tracking.copy(
            holdEligible = holdEligible,
            tapEligible = tapEligible,
            filteredX = nextFilteredX,
            filteredY = nextFilteredY,
        )
        return handlePointerMove(event)
    }

    private fun handleHoldArmedMove(event: MotionEvent): List<InputAction> {
        val armed = primaryState as? PrimaryPointerState.HoldArmed ?: return emptyList()
        val dragStartSlopPx = settingsRepository.dragStartSlopPx.value
            .coerceAtLeast(MIN_DRAG_START_SLOP_PX)
        val distanceFromAnchorPx = hypot(
            (event.x - armed.anchorX).toDouble(),
            (event.y - armed.anchorY).toDouble(),
        ).toFloat()
        if (distanceFromAnchorPx < dragStartSlopPx) {
            // Keep baseline synced while waiting for intentional drag motion.
            lastX = event.x
            lastY = event.y
            return emptyList()
        }

        primaryState = PrimaryPointerState.Dragging(
            downX = armed.downX,
            downY = armed.downY,
            downAtMs = armed.downAtMs,
        )
        clearPendingLeftTap()
        resetPointerMotionState()
        val moveActions = handlePointerMove(event)
        return buildList {
            add(InputAction.ButtonDown(MouseButton.LEFT))
            addAll(moveActions)
        }
    }

    private fun armHoldDragIfDue(nowMs: Long): List<InputAction> {
        val tracking = primaryState as? PrimaryPointerState.Tracking ?: return emptyList()
        if (!tracking.holdEligible) {
            return emptyList()
        }
        if (nowMs < tracking.holdArmAtMs) {
            return emptyList()
        }

        primaryState = PrimaryPointerState.HoldArmed(
            downX = tracking.downX,
            downY = tracking.downY,
            downAtMs = tracking.downAtMs,
            secondTapDragArmed = tracking.secondTapDragArmed,
            anchorX = lastX,
            anchorY = lastY,
        )
        return listOf(InputAction.Haptic(HapticFeedbackKind.DRAG_START))
    }

    private fun handleTwoFingerMove(event: MotionEvent): List<InputAction> {
        if (event.pointerCount != 2 || !twoFingerActive) {
            return emptyList()
        }

        val (centroidX, centroidY) = twoFingerCentroid(event)
        val rawDx = centroidX - lastCentroidX
        val rawDy = centroidY - lastCentroidY
        lastCentroidX = centroidX
        lastCentroidY = centroidY

        val moveFromDownX = abs(centroidX - twoFingerDownCentroidX)
        val moveFromDownY = abs(centroidY - twoFingerDownCentroidY)
        if (moveFromDownX >= TWO_FINGER_TAP_SLOP_PX || moveFromDownY >= TWO_FINGER_TAP_SLOP_PX) {
            twoFingerTapMoved = true
        }

        if (!twoFingerScrolling) {
            val scrollGracePx = settingsRepository.twoFingerScrollGraceMs.value.toFloat()
            val movedDistance = hypot(moveFromDownX.toDouble(), moveFromDownY.toDouble()).toFloat()
            if (movedDistance < scrollGracePx) {
                return emptyList()
            }
            if (!twoFingerTapMoved) {
                return emptyList()
            }
            twoFingerScrolling = true
        }

        return buildScrollActions(
            rawDx = rawDx,
            rawDy = rawDy,
            allowHorizontal = true,
            eventTimeMs = event.eventTime,
        )
    }

    private fun buildScrollActions(
        rawDx: Float,
        rawDy: Float,
        allowHorizontal: Boolean,
        eventTimeMs: Long,
    ): List<InputAction> {
        val horizontalDirection = if (settingsRepository.horizontalScrollInverted.value) -1f else 1f
        val verticalDirection = if (settingsRepository.verticalScrollInverted.value) 1f else -1f

        val (lockedDx, lockedDy) = if (allowHorizontal) {
            applyAxisLock(rawDx, rawDy)
        } else {
            0f to rawDy
        }

        val effectiveDx = if (allowHorizontal) lockedDx else 0f
        val effectiveDy = lockedDy

        val totalDx = if (allowHorizontal) {
            (effectiveDx * horizontalDirection * SCROLL_GAIN_FACTOR) + scrollResidualDx
        } else {
            scrollResidualDx
        }
        val totalDy = (effectiveDy * verticalDirection * SCROLL_GAIN_FACTOR) + scrollResidualDy
        val fixedDx = totalDx * SCROLL_OUTPUT_FIXED_POINT
        val fixedDy = totalDy * SCROLL_OUTPUT_FIXED_POINT
        val scaledDx = if (fixedDx >= 0f) floor(fixedDx).toInt() else ceil(fixedDx).toInt()
        val scaledDy = if (fixedDy >= 0f) floor(fixedDy).toInt() else ceil(fixedDy).toInt()
        scrollResidualDx = (fixedDx - scaledDx) / SCROLL_OUTPUT_FIXED_POINT
        scrollResidualDy = (fixedDy - scaledDy) / SCROLL_OUTPUT_FIXED_POINT

        return if (scaledDx == 0 && scaledDy == 0) {
            emptyList()
        } else {
            updateScrollVelocity(scaledDx, scaledDy, eventTimeMs)
            listOf(InputAction.ScrollBy(scaledDx, scaledDy))
        }
    }

    private fun updateScrollVelocity(dx: Int, dy: Int, eventTimeMs: Long) {
        val previousSampleAt = lastScrollSampleAtMs
        lastScrollSampleAtMs = eventTimeMs
        if (previousSampleAt <= 0L) {
            return
        }
        val dtMs = (eventTimeMs - previousSampleAt).coerceAtLeast(1L)
        val instantVx = dx.toFloat() / dtMs
        val instantVy = dy.toFloat() / dtMs
        scrollVelocityDxPerMs = (scrollVelocityDxPerMs * SCROLL_VELOCITY_PREVIOUS_WEIGHT) +
            (instantVx * (1f - SCROLL_VELOCITY_PREVIOUS_WEIGHT))
        scrollVelocityDyPerMs = (scrollVelocityDyPerMs * SCROLL_VELOCITY_PREVIOUS_WEIGHT) +
            (instantVy * (1f - SCROLL_VELOCITY_PREVIOUS_WEIGHT))
    }

    private fun updatePointerVelocity(dx: Int, dy: Int, eventTimeMs: Long) {
        val previousSampleAt = lastPointerSampleAtMs
        lastPointerSampleAtMs = eventTimeMs
        if (previousSampleAt <= 0L || eventTimeMs <= previousSampleAt) {
            return
        }
        val dtMs = (eventTimeMs - previousSampleAt).coerceAtLeast(1L)
        val instantVx = dx.toFloat() / dtMs
        val instantVy = dy.toFloat() / dtMs
        pointerVelocityDxPerMs = (pointerVelocityDxPerMs * POINTER_VELOCITY_PREVIOUS_WEIGHT) +
            (instantVx * (1f - POINTER_VELOCITY_PREVIOUS_WEIGHT))
        pointerVelocityDyPerMs = (pointerVelocityDyPerMs * POINTER_VELOCITY_PREVIOUS_WEIGHT) +
            (instantVy * (1f - POINTER_VELOCITY_PREVIOUS_WEIGHT))
    }

    private fun startDragReleaseInertia(startAtMs: Long): Boolean {
        val inertiaMs = settingsRepository.dragFlickKickMs.value.toFloat()
        if (!inertiaMs.isFinite() || inertiaMs <= 0f) {
            dragReleaseInertia = null
            return false
        }
        val speed = hypot(pointerVelocityDxPerMs.toDouble(), pointerVelocityDyPerMs.toDouble()).toFloat()
        if (speed < DRAG_RELEASE_INERTIA_MIN_SPEED_PER_MS) {
            dragReleaseInertia = null
            return false
        }
        dragReleaseInertia = DragReleaseInertia(
            velocityDxPerMs = pointerVelocityDxPerMs,
            velocityDyPerMs = pointerVelocityDyPerMs,
            startedAtMs = startAtMs,
            lastTickAtMs = startAtMs,
            durationMs = inertiaMs,
            residualDx = 0f,
            residualDy = 0f,
            movedDx = 0f,
            movedDy = 0f,
        )
        return true
    }

    private fun advanceDragReleaseInertia(nowMs: Long): List<InputAction> {
        val state = dragReleaseInertia ?: return emptyList()
        val dtMs = (nowMs - state.lastTickAtMs).coerceAtLeast(0L)
        if (dtMs <= 0L) {
            return emptyList()
        }

        val elapsedStartMs = (state.lastTickAtMs - state.startedAtMs).coerceAtLeast(0L).toFloat()
        val elapsedEndMs = (nowMs - state.startedAtMs).coerceAtLeast(0L).toFloat()
        val startProgress = (elapsedStartMs / state.durationMs).coerceIn(0f, 1f)
        val endProgress = (elapsedEndMs / state.durationMs).coerceIn(0f, 1f)
        val averageDecay = (1f - ((startProgress + endProgress) * 0.5f)).coerceIn(0f, 1f)

        val remainingDxBudget = (DRAG_RELEASE_INERTIA_MAX_TOTAL_PX - abs(state.movedDx)).coerceAtLeast(0f)
        val remainingDyBudget = (DRAG_RELEASE_INERTIA_MAX_TOTAL_PX - abs(state.movedDy)).coerceAtLeast(0f)

        val rawDx = ((state.velocityDxPerMs * dtMs.toFloat() * averageDecay) + state.residualDx)
            .coerceIn(-remainingDxBudget, remainingDxBudget)
        val rawDy = ((state.velocityDyPerMs * dtMs.toFloat() * averageDecay) + state.residualDy)
            .coerceIn(-remainingDyBudget, remainingDyBudget)

        val stepDx = if (rawDx >= 0f) floor(rawDx).toInt() else ceil(rawDx).toInt()
        val stepDy = if (rawDy >= 0f) floor(rawDy).toInt() else ceil(rawDy).toInt()

        val moveActions = mutableListOf<InputAction>()
        if (stepDx != 0 || stepDy != 0) {
            cursorStateStore.applyDelta(stepDx, stepDy)
            moveActions += InputAction.MoveBy(stepDx, stepDy)
        }

        val finished = endProgress >= 1f ||
            (remainingDxBudget <= 0f && remainingDyBudget <= 0f)
        if (finished) {
            dragReleaseInertia = null
            return buildList {
                addAll(moveActions)
                add(InputAction.ScrollBy(0, 0))
                add(InputAction.ButtonUp(MouseButton.LEFT))
            }
        }

        dragReleaseInertia = state.copy(
            lastTickAtMs = nowMs,
            residualDx = rawDx - stepDx,
            residualDy = rawDy - stepDy,
            movedDx = state.movedDx + stepDx,
            movedDy = state.movedDy + stepDy,
        )
        return moveActions
    }

    private fun cancelDragReleaseInertiaIfNeeded(): List<InputAction> {
        if (dragReleaseInertia == null) {
            return emptyList()
        }
        dragReleaseInertia = null
        return listOf(
            InputAction.ScrollBy(0, 0),
            InputAction.ButtonUp(MouseButton.LEFT),
        )
    }

    private fun buildScrollFlingAction(): InputAction.ScrollFling? {
        val inertiaMs = settingsRepository.flingDurationMs.value
        if (inertiaMs <= 0) {
            return null
        }
        val velocityMagnitudePerMs = hypot(scrollVelocityDxPerMs.toDouble(), scrollVelocityDyPerMs.toDouble()).toFloat()
        if (velocityMagnitudePerMs < FLING_MIN_SPEED_PER_MS) {
            return null
        }
        val velocityXPerSecond = (scrollVelocityDxPerMs * 1000f)
            .coerceIn(-FLING_MAX_SPEED_PER_SECOND, FLING_MAX_SPEED_PER_SECOND)
        val velocityYPerSecond = (scrollVelocityDyPerMs * 1000f)
            .coerceIn(-FLING_MAX_SPEED_PER_SECOND, FLING_MAX_SPEED_PER_SECOND)
        return InputAction.ScrollFling(
            velocityXPerSecond = velocityXPerSecond,
            velocityYPerSecond = velocityYPerSecond,
            inertiaMs = inertiaMs,
        )
    }

    private fun shouldArmSecondTapDrag(event: MotionEvent): Boolean {
        if (!settingsRepository.tapToDragEnabled.value) {
            return false
        }
        if (pendingLeftTapAtMs <= 0L) {
            return false
        }
        val elapsed = event.eventTime - pendingLeftTapAtMs
        if (elapsed > LEFT_DRAG_SECOND_TAP_TIMEOUT_MS) {
            clearPendingLeftTap()
            return false
        }
        val moveFromPendingX = abs(event.x - pendingLeftTapX)
        val moveFromPendingY = abs(event.y - pendingLeftTapY)
        val withinSlop = moveFromPendingX <= LEFT_DRAG_SECOND_TAP_SLOP_PX &&
            moveFromPendingY <= LEFT_DRAG_SECOND_TAP_SLOP_PX
        if (!withinSlop) {
            // New tap started elsewhere; don't keep stale tap-drag arming alive.
            clearPendingLeftTap()
            return false
        }
        return true
    }

    private fun expirePendingGestures(nowMs: Long) {
        if (pendingLeftTapAtMs > 0L && nowMs - pendingLeftTapAtMs > LEFT_DRAG_SECOND_TAP_TIMEOUT_MS) {
            clearPendingLeftTap()
        }
    }

    private fun clearPendingLeftTap() {
        pendingLeftTapAtMs = 0L
        pendingLeftTapX = 0f
        pendingLeftTapY = 0f
    }

    private fun resetScrollMotionState() {
        scrollResidualDx = 0f
        scrollResidualDy = 0f
        scrollVelocityDxPerMs = 0f
        scrollVelocityDyPerMs = 0f
        lastScrollSampleAtMs = 0L
        scrollAxisLock = ScrollAxisLock.NONE
        axisLockAccumDx = 0f
        axisLockAccumDy = 0f
    }

    private fun resetPointerMotionState() {
        pointerVelocityDxPerMs = 0f
        pointerVelocityDyPerMs = 0f
        lastPointerSampleAtMs = 0L
    }

    private fun applySoftDeadband(rawDelta: Float, noiseFloor: Float): Float {
        if (!noiseFloor.isFinite() || noiseFloor <= 0f) {
            return rawDelta
        }
        return when {
            rawDelta > noiseFloor -> rawDelta - noiseFloor
            rawDelta < -noiseFloor -> rawDelta + noiseFloor
            else -> 0f
        }
    }

    private fun accelerationGain(dx: Float, dy: Float): Float {
        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val normalized = (distance / ACCELERATION_DISTANCE_NORM_PX).coerceIn(0f, 1f)
        val curved = normalized * normalized
        return 1f + (curved * ACCELERATION_EXTRA_GAIN_MAX)
    }

    private fun beginTwoFingerGesture(event: MotionEvent) {
        primaryState = PrimaryPointerState.Idle
        resetPointerMotionState()
        twoFingerActive = true
        twoFingerScrolling = false
        twoFingerTapMoved = false
        val (centroidX, centroidY) = twoFingerCentroid(event)
        lastCentroidX = centroidX
        lastCentroidY = centroidY
        twoFingerDownCentroidX = centroidX
        twoFingerDownCentroidY = centroidY
        twoFingerDownAtMs = event.eventTime
        resetScrollMotionState()
    }

    private fun applyAxisLock(rawDx: Float, rawDy: Float): Pair<Float, Float> {
        if (!AXIS_LOCK_ENABLED) {
            return rawDx to rawDy
        }

        axisLockAccumDx += rawDx
        axisLockAccumDy += rawDy

        if (scrollAxisLock == ScrollAxisLock.NONE) {
            val absAccumX = abs(axisLockAccumDx)
            val absAccumY = abs(axisLockAccumDy)
            val armDistance = hypot(absAccumX.toDouble(), absAccumY.toDouble()).toFloat()
            if (armDistance >= AXIS_LOCK_ARM_PX) {
                when {
                    absAccumX >= (absAccumY * AXIS_LOCK_RATIO) -> scrollAxisLock = ScrollAxisLock.HORIZONTAL
                    absAccumY >= (absAccumX * AXIS_LOCK_RATIO) -> scrollAxisLock = ScrollAxisLock.VERTICAL
                }
            }
        } else {
            val absDx = abs(rawDx)
            val absDy = abs(rawDy)
            if (absDx > 0f && absDy > 0f) {
                val ratio = if (absDx > absDy) absDx / absDy else absDy / absDx
                if (ratio <= AXIS_UNLOCK_RATIO) {
                    scrollAxisLock = ScrollAxisLock.NONE
                }
            }
        }

        return when (scrollAxisLock) {
            ScrollAxisLock.HORIZONTAL -> rawDx to 0f
            ScrollAxisLock.VERTICAL -> 0f to rawDy
            ScrollAxisLock.NONE -> rawDx to rawDy
        }
    }

    private fun twoFingerCentroid(event: MotionEvent): Pair<Float, Float> {
        val centroidX = (event.getX(0) + event.getX(1)) / 2f
        val centroidY = (event.getY(0) + event.getY(1)) / 2f
        return centroidX to centroidY
    }

    private sealed interface PrimaryPointerState {
        data object Idle : PrimaryPointerState

        data class Tracking(
            val downX: Float,
            val downY: Float,
            val downAtMs: Long,
            val holdArmAtMs: Long,
            val holdEligible: Boolean,
            val secondTapDragArmed: Boolean,
            val tapEligible: Boolean,
            val filteredX: Float,
            val filteredY: Float,
        ) : PrimaryPointerState

        data class HoldArmed(
            val downX: Float,
            val downY: Float,
            val downAtMs: Long,
            val secondTapDragArmed: Boolean,
            val anchorX: Float,
            val anchorY: Float,
        ) : PrimaryPointerState

        data class Dragging(
            val downX: Float,
            val downY: Float,
            val downAtMs: Long,
        ) : PrimaryPointerState

    }

    private data class DragReleaseInertia(
        val velocityDxPerMs: Float,
        val velocityDyPerMs: Float,
        val startedAtMs: Long,
        val lastTickAtMs: Long,
        val durationMs: Float,
        val residualDx: Float,
        val residualDy: Float,
        val movedDx: Float,
        val movedDy: Float,
    )

    companion object {
        private const val TAP_SLOP_PX = 60f
        private const val TAP_TIMEOUT_MS = 560L

        // Tap-drag: first tap click, second tap+hold starts drag (libinput/synaptics-style).
        private const val LEFT_DRAG_SECOND_TAP_TIMEOUT_MS = 320L
        private const val LEFT_DRAG_SECOND_TAP_SLOP_PX = 72f

        // Two-finger right click: one two-finger tap (staggered finger-down is allowed).
        private const val TWO_FINGER_TAP_SLOP_PX = 36f
        private const val TWO_FINGER_TAP_TIMEOUT_MS = 260L

        // Scroll command output is intentionally damped relative to pointer movement.
        private const val SCROLL_GAIN_FACTOR = 0.10f
        private const val SCROLL_OUTPUT_FIXED_POINT = 100f
        private const val AXIS_LOCK_ENABLED = true
        private const val AXIS_LOCK_ARM_PX = 8f
        private const val AXIS_LOCK_RATIO = 2.0f
        private const val AXIS_UNLOCK_RATIO = 1.2f
        private const val SCROLL_VELOCITY_PREVIOUS_WEIGHT = 0.60f
        private const val FLING_MIN_SPEED_PER_MS = 0.12f
        private const val FLING_MAX_SPEED_PER_SECOND = 28000f
        private const val POINTER_VELOCITY_PREVIOUS_WEIGHT = 0.80f
        private const val DRAG_RELEASE_INERTIA_MIN_SPEED_PER_MS = 0.10f
        private const val DRAG_RELEASE_INERTIA_MAX_TOTAL_PX = 900f

        private const val ACCELERATION_DISTANCE_NORM_PX = 26f
        private const val ACCELERATION_EXTRA_GAIN_MAX = 0.75f
        private const val HOLD_ARM_FILTER_ALPHA = 0.35f
        private const val MIN_HOLD_SLOP_PX = 0f
        private const val MIN_DRAG_START_SLOP_PX = 1f
    }

    private enum class ScrollAxisLock {
        NONE,
        HORIZONTAL,
        VERTICAL,
    }
}
