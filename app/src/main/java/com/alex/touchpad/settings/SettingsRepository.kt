package com.alex.touchpad.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EndpointConfig(
    val host: String = "127.0.0.1",
    val port: Int = 53535,
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("touchpad_settings", Context.MODE_PRIVATE)

    private val _speedMultiplier = MutableStateFlow(prefs.getFloat(KEY_SPEED_MULTIPLIER, 1.0f))
    val speedMultiplier: StateFlow<Float> = _speedMultiplier.asStateFlow()
    private val _verticalScrollPixelsPerStep = MutableStateFlow(loadVerticalScrollPixelsPerStep())
    val verticalScrollPixelsPerStep: StateFlow<Float> = _verticalScrollPixelsPerStep.asStateFlow()
    private val _horizontalScrollPixelsPerStep = MutableStateFlow(loadHorizontalScrollPixelsPerStep())
    val horizontalScrollPixelsPerStep: StateFlow<Float> = _horizontalScrollPixelsPerStep.asStateFlow()
    private val _verticalFollowupStepFactor = MutableStateFlow(loadVerticalFollowupStepFactor())
    val verticalFollowupStepFactor: StateFlow<Float> = _verticalFollowupStepFactor.asStateFlow()
    private val _horizontalFollowupStepFactor = MutableStateFlow(loadHorizontalFollowupStepFactor())
    val horizontalFollowupStepFactor: StateFlow<Float> = _horizontalFollowupStepFactor.asStateFlow()
    private val _syncIntervalMs = MutableStateFlow(
        prefs.getInt(KEY_SYNC_INTERVAL_MS, DEFAULT_SYNC_INTERVAL_MS)
    )
    val syncIntervalMs: StateFlow<Int> = _syncIntervalMs.asStateFlow()
    private val _edgeScrollRepeatDelayMs = MutableStateFlow(
        prefs.getInt(KEY_EDGE_SCROLL_REPEAT_DELAY_MS, DEFAULT_EDGE_SCROLL_REPEAT_DELAY_MS)
    )
    val edgeScrollRepeatDelayMs: StateFlow<Int> = _edgeScrollRepeatDelayMs.asStateFlow()
    private val _edgeScrollJumpMultiplier = MutableStateFlow(loadEdgeScrollJumpMultiplier())
    val edgeScrollJumpMultiplier: StateFlow<Float> = _edgeScrollJumpMultiplier.asStateFlow()
    private val _cursorCalibrationUnitsPerPxX = MutableStateFlow(
        prefs.getFloat(KEY_CURSOR_CALIBRATION_UNITS_PER_PX_X, DEFAULT_PORTRAIT_CURSOR_CALIBRATION_UNITS_PER_PX_X)
    )
    val cursorCalibrationUnitsPerPxX: StateFlow<Float> = _cursorCalibrationUnitsPerPxX.asStateFlow()
    private val _cursorCalibrationUnitsPerPxY = MutableStateFlow(
        prefs.getFloat(KEY_CURSOR_CALIBRATION_UNITS_PER_PX_Y, DEFAULT_PORTRAIT_CURSOR_CALIBRATION_UNITS_PER_PX_Y)
    )
    val cursorCalibrationUnitsPerPxY: StateFlow<Float> = _cursorCalibrationUnitsPerPxY.asStateFlow()
    private val _cursorCalibrationLandscapeUnitsPerPxX = MutableStateFlow(
        prefs.getFloat(KEY_CURSOR_CALIBRATION_LANDSCAPE_UNITS_PER_PX_X, DEFAULT_LANDSCAPE_CURSOR_CALIBRATION_UNITS_PER_PX_X)
    )
    val cursorCalibrationLandscapeUnitsPerPxX: StateFlow<Float> = _cursorCalibrationLandscapeUnitsPerPxX.asStateFlow()
    private val _cursorCalibrationLandscapeUnitsPerPxY = MutableStateFlow(
        prefs.getFloat(KEY_CURSOR_CALIBRATION_LANDSCAPE_UNITS_PER_PX_Y, DEFAULT_LANDSCAPE_CURSOR_CALIBRATION_UNITS_PER_PX_Y)
    )
    val cursorCalibrationLandscapeUnitsPerPxY: StateFlow<Float> = _cursorCalibrationLandscapeUnitsPerPxY.asStateFlow()
    private val _restoreCursorAfterEdgeScroll = MutableStateFlow(
        prefs.getBoolean(KEY_RESTORE_CURSOR_AFTER_EDGE_SCROLL, DEFAULT_RESTORE_CURSOR_AFTER_EDGE_SCROLL)
    )
    val restoreCursorAfterEdgeScroll: StateFlow<Boolean> = _restoreCursorAfterEdgeScroll.asStateFlow()

    private val _scrollDispatchIntervalMs = MutableStateFlow(
        prefs.getInt(KEY_SCROLL_DISPATCH_INTERVAL_MS, DEFAULT_SCROLL_DISPATCH_INTERVAL_MS)
    )
    val scrollDispatchIntervalMs: StateFlow<Int> = _scrollDispatchIntervalMs.asStateFlow()
    private val _flingDurationMs = MutableStateFlow(
        prefs.getInt(KEY_FLING_DURATION_MS, DEFAULT_FLING_DURATION_MS)
    )
    val flingDurationMs: StateFlow<Int> = _flingDurationMs.asStateFlow()
    private val _holdDragDelayMs = MutableStateFlow(
        prefs.getInt(KEY_HOLD_DRAG_DELAY_MS, DEFAULT_HOLD_DRAG_DELAY_MS)
    )
    val holdDragDelayMs: StateFlow<Int> = _holdDragDelayMs.asStateFlow()
    private val _tapToDragEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_TAP_TO_DRAG_ENABLED, DEFAULT_TAP_TO_DRAG_ENABLED)
    )
    val tapToDragEnabled: StateFlow<Boolean> = _tapToDragEnabled.asStateFlow()
    private val _dragStartSlopPx = MutableStateFlow(
        prefs.getFloat(KEY_DRAG_START_SLOP_PX, DEFAULT_DRAG_START_SLOP_PX)
    )
    val dragStartSlopPx: StateFlow<Float> = _dragStartSlopPx.asStateFlow()
    private val _dragFlickKickMs = MutableStateFlow(
        prefs.getInt(KEY_DRAG_FLICK_KICK_MS, DEFAULT_DRAG_FLICK_KICK_MS)
    )
    val dragFlickKickMs: StateFlow<Int> = _dragFlickKickMs.asStateFlow()
    private val _twoFingerScrollGraceMs = MutableStateFlow(
        prefs.getInt(KEY_TWO_FINGER_SCROLL_GRACE_MS, DEFAULT_TWO_FINGER_SCROLL_GRACE_MS)
    )
    val twoFingerScrollGraceMs: StateFlow<Int> = _twoFingerScrollGraceMs.asStateFlow()
    private val _holdDragSlopPx = MutableStateFlow(
        prefs.getFloat(KEY_HOLD_DRAG_SLOP_PX, DEFAULT_HOLD_DRAG_SLOP_PX)
    )
    val holdDragSlopPx: StateFlow<Float> = _holdDragSlopPx.asStateFlow()
    private val _doubleClickGuardMs = MutableStateFlow(
        prefs.getInt(KEY_DOUBLE_CLICK_GUARD_MS, DEFAULT_DOUBLE_CLICK_GUARD_MS)
    )
    val doubleClickGuardMs: StateFlow<Int> = _doubleClickGuardMs.asStateFlow()
    private val _oneFingerEdgeScrollEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_ONE_FINGER_EDGE_SCROLL_ENABLED, DEFAULT_ONE_FINGER_EDGE_SCROLL_ENABLED)
    )
    val oneFingerEdgeScrollEnabled: StateFlow<Boolean> = _oneFingerEdgeScrollEnabled.asStateFlow()
    private val _mouseAccelerationEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_MOUSE_ACCELERATION_ENABLED, DEFAULT_MOUSE_ACCELERATION_ENABLED)
    )
    val mouseAccelerationEnabled: StateFlow<Boolean> = _mouseAccelerationEnabled.asStateFlow()
    private val _hidMoveChunkSize = MutableStateFlow(
        prefs.getInt(KEY_HID_MOVE_CHUNK_SIZE, DEFAULT_HID_MOVE_CHUNK_SIZE)
    )
    val hidMoveChunkSize: StateFlow<Int> = _hidMoveChunkSize.asStateFlow()
    private val _pointerMoveDeadbandPx = MutableStateFlow(
        prefs.getFloat(KEY_POINTER_MOVE_DEADBAND_PX, DEFAULT_POINTER_MOVE_DEADBAND_PX)
    )
    val pointerMoveDeadbandPx: StateFlow<Float> = _pointerMoveDeadbandPx.asStateFlow()
    private val _autoStartOnBootEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_AUTO_START_ON_BOOT_ENABLED, DEFAULT_AUTO_START_ON_BOOT_ENABLED)
    )
    val autoStartOnBootEnabled: StateFlow<Boolean> = _autoStartOnBootEnabled.asStateFlow()
    private val _hapticTouchContactIntensity = MutableStateFlow(
        prefs.getInt(KEY_HAPTIC_TOUCH_CONTACT_INTENSITY, DEFAULT_HAPTIC_TOUCH_CONTACT_INTENSITY)
    )
    val hapticTouchContactIntensity: StateFlow<Int> = _hapticTouchContactIntensity.asStateFlow()
    private val _hapticClickIntensity = MutableStateFlow(
        prefs.getInt(KEY_HAPTIC_CLICK_INTENSITY, DEFAULT_HAPTIC_CLICK_INTENSITY)
    )
    val hapticClickIntensity: StateFlow<Int> = _hapticClickIntensity.asStateFlow()
    private val _hapticRightClickIntensity = MutableStateFlow(
        prefs.getInt(KEY_HAPTIC_RIGHT_CLICK_INTENSITY, DEFAULT_HAPTIC_RIGHT_CLICK_INTENSITY)
    )
    val hapticRightClickIntensity: StateFlow<Int> = _hapticRightClickIntensity.asStateFlow()
    private val _hapticDragStartIntensity = MutableStateFlow(
        prefs.getInt(KEY_HAPTIC_DRAG_START_INTENSITY, DEFAULT_HAPTIC_DRAG_START_INTENSITY)
    )
    val hapticDragStartIntensity: StateFlow<Int> = _hapticDragStartIntensity.asStateFlow()
    private val _hapticOverlayToggleIntensity = MutableStateFlow(
        prefs.getInt(KEY_HAPTIC_OVERLAY_TOGGLE_INTENSITY, DEFAULT_HAPTIC_OVERLAY_TOGGLE_INTENSITY)
    )
    val hapticOverlayToggleIntensity: StateFlow<Int> = _hapticOverlayToggleIntensity.asStateFlow()
    private val _hapticFaultIntensity = MutableStateFlow(
        prefs.getInt(KEY_HAPTIC_FAULT_INTENSITY, DEFAULT_HAPTIC_FAULT_INTENSITY)
    )
    val hapticFaultIntensity: StateFlow<Int> = _hapticFaultIntensity.asStateFlow()
    private val _hapticScrollStepIntensity = MutableStateFlow(
        prefs.getInt(KEY_HAPTIC_SCROLL_STEP_INTENSITY, DEFAULT_HAPTIC_SCROLL_STEP_INTENSITY)
    )
    val hapticScrollStepIntensity: StateFlow<Int> = _hapticScrollStepIntensity.asStateFlow()
    private val _hapticEdgeHitIntensity = MutableStateFlow(
        prefs.getInt(KEY_HAPTIC_EDGE_HIT_INTENSITY, DEFAULT_HAPTIC_EDGE_HIT_INTENSITY)
    )
    val hapticEdgeHitIntensity: StateFlow<Int> = _hapticEdgeHitIntensity.asStateFlow()
    private val _hapticEdgeScrollStartIntensity = MutableStateFlow(
        prefs.getInt(KEY_HAPTIC_EDGE_SCROLL_START_INTENSITY, DEFAULT_HAPTIC_EDGE_SCROLL_START_INTENSITY)
    )
    val hapticEdgeScrollStartIntensity: StateFlow<Int> = _hapticEdgeScrollStartIntensity.asStateFlow()
    private val _horizontalScrollInverted = MutableStateFlow(
        prefs.getBoolean(KEY_HORIZONTAL_SCROLL_INVERTED, DEFAULT_HORIZONTAL_SCROLL_INVERTED)
    )
    val horizontalScrollInverted: StateFlow<Boolean> = _horizontalScrollInverted.asStateFlow()
    private val _verticalScrollInverted = MutableStateFlow(
        prefs.getBoolean(KEY_VERTICAL_SCROLL_INVERTED, DEFAULT_VERTICAL_SCROLL_INVERTED)
    )
    val verticalScrollInverted: StateFlow<Boolean> = _verticalScrollInverted.asStateFlow()
    private val _edgeHorizontalScrollInverted = MutableStateFlow(loadEdgeHorizontalScrollInverted())
    val edgeHorizontalScrollInverted: StateFlow<Boolean> = _edgeHorizontalScrollInverted.asStateFlow()
    private val _edgeVerticalScrollInverted = MutableStateFlow(loadEdgeVerticalScrollInverted())
    val edgeVerticalScrollInverted: StateFlow<Boolean> = _edgeVerticalScrollInverted.asStateFlow()
    private val _sideEdgeScrollEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_SIDE_EDGE_SCROLL_ENABLED, DEFAULT_SIDE_EDGE_SCROLL_ENABLED)
    )
    val sideEdgeScrollEnabled: StateFlow<Boolean> = _sideEdgeScrollEnabled.asStateFlow()
    private val _bottomNavBarPassthroughEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_BOTTOM_NAV_BAR_PASSTHROUGH_ENABLED, DEFAULT_BOTTOM_NAV_BAR_PASSTHROUGH_ENABLED)
    )
    val bottomNavBarPassthroughEnabled: StateFlow<Boolean> = _bottomNavBarPassthroughEnabled.asStateFlow()
    private val _quickToggleOverlayEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_QUICK_TOGGLE_OVERLAY_ENABLED, DEFAULT_QUICK_TOGGLE_OVERLAY_ENABLED)
    )
    val quickToggleOverlayEnabled: StateFlow<Boolean> = _quickToggleOverlayEnabled.asStateFlow()
    private val _quickToggleRightAutoRotateEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_QUICK_TOGGLE_RIGHT_AUTO_ROTATE_ENABLED, DEFAULT_QUICK_TOGGLE_RIGHT_AUTO_ROTATE_ENABLED)
    )
    val quickToggleRightAutoRotateEnabled: StateFlow<Boolean> = _quickToggleRightAutoRotateEnabled.asStateFlow()
    private val _debugOverlayEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_DEBUG_OVERLAY_ENABLED, DEFAULT_DEBUG_OVERLAY_ENABLED)
    )
    val debugOverlayEnabled: StateFlow<Boolean> = _debugOverlayEnabled.asStateFlow()

    private val _endpointConfig = MutableStateFlow(EndpointConfig())
    val endpointConfig: StateFlow<EndpointConfig> = _endpointConfig.asStateFlow()

    fun setSpeedMultiplier(value: Float) {
        val normalized = if (value.isFinite()) value else _speedMultiplier.value
        prefs.edit().putFloat(KEY_SPEED_MULTIPLIER, normalized).apply()
        _speedMultiplier.value = normalized
    }

    fun setVerticalScrollPixelsPerStep(value: Float) {
        val normalized = if (value.isFinite()) value else _verticalScrollPixelsPerStep.value
        prefs.edit().putFloat(KEY_VERTICAL_SCROLL_PIXELS_PER_STEP, normalized).apply()
        _verticalScrollPixelsPerStep.value = normalized
    }

    fun setHorizontalScrollPixelsPerStep(value: Float) {
        val normalized = if (value.isFinite()) value else _horizontalScrollPixelsPerStep.value
        prefs.edit().putFloat(KEY_HORIZONTAL_SCROLL_PIXELS_PER_STEP, normalized).apply()
        _horizontalScrollPixelsPerStep.value = normalized
    }

    fun setVerticalFollowupStepFactor(value: Float) {
        val normalized = sanitizeFollowupFactor(value, _verticalFollowupStepFactor.value)
        prefs.edit().putFloat(KEY_VERTICAL_FOLLOWUP_STEP_FACTOR, normalized).apply()
        _verticalFollowupStepFactor.value = normalized
    }

    fun setHorizontalFollowupStepFactor(value: Float) {
        val normalized = sanitizeFollowupFactor(value, _horizontalFollowupStepFactor.value)
        prefs.edit().putFloat(KEY_HORIZONTAL_FOLLOWUP_STEP_FACTOR, normalized).apply()
        _horizontalFollowupStepFactor.value = normalized
    }

    fun setSyncIntervalMs(value: Int) {
        val normalized = value.coerceAtLeast(1)
        prefs.edit().putInt(KEY_SYNC_INTERVAL_MS, normalized).apply()
        _syncIntervalMs.value = normalized
    }

    fun setEdgeScrollRepeatDelayMs(value: Int) {
        val normalized = value.coerceAtLeast(0)
        prefs.edit().putInt(KEY_EDGE_SCROLL_REPEAT_DELAY_MS, normalized).apply()
        _edgeScrollRepeatDelayMs.value = normalized
    }

    fun setEdgeScrollJumpMultiplier(value: Float) {
        val normalized = sanitizePositiveFloat(value, _edgeScrollJumpMultiplier.value)
        prefs.edit().putFloat(KEY_EDGE_SCROLL_JUMP_MULTIPLIER, normalized).apply()
        _edgeScrollJumpMultiplier.value = normalized
    }

    fun setEdgeCornerDeadzonePx(value: Float) {
        // Compatibility shim for stale tests that still reference the old corner-deadzone setting.
        // Corner edge resolution is now fixed in code, not configurable through persisted settings.
        value.hashCode()
    }

    fun setCursorCalibrationUnitsPerPx(xUnitsPerPx: Float, yUnitsPerPx: Float, isLandscape: Boolean) {
        val normalizedX = if (xUnitsPerPx.isFinite()) xUnitsPerPx.coerceAtLeast(0f) else _cursorCalibrationUnitsPerPxX.value
        val normalizedY = if (yUnitsPerPx.isFinite()) yUnitsPerPx.coerceAtLeast(0f) else _cursorCalibrationUnitsPerPxY.value
        if (isLandscape) {
            prefs.edit()
                .putFloat(KEY_CURSOR_CALIBRATION_LANDSCAPE_UNITS_PER_PX_X, normalizedX)
                .putFloat(KEY_CURSOR_CALIBRATION_LANDSCAPE_UNITS_PER_PX_Y, normalizedY)
                .apply()
            _cursorCalibrationLandscapeUnitsPerPxX.value = normalizedX
            _cursorCalibrationLandscapeUnitsPerPxY.value = normalizedY
        } else {
            prefs.edit()
                .putFloat(KEY_CURSOR_CALIBRATION_UNITS_PER_PX_X, normalizedX)
                .putFloat(KEY_CURSOR_CALIBRATION_UNITS_PER_PX_Y, normalizedY)
                .apply()
            _cursorCalibrationUnitsPerPxX.value = normalizedX
            _cursorCalibrationUnitsPerPxY.value = normalizedY
        }
    }

    fun clearCursorCalibration() {
        prefs.edit()
            .remove(KEY_CURSOR_CALIBRATION_UNITS_PER_PX_X)
            .remove(KEY_CURSOR_CALIBRATION_UNITS_PER_PX_Y)
            .remove(KEY_CURSOR_CALIBRATION_LANDSCAPE_UNITS_PER_PX_X)
            .remove(KEY_CURSOR_CALIBRATION_LANDSCAPE_UNITS_PER_PX_Y)
            .apply()
        _cursorCalibrationUnitsPerPxX.value = CLEARED_CURSOR_CALIBRATION_UNITS_PER_PX_X
        _cursorCalibrationUnitsPerPxY.value = CLEARED_CURSOR_CALIBRATION_UNITS_PER_PX_Y
        _cursorCalibrationLandscapeUnitsPerPxX.value = CLEARED_CURSOR_CALIBRATION_UNITS_PER_PX_X
        _cursorCalibrationLandscapeUnitsPerPxY.value = CLEARED_CURSOR_CALIBRATION_UNITS_PER_PX_Y
    }

    fun setRestoreCursorAfterEdgeScroll(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RESTORE_CURSOR_AFTER_EDGE_SCROLL, enabled).apply()
        _restoreCursorAfterEdgeScroll.value = enabled
    }

    fun setScrollDispatchIntervalMs(value: Int) {
        val normalized = value
        prefs.edit().putInt(KEY_SCROLL_DISPATCH_INTERVAL_MS, normalized).apply()
        _scrollDispatchIntervalMs.value = normalized
    }

    fun setFlingDurationMs(value: Int) {
        val normalized = value
        prefs.edit().putInt(KEY_FLING_DURATION_MS, normalized).apply()
        _flingDurationMs.value = normalized
    }

    fun setHoldDragDelayMs(value: Int) {
        val normalized = value
        prefs.edit().putInt(KEY_HOLD_DRAG_DELAY_MS, normalized).apply()
        _holdDragDelayMs.value = normalized
    }

    fun setTapToDragEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TAP_TO_DRAG_ENABLED, enabled).apply()
        _tapToDragEnabled.value = enabled
    }

    fun setDragStartSlopPx(value: Float) {
        val normalized = if (value.isFinite()) value else _dragStartSlopPx.value
        prefs.edit().putFloat(KEY_DRAG_START_SLOP_PX, normalized).apply()
        _dragStartSlopPx.value = normalized
    }

    fun setDragFlickKickMs(value: Int) {
        val normalized = value.coerceAtLeast(0)
        prefs.edit().putInt(KEY_DRAG_FLICK_KICK_MS, normalized).apply()
        _dragFlickKickMs.value = normalized
    }

    fun setTwoFingerScrollGraceMs(value: Int) {
        val normalized = value
        prefs.edit().putInt(KEY_TWO_FINGER_SCROLL_GRACE_MS, normalized).apply()
        _twoFingerScrollGraceMs.value = normalized
    }

    fun setHoldDragSlopPx(value: Float) {
        val normalized = if (value.isFinite()) value else _holdDragSlopPx.value
        prefs.edit().putFloat(KEY_HOLD_DRAG_SLOP_PX, normalized).apply()
        _holdDragSlopPx.value = normalized
    }

    fun setDoubleClickGuardMs(value: Int) {
        val normalized = value
        prefs.edit().putInt(KEY_DOUBLE_CLICK_GUARD_MS, normalized).apply()
        _doubleClickGuardMs.value = normalized
    }

    fun setOneFingerEdgeScrollEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ONE_FINGER_EDGE_SCROLL_ENABLED, enabled).apply()
        _oneFingerEdgeScrollEnabled.value = enabled
    }

    fun setMouseAccelerationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MOUSE_ACCELERATION_ENABLED, enabled).apply()
        _mouseAccelerationEnabled.value = enabled
    }

    fun setHidMoveChunkSize(value: Int) {
        val normalized = value.coerceAtLeast(1)
        prefs.edit().putInt(KEY_HID_MOVE_CHUNK_SIZE, normalized).apply()
        _hidMoveChunkSize.value = normalized
    }

    fun setPointerMoveDeadbandPx(value: Float) {
        val normalized = if (value.isFinite()) value.coerceAtLeast(0f) else _pointerMoveDeadbandPx.value
        prefs.edit().putFloat(KEY_POINTER_MOVE_DEADBAND_PX, normalized).apply()
        _pointerMoveDeadbandPx.value = normalized
    }

    fun setAutoStartOnBootEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START_ON_BOOT_ENABLED, enabled).apply()
        _autoStartOnBootEnabled.value = enabled
    }

    fun setHapticTouchContactIntensity(value: Int) {
        val normalized = value.coerceIn(HAPTIC_INTENSITY_MIN, HAPTIC_INTENSITY_MAX)
        prefs.edit().putInt(KEY_HAPTIC_TOUCH_CONTACT_INTENSITY, normalized).apply()
        _hapticTouchContactIntensity.value = normalized
    }

    fun setHapticClickIntensity(value: Int) {
        val normalized = value.coerceIn(HAPTIC_INTENSITY_MIN, HAPTIC_INTENSITY_MAX)
        prefs.edit().putInt(KEY_HAPTIC_CLICK_INTENSITY, normalized).apply()
        _hapticClickIntensity.value = normalized
    }

    fun setHapticRightClickIntensity(value: Int) {
        val normalized = value.coerceIn(HAPTIC_INTENSITY_MIN, HAPTIC_INTENSITY_MAX)
        prefs.edit().putInt(KEY_HAPTIC_RIGHT_CLICK_INTENSITY, normalized).apply()
        _hapticRightClickIntensity.value = normalized
    }

    fun setHapticDragStartIntensity(value: Int) {
        val normalized = value.coerceIn(HAPTIC_INTENSITY_MIN, HAPTIC_INTENSITY_MAX)
        prefs.edit().putInt(KEY_HAPTIC_DRAG_START_INTENSITY, normalized).apply()
        _hapticDragStartIntensity.value = normalized
    }

    fun setHapticOverlayToggleIntensity(value: Int) {
        val normalized = value.coerceIn(HAPTIC_INTENSITY_MIN, HAPTIC_INTENSITY_MAX)
        prefs.edit().putInt(KEY_HAPTIC_OVERLAY_TOGGLE_INTENSITY, normalized).apply()
        _hapticOverlayToggleIntensity.value = normalized
    }

    fun setHapticFaultIntensity(value: Int) {
        val normalized = value.coerceIn(HAPTIC_INTENSITY_MIN, HAPTIC_INTENSITY_MAX)
        prefs.edit().putInt(KEY_HAPTIC_FAULT_INTENSITY, normalized).apply()
        _hapticFaultIntensity.value = normalized
    }

    fun setHapticScrollStepIntensity(value: Int) {
        val normalized = value.coerceIn(HAPTIC_INTENSITY_MIN, HAPTIC_INTENSITY_MAX)
        prefs.edit().putInt(KEY_HAPTIC_SCROLL_STEP_INTENSITY, normalized).apply()
        _hapticScrollStepIntensity.value = normalized
    }

    fun setHapticEdgeHitIntensity(value: Int) {
        val normalized = value.coerceIn(HAPTIC_INTENSITY_MIN, HAPTIC_INTENSITY_MAX)
        prefs.edit().putInt(KEY_HAPTIC_EDGE_HIT_INTENSITY, normalized).apply()
        _hapticEdgeHitIntensity.value = normalized
    }

    fun setHapticEdgeScrollStartIntensity(value: Int) {
        val normalized = value.coerceIn(HAPTIC_INTENSITY_MIN, HAPTIC_INTENSITY_MAX)
        prefs.edit().putInt(KEY_HAPTIC_EDGE_SCROLL_START_INTENSITY, normalized).apply()
        _hapticEdgeScrollStartIntensity.value = normalized
    }

    fun setHorizontalScrollInverted(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HORIZONTAL_SCROLL_INVERTED, enabled).apply()
        _horizontalScrollInverted.value = enabled
    }

    fun setVerticalScrollInverted(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VERTICAL_SCROLL_INVERTED, enabled).apply()
        _verticalScrollInverted.value = enabled
    }

    fun setEdgeHorizontalScrollInverted(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EDGE_HORIZONTAL_SCROLL_INVERTED, enabled).apply()
        _edgeHorizontalScrollInverted.value = enabled
    }

    fun setEdgeVerticalScrollInverted(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EDGE_VERTICAL_SCROLL_INVERTED, enabled).apply()
        _edgeVerticalScrollInverted.value = enabled
    }

    fun setSideEdgeScrollEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SIDE_EDGE_SCROLL_ENABLED, enabled).apply()
        _sideEdgeScrollEnabled.value = enabled
    }

    fun setBottomNavBarPassthroughEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BOTTOM_NAV_BAR_PASSTHROUGH_ENABLED, enabled).apply()
        _bottomNavBarPassthroughEnabled.value = enabled
    }

    fun setQuickToggleOverlayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QUICK_TOGGLE_OVERLAY_ENABLED, enabled).apply()
        _quickToggleOverlayEnabled.value = enabled
    }

    fun setQuickToggleRightAutoRotateEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QUICK_TOGGLE_RIGHT_AUTO_ROTATE_ENABLED, enabled).apply()
        _quickToggleRightAutoRotateEnabled.value = enabled
    }

    fun setDebugOverlayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_OVERLAY_ENABLED, enabled).apply()
        _debugOverlayEnabled.value = enabled
    }

    fun setEndpointConfig(host: String, port: Int) {
        _endpointConfig.value = EndpointConfig()
    }

    fun exportCurrentConfigAsIni(): String {
        fun StringBuilder.appendSection(name: String) {
            if (isNotEmpty()) {
                appendLine()
            }
            appendLine("[$name]")
        }

        fun StringBuilder.appendValue(key: String, value: Any) {
            append(key)
            append('=')
            appendLine(value.toString())
        }

        return buildString {
            appendLine("; ADB Touchpad configuration export")
            appendLine("; Generated from current in-app settings")

            appendSection("pointer")
            appendValue("speed_multiplier", speedMultiplier.value)
            appendValue("mouse_acceleration_enabled", mouseAccelerationEnabled.value)
            appendValue("hid_move_chunk_size", hidMoveChunkSize.value)
            appendValue("pointer_move_deadband_px", pointerMoveDeadbandPx.value)
            appendValue("portrait_cursor_calibration_units_per_px_x", cursorCalibrationUnitsPerPxX.value)
            appendValue("portrait_cursor_calibration_units_per_px_y", cursorCalibrationUnitsPerPxY.value)
            appendValue("landscape_cursor_calibration_units_per_px_x", cursorCalibrationLandscapeUnitsPerPxX.value)
            appendValue("landscape_cursor_calibration_units_per_px_y", cursorCalibrationLandscapeUnitsPerPxY.value)

            appendSection("scroll")
            appendValue("vertical_scroll_pixels_per_step", verticalScrollPixelsPerStep.value)
            appendValue("horizontal_scroll_pixels_per_step", horizontalScrollPixelsPerStep.value)
            appendValue("vertical_followup_step_factor", verticalFollowupStepFactor.value)
            appendValue("horizontal_followup_step_factor", horizontalFollowupStepFactor.value)
            appendValue("two_finger_scroll_grace_px", twoFingerScrollGraceMs.value)
            appendValue("scroll_dispatch_interval_ms", scrollDispatchIntervalMs.value)
            appendValue("fling_duration_ms", flingDurationMs.value)
            appendValue("one_finger_edge_scroll_enabled", oneFingerEdgeScrollEnabled.value)
            appendValue("side_edge_scroll_enabled", sideEdgeScrollEnabled.value)
            appendValue("edge_scroll_activation_distance_px", edgeScrollRepeatDelayMs.value)
            appendValue("edge_scroll_jump_multiplier", edgeScrollJumpMultiplier.value)
            appendValue("restore_cursor_after_edge_scroll", restoreCursorAfterEdgeScroll.value)
            appendValue("horizontal_two_finger_scroll_inverted", horizontalScrollInverted.value)
            appendValue("vertical_two_finger_scroll_inverted", verticalScrollInverted.value)
            appendValue("horizontal_edge_scroll_inverted", edgeHorizontalScrollInverted.value)
            appendValue("vertical_edge_scroll_inverted", edgeVerticalScrollInverted.value)

            appendSection("drag")
            appendValue("tap_to_drag_enabled", tapToDragEnabled.value)
            appendValue("hold_drag_delay_ms", holdDragDelayMs.value)
            appendValue("drag_start_slop_px", dragStartSlopPx.value)
            appendValue("hold_drag_slop_px", holdDragSlopPx.value)
            appendValue("drag_release_inertia_ms", dragFlickKickMs.value)
            appendValue("double_click_guard_ms", doubleClickGuardMs.value)

            appendSection("haptics")
            appendValue("touch_contact_intensity", hapticTouchContactIntensity.value)
            appendValue("click_intensity", hapticClickIntensity.value)
            appendValue("right_click_intensity", hapticRightClickIntensity.value)
            appendValue("drag_start_intensity", hapticDragStartIntensity.value)
            appendValue("overlay_toggle_intensity", hapticOverlayToggleIntensity.value)
            appendValue("fault_intensity", hapticFaultIntensity.value)
            appendValue("scroll_step_intensity", hapticScrollStepIntensity.value)
            appendValue("edge_hit_intensity", hapticEdgeHitIntensity.value)
            appendValue("edge_scroll_start_intensity", hapticEdgeScrollStartIntensity.value)

            appendSection("system")
            appendValue("sync_interval_ms", syncIntervalMs.value)
            appendValue("bottom_nav_bar_passthrough_enabled", bottomNavBarPassthroughEnabled.value)
            appendValue("auto_start_on_boot_enabled", autoStartOnBootEnabled.value)
            appendValue("debug_overlay_enabled", debugOverlayEnabled.value)
            appendValue("quick_toggle_overlay_enabled", quickToggleOverlayEnabled.value)
            appendValue("quick_toggle_right_auto_rotate_enabled", quickToggleRightAutoRotateEnabled.value)
        }
    }

    private fun loadVerticalScrollPixelsPerStep(): Float {
        val raw = when {
            prefs.contains(KEY_VERTICAL_SCROLL_PIXELS_PER_STEP) -> {
                prefs.getFloat(KEY_VERTICAL_SCROLL_PIXELS_PER_STEP, DEFAULT_VERTICAL_SCROLL_PIXELS_PER_STEP)
            }

            prefs.contains(KEY_SCROLL_MULTIPLIER) -> {
                legacySensitivityToPixels(
                    raw = prefs.getFloat(KEY_SCROLL_MULTIPLIER, LEGACY_DEFAULT_SCROLL_SENSITIVITY),
                    defaultPixels = DEFAULT_VERTICAL_SCROLL_PIXELS_PER_STEP,
                )
            }

            else -> DEFAULT_VERTICAL_SCROLL_PIXELS_PER_STEP
        }
        val normalized = if (raw.isFinite()) raw else DEFAULT_VERTICAL_SCROLL_PIXELS_PER_STEP
        prefs.edit().putFloat(KEY_VERTICAL_SCROLL_PIXELS_PER_STEP, normalized).apply()
        return normalized
    }

    private fun loadHorizontalScrollPixelsPerStep(): Float {
        val raw = when {
            prefs.contains(KEY_HORIZONTAL_SCROLL_PIXELS_PER_STEP) -> {
                prefs.getFloat(KEY_HORIZONTAL_SCROLL_PIXELS_PER_STEP, DEFAULT_HORIZONTAL_SCROLL_PIXELS_PER_STEP)
            }

            prefs.contains(KEY_HORIZONTAL_SCROLL_MULTIPLIER) -> {
                legacySensitivityToPixels(
                    raw = prefs.getFloat(KEY_HORIZONTAL_SCROLL_MULTIPLIER, LEGACY_DEFAULT_HORIZONTAL_SCROLL_SENSITIVITY),
                    defaultPixels = DEFAULT_HORIZONTAL_SCROLL_PIXELS_PER_STEP,
                )
            }

            else -> DEFAULT_HORIZONTAL_SCROLL_PIXELS_PER_STEP
        }
        val normalized = if (raw.isFinite()) raw else DEFAULT_HORIZONTAL_SCROLL_PIXELS_PER_STEP
        prefs.edit().putFloat(KEY_HORIZONTAL_SCROLL_PIXELS_PER_STEP, normalized).apply()
        return normalized
    }

    private fun loadEdgeScrollJumpMultiplier(): Float {
        val raw = prefs.getFloat(KEY_EDGE_SCROLL_JUMP_MULTIPLIER, DEFAULT_EDGE_SCROLL_JUMP_MULTIPLIER)
        val normalized = sanitizePositiveFloat(raw, DEFAULT_EDGE_SCROLL_JUMP_MULTIPLIER)
        prefs.edit().putFloat(KEY_EDGE_SCROLL_JUMP_MULTIPLIER, normalized).apply()
        return normalized
    }

    private fun legacySensitivityToPixels(raw: Float, defaultPixels: Float): Float {
        if (!raw.isFinite() || raw <= 0f) {
            return defaultPixels
        }
        if (raw > LEGACY_SENSITIVITY_MAX) {
            return raw
        }
        val legacyClamped = raw.coerceAtLeast(LEGACY_SENSITIVITY_MIN)
        return LEGACY_PIXELS_REFERENCE / legacyClamped
    }

    private fun loadVerticalFollowupStepFactor(): Float {
        val raw = prefs.getFloat(KEY_VERTICAL_FOLLOWUP_STEP_FACTOR, DEFAULT_VERTICAL_FOLLOWUP_STEP_FACTOR)
        val normalized = sanitizeFollowupFactor(raw, DEFAULT_VERTICAL_FOLLOWUP_STEP_FACTOR)
        prefs.edit().putFloat(KEY_VERTICAL_FOLLOWUP_STEP_FACTOR, normalized).apply()
        return normalized
    }

    private fun loadHorizontalFollowupStepFactor(): Float {
        val raw = prefs.getFloat(KEY_HORIZONTAL_FOLLOWUP_STEP_FACTOR, DEFAULT_HORIZONTAL_FOLLOWUP_STEP_FACTOR)
        val normalized = sanitizeFollowupFactor(raw, DEFAULT_HORIZONTAL_FOLLOWUP_STEP_FACTOR)
        prefs.edit().putFloat(KEY_HORIZONTAL_FOLLOWUP_STEP_FACTOR, normalized).apply()
        return normalized
    }

    private fun sanitizeFollowupFactor(raw: Float, fallback: Float): Float {
        val candidate = if (raw.isFinite()) raw else fallback
        if (candidate <= 0f) {
            return fallback
        }
        return candidate.coerceIn(MIN_FOLLOWUP_STEP_FACTOR, MAX_FOLLOWUP_STEP_FACTOR)
    }

    private fun sanitizePositiveFloat(raw: Float, fallback: Float): Float {
        val candidate = if (raw.isFinite()) raw else fallback
        return if (candidate > 0f) candidate else fallback
    }

    private fun loadEdgeHorizontalScrollInverted(): Boolean {
        return if (prefs.contains(KEY_EDGE_HORIZONTAL_SCROLL_INVERTED)) {
            prefs.getBoolean(KEY_EDGE_HORIZONTAL_SCROLL_INVERTED, DEFAULT_EDGE_HORIZONTAL_SCROLL_INVERTED)
        } else {
            prefs.getBoolean(KEY_HORIZONTAL_SCROLL_INVERTED, DEFAULT_HORIZONTAL_SCROLL_INVERTED)
        }
    }

    private fun loadEdgeVerticalScrollInverted(): Boolean {
        return if (prefs.contains(KEY_EDGE_VERTICAL_SCROLL_INVERTED)) {
            prefs.getBoolean(KEY_EDGE_VERTICAL_SCROLL_INVERTED, DEFAULT_EDGE_VERTICAL_SCROLL_INVERTED)
        } else {
            prefs.getBoolean(KEY_VERTICAL_SCROLL_INVERTED, DEFAULT_VERTICAL_SCROLL_INVERTED)
        }
    }

    private companion object {
        const val KEY_SPEED_MULTIPLIER = "speed_multiplier"
        const val KEY_VERTICAL_SCROLL_PIXELS_PER_STEP = "vertical_scroll_pixels_per_step"
        const val KEY_HORIZONTAL_SCROLL_PIXELS_PER_STEP = "horizontal_scroll_pixels_per_step"
        const val KEY_VERTICAL_FOLLOWUP_STEP_FACTOR = "vertical_followup_step_factor"
        const val KEY_HORIZONTAL_FOLLOWUP_STEP_FACTOR = "horizontal_followup_step_factor"
        const val KEY_SYNC_INTERVAL_MS = "sync_interval_ms"
        const val KEY_EDGE_SCROLL_REPEAT_DELAY_MS = "edge_scroll_repeat_delay_ms"
        const val KEY_EDGE_SCROLL_JUMP_MULTIPLIER = "edge_scroll_jump_multiplier"
        const val KEY_CURSOR_CALIBRATION_UNITS_PER_PX_X = "cursor_calibration_units_per_px_x"
        const val KEY_CURSOR_CALIBRATION_UNITS_PER_PX_Y = "cursor_calibration_units_per_px_y"
        const val KEY_CURSOR_CALIBRATION_LANDSCAPE_UNITS_PER_PX_X = "cursor_calibration_landscape_units_per_px_x"
        const val KEY_CURSOR_CALIBRATION_LANDSCAPE_UNITS_PER_PX_Y = "cursor_calibration_landscape_units_per_px_y"
        const val KEY_RESTORE_CURSOR_AFTER_EDGE_SCROLL = "restore_cursor_after_edge_scroll"
        const val KEY_SCROLL_MULTIPLIER = "scroll_multiplier"
        const val KEY_HORIZONTAL_SCROLL_MULTIPLIER = "horizontal_scroll_multiplier"
        const val KEY_SCROLL_DISPATCH_INTERVAL_MS = "scroll_dispatch_interval_ms"
        const val KEY_FLING_DURATION_MS = "fling_duration_ms"
        const val KEY_HOLD_DRAG_DELAY_MS = "hold_drag_delay_ms"
        const val KEY_TAP_TO_DRAG_ENABLED = "tap_to_drag_enabled"
        const val KEY_DRAG_START_SLOP_PX = "drag_start_slop_px"
        const val KEY_DRAG_FLICK_KICK_MS = "drag_flick_kick_ms"
        const val KEY_TWO_FINGER_SCROLL_GRACE_MS = "two_finger_scroll_grace_ms"
        const val KEY_HOLD_DRAG_SLOP_PX = "hold_drag_slop_px"
        const val KEY_DOUBLE_CLICK_GUARD_MS = "double_click_guard_ms"
        const val KEY_ONE_FINGER_EDGE_SCROLL_ENABLED = "one_finger_edge_scroll_enabled"
        const val KEY_MOUSE_ACCELERATION_ENABLED = "mouse_acceleration_enabled"
        const val KEY_HID_MOVE_CHUNK_SIZE = "hid_move_chunk_size"
        const val KEY_POINTER_MOVE_DEADBAND_PX = "pointer_move_deadband_px"
        const val KEY_AUTO_START_ON_BOOT_ENABLED = "auto_start_on_boot_enabled"
        const val KEY_HAPTIC_TOUCH_CONTACT_INTENSITY = "haptic_touch_contact_intensity"
        const val KEY_HAPTIC_CLICK_INTENSITY = "haptic_click_intensity"
        const val KEY_HAPTIC_RIGHT_CLICK_INTENSITY = "haptic_right_click_intensity"
        const val KEY_HAPTIC_DRAG_START_INTENSITY = "haptic_drag_start_intensity"
        const val KEY_HAPTIC_OVERLAY_TOGGLE_INTENSITY = "haptic_overlay_toggle_intensity"
        const val KEY_HAPTIC_FAULT_INTENSITY = "haptic_fault_intensity"
        const val KEY_HAPTIC_SCROLL_STEP_INTENSITY = "haptic_scroll_step_intensity"
        const val KEY_HAPTIC_EDGE_HIT_INTENSITY = "haptic_edge_hit_intensity"
        const val KEY_HAPTIC_EDGE_SCROLL_START_INTENSITY = "haptic_edge_scroll_start_intensity"
        const val KEY_HORIZONTAL_SCROLL_INVERTED = "horizontal_scroll_inverted"
        const val KEY_VERTICAL_SCROLL_INVERTED = "vertical_scroll_inverted"
        const val KEY_EDGE_HORIZONTAL_SCROLL_INVERTED = "edge_horizontal_scroll_inverted"
        const val KEY_EDGE_VERTICAL_SCROLL_INVERTED = "edge_vertical_scroll_inverted"
        const val KEY_SIDE_EDGE_SCROLL_ENABLED = "side_edge_scroll_enabled"
        const val KEY_BOTTOM_NAV_BAR_PASSTHROUGH_ENABLED = "bottom_nav_bar_passthrough_enabled"
        const val KEY_DEBUG_OVERLAY_ENABLED = "debug_overlay_enabled"
        const val KEY_QUICK_TOGGLE_OVERLAY_ENABLED = "quick_toggle_overlay_enabled"
        const val KEY_QUICK_TOGGLE_RIGHT_AUTO_ROTATE_ENABLED = "quick_toggle_right_auto_rotate_enabled"
        const val DEFAULT_ENDPOINT_HOST = "127.0.0.1"
        const val DEFAULT_ENDPOINT_PORT = 53535
        const val DEFAULT_VERTICAL_SCROLL_PIXELS_PER_STEP = 40f
        const val DEFAULT_HORIZONTAL_SCROLL_PIXELS_PER_STEP = 40f
        const val DEFAULT_VERTICAL_FOLLOWUP_STEP_FACTOR = 1.2f
        const val DEFAULT_HORIZONTAL_FOLLOWUP_STEP_FACTOR = 2.0f
        const val DEFAULT_SYNC_INTERVAL_MS = 50
        const val DEFAULT_EDGE_SCROLL_REPEAT_DELAY_MS = 50
        const val DEFAULT_EDGE_SCROLL_JUMP_MULTIPLIER = 0.7f
        const val DEFAULT_PORTRAIT_CURSOR_CALIBRATION_UNITS_PER_PX_X = 0.14583333f
        const val DEFAULT_PORTRAIT_CURSOR_CALIBRATION_UNITS_PER_PX_Y = 0.14054404f
        const val DEFAULT_LANDSCAPE_CURSOR_CALIBRATION_UNITS_PER_PX_X = 0f
        const val DEFAULT_LANDSCAPE_CURSOR_CALIBRATION_UNITS_PER_PX_Y = 0f
        const val CLEARED_CURSOR_CALIBRATION_UNITS_PER_PX_X = 0f
        const val CLEARED_CURSOR_CALIBRATION_UNITS_PER_PX_Y = 0f
        const val DEFAULT_RESTORE_CURSOR_AFTER_EDGE_SCROLL = true
        const val LEGACY_DEFAULT_SCROLL_SENSITIVITY = 0.10f
        const val LEGACY_DEFAULT_HORIZONTAL_SCROLL_SENSITIVITY = 0.05f
        const val LEGACY_SENSITIVITY_MAX = 4f
        const val LEGACY_SENSITIVITY_MIN = 0.0001f
        const val LEGACY_PIXELS_REFERENCE = 4f
        const val DEFAULT_SCROLL_DISPATCH_INTERVAL_MS = 2
        const val DEFAULT_FLING_DURATION_MS = 100
        const val DEFAULT_HOLD_DRAG_DELAY_MS = 200
        const val DEFAULT_TAP_TO_DRAG_ENABLED = false
        const val DEFAULT_DRAG_START_SLOP_PX = 8f
        const val DEFAULT_DRAG_FLICK_KICK_MS = 50
        const val DEFAULT_TWO_FINGER_SCROLL_GRACE_MS = 24
        const val DEFAULT_HOLD_DRAG_SLOP_PX = 14f
        const val DEFAULT_DOUBLE_CLICK_GUARD_MS = 0
        const val DEFAULT_ONE_FINGER_EDGE_SCROLL_ENABLED = true
        const val DEFAULT_MOUSE_ACCELERATION_ENABLED = false
        const val DEFAULT_HID_MOVE_CHUNK_SIZE = 8
        const val DEFAULT_POINTER_MOVE_DEADBAND_PX = 0.5f
        const val DEFAULT_AUTO_START_ON_BOOT_ENABLED = false
        const val DEFAULT_HAPTIC_TOUCH_CONTACT_INTENSITY = 0
        const val DEFAULT_HAPTIC_CLICK_INTENSITY = 0
        const val DEFAULT_HAPTIC_RIGHT_CLICK_INTENSITY = 175
        const val DEFAULT_HAPTIC_DRAG_START_INTENSITY = 170
        const val DEFAULT_HAPTIC_OVERLAY_TOGGLE_INTENSITY = 140
        const val DEFAULT_HAPTIC_FAULT_INTENSITY = 220
        const val DEFAULT_HAPTIC_SCROLL_STEP_INTENSITY = 50
        const val DEFAULT_HAPTIC_EDGE_HIT_INTENSITY = 120
        const val DEFAULT_HAPTIC_EDGE_SCROLL_START_INTENSITY = 200
        const val MIN_FOLLOWUP_STEP_FACTOR = 1f
        const val MAX_FOLLOWUP_STEP_FACTOR = 8f
        const val HAPTIC_INTENSITY_MIN = 0
        const val HAPTIC_INTENSITY_MAX = 255
        const val DEFAULT_HORIZONTAL_SCROLL_INVERTED = true
        const val DEFAULT_VERTICAL_SCROLL_INVERTED = false
        const val DEFAULT_EDGE_HORIZONTAL_SCROLL_INVERTED = false
        const val DEFAULT_EDGE_VERTICAL_SCROLL_INVERTED = false
        const val DEFAULT_SIDE_EDGE_SCROLL_ENABLED = true
        const val DEFAULT_BOTTOM_NAV_BAR_PASSTHROUGH_ENABLED = true
        const val DEFAULT_DEBUG_OVERLAY_ENABLED = false
        const val DEFAULT_QUICK_TOGGLE_OVERLAY_ENABLED = true
        const val DEFAULT_QUICK_TOGGLE_RIGHT_AUTO_ROTATE_ENABLED = false
    }
}
