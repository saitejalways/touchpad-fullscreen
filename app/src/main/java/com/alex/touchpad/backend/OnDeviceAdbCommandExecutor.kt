package com.alex.touchpad.backend

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.alex.touchpad.core.AppLog as Log
import com.alex.touchpad.core.DebugFlags
import com.alex.touchpad.input.MouseButton
import com.alex.touchpad.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

data class ExecutorStatus(
    val adbBinaryReady: Boolean,
    val adbBinaryPath: String?,
    val lastError: String?,
)

data class HidScrollDebugEvent(
    val elapsedRealtimeMs: Long,
    val vWheel: Int,
    val hWheel: Int,
)

data class EdgeSwipeDebugEvent(
    val elapsedRealtimeMs: Long,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
)

data class CursorCenterCalibrationResult(
    val isLandscape: Boolean,
    val unitsPerPxX: Float,
    val unitsPerPxY: Float,
    val centerRelativeDx: Int,
    val centerRelativeDy: Int,
    val maxErrorPx: Float,
)

data class CursorCalibrationProgress(
    val message: String,
    val progressFraction: Float,
)

interface ExecutorHapticEvents {
    fun onScrollStep()
    fun onEdgeHit()
    fun onEdgeScrollStart()
}

interface WireCommandExecutor {
    val status: StateFlow<ExecutorStatus>
    suspend fun execute(command: WireCommand): Boolean
}

class OnDeviceAdbCommandExecutor(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val hapticEvents: ExecutorHapticEvents? = null,
    private val daemonRuntimeBridge: DaemonRuntimeBridge? = null,
) : WireCommandExecutor {
    private enum class EdgeSide {
        LEFT,
        RIGHT,
        TOP,
        BOTTOM,
    }

    private data class EdgeAtState(
        val side: EdgeSide,
    )

    private data class EdgePushState(
        val side: EdgeSide,
        val scrollDx: Int,
        val scrollDy: Int,
    )

    private data class CenteredEdgeScrollSession(
        val side: EdgeSide,
        val restoreX: Float,
        val restoreY: Float,
    )

    private data class CursorCalibration(
        val unitsPerPxX: Float,
        val unitsPerPxY: Float,
    )

    private data class InputViewport(
        val orientation: Int,
        val logicalWidthPx: Int,
        val logicalHeightPx: Int,
    )

    private val binaryMutex = Mutex()
    private val hidMutex = Mutex()
    private val executorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(
        ExecutorStatus(
            adbBinaryReady = false,
            adbBinaryPath = null,
            lastError = null,
        )
    )
    override val status: StateFlow<ExecutorStatus> = _status.asStateFlow()
    private val _edgeHighlightState = MutableStateFlow(EdgeHighlightState.NONE)
    val edgeHighlightState: StateFlow<EdgeHighlightState> = _edgeHighlightState.asStateFlow()
    private val _cursorGroundTruth = MutableStateFlow<CursorGroundTruth?>(null)
    val cursorGroundTruth: StateFlow<CursorGroundTruth?> = _cursorGroundTruth.asStateFlow()
    private val _hidScrollDebugHistory = MutableStateFlow<List<HidScrollDebugEvent>>(emptyList())
    val hidScrollDebugHistory: StateFlow<List<HidScrollDebugEvent>> = _hidScrollDebugHistory.asStateFlow()
    private val _edgeSwipeDebugHistory = MutableStateFlow<List<EdgeSwipeDebugEvent>>(emptyList())
    val edgeSwipeDebugHistory: StateFlow<List<EdgeSwipeDebugEvent>> = _edgeSwipeDebugHistory.asStateFlow()

    private var hidSession: HidSession? = null
    private var syncWorkerJob: Job? = null
    private var hidButtonMask = 0
    private val edgeScrollRouter = EdgeScrollRouter(scrollUnitsPerPixel = SCROLL_COMMAND_UNITS_PER_PIXEL)
    private val scrollAccumulator = ScrollAccumulator(
        minAxisThreshold = MIN_AXIS_THRESHOLD,
        maxFollowupFactor = MAX_SCROLL_FOLLOWUP_FACTOR,
        clampScrollStepsPerDispatch = CLAMP_SCROLL_STEPS_PER_DISPATCH,
        horizontalHidSign = HORIZONTAL_HID_SIGN,
    )
    @Volatile
    private var lastGroundTruthCursor: CursorGroundTruth? = null
    @Volatile
    private var externalGroundTruthOverride: CursorGroundTruth? = null
    @Volatile
    private var targetDisplayWidthPx: Int = 0
    @Volatile
    private var targetDisplayHeightPx: Int = 0
    @Volatile
    private var currentInputViewportOrientation: Int = 0
    private var lastDisplaySizeRefreshAtMs: Long = 0L
    private var lastNonZeroScrollInputAtMs = 0L
    private val lastTouchActivityAtMs = AtomicLong(0L)
    private val lastSyncFailureLogAtMs = AtomicLong(0L)
    private val moveRequestedCount = AtomicLong(0L)
    private val moveRequestedDxSum = AtomicLong(0L)
    private val moveRequestedDySum = AtomicLong(0L)
    private val moveSuccessCount = AtomicLong(0L)
    private val moveSuccessDxSum = AtomicLong(0L)
    private val moveSuccessDySum = AtomicLong(0L)
    private val moveFailureCount = AtomicLong(0L)
    private val lastMoveStatsLogAtMs = AtomicLong(0L)
    private val lastEdgeRouteLogAtMs = AtomicLong(0L)
    private val lastScrollNoStepLogAtMs = AtomicLong(0L)
    private var lastEdgeAtSide: EdgeSide? = null
    private var lastEdgePushSide: EdgeSide? = null
    private var edgeActivationAccumulatedPx = 0f
    private var centeredEdgeScrollSession: CenteredEdgeScrollSession? = null
    private var edgePushDebugDx = 0f
    private var edgePushDebugDy = 0f

    init {
        val nativeBinary = findBundledAdbBinary()
        if (nativeBinary?.exists() == true) {
            _status.value = _status.value.copy(
                adbBinaryReady = true,
                adbBinaryPath = nativeBinary.absolutePath,
                lastError = null,
            )
        }
        val metrics = context.resources.displayMetrics
        targetDisplayWidthPx = metrics.widthPixels.coerceAtLeast(1)
        targetDisplayHeightPx = metrics.heightPixels.coerceAtLeast(1)
        ensureSyncWorker()
    }

    override suspend fun execute(command: WireCommand): Boolean {
        return when (command) {
            is WireCommand.Ping -> true
            is WireCommand.MoveRel -> {
                markTouchActivity()
                executeMove(command)
            }
            is WireCommand.Click -> {
                markTouchActivity()
                executeClick(command.button)
            }
            is WireCommand.ButtonDown -> {
                markTouchActivity()
                executeButton(command.button, isDown = true)
            }
            is WireCommand.ButtonUp -> {
                markTouchActivity()
                executeButton(command.button, isDown = false)
            }
            is WireCommand.ScrollWheel -> {
                markTouchActivity()
                executeScroll(dx = command.hWheel, dy = command.vWheel)
            }
            is WireCommand.Scroll -> {
                markTouchActivity()
                executeScroll(dx = command.dx, dy = command.dy)
            }
            is WireCommand.TouchContact -> executeTouchContact(command.active)
            is WireCommand.TouchDelta -> executeTouchDelta(command.dx, command.dy)
            is WireCommand.Pair -> executePair(host = command.host, port = command.port, code = command.code)
        }
    }

    suspend fun isWirelessDebuggingPaired(): Boolean {
        val adbBinary = ensureAdbBinary() ?: return false
        return ensureTargetConnected(
            adbBinary = adbBinary,
            requestedSerial = FIXED_TARGET_SERIAL,
        ) != null
    }

    suspend fun calibrateCursorCenterMapping(
        onProgress: ((CursorCalibrationProgress) -> Unit)? = null,
    ): CursorCenterCalibrationResult? {
        val refinementPasses = 3
        val refinementGain = 0.7f
        val refinementStopTolerancePx = 6f

        fun reportProgress(message: String, progressFraction: Float) {
            onProgress?.invoke(
                CursorCalibrationProgress(
                    message = message,
                    progressFraction = progressFraction.coerceIn(0f, 1f),
                )
            )
        }

        reportProgress("Reading display state", 0.05f)
        val display = queryCursorGroundTruth(forceDisplayRefresh = true)
        val displayWidth = display?.widthPx ?: targetDisplayWidthPx.takeIf { it > 0 } ?: return null
        val displayHeight = display?.heightPx ?: targetDisplayHeightPx.takeIf { it > 0 } ?: return null
        val targetCenterX = displayWidth / 2f
        val targetCenterY = displayHeight / 2f

        reportProgress("Moving cursor to top-left", 0.15f)
        moveCursorToTopLeftForCalibration()
        val corner = waitForCursorGroundTruth(timeoutMs = CALIBRATION_WAIT_TIMEOUT_MS) {
            it.x <= CALIBRATION_CORNER_TOLERANCE_PX && it.y <= CALIBRATION_CORNER_TOLERANCE_PX
        } ?: return null
        if (corner.x > CALIBRATION_CORNER_TOLERANCE_PX || corner.y > CALIBRATION_CORNER_TOLERANCE_PX) {
            return null
        }

        reportProgress("Calibrating horizontal center", 0.35f)
        var calibratedDx = calibrateAxisToTarget(horizontal = true, targetPx = targetCenterX) ?: return null
        reportProgress("Calibrating vertical center", 0.55f)
        var calibratedDy = calibrateAxisToTarget(horizontal = false, targetPx = targetCenterY) ?: return null
        var unitsPerPxX = calibratedDx.toFloat() / targetCenterX.coerceAtLeast(1f)
        var unitsPerPxY = calibratedDy.toFloat() / targetCenterY.coerceAtLeast(1f)

        repeat(refinementPasses) { pass ->
            reportProgress(
                message = "Refining center alignment (${pass + 1}/$refinementPasses)",
                progressFraction = 0.68f + (0.12f * ((pass + 1).toFloat() / refinementPasses.toFloat())),
            )
            moveCursorToTopLeftForCalibration()
            if (!sendLogicalHidMove(calibratedDx, calibratedDy)) {
                return null
            }
            val sample = waitForCursorGroundTruth(timeoutMs = CALIBRATION_WAIT_TIMEOUT_MS) { true } ?: return null
            val errorX = targetCenterX - sample.x
            val errorY = targetCenterY - sample.y
            if (abs(errorX) <= refinementStopTolerancePx && abs(errorY) <= refinementStopTolerancePx) {
                return@repeat
            }
            val adjustmentDx = if (unitsPerPxX > 0f) {
                (errorX * unitsPerPxX * refinementGain).roundToInt()
            } else {
                0
            }
            val adjustmentDy = if (unitsPerPxY > 0f) {
                (errorY * unitsPerPxY * refinementGain).roundToInt()
            } else {
                0
            }
            if (adjustmentDx == 0 && adjustmentDy == 0) {
                return@repeat
            }
            calibratedDx += adjustmentDx
            calibratedDy += adjustmentDy
            unitsPerPxX = calibratedDx.toFloat() / targetCenterX.coerceAtLeast(1f)
            unitsPerPxY = calibratedDy.toFloat() / targetCenterY.coerceAtLeast(1f)
        }

        reportProgress("Validating calibration", 0.86f)
        val errors = mutableListOf<Float>()
        repeat(CALIBRATION_VALIDATION_TRIALS) {
            moveCursorToTopLeftForCalibration()
            if (!sendLogicalHidMove(calibratedDx, calibratedDy)) {
                return null
            }
            val sample = waitForCursorGroundTruth(timeoutMs = CALIBRATION_WAIT_TIMEOUT_MS) { true } ?: return null
            errors += maxOf(abs(sample.x - targetCenterX), abs(sample.y - targetCenterY))
        }
        val isLandscape = targetDisplayWidthPx >= targetDisplayHeightPx
        val maxError = errors.maxOrNull() ?: Float.MAX_VALUE
        reportProgress("Calibration complete", 1.0f)
        return CursorCenterCalibrationResult(
            isLandscape = isLandscape,
            unitsPerPxX = unitsPerPxX,
            unitsPerPxY = unitsPerPxY,
            centerRelativeDx = calibratedDx,
            centerRelativeDy = calibratedDy,
            maxErrorPx = maxError,
        )
    }

    suspend fun debugMoveCursorToTopLeftForCalibration(): Boolean {
        if (!DebugFlags.ENABLED) {
            return false
        }
        queryCursorGroundTruth(forceDisplayRefresh = true)
        return moveCursorToTopLeftForCalibration()
    }

    suspend fun debugQueryCursorGroundTruth(): CursorGroundTruth? {
        if (!DebugFlags.ENABLED) {
            return null
        }
        return queryCursorGroundTruth(forceDisplayRefresh = true)
    }

    suspend fun debugSendLogicalMove(dx: Int, dy: Int): Boolean {
        if (!DebugFlags.ENABLED) {
            return false
        }
        queryCursorGroundTruth(forceDisplayRefresh = true)
        return sendLogicalHidMove(dx, dy)
    }

    suspend fun debugCurrentInputViewportOrientation(): Int {
        if (!DebugFlags.ENABLED) {
            return 0
        }
        queryCursorGroundTruth(forceDisplayRefresh = true)
        return currentInputViewportOrientation
    }

    suspend fun debugProbeCalibration(): String {
        if (!DebugFlags.ENABLED) {
            return "debug_disabled"
        }
        val display = queryCursorGroundTruth(forceDisplayRefresh = true)
            ?: return "fail:display-null orientation=$currentInputViewportOrientation size=${targetDisplayWidthPx}x${targetDisplayHeightPx}"
        val targetCenterX = display.widthPx / 2f
        val targetCenterY = display.heightPx / 2f

        val movedToCorner = moveCursorToTopLeftForCalibration()
        if (!movedToCorner) {
            return "fail:corner-sweep-command orientation=$currentInputViewportOrientation size=${targetDisplayWidthPx}x${targetDisplayHeightPx} display=$display"
        }
        val corner = waitForCursorGroundTruth(timeoutMs = CALIBRATION_WAIT_TIMEOUT_MS) {
            it.x <= CALIBRATION_CORNER_TOLERANCE_PX && it.y <= CALIBRATION_CORNER_TOLERANCE_PX
        } ?: return "fail:corner-wait orientation=$currentInputViewportOrientation size=${targetDisplayWidthPx}x${targetDisplayHeightPx} display=$display"
        if (corner.x > CALIBRATION_CORNER_TOLERANCE_PX || corner.y > CALIBRATION_CORNER_TOLERANCE_PX) {
            return "fail:corner-miss corner=$corner tolerance=$CALIBRATION_CORNER_TOLERANCE_PX"
        }

        val calibratedDx = calibrateAxisToTarget(horizontal = true, targetPx = targetCenterX)
            ?: return "fail:calibrate-x target=$targetCenterX corner=$corner"
        val calibratedDy = calibrateAxisToTarget(horizontal = false, targetPx = targetCenterY)
            ?: return "fail:calibrate-y target=$targetCenterY corner=$corner calibratedDx=$calibratedDx"

        if (!moveCursorToTopLeftForCalibration()) {
            return "fail:post-corner-sweep calibrated=($calibratedDx,$calibratedDy)"
        }
        if (!sendLogicalHidMove(calibratedDx, calibratedDy)) {
            return "fail:center-move calibrated=($calibratedDx,$calibratedDy) orientation=$currentInputViewportOrientation"
        }
        val sample = waitForCursorGroundTruth(timeoutMs = CALIBRATION_WAIT_TIMEOUT_MS) { true }
            ?: return "fail:center-sample calibrated=($calibratedDx,$calibratedDy)"
        return "ok display=$display corner=$corner calibrated=($calibratedDx,$calibratedDy) sample=$sample target=($targetCenterX,$targetCenterY)"
    }

    fun setExternalGroundTruthOverride(groundTruth: CursorGroundTruth?) {
        externalGroundTruthOverride = groundTruth
        if (groundTruth != null) {
            lastGroundTruthCursor = groundTruth
            _cursorGroundTruth.value = groundTruth
            _edgeHighlightState.value = edgeScrollRouter.highlightState(
                groundTruth = groundTruth,
                edgeThicknessPx = 28f,
                cornerDeadzonePx = 0f,
            )
        } else if (!shouldRunSyncPoller()) {
            lastGroundTruthCursor = null
            _cursorGroundTruth.value = null
            _edgeHighlightState.value = EdgeHighlightState.NONE
        }
    }

    private suspend fun executePair(host: String, port: Int, code: String): Boolean {
        val normalizedHost = host.trim().ifEmpty { "127.0.0.1" }
        val normalizedPort = port.coerceAtLeast(1)
        val normalizedCode = code.trim()
        if (normalizedCode.isEmpty()) {
            _status.value = _status.value.copy(lastError = "Pairing code is empty")
            return false
        }

        val adbBinary = ensureAdbBinary() ?: return false
        val result = runProcess(
            listOf(
                adbBinary.absolutePath,
                "pair",
                "$normalizedHost:$normalizedPort",
                normalizedCode,
            ),
            timeoutMs = PROCESS_TIMEOUT_PAIR_MS,
        )
        if (result.exitCode == 0) {
            _status.value = _status.value.copy(lastError = null)
            Log.i(TAG, "adb pair succeeded target=$normalizedHost:$normalizedPort")
            return true
        }

        _status.value = _status.value.copy(
            lastError = "adb pair failed code=${result.exitCode} output=${result.output.take(180)}",
        )
        Log.w(
            TAG,
            "adb pair failed target=$normalizedHost:$normalizedPort code=${result.exitCode} output=${result.output}",
        )
        return false
    }

    private suspend fun executeMove(command: WireCommand.MoveRel): Boolean {
        val now = SystemClock.elapsedRealtime()
        moveRequestedCount.incrementAndGet()
        moveRequestedDxSum.addAndGet(command.dx.toLong())
        moveRequestedDySum.addAndGet(command.dy.toLong())

        val activeCenteredSession = centeredEdgeScrollSession
        if (activeCenteredSession != null) {
            updateCenteredEdgeDebug(
                side = activeCenteredSession.side,
                moveDx = 0,
                moveDy = 0,
            )
            val success = true
            if (success) {
                moveSuccessCount.incrementAndGet()
                moveSuccessDxSum.addAndGet(command.dx.toLong())
                moveSuccessDySum.addAndGet(command.dy.toLong())
            } else {
                moveFailureCount.incrementAndGet()
            }
            maybeLogMoveStats()
            return success
        }

        val routingGroundTruth = latestGroundTruthForRouting(nowMs = now)

        val hasRoutingGroundTruth = routingGroundTruth != null
        val edgeAtState = resolveEdgeAtState(
            groundTruth = routingGroundTruth,
            cornerDeadzonePx = 0f,
            edgeScrollEnabled = settingsRepository.oneFingerEdgeScrollEnabled.value,
        )
        val edgePushState = resolveEdgePushState(
            moveDx = command.dx,
            moveDy = command.dy,
            groundTruth = routingGroundTruth,
            cornerDeadzonePx = 0f,
            edgeScrollEnabled = settingsRepository.oneFingerEdgeScrollEnabled.value,
            horizontalInverted = settingsRepository.edgeHorizontalScrollInverted.value,
            verticalInverted = settingsRepository.edgeVerticalScrollInverted.value,
        )

        val previousEdgeAtSide = lastEdgeAtSide
        val currentEdgeAtSide = edgeAtState?.side
        val currentEdgePushSide = edgePushState?.side
        val switchedEdgeDirection =
            currentEdgeAtSide != null &&
                previousEdgeAtSide != null &&
                hasRoutingGroundTruth &&
                currentEdgeAtSide != previousEdgeAtSide
        val enteredEdge =
            currentEdgeAtSide != null &&
                previousEdgeAtSide == null &&
                hasRoutingGroundTruth
        val leftEdge =
            currentEdgeAtSide == null &&
                previousEdgeAtSide != null &&
                hasRoutingGroundTruth

        if (enteredEdge) {
            hapticEvents?.onEdgeHit()
            edgeActivationAccumulatedPx = 0f
            clearEdgeSwipeDebugHistory()
        } else if (leftEdge) {
            edgeActivationAccumulatedPx = 0f
            clearEdgeSwipeDebugHistory()
        } else if (switchedEdgeDirection) {
            edgeActivationAccumulatedPx = 0f
            clearEdgeSwipeDebugHistory()
        }

        if (hasRoutingGroundTruth) {
            lastEdgeAtSide = currentEdgeAtSide
            lastEdgePushSide = currentEdgePushSide
        }

        var moveSuccess = true
        var scrollSuccess = true
        val shouldActivateCenteredEdgeScroll =
            edgePushState != null && edgeActivationAccumulatedPx >= edgeActivationDistancePx()

        if (shouldActivateCenteredEdgeScroll) {
            val activationEdgePushState = edgePushState ?: return false
            moveSuccess = activateCenteredEdgeScroll(
                side = activationEdgePushState.side,
                groundTruth = routingGroundTruth,
            )
            if (moveSuccess) {
                updateCenteredEdgeDebug(
                    side = activationEdgePushState.side,
                    moveDx = command.dx,
                    moveDy = command.dy,
                )
                scrollSuccess = executeScroll(
                    dx = activationEdgePushState.scrollDx,
                    dy = activationEdgePushState.scrollDy,
                    edgeSide = activationEdgePushState.side,
                    forceImmediateEdgeStep = true,
                )
            } else {
                moveSuccess = sendHidMove(command.dx, command.dy)
            }
        } else {
            moveSuccess = sendHidMove(command.dx, command.dy)
        }

        val success = moveSuccess && scrollSuccess
        if (success) {
            moveSuccessCount.incrementAndGet()
            moveSuccessDxSum.addAndGet(command.dx.toLong())
            moveSuccessDySum.addAndGet(command.dy.toLong())
        } else {
            moveFailureCount.incrementAndGet()
        }
        maybeLogMoveStats()
        return success
    }

    private suspend fun executeClick(button: MouseButton): Boolean {
        return sendHidClick(button)
    }

    private suspend fun executeButton(button: MouseButton, isDown: Boolean): Boolean {
        return sendHidButton(button, isDown)
    }

    private suspend fun executeTouchContact(active: Boolean): Boolean {
        if (active) {
            markTouchActivity()
            return true
        }
        edgeActivationAccumulatedPx = 0f
        clearEdgeSwipeDebugHistory()
        lastEdgePushSide = null
        return if (centeredEdgeScrollSession != null) {
            deactivateCenteredEdgeScrollAndRestore()
        } else {
            lastEdgeAtSide = null
            true
        }
    }

    private suspend fun executeTouchDelta(dx: Int, dy: Int): Boolean {
        markTouchActivity()
        val activeCenteredSession = centeredEdgeScrollSession
        if (activeCenteredSession != null) {
            val centeredPushState = resolveCenteredEdgePushState(
                moveDx = dx,
                moveDy = dy,
                side = activeCenteredSession.side,
                horizontalInverted = settingsRepository.edgeHorizontalScrollInverted.value,
                verticalInverted = settingsRepository.edgeVerticalScrollInverted.value,
            )
            updateCenteredEdgeDebug(
                side = activeCenteredSession.side,
                moveDx = dx,
                moveDy = dy,
            )
            return if (centeredPushState != null) {
                executeScroll(dx = centeredPushState.scrollDx, dy = centeredPushState.scrollDy)
            } else {
                true
            }
        }
        val side = lastEdgeAtSide ?: return true
        val pushPx = when (side) {
            EdgeSide.LEFT -> (-dx).coerceAtLeast(0)
            EdgeSide.RIGHT -> dx.coerceAtLeast(0)
            EdgeSide.TOP -> (-dy).coerceAtLeast(0)
            EdgeSide.BOTTOM -> dy.coerceAtLeast(0)
        }
        if (pushPx > 0) {
            val previousAccumulated = edgeActivationAccumulatedPx
            edgeActivationAccumulatedPx += pushPx.toFloat()
            val threshold = edgeActivationDistancePx()
            if (previousAccumulated < threshold && edgeActivationAccumulatedPx >= threshold) {
                val groundTruth = latestGroundTruthForRouting() ?: lastGroundTruthCursor
                if (
                    groundTruth != null &&
                    resolveEdgeAtState(
                        groundTruth = groundTruth,
                        cornerDeadzonePx = 0f,
                        edgeScrollEnabled = settingsRepository.oneFingerEdgeScrollEnabled.value,
                    )?.side == side
                ) {
                    val activated = activateCenteredEdgeScroll(side = side, groundTruth = groundTruth)
                    if (activated) {
                        val centeredPushState = resolveCenteredEdgePushState(
                            moveDx = dx,
                            moveDy = dy,
                            side = side,
                            horizontalInverted = settingsRepository.edgeHorizontalScrollInverted.value,
                            verticalInverted = settingsRepository.edgeVerticalScrollInverted.value,
                        )
                        if (centeredPushState != null) {
                            executeScroll(
                                dx = centeredPushState.scrollDx,
                                dy = centeredPushState.scrollDy,
                                edgeSide = side,
                                forceImmediateEdgeStep = true,
                            )
                        }
                    }
                }
            }
        }
        return true
    }

    private suspend fun executeScroll(
        dx: Int,
        dy: Int,
        edgeSide: EdgeSide? = null,
        forceImmediateEdgeStep: Boolean = false,
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (dx == 0 && dy == 0) {
            resetScrollAccumulators()
            lastNonZeroScrollInputAtMs = 0L
            return true
        }

        if (lastNonZeroScrollInputAtMs > 0L && (now - lastNonZeroScrollInputAtMs) > SCROLL_IDLE_RESET_MS) {
            resetScrollAccumulators()
        }
        lastNonZeroScrollInputAtMs = now
        return dispatchScroll(
            dx = dx,
            dy = dy,
            edgeSide = edgeSide,
            forceImmediateEdgeStep = forceImmediateEdgeStep,
        )
    }

    private suspend fun dispatchScroll(
        dx: Int,
        dy: Int,
        edgeSide: EdgeSide? = null,
        forceImmediateEdgeStep: Boolean = false,
    ): Boolean {
        val (rawVWheel, rawHWheel) = scrollAccumulator.consumeScrollSteps(
            dx = dx,
            dy = dy,
            verticalUnitsPerStep = verticalUnitsPerStep(),
            horizontalUnitsPerStep = horizontalUnitsPerStep(),
            verticalFollowupMultiplier = settingsRepository.verticalFollowupStepFactor.value,
            horizontalFollowupMultiplier = settingsRepository.horizontalFollowupStepFactor.value,
        )
        val (clampedVWheel, clampedHWheel) = clampEdgeWheelDirection(rawVWheel, rawHWheel, edgeSide)
        val (vWheel, hWheel) = if (
            forceImmediateEdgeStep &&
            edgeSide != null &&
            clampedVWheel == 0 &&
            clampedHWheel == 0
        ) {
            forcedEdgeWheelStep(
                edgeSide = edgeSide,
                horizontalInverted = settingsRepository.edgeHorizontalScrollInverted.value,
                verticalInverted = settingsRepository.edgeVerticalScrollInverted.value,
            )
        } else {
            clampedVWheel to clampedHWheel
        }

        if (vWheel == 0 && hWheel == 0) {
            maybeLogNoStep(dx.toFloat(), dy.toFloat())
            return true
        }

        Log.i(TAG, "scroll step emit vWheel=$vWheel hWheel=$hWheel rawDx=$dx rawDy=$dy")
        val sent = sendHidScroll(vWheel = vWheel, hWheel = hWheel)
        if (sent) {
            hapticEvents?.onScrollStep()
        }
        return sent
    }

    private fun resolveEdgeAtState(
        groundTruth: CursorGroundTruth?,
        cornerDeadzonePx: Float,
        edgeScrollEnabled: Boolean,
    ): EdgeAtState? {
        if (!edgeScrollEnabled || groundTruth == null) {
            return null
        }
        return resolvePriorityEdgeSide(
            groundTruth = groundTruth,
            cornerDeadzonePx = cornerDeadzonePx,
        )?.let { EdgeAtState(it) }
    }

    private fun resolveEdgePushState(
        moveDx: Int,
        moveDy: Int,
        groundTruth: CursorGroundTruth?,
        cornerDeadzonePx: Float,
        edgeScrollEnabled: Boolean,
        horizontalInverted: Boolean,
        verticalInverted: Boolean,
    ): EdgePushState? {
        if (!edgeScrollEnabled || groundTruth == null) {
            return null
        }
        if (moveDx == 0 && moveDy == 0) {
            return null
        }
        val side = resolvePriorityEdgeSide(
            groundTruth = groundTruth,
            cornerDeadzonePx = cornerDeadzonePx,
        ) ?: return null
        val horizontalDirection = if (horizontalInverted) -1f else 1f
        val verticalDirection = if (verticalInverted) -1f else 1f
        return when (side) {
            EdgeSide.LEFT -> {
                if (moveDx >= 0) {
                    return null
                }
                val scrollDx = (moveDx.toFloat() * SCROLL_COMMAND_UNITS_PER_PIXEL * horizontalDirection).toInt()
                EdgePushState(side = side, scrollDx = scrollDx, scrollDy = 0)
            }
            EdgeSide.RIGHT -> {
                if (moveDx <= 0) {
                    return null
                }
                val scrollDx = (moveDx.toFloat() * SCROLL_COMMAND_UNITS_PER_PIXEL * horizontalDirection).toInt()
                EdgePushState(side = side, scrollDx = scrollDx, scrollDy = 0)
            }
            EdgeSide.TOP -> {
                if (moveDy >= 0) {
                    return null
                }
                val scrollDy = ((-moveDy.toFloat()) * SCROLL_COMMAND_UNITS_PER_PIXEL * verticalDirection).toInt()
                EdgePushState(side = side, scrollDx = 0, scrollDy = scrollDy)
            }
            EdgeSide.BOTTOM -> {
                if (moveDy <= 0) {
                    return null
                }
                val scrollDy = ((-moveDy.toFloat()) * SCROLL_COMMAND_UNITS_PER_PIXEL * verticalDirection).toInt()
                EdgePushState(side = side, scrollDx = 0, scrollDy = scrollDy)
            }
        }
    }

    private fun resolveCenteredEdgePushState(
        moveDx: Int,
        moveDy: Int,
        side: EdgeSide,
        horizontalInverted: Boolean,
        verticalInverted: Boolean,
    ): EdgePushState? {
        if (moveDx == 0 && moveDy == 0) {
            return null
        }
        val horizontalDirection = if (horizontalInverted) -1f else 1f
        val verticalDirection = if (verticalInverted) -1f else 1f
        return EdgePushState(
            side = side,
            scrollDx = (moveDx.toFloat() * SCROLL_COMMAND_UNITS_PER_PIXEL * horizontalDirection).toInt(),
            scrollDy = ((-moveDy.toFloat()) * SCROLL_COMMAND_UNITS_PER_PIXEL * verticalDirection).toInt(),
        )
    }

    private fun resolvePriorityEdgeSide(
        groundTruth: CursorGroundTruth,
        cornerDeadzonePx: Float,
    ): EdgeSide? {
        val widthPx = groundTruth.widthPx.coerceAtLeast(1).toFloat()
        val heightPx = groundTruth.heightPx.coerceAtLeast(1).toFloat()
        val cornerPriorityPx = 3f
        val cornerDeadzone = cornerDeadzonePx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val hasCornerDeadzone = cornerDeadzone > 0f

        val inTopCornerPrioritySquare =
            groundTruth.y <= cornerPriorityPx &&
                (groundTruth.x <= cornerPriorityPx || groundTruth.x >= (widthPx - cornerPriorityPx))
        if (inTopCornerPrioritySquare) {
            return EdgeSide.TOP
        }

        val inBottomCornerPrioritySquare =
            groundTruth.y >= (heightPx - cornerPriorityPx) &&
                (groundTruth.x <= cornerPriorityPx || groundTruth.x >= (widthPx - cornerPriorityPx))
        if (inBottomCornerPrioritySquare) {
            return EdgeSide.BOTTOM
        }

        val atLeft = groundTruth.x <= STRICT_EDGE_TRIGGER_PX
        val atRight = groundTruth.x >= (widthPx - STRICT_EDGE_TRIGGER_PX)
        val atTop = groundTruth.y <= STRICT_EDGE_TRIGGER_PX
        val atBottom = groundTruth.y >= (heightPx - STRICT_EDGE_TRIGGER_PX)
        val inVerticalCornerDeadzone =
            hasCornerDeadzone &&
                (groundTruth.y <= cornerDeadzone || groundTruth.y >= (heightPx - cornerDeadzone))
        val inHorizontalCornerDeadzone =
            hasCornerDeadzone &&
                (groundTruth.x <= cornerDeadzone || groundTruth.x >= (widthPx - cornerDeadzone))

        if (atTop && !inHorizontalCornerDeadzone) {
            return EdgeSide.TOP
        }
        if (atBottom && !inHorizontalCornerDeadzone) {
            return EdgeSide.BOTTOM
        }
        if (atLeft && !inVerticalCornerDeadzone) {
            return EdgeSide.LEFT
        }
        if (atRight && !inVerticalCornerDeadzone) {
            return EdgeSide.RIGHT
        }
        return null
    }

    private fun clampEdgeWheelDirection(vWheel: Int, hWheel: Int, edgeSide: EdgeSide?): Pair<Int, Int> {
        if (edgeSide == null) {
            return vWheel to hWheel
        }
        return when (edgeSide) {
            EdgeSide.LEFT -> 0 to hWheel.coerceAtMost(0)
            EdgeSide.RIGHT -> 0 to hWheel.coerceAtLeast(0)
            EdgeSide.TOP -> vWheel.coerceAtLeast(0) to 0
            EdgeSide.BOTTOM -> vWheel.coerceAtMost(0) to 0
        }
    }

    private fun forcedEdgeWheelStep(
        edgeSide: EdgeSide,
        horizontalInverted: Boolean,
        verticalInverted: Boolean,
    ): Pair<Int, Int> {
        return when (edgeSide) {
            EdgeSide.LEFT -> 0 to if (horizontalInverted) 1 else -1
            EdgeSide.RIGHT -> 0 to if (horizontalInverted) -1 else 1
            EdgeSide.TOP -> (if (verticalInverted) -1 else 1) to 0
            EdgeSide.BOTTOM -> (if (verticalInverted) 1 else -1) to 0
        }
    }

    private fun verticalUnitsPerStep(): Float {
        val safePixels = settingsRepository.verticalScrollPixelsPerStep.value
            .takeIf { it.isFinite() }
            ?.coerceAtLeast(MIN_SCROLL_PIXELS_PER_STEP)
            ?: DEFAULT_SCROLL_PIXELS_PER_STEP
        return safePixels * SCROLL_COMMAND_UNITS_PER_PIXEL
    }

    private fun horizontalUnitsPerStep(): Float {
        val safePixels = settingsRepository.horizontalScrollPixelsPerStep.value
            .takeIf { it.isFinite() }
            ?.coerceAtLeast(MIN_SCROLL_PIXELS_PER_STEP)
            ?: DEFAULT_SCROLL_PIXELS_PER_STEP
        return safePixels * SCROLL_COMMAND_UNITS_PER_PIXEL
    }

    private fun resetScrollAccumulators() {
        scrollAccumulator.reset()
    }

    private fun markTouchActivity() {
        lastTouchActivityAtMs.set(SystemClock.elapsedRealtime())
    }

    private fun latestGroundTruthForRouting(nowMs: Long = SystemClock.elapsedRealtime()): CursorGroundTruth? {
        val snapshot = externalGroundTruthOverride ?: lastGroundTruthCursor ?: return null
        val maxAgeMs = (settingsRepository.syncIntervalMs.value * ROUTING_GROUND_TRUTH_STALE_MULTIPLIER) +
            ROUTING_GROUND_TRUTH_STALE_GRACE_MS
        val ageMs = nowMs - snapshot.sampledAtMs
        return if (ageMs in 0..maxAgeMs) snapshot else null
    }

    private suspend fun queryCursorGroundTruth(forceDisplayRefresh: Boolean = false): CursorGroundTruth? {
        val adbBinary = ensureAdbBinary() ?: return null
        val serial = activeHidCommandSerial() ?: ensureTargetConnected(
            adbBinary = adbBinary,
            requestedSerial = FIXED_TARGET_SERIAL,
        ) ?: return null

        refreshDisplaySizeIfNeeded(adbBinary = adbBinary, serial = serial, force = forceDisplayRefresh)
        val groundTruth = readCursorGroundTruth(adbBinary = adbBinary, serial = serial)
            ?: if (!forceDisplayRefresh) recentCachedGroundTruth() else null
            ?: return null
        lastGroundTruthCursor = groundTruth
        _cursorGroundTruth.value = groundTruth
        return groundTruth
    }

    private suspend fun readCursorGroundTruth(
        adbBinary: File,
        serial: String,
    ): CursorGroundTruth? {
        val fastResult = runProcess(
            args = listOf(
                adbBinary.absolutePath,
                "-s",
                serial,
                "shell",
                FAST_POINTER_DUMPSYS_COMMAND,
            ),
            timeoutMs = PROCESS_TIMEOUT_POINTER_DUMPSYS_MS,
        )
        if (fastResult.output.isNotBlank()) {
            parseGroundTruthFromDumpsys(fastResult.output)?.let { return it }
        }

        val fullResult = runProcess(
            args = listOf(
                adbBinary.absolutePath,
                "-s",
                serial,
                "shell",
                FULL_POINTER_DUMPSYS_COMMAND,
            ),
            timeoutMs = PROCESS_TIMEOUT_FULL_POINTER_DUMPSYS_MS,
        )
        if (fullResult.output.isBlank()) {
            return null
        }
        return parseGroundTruthFromDumpsys(fullResult.output)
    }

    private suspend fun waitForCursorGroundTruth(
        timeoutMs: Long,
        predicate: (CursorGroundTruth) -> Boolean,
    ): CursorGroundTruth? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var latest: CursorGroundTruth? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val sample = queryCursorGroundTruth()
            if (sample != null) {
                latest = sample
                if (predicate(sample)) {
                    return sample
                }
            }
            delay(CALIBRATION_POLL_INTERVAL_MS)
        }
        return latest
    }

    private suspend fun moveCursorToTopLeftForCalibration(): Boolean {
        repeat(CALIBRATION_CORNER_SWEEPS) {
            if (!sendLogicalHidMove(-CALIBRATION_SWEEP_DELTA, -CALIBRATION_SWEEP_DELTA)) {
                return false
            }
            delay(CALIBRATION_MOVE_SETTLE_MS)
        }
        return true
    }

    private suspend fun calibrateAxisToTarget(horizontal: Boolean, targetPx: Float): Int? {
        var low = 0
        var high = (targetPx * CALIBRATION_HIGH_MULTIPLIER).roundToInt().coerceAtLeast(CALIBRATION_MIN_HIGH)
        repeat(CALIBRATION_BINARY_SEARCH_STEPS) {
            if (!moveCursorToTopLeftForCalibration()) {
                return null
            }
            val atCorner = waitForCursorGroundTruth(timeoutMs = CALIBRATION_WAIT_TIMEOUT_MS) {
                it.x <= CALIBRATION_CORNER_TOLERANCE_PX && it.y <= CALIBRATION_CORNER_TOLERANCE_PX
            } ?: return null
            if (atCorner.x > CALIBRATION_CORNER_TOLERANCE_PX || atCorner.y > CALIBRATION_CORNER_TOLERANCE_PX) {
                return null
            }
            val candidate = (low + high) / 2
            val (logicalDx, logicalDy) = axisMoveCommand(horizontal = horizontal, amount = candidate)
            if (!sendLogicalHidMove(logicalDx, logicalDy)) {
                return null
            }
            val sample = waitForCursorGroundTruth(timeoutMs = CALIBRATION_WAIT_TIMEOUT_MS) { true } ?: return null
            val actual = if (horizontal) sample.x else sample.y
            if (actual < targetPx) {
                low = candidate + 1
            } else {
                high = candidate
            }
        }
        return high
    }

    private fun axisMoveCommand(
        horizontal: Boolean,
        amount: Int,
    ): Pair<Int, Int> {
        return if (horizontal) {
            amount to 0
        } else {
            0 to amount
        }
    }

    private fun ensureSyncWorker() {
        if (syncWorkerJob?.isActive == true) {
            return
        }
        syncWorkerJob = executorScope.launch(Dispatchers.IO) {
            while (isActive) {
                val externalGroundTruth = externalGroundTruthOverride
                if (externalGroundTruth != null) {
                    lastGroundTruthCursor = externalGroundTruth
                    _cursorGroundTruth.value = externalGroundTruth
                    _edgeHighlightState.value = edgeScrollRouter.highlightState(
                        groundTruth = externalGroundTruth,
                        edgeThicknessPx = 28f,
                        cornerDeadzonePx = 0f,
                    )
                    delay(EXTERNAL_GROUND_TRUTH_SYNC_MS)
                    continue
                }
                if (!shouldRunSyncPoller()) {
                    lastGroundTruthCursor = null
                    _cursorGroundTruth.value = null
                    if (_edgeHighlightState.value != EdgeHighlightState.NONE) {
                        _edgeHighlightState.value = EdgeHighlightState.NONE
                    }
                    delay(SYNC_IDLE_SLEEP_MS)
                    continue
                }

                runGroundTruthSyncOnce()
                val intervalMs = settingsRepository.syncIntervalMs.value
                    .toLong()
                    .coerceIn(MIN_SYNC_INTERVAL_MS, MAX_SYNC_INTERVAL_MS)
                delay(intervalMs)
            }
        }
    }

    private fun shouldRunSyncPoller(nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        val lastActivity = lastTouchActivityAtMs.get()
        if (lastActivity <= 0L) {
            return false
        }
        return (nowMs - lastActivity) <= TOUCH_SYNC_IDLE_TIMEOUT_MS
    }

    private suspend fun runGroundTruthSyncOnce() {
        val adbBinary = ensureAdbBinary() ?: return
        val serial = activeHidCommandSerial() ?: ensureTargetConnected(
            adbBinary = adbBinary,
            requestedSerial = FIXED_TARGET_SERIAL,
        ) ?: return

        refreshDisplaySizeIfNeeded(adbBinary = adbBinary, serial = serial)
        val groundTruth = readCursorGroundTruth(adbBinary = adbBinary, serial = serial)
        if (groundTruth == null) {
            val cached = recentCachedGroundTruth()
            if (cached != null) {
                _cursorGroundTruth.value = cached
                _edgeHighlightState.value = edgeScrollRouter.highlightState(
                    groundTruth = cached,
                    edgeThicknessPx = 28f,
                    cornerDeadzonePx = 0f,
                )
                return
            }
            maybeLogSyncFailure("Failed to read cursor ground truth")
            lastGroundTruthCursor = null
            _cursorGroundTruth.value = null
            _edgeHighlightState.value = EdgeHighlightState.NONE
            return
        }

        lastGroundTruthCursor = groundTruth
        _cursorGroundTruth.value = groundTruth
        _edgeHighlightState.value = edgeScrollRouter.highlightState(
            groundTruth = groundTruth,
            edgeThicknessPx = 28f,
            cornerDeadzonePx = 0f,
        )
    }

    private fun recentCachedGroundTruth(nowMs: Long = SystemClock.elapsedRealtime()): CursorGroundTruth? {
        val cached = lastGroundTruthCursor ?: return null
        val ageMs = nowMs - cached.sampledAtMs
        return if (ageMs in 0..CURSOR_GROUND_TRUTH_CACHE_HOLD_MS) cached else null
    }

    private suspend fun refreshDisplaySizeIfNeeded(
        adbBinary: File,
        serial: String,
        nowMs: Long = SystemClock.elapsedRealtime(),
        force: Boolean = false,
    ) {
        val shouldRefresh = force ||
            targetDisplayWidthPx <= 0 ||
            targetDisplayHeightPx <= 0 ||
            (nowMs - lastDisplaySizeRefreshAtMs) >= DISPLAY_SIZE_REFRESH_INTERVAL_MS
        if (!shouldRefresh) {
            return
        }

        val result = runProcess(
            args = listOf(
                adbBinary.absolutePath,
                "-s",
                serial,
                "shell",
                "wm",
                "size",
            ),
            timeoutMs = PROCESS_TIMEOUT_WM_SIZE_MS,
        )
        if (result.output.isBlank()) {
            return
        }

        val wmSizeMatch = WM_SIZE_REGEX.findAll(result.output).lastOrNull()
        val width = wmSizeMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return
        val height = wmSizeMatch.groupValues.getOrNull(2)?.toIntOrNull() ?: return
        if (width <= 0 || height <= 0) {
            return
        }
        targetDisplayWidthPx = width
        targetDisplayHeightPx = height
        lastDisplaySizeRefreshAtMs = nowMs
    }

    private suspend fun activeHidCommandSerial(): String? = hidMutex.withLock {
        hidSession?.takeIf { it.process.isAlive }?.commandSerial
    }

    private fun parseGroundTruthFromDumpsys(dump: String): CursorGroundTruth? {
        val viewport = parseInputViewport(dump)
        if (viewport != null) {
            currentInputViewportOrientation = viewport.orientation
            targetDisplayWidthPx = viewport.logicalWidthPx
            targetDisplayHeightPx = viewport.logicalHeightPx
        }

        val pointerMatch = HOVER_POINTER_REGEX.findAll(dump).lastOrNull()
            ?: GENERIC_MOUSE_POINTER_REGEX.findAll(dump).lastOrNull()
            ?: return null
        val rawX = pointerMatch.groupValues.getOrNull(1)?.toFloatOrNull() ?: return null
        val rawY = pointerMatch.groupValues.getOrNull(2)?.toFloatOrNull() ?: return null

        val width = viewport?.logicalWidthPx
            ?: targetDisplayWidthPx.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        val height = viewport?.logicalHeightPx
            ?: targetDisplayHeightPx.takeIf { it > 0 }
            ?: context.resources.displayMetrics.heightPixels
        if (width <= 0 || height <= 0) {
            return null
        }

        val (x, y) = normalizePointerCoordinates(
            rawX = rawX,
            rawY = rawY,
            logicalWidthPx = width,
            logicalHeightPx = height,
            orientation = viewport?.orientation ?: 0,
        )

        return CursorGroundTruth(
            x = x,
            y = y,
            widthPx = width,
            heightPx = height,
            sampledAtMs = SystemClock.elapsedRealtime(),
        )
    }

    private fun parseInputViewport(dump: String): InputViewport? {
        val match = INTERNAL_VIEWPORT_REGEX.findAll(dump).lastOrNull() ?: return null
        val orientation = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val left = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        val top = match.groupValues.getOrNull(3)?.toIntOrNull() ?: return null
        val right = match.groupValues.getOrNull(4)?.toIntOrNull() ?: return null
        val bottom = match.groupValues.getOrNull(5)?.toIntOrNull() ?: return null
        return InputViewport(
            orientation = orientation,
            logicalWidthPx = (right - left).coerceAtLeast(1),
            logicalHeightPx = (bottom - top).coerceAtLeast(1),
        )
    }

    private fun normalizePointerCoordinates(
        rawX: Float,
        rawY: Float,
        logicalWidthPx: Int,
        logicalHeightPx: Int,
        orientation: Int,
    ): Pair<Float, Float> {
        val maxX = logicalWidthPx.toFloat().coerceAtLeast(0f)
        val maxY = logicalHeightPx.toFloat().coerceAtLeast(0f)
        val normalizedOrientation = ((orientation % 4) + 4) % 4
        val (logicalX, logicalY) = when (normalizedOrientation) {
            1 -> rawY to (maxY - rawX)
            2 -> (maxX - rawX) to (maxY - rawY)
            3 -> (maxY - rawY) to (maxX - rawX)
            else -> rawX to rawY
        }
        return logicalX.coerceIn(0f, maxX) to logicalY.coerceIn(0f, maxY)
    }

    private fun maybeLogSyncFailure(message: String) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastSyncFailureLogAtMs.get()
        if (now - previous < SYNC_FAILURE_LOG_INTERVAL_MS) {
            return
        }
        if (!lastSyncFailureLogAtMs.compareAndSet(previous, now)) {
            return
        }
        Log.w(TAG, "cursor sync fallback: $message")
    }

    private suspend fun sendHidMove(dx: Int, dy: Int): Boolean {
        if (dx == 0 && dy == 0) {
            return true
        }
        if (daemonRuntimeBridge?.isActive() == true) {
            return daemonRuntimeBridge.sendMove(
                dx = dx,
                dy = dy,
                hidMoveChunkSize = settingsRepository.hidMoveChunkSize.value,
                mouseAccelerationEnabled = settingsRepository.mouseAccelerationEnabled.value,
            )
        }
        return withHidSession { session ->
            writeHidRelativeLocked(session, dx = dx, dy = dy, wheel = 0, hWheel = 0)
        }
    }

    private suspend fun sendLogicalHidMove(logicalDx: Int, logicalDy: Int): Boolean {
        val orientation = ((currentInputViewportOrientation % 4) + 4) % 4
        val (physicalDx, physicalDy) = when (orientation) {
            1 -> logicalDx to logicalDy
            2 -> -logicalDx to -logicalDy
            3 -> logicalDy to logicalDx
            else -> logicalDx to logicalDy
        }
        return sendHidMove(physicalDx, physicalDy)
    }

    private suspend fun sendHidClick(button: MouseButton): Boolean {
        if (daemonRuntimeBridge?.isActive() == true) {
            return daemonRuntimeBridge.sendClick(button)
        }
        val bit = button.toHidBit() ?: return false
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

    private suspend fun sendHidButton(button: MouseButton, isDown: Boolean): Boolean {
        if (daemonRuntimeBridge?.isActive() == true) {
            return daemonRuntimeBridge.sendButton(button = button, isDown = isDown)
        }
        val bit = button.toHidBit() ?: return false
        return withHidSession { session ->
            hidButtonMask = if (isDown) {
                hidButtonMask or bit
            } else {
                hidButtonMask and bit.inv()
            }
            writeHidReportLocked(session, hidButtonMask, dx = 0, dy = 0, wheel = 0, hWheel = 0)
        }
    }

    private suspend fun sendHidScroll(vWheel: Int, hWheel: Int): Boolean {
        if (vWheel == 0 && hWheel == 0) {
            return true
        }
        val sent = if (daemonRuntimeBridge?.isActive() == true) {
            daemonRuntimeBridge.sendScrollWheel(vWheel = vWheel, hWheel = hWheel)
        } else {
            withHidSession { session ->
                writeHidRelativeLocked(session, dx = 0, dy = 0, wheel = vWheel, hWheel = hWheel)
            }
        }
        if (sent && shouldCaptureDebugTelemetry()) {
            val next = (_hidScrollDebugHistory.value + HidScrollDebugEvent(
                elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                vWheel = vWheel,
                hWheel = hWheel,
            )).takeLast(MAX_HID_SCROLL_DEBUG_HISTORY)
            _hidScrollDebugHistory.value = next
        }
        return sent
    }

    private fun clearEdgeSwipeDebugHistory() {
        if (_edgeSwipeDebugHistory.value.isNotEmpty()) {
            _edgeSwipeDebugHistory.value = emptyList()
        }
    }

    private suspend fun activateCenteredEdgeScroll(
        side: EdgeSide,
        groundTruth: CursorGroundTruth?,
    ): Boolean {
        val anchor = groundTruth ?: return false
        val calibration = currentCursorCalibration()
        val jumpMultiplier = settingsRepository.edgeScrollJumpMultiplier.value
            .takeIf { it.isFinite() && it > 0f }
            ?: 1.0f
        val centerX = (targetDisplayWidthPx / 2f).coerceAtLeast(0f)
        val centerY = (targetDisplayHeightPx / 2f).coerceAtLeast(0f)
        val moveDx = if (calibration != null) {
            ((centerX - anchor.x) * calibration.unitsPerPxX * jumpMultiplier).roundToInt()
        } else {
            ((centerX - anchor.x) * jumpMultiplier).roundToInt()
        }
        val moveDy = if (calibration != null) {
            ((centerY - anchor.y) * calibration.unitsPerPxY * jumpMultiplier).roundToInt()
        } else {
            ((centerY - anchor.y) * jumpMultiplier).roundToInt()
        }
        val centered = sendLogicalHidMove(moveDx, moveDy)
        if (!centered) {
            return false
        }
        centeredEdgeScrollSession = CenteredEdgeScrollSession(
            side = side,
            restoreX = anchor.x,
            restoreY = anchor.y,
        )
        edgePushDebugDx = 0f
        edgePushDebugDy = 0f
        resetScrollAccumulators()
        lastNonZeroScrollInputAtMs = 0L
        hapticEvents?.onEdgeScrollStart()
        return true
    }

    private suspend fun deactivateCenteredEdgeScrollAndRestore(): Boolean {
        val session = centeredEdgeScrollSession ?: return true
        centeredEdgeScrollSession = null
        edgePushDebugDx = 0f
        edgePushDebugDy = 0f
        val shouldRestoreCursor = settingsRepository.restoreCursorAfterEdgeScroll.value

        resetScrollAccumulators()
        lastNonZeroScrollInputAtMs = 0L
        if (!shouldRestoreCursor) {
            lastEdgeAtSide = null
            return true
        }

        val calibration = currentCursorCalibration()
        val centerX = (targetDisplayWidthPx / 2f).coerceAtLeast(0f)
        val centerY = (targetDisplayHeightPx / 2f).coerceAtLeast(0f)
        val restoreDx = if (calibration != null) {
            ((session.restoreX - centerX) * calibration.unitsPerPxX).roundToInt()
        } else {
            (session.restoreX - centerX).roundToInt()
        }
        val restoreDy = if (calibration != null) {
            ((session.restoreY - centerY) * calibration.unitsPerPxY).roundToInt()
        } else {
            (session.restoreY - centerY).roundToInt()
        }
        val restored = sendLogicalHidMove(restoreDx, restoreDy)
        lastEdgeAtSide = if (restored) session.side else null
        return restored
    }

    private fun updateCenteredEdgeDebug(side: EdgeSide, moveDx: Int, moveDy: Int) {
        if (!shouldCaptureDebugTelemetry()) {
            return
        }
        when (side) {
            EdgeSide.LEFT, EdgeSide.RIGHT -> {
                edgePushDebugDx += (moveDx.toFloat() / currentPointerScale()).coerceIn(-MAX_EDGE_DEBUG_VECTOR_PX, MAX_EDGE_DEBUG_VECTOR_PX)
            }
            EdgeSide.TOP, EdgeSide.BOTTOM -> {
                edgePushDebugDy += (moveDy.toFloat() / currentPointerScale()).coerceIn(-MAX_EDGE_DEBUG_VECTOR_PX, MAX_EDGE_DEBUG_VECTOR_PX)
            }
        }
        val centerX = (targetDisplayWidthPx / 2f).coerceAtLeast(0f)
        val centerY = (targetDisplayHeightPx / 2f).coerceAtLeast(0f)
        val event = EdgeSwipeDebugEvent(
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            startX = centerX,
            startY = centerY,
            endX = centerX + edgePushDebugDx.coerceIn(-MAX_EDGE_DEBUG_VECTOR_PX, MAX_EDGE_DEBUG_VECTOR_PX),
            endY = centerY + edgePushDebugDy.coerceIn(-MAX_EDGE_DEBUG_VECTOR_PX, MAX_EDGE_DEBUG_VECTOR_PX),
        )
        _edgeSwipeDebugHistory.value = listOf(event)
    }

    private fun shouldCaptureDebugTelemetry(): Boolean {
        return DebugFlags.ENABLED || settingsRepository.debugOverlayEnabled.value
    }

    private fun currentPointerScale(): Float {
        return settingsRepository.speedMultiplier.value
            .takeIf { it.isFinite() && abs(it) >= 0.0001f }
            ?: 1f
    }

    private fun currentCursorCalibration(): CursorCalibration? {
        val portraitCalibration = cursorCalibrationOrNull(
            unitsPerPxX = settingsRepository.cursorCalibrationUnitsPerPxX.value,
            unitsPerPxY = settingsRepository.cursorCalibrationUnitsPerPxY.value,
        )
        val landscapeCalibration = cursorCalibrationOrNull(
            unitsPerPxX = settingsRepository.cursorCalibrationLandscapeUnitsPerPxX.value,
            unitsPerPxY = settingsRepository.cursorCalibrationLandscapeUnitsPerPxY.value,
        )
        val useLandscapeCalibration = targetDisplayWidthPx > targetDisplayHeightPx
        val direct = if (useLandscapeCalibration) landscapeCalibration else portraitCalibration
        if (direct != null) {
            return direct
        }
        val fallback = if (useLandscapeCalibration) {
            portraitCalibration?.let {
                CursorCalibration(
                    unitsPerPxX = it.unitsPerPxY,
                    unitsPerPxY = it.unitsPerPxX,
                )
            }
        } else {
            landscapeCalibration?.let {
                CursorCalibration(
                    unitsPerPxX = it.unitsPerPxY,
                    unitsPerPxY = it.unitsPerPxX,
                )
            }
        }
        return fallback
    }

    private fun cursorCalibrationOrNull(unitsPerPxX: Float, unitsPerPxY: Float): CursorCalibration? {
        if (!unitsPerPxX.isFinite() || !unitsPerPxY.isFinite() || unitsPerPxX <= 0f || unitsPerPxY <= 0f) {
            return null
        }
        return CursorCalibration(unitsPerPxX = unitsPerPxX, unitsPerPxY = unitsPerPxY)
    }

    private fun edgeActivationDistancePx(): Float {
        return settingsRepository.edgeScrollRepeatDelayMs.value
            .coerceAtLeast(0)
            .toFloat()
    }

    private suspend fun withHidSession(block: suspend (HidSession) -> Boolean): Boolean {
        return hidMutex.withLock {
            val session = ensureHidSessionLocked() ?: return@withLock false
            val success = block(session)
            if (!success) {
                closeHidSessionLocked("hid write failed")
            } else {
                _status.value = _status.value.copy(lastError = null)
            }
            success
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

        val adbBinary = ensureAdbBinary() ?: return null
        val commandSerial = ensureTargetConnected(
            adbBinary = adbBinary,
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

        val process = startProcess(args) ?: run {
            _status.value = _status.value.copy(lastError = "Failed to start hid process")
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
            _status.value = _status.value.copy(lastError = "HID register failed")
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
        val moveChunkLimit = if (settingsRepository.mouseAccelerationEnabled.value) {
            HID_REL_AXIS_MAX
        } else {
            settingsRepository.hidMoveChunkSize.value
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
        val duration = durationMs.coerceAtLeast(0)
        val jsonLine = "{\"id\":$HID_DEVICE_ID,\"command\":\"delay\",\"duration\":$duration}"
        return writeHidJsonLineLocked(session, jsonLine)
    }

    private suspend fun writeHidJsonLineLocked(session: HidSession, line: String): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                if (!session.process.isAlive) {
                    return@runCatching false
                }
                session.writer.write(line)
                session.writer.newLine()
                session.writer.flush()
                true
            }.getOrElse { error ->
                Log.w(TAG, "hid stream write failed line=$line", error)
                false
            }
        }
    }

    private fun startHidOutputDrainer(process: Process): Thread {
        return Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            Log.w(TAG, "hid stderr/stdout: $line")
                        }
                    }
                }
            }.onFailure { error ->
                val isExpectedClose =
                    error is java.io.IOException &&
                        (error.message?.contains("stream closed", ignoreCase = true) == true || !process.isAlive)
                if (!isExpectedClose) {
                    Log.w(TAG, "hid output drainer failed", error)
                }
            }
        }.apply {
            name = "touchpad-hid-drainer"
            isDaemon = true
            start()
        }
    }

    private fun closeHidSessionLocked(reason: String) {
        val session = hidSession ?: return
        hidSession = null
        hidButtonMask = 0

        Log.i(TAG, "Closing HID session: $reason")
        runCatching { session.writer.flush() }
        runCatching { session.writer.close() }
        runCatching { session.process.outputStream.close() }
        runCatching { session.process.inputStream.close() }
        runCatching { session.process.errorStream.close() }
        runCatching { session.process.destroy() }
    }

    private fun MouseButton.toHidBit(): Int? {
        return when (this) {
            MouseButton.LEFT -> 0x01
            MouseButton.RIGHT -> 0x02
            MouseButton.MIDDLE -> 0x04
        }
    }

    private fun toUnsignedByte(value: Int): Int {
        return value and 0xFF
    }

    private fun maybeLogMoveStats(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastMoveStatsLogAtMs.get()
        if (!force && now - previous < MOVE_STATS_LOG_INTERVAL_MS) {
            return
        }
        if (!lastMoveStatsLogAtMs.compareAndSet(previous, now)) {
            return
        }
        Log.i(
            TAG,
            "move_exec req=${moveRequestedCount.get()} ok=${moveSuccessCount.get()} fail=${moveFailureCount.get()} " +
                "sumReq=(${moveRequestedDxSum.get()},${moveRequestedDySum.get()}) " +
                "sumOk=(${moveSuccessDxSum.get()},${moveSuccessDySum.get()})",
        )
    }

    private fun maybeLogEdgeRoute(command: WireCommand.MoveRel, routed: EdgeScrollRoutingResult) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastEdgeRouteLogAtMs.get()
        if (now - previous < EDGE_ROUTE_LOG_INTERVAL_MS) {
            return
        }
        if (!lastEdgeRouteLogAtMs.compareAndSet(previous, now)) {
            return
        }
        val gt = lastGroundTruthCursor
        Log.i(
            TAG,
            "edge_route input=(${command.dx},${command.dy}) move=(${routed.moveDx},${routed.moveDy}) " +
                "scroll=(${routed.scrollDx},${routed.scrollDy}) gt=${gt?.x},${gt?.y}",
        )
    }

    private fun maybeLogNoStep(commandDxUnits: Float, commandDyUnits: Float) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastScrollNoStepLogAtMs.get()
        if (now - previous < SCROLL_NO_STEP_LOG_INTERVAL_MS) {
            return
        }
        if (!lastScrollNoStepLogAtMs.compareAndSet(previous, now)) {
            return
        }
        val accumulatorSnapshot = scrollAccumulator.snapshot()
        Log.i(
            TAG,
            "scroll no-step cmd=($commandDxUnits,$commandDyUnits) " +
                "acc=(${accumulatorSnapshot.horizontalAccumulator},${accumulatorSnapshot.verticalAccumulator}) " +
                "thresholdPx=(${settingsRepository.horizontalScrollPixelsPerStep.value},${settingsRepository.verticalScrollPixelsPerStep.value})",
        )
    }

    private suspend fun ensureTargetConnected(adbBinary: File, requestedSerial: String): String? {
        val normalizedRequested = requestedSerial.trim()
        if (normalizedRequested.isEmpty()) {
            _status.value = _status.value.copy(lastError = "Target serial is empty")
            return null
        }

        if (isSerialReady(adbBinary, normalizedRequested)) {
            return normalizedRequested
        }

        var connectResult: ProcessResult? = null
        if (normalizedRequested.contains(":")) {
            connectResult = runProcess(
                listOf(
                    adbBinary.absolutePath,
                    "connect",
                    normalizedRequested,
                ),
                timeoutMs = PROCESS_TIMEOUT_CONNECT_MS,
            )
            if (connectResult.exitCode == 0 && isSerialReady(adbBinary, normalizedRequested)) {
                return normalizedRequested
            }
        }

        val availableSerials = listConnectedDeviceSerials(adbBinary)
        if (availableSerials.contains(normalizedRequested)) {
            return normalizedRequested
        }
        if (availableSerials.size == 1) {
            val fallbackSerial = availableSerials.first()
            if (connectResult != null) {
                Log.i(
                    TAG,
                    "Requested serial unavailable requested=$normalizedRequested; " +
                        "using detected serial=$fallbackSerial after connect result " +
                        "code=${connectResult.exitCode} output=${connectResult.output}",
                )
            }
            return fallbackSerial
        }

        val availableSummary = if (availableSerials.isEmpty()) "none" else availableSerials.joinToString(", ")
        _status.value = _status.value.copy(
            lastError = "Target serial unavailable requested=$normalizedRequested available=$availableSummary",
        )
        if (connectResult != null) {
            Log.w(
                TAG,
                "adb connect did not yield ready requestedSerial=$normalizedRequested " +
                    "code=${connectResult.exitCode} output=${connectResult.output} available=$availableSummary",
            )
        }
        Log.w(
            TAG,
            "Unable to resolve target serial requested=$normalizedRequested available=$availableSummary",
        )
        return null
    }

    private suspend fun isSerialReady(adbBinary: File, serial: String): Boolean {
        val stateResult = runProcess(
            listOf(
                adbBinary.absolutePath,
                "-s",
                serial,
                "get-state",
            ),
            timeoutMs = PROCESS_TIMEOUT_STATE_MS,
        )
        if (stateResult.exitCode != 0) {
            return false
        }
        return stateResult.output
            .lineSequence()
            .map { it.trim() }
            .any { it == "device" }
    }

    private suspend fun listConnectedDeviceSerials(adbBinary: File): List<String> {
        val devicesResult = runProcess(
            listOf(
                adbBinary.absolutePath,
                "devices",
                "-l",
            ),
            timeoutMs = PROCESS_TIMEOUT_STATE_MS,
        )
        if (devicesResult.exitCode != 0) {
            return emptyList()
        }
        return devicesResult.output
            .lineSequence()
            .map { it.trim() }
            .filter { line -> line.isNotBlank() && !line.startsWith("List of devices attached") }
            .mapNotNull { line ->
                val columns = line.split(Regex("\\s+"))
                if (columns.size < 2) {
                    return@mapNotNull null
                }
                val serial = columns[0]
                val state = columns[1]
                if (state == "device") serial else null
            }
            .toList()
    }

    private suspend fun ensureAdbBinary(): File? = binaryMutex.withLock {
        // Preferred path: packaged native library directories are executable on Android.
        val packagedExec = findBundledAdbBinary()
        if (packagedExec != null) {
            _status.value = _status.value.copy(
                adbBinaryReady = true,
                adbBinaryPath = packagedExec.absolutePath,
                lastError = null,
            )
            return packagedExec
        }

        val assets = context.assets
        val supportedAbi = Build.SUPPORTED_ABIS.firstOrNull { abi ->
            assets.list("bin")?.contains(abi) == true
        }

        if (supportedAbi != null) {
            val destinationDir = File(context.filesDir, "adb-bin")
            if (!destinationDir.exists()) {
                destinationDir.mkdirs()
            }

            val destination = File(destinationDir, "adb")
            if (!destination.exists()) {
                val extracted = withContext(Dispatchers.IO) {
                    runCatching {
                        assets.open("bin/$supportedAbi/adb").use { input ->
                            destination.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        destination.setExecutable(true, false)
                    }.isSuccess
                }
                if (!extracted) {
                    _status.value = _status.value.copy(
                        adbBinaryReady = false,
                        adbBinaryPath = null,
                        lastError = "Failed to extract adb for ABI $supportedAbi",
                    )
                    return null
                }
            }

            _status.value = _status.value.copy(
                adbBinaryReady = true,
                adbBinaryPath = destination.absolutePath,
                lastError = null,
            )
            return destination
        }

        _status.value = _status.value.copy(
            adbBinaryReady = false,
            adbBinaryPath = null,
            lastError = "No bundled adb binary for this ABI",
        )
        return null
    }

    private fun findBundledAdbBinary(): File? {
        val candidates = linkedSetOf<String>()

        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        if (!nativeLibraryDir.isNullOrBlank()) {
            candidates += File(nativeLibraryDir, LIBADB_EXEC_NAME).absolutePath
        }

        val sourceDir = context.applicationInfo.sourceDir
        val apkParent = if (sourceDir.isNullOrBlank()) null else File(sourceDir).parentFile
        if (apkParent != null) {
            val abiCandidates = (Build.SUPPORTED_ABIS.toList() + COMMON_ABI_DIR_NAMES).distinct()
            for (abi in abiCandidates) {
                candidates += File(apkParent, "lib/$abi/$LIBADB_EXEC_NAME").absolutePath
            }
        }

        return candidates.asSequence()
            .map(::File)
            .firstOrNull { it.exists() }
    }

    private suspend fun runProcess(
        args: List<String>,
        timeoutMs: Long = PROCESS_TIMEOUT_SHELL_MS,
    ): ProcessResult = withContext(Dispatchers.IO) {
        runCatching {
            val startedAt = SystemClock.elapsedRealtime()
            val process = newProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val outputReader = thread(
                start = true,
                isDaemon = true,
                name = "ondevice-adb-process-output",
            ) {
                runCatching {
                    process.inputStream.bufferedReader().use { reader ->
                        val buffer = CharArray(PROCESS_READ_BUFFER_CHARS)
                        while (true) {
                            val read = reader.read(buffer)
                            if (read <= 0) {
                                break
                            }
                            synchronized(output) {
                                output.append(buffer, 0, read)
                            }
                        }
                    }
                }
            }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { process.destroy() }
                process.waitFor(PROCESS_DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)
                if (process.isAlive) {
                    runCatching { process.destroyForcibly() }
                    process.waitFor(PROCESS_DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)
                }
                val timedOutMs = SystemClock.elapsedRealtime() - startedAt
                outputReader.join(PROCESS_READER_JOIN_MS)
                val timeoutOutput = synchronized(output) { output.toString().trim() }
                Log.w(TAG, "Process timeout (${timedOutMs}ms >= ${timeoutMs}ms) args=$args out=$timeoutOutput")
                return@runCatching ProcessResult(
                    exitCode = PROCESS_TIMEOUT_EXIT_CODE,
                    output = "timeout after ${timedOutMs}ms ${timeoutOutput}".trim(),
                )
            }

            outputReader.join(PROCESS_READER_JOIN_MS)
            val exitCode = process.exitValue()
            val finalOutput = synchronized(output) { output.toString().trim() }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            if (elapsedMs >= PROCESS_SLOW_LOG_MS) {
                Log.w(TAG, "Slow process ${elapsedMs}ms args=$args exit=$exitCode out=$finalOutput")
            }
            ProcessResult(exitCode = exitCode, output = finalOutput)
        }.getOrElse { error ->
            ProcessResult(
                exitCode = -1,
                output = error.message.orEmpty(),
            )
        }
    }

    private suspend fun startProcess(args: List<String>): Process? = withContext(Dispatchers.IO) {
        runCatching {
            newProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
        }.getOrElse { error ->
            Log.w(TAG, "Failed to start process args=$args", error)
            null
        }
    }

    private fun newProcessBuilder(args: List<String>): ProcessBuilder {
        val adbHome = File(context.noBackupFilesDir, "adb-home")
        val androidUserHome = File(adbHome, ".android")
        adbHome.mkdirs()
        androidUserHome.mkdirs()
        args.firstOrNull()
            ?.let(::File)
            ?.takeIf { it.exists() }
            ?.let { adbBinary -> AdbAuthKeyManager.ensureKeys(androidUserHome, adbBinary) }

        return ProcessBuilder(args).apply {
            environment()["HOME"] = adbHome.absolutePath
            environment()["ANDROID_USER_HOME"] = adbHome.absolutePath
            environment()["ANDROID_SDK_HOME"] = adbHome.absolutePath
            environment()["ADB_VENDOR_KEYS"] = androidUserHome.absolutePath
            environment()["TMPDIR"] = context.cacheDir.absolutePath
        }
    }

    private data class HidSession(
        val requestedSerial: String,
        val commandSerial: String,
        val process: Process,
        val writer: BufferedWriter,
        val logDrainer: Thread,
    )

    private data class ProcessResult(
        val exitCode: Int,
        val output: String,
    )

    companion object {
        const val TAG = "OnDeviceAdbExecutor"
        const val LIBADB_EXEC_NAME = "libadbexec.so"
        const val HID_DEVICE_ID = 1
        const val HID_VENDOR_ID = 6353
        const val HID_PRODUCT_ID = 20001
        const val HID_DEVICE_NAME = "Touchpad Virtual Mouse"
        const val HID_REL_AXIS_MAX = 127
        const val HID_REGISTER_DELAY_MS = 40
        const val HID_CLICK_GAP_MS = 8
        // TouchpadEngine emits ScrollBy values in command units (currently 10 units per 1 finger px).
        const val SCROLL_COMMAND_UNITS_PER_PIXEL = 10f
        const val DEFAULT_SCROLL_PIXELS_PER_STEP = 40f
        const val MIN_SCROLL_PIXELS_PER_STEP = 1f
        const val SCROLL_IDLE_RESET_MS = 350L
        const val MAX_SCROLL_FOLLOWUP_FACTOR = 8f
        const val HORIZONTAL_HID_SIGN = 1f
        const val CLAMP_SCROLL_STEPS_PER_DISPATCH = true
        const val MIN_AXIS_THRESHOLD = 0.0001f
        const val MAX_EDGE_DEBUG_VECTOR_PX = 220f
        const val PROCESS_TIMEOUT_EXIT_CODE = -2
        const val PROCESS_TIMEOUT_SHELL_MS = 700L
        const val PROCESS_TIMEOUT_PAIR_MS = 6_000L
        const val PROCESS_TIMEOUT_CONNECT_MS = 2200L
        const val PROCESS_TIMEOUT_STATE_MS = 900L
        const val PROCESS_TIMEOUT_POINTER_DUMPSYS_MS = 900L
        const val PROCESS_TIMEOUT_FULL_POINTER_DUMPSYS_MS = 2_500L
        const val CURSOR_GROUND_TRUTH_CACHE_HOLD_MS = 3_000L
        const val PROCESS_TIMEOUT_WM_SIZE_MS = 900L
        const val FIXED_TARGET_SERIAL = "127.0.0.1:5555"
        const val PROCESS_DESTROY_GRACE_MS = 150L
        const val PROCESS_SLOW_LOG_MS = 350L
        const val PROCESS_READER_JOIN_MS = 200L
        const val PROCESS_READ_BUFFER_CHARS = 4_096
        const val MOVE_STATS_LOG_INTERVAL_MS = 1500L
        const val EDGE_ROUTE_LOG_INTERVAL_MS = 250L
        const val SCROLL_NO_STEP_LOG_INTERVAL_MS = 250L
        const val MAX_HID_SCROLL_DEBUG_HISTORY = 20
        const val CALIBRATION_WAIT_TIMEOUT_MS = 2_000L
        const val CALIBRATION_POLL_INTERVAL_MS = 45L
        const val CALIBRATION_MOVE_SETTLE_MS = 180L
        const val CALIBRATION_CORNER_TOLERANCE_PX = 3f
        const val CALIBRATION_CORNER_SWEEPS = 6
        const val CALIBRATION_SWEEP_DELTA = 8_000
        const val CALIBRATION_HIGH_MULTIPLIER = 4f
        const val CALIBRATION_MIN_HIGH = 512
        const val CALIBRATION_BINARY_SEARCH_STEPS = 12
        const val CALIBRATION_VALIDATION_TRIALS = 3
        const val TOUCH_SYNC_IDLE_TIMEOUT_MS = 1_000L
        const val SYNC_IDLE_SLEEP_MS = 250L
        const val MIN_SYNC_INTERVAL_MS = 50L
        const val MAX_SYNC_INTERVAL_MS = 500L
        const val SYNC_FAILURE_LOG_INTERVAL_MS = 2_500L
        const val SYNC_LOG_OUTPUT_LIMIT = 180
        const val EXTERNAL_GROUND_TRUTH_SYNC_MS = 50L
        const val ROUTING_GROUND_TRUTH_STALE_MULTIPLIER = 2L
        const val ROUTING_GROUND_TRUTH_STALE_GRACE_MS = 60L
        const val DISPLAY_SIZE_REFRESH_INTERVAL_MS = 2_500L
        const val STRICT_EDGE_TRIGGER_PX = 1f
        const val FAST_POINTER_DUMPSYS_COMMAND =
            "dumpsys input | grep -E 'Viewport INTERNAL|hoveringPointers|Pointer\\(id=[0-9]+, *MOUSE\\)'"
        const val FULL_POINTER_DUMPSYS_COMMAND = "dumpsys input"
        val HOVER_POINTER_REGEX = Regex(
            "hoveringPointers=\\[Pointer\\(id=\\d+,\\s*MOUSE\\)\\s*at\\s*\\((-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)\\)",
        )
        val GENERIC_MOUSE_POINTER_REGEX = Regex(
            "Pointer\\(id=\\d+,\\s*MOUSE\\)\\s*at\\s*\\((-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)\\)",
        )
        val INTERNAL_VIEWPORT_REGEX = Regex(
            "Viewport\\s+INTERNAL.*orientation=(\\d+),.*logicalFrame=\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\]",
        )
        val WM_SIZE_REGEX = Regex("(?:Physical size|Override size):\\s*(\\d+)x(\\d+)")
        val COMMON_ABI_DIR_NAMES = listOf(
            "arm64",
            "arm64-v8a",
            "armeabi-v7a",
            "x86_64",
            "x86",
        )
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
