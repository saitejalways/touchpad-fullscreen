package com.alex.touchpad.backend

import android.content.Context
import android.os.SystemClock
import com.alex.touchpad.core.AppLog as Log
import com.alex.touchpad.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class CursorSyncWorker(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val adbProcessManager: AdbProcessManager,
    private val hidDeviceWriter: HidDeviceWriter,
    private val workerScope: CoroutineScope,
) {
    private data class InputViewport(
        val orientation: Int,
        val logicalWidthPx: Int,
        val logicalHeightPx: Int,
    )

    private var syncWorkerJob: Job? = null
    
    @Volatile
    var lastGroundTruthCursor: CursorGroundTruth? = null
        private set

    @Volatile
    private var targetDisplayWidthPx: Int = 0
    @Volatile
    private var targetDisplayHeightPx: Int = 0
    private var lastDisplaySizeRefreshAtMs: Long = 0L

    private val lastTouchActivityAtMs = AtomicLong(0L)
    private val lastSyncFailureLogAtMs = AtomicLong(0L)

    private val _edgeHighlightState = MutableStateFlow(EdgeHighlightState.NONE)
    val edgeHighlightState: StateFlow<EdgeHighlightState> = _edgeHighlightState.asStateFlow()
    
    // We instantiate EdgeScrollRouter purely for highlight tracking inside the sync worker to mimic original functionality
    private val edgeScrollRouter = EdgeScrollRouter(scrollUnitsPerPixel = 10f)

    init {
        val metrics = context.resources.displayMetrics
        targetDisplayWidthPx = metrics.widthPixels.coerceAtLeast(1)
        targetDisplayHeightPx = metrics.heightPixels.coerceAtLeast(1)
        ensureSyncWorker()
    }

    fun markTouchActivity() {
        lastTouchActivityAtMs.set(SystemClock.elapsedRealtime())
    }

    fun latestGroundTruthForRouting(nowMs: Long = SystemClock.elapsedRealtime()): CursorGroundTruth? {
        val snapshot = lastGroundTruthCursor ?: return null
        val maxAgeMs = (settingsRepository.syncIntervalMs.value * ROUTING_GROUND_TRUTH_STALE_MULTIPLIER) +
            ROUTING_GROUND_TRUTH_STALE_GRACE_MS
        val ageMs = nowMs - snapshot.sampledAtMs
        return if (ageMs in 0..maxAgeMs) snapshot else null
    }

    private fun ensureSyncWorker() {
        if (syncWorkerJob?.isActive == true) {
            return
        }
        syncWorkerJob = workerScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (!shouldRunSyncPoller()) {
                    lastGroundTruthCursor = null
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
        val adbBinary = adbProcessManager.ensureAdbBinary() ?: return
        val serial = hidDeviceWriter.getActiveCommandSerial() ?: adbProcessManager.ensureTargetConnected(
            requestedSerial = FIXED_TARGET_SERIAL,
        ) ?: return

        refreshDisplaySizeIfNeeded(adbBinary = adbBinary, serial = serial)
        val result = adbProcessManager.runProcess(
            args = listOf(
                adbBinary.absolutePath,
                "-s",
                serial,
                "shell",
                FAST_POINTER_DUMPSYS_COMMAND,
            ),
            timeoutMs = AdbProcessManager.PROCESS_TIMEOUT_POINTER_DUMPSYS_MS,
        )
        if (result.output.isBlank()) {
            maybeLogSyncFailure(
                "dumpsys input failed code=${result.exitCode} output=${result.output.take(SYNC_LOG_OUTPUT_LIMIT)}",
            )
            lastGroundTruthCursor = null
            _edgeHighlightState.value = EdgeHighlightState.NONE
            return
        }

        val groundTruth = parseGroundTruthFromDumpsys(result.output)
        if (groundTruth == null) {
            if (result.exitCode != 0) {
                maybeLogSyncFailure(
                    "pointer dump parse failed code=${result.exitCode} output=${result.output.take(SYNC_LOG_OUTPUT_LIMIT)}",
                )
            } else {
                maybeLogSyncFailure("Failed to parse cursor from pointer dump")
            }
            lastGroundTruthCursor = null
            _edgeHighlightState.value = EdgeHighlightState.NONE
            return
        }

        lastGroundTruthCursor = groundTruth
        _edgeHighlightState.value = edgeScrollRouter.highlightState(
            groundTruth = groundTruth,
            edgeThicknessPx = EDGE_HIGHLIGHT_THICKNESS_PX,
            cornerDeadzonePx = 0f,
        )
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

        val result = adbProcessManager.runProcess(
            args = listOf(
                adbBinary.absolutePath,
                "-s",
                serial,
                "shell",
                "wm",
                "size",
            ),
            timeoutMs = AdbProcessManager.PROCESS_TIMEOUT_WM_SIZE_MS,
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

    private fun parseGroundTruthFromDumpsys(dump: String): CursorGroundTruth? {
        val pointerMatch = HOVER_POINTER_REGEX.findAll(dump).lastOrNull()
            ?: GENERIC_MOUSE_POINTER_REGEX.findAll(dump).lastOrNull()
            ?: return null
        val rawX = pointerMatch.groupValues.getOrNull(1)?.toFloatOrNull() ?: return null
        val rawY = pointerMatch.groupValues.getOrNull(2)?.toFloatOrNull() ?: return null
        val viewport = parseInputViewport(dump)
        if (viewport != null) {
            targetDisplayWidthPx = viewport.logicalWidthPx
            targetDisplayHeightPx = viewport.logicalHeightPx
        }

        val width = viewport?.logicalWidthPx
            ?: targetDisplayWidthPx.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        val height = viewport?.logicalHeightPx
            ?: targetDisplayHeightPx.takeIf { it > 0 }
            ?: context.resources.displayMetrics.heightPixels
        if (width <= 0 || height <= 0) {
            return null
        }

        val orientation = viewport?.orientation ?: 0
        val (x, y) = normalizePointerCoordinates(
            rawX = rawX,
            rawY = rawY,
            logicalWidthPx = width,
            logicalHeightPx = height,
            orientation = orientation,
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
        val viewportMatch = INTERNAL_VIEWPORT_REGEX.findAll(dump).lastOrNull() ?: return null
        val orientation = viewportMatch.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val left = viewportMatch.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        val top = viewportMatch.groupValues.getOrNull(3)?.toIntOrNull() ?: return null
        val right = viewportMatch.groupValues.getOrNull(4)?.toIntOrNull() ?: return null
        val bottom = viewportMatch.groupValues.getOrNull(5)?.toIntOrNull() ?: return null
        val logicalWidthPx = (right - left).coerceAtLeast(1)
        val logicalHeightPx = (bottom - top).coerceAtLeast(1)
        return InputViewport(
            orientation = orientation,
            logicalWidthPx = logicalWidthPx,
            logicalHeightPx = logicalHeightPx,
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
        val (rotatedX, rotatedY) = when (normalizedOrientation) {
            1 -> rawY to (maxY - rawX)
            2 -> (maxX - rawX) to (maxY - rawY)
            3 -> (maxX - rawY) to rawX
            else -> rawX to rawY
        }
        return rotatedX.coerceIn(0f, maxX) to rotatedY.coerceIn(0f, maxY)
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

    companion object {
        private const val TAG = "CursorSyncWorker"
        const val TOUCH_SYNC_IDLE_TIMEOUT_MS = 1_000L
        const val SYNC_IDLE_SLEEP_MS = 250L
        const val MIN_SYNC_INTERVAL_MS = 50L
        const val MAX_SYNC_INTERVAL_MS = 500L
        const val SYNC_FAILURE_LOG_INTERVAL_MS = 2_500L
        const val SYNC_LOG_OUTPUT_LIMIT = 180
        const val ROUTING_GROUND_TRUTH_STALE_MULTIPLIER = 2L
        const val ROUTING_GROUND_TRUTH_STALE_GRACE_MS = 60L
        const val DISPLAY_SIZE_REFRESH_INTERVAL_MS = 2_500L
        const val EDGE_HIGHLIGHT_THICKNESS_PX = 28f
        
        const val FIXED_TARGET_SERIAL = "127.0.0.1:5555"

        const val FAST_POINTER_DUMPSYS_COMMAND =
            "dumpsys input | grep -E 'Viewport INTERNAL|hoveringPointers|Pointer\\(id=[0-9]+, *MOUSE\\)'"
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
    }
}
