package com.alex.touchpad.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.database.ContentObserver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import com.alex.touchpad.core.AppLog as Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.alex.touchpad.TouchpadApplication
import com.alex.touchpad.core.AppMode
import com.alex.touchpad.core.AppContainer
import com.alex.touchpad.ui.MainActivity
import com.alex.touchpad.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private data class RuntimeGateSnapshot(
    val desired: Boolean,
    val connected: Boolean,
    val paired: Boolean,
    val mode: AppMode,
    val quickToggleOverlayEnabled: Boolean,
    val quickToggleRightAutoRotateEnabled: Boolean,
)

private data class QuickToggleState(
    val active: Boolean,
    val ready: Boolean,
)

private enum class QuickToggleSlot {
    PRIMARY,
    SECONDARY,
}

private data class QuickTogglePlacement(
    val primaryGravity: Int,
    val secondaryGravity: Int,
)

private enum class QuickToggleMode {
    TOUCH_CAPTURE,
    AUTO_ROTATE,
}

class TouchpadAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowManager by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private var collectJob: Job? = null

    private var appContainer: AppContainer? = null
    private var touchInteractionCaptureController: TouchInteractionCaptureController? = null
    private var leftQuickToggleView: QuickToggleView? = null
    private var rightQuickToggleView: QuickToggleView? = null
    private var leftQuickToggleAttached = false
    private var rightQuickToggleAttached = false
    private var captureRequestedByRuntime = false
    private var isBiometricPromptActive = false
    private var lastBiometricSeenAtMs: Long = 0L
    private var isForegroundNotificationActive = false
    private var autoRotateObserverRegistered = false

    private val autoRotateObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshQuickToggleOverlays()
        }
    }

    override fun onServiceConnected() {
        Log.i(TAG, "onServiceConnected")
        instance = this
        val container = (application as TouchpadApplication).appContainer
        appContainer = container
        registerAutoRotateObserver()
        ensureForegroundNotification()
        updateServiceFlags(captureEnabled = false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            touchInteractionCaptureController = TouchInteractionCaptureController(
                service = this,
                touchpadEngine = container.touchpadEngine,
                actionRouter = container.actionRouter,
                cursorStateStore = container.cursorStateStore,
                settingsRepository = container.settingsRepository,
                runtimeModeState = container.runtimeStateMachine.mode,
                sessionConnectedState = container.sessionManager.isConnected,
                overlayDesiredState = container.overlayDesired,
                localBackendState = container.localBackendState,
                executorStatusState = container.localExecutorStatus,
                edgeHighlightState = container.edgeHighlightState,
                cursorGroundTruthState = container.cursorGroundTruth,
                shellDaemonActiveState = container.shellDaemonActive,
                hidScrollDebugHistoryState = container.hidScrollDebugHistory,
                edgeSwipeDebugHistoryState = container.edgeSwipeDebugHistory,
                overlayAttachedState = container.overlayAttached,
                feedbackModule = container.feedbackModule,
            )
        }

        collectJob?.cancel()
        collectJob = serviceScope.launch {
            combine(
                container.overlayDesired,
                container.sessionManager.isConnected,
                container.wirelessDebuggingPaired,
                container.runtimeStateMachine.mode,
                container.settingsRepository.quickToggleOverlayEnabled,
                container.settingsRepository.quickToggleRightAutoRotateEnabled,
            ) { values ->
                val desired = values[0] as Boolean
                val connected = values[1] as Boolean
                val paired = values[2] as Boolean?
                val mode = values[3] as AppMode
                val quickToggleEnabled = values[4] as Boolean
                val quickToggleRightAutoRotateEnabled = values[5] as Boolean
                Log.i(
                    TAG,
                    "gate desired=$desired connected=$connected paired=$paired mode=$mode quickToggleOverlayEnabled=$quickToggleEnabled quickToggleRightAutoRotateEnabled=$quickToggleRightAutoRotateEnabled biometric=$isBiometricPromptActive",
                )
                RuntimeGateSnapshot(
                    desired = desired,
                    connected = connected,
                    paired = paired == true,
                    mode = mode,
                    quickToggleOverlayEnabled = quickToggleEnabled,
                    quickToggleRightAutoRotateEnabled = quickToggleRightAutoRotateEnabled,
                )
            }.collect { snapshot ->
                if (!snapshot.paired && container.overlayDesired.value) {
                    container.overlayDesired.value = false
                }
                val shouldEnable = snapshot.desired &&
                    snapshot.connected &&
                    snapshot.paired &&
                    snapshot.mode != AppMode.FAULT
                captureRequestedByRuntime = shouldEnable
                reconcileTouchCapture(container, reason = "runtime_gate")
                refreshQuickToggleOverlays(snapshot)
            }
        }
        refreshQuickToggleOverlays()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        val type = safeEvent.eventType
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            touchInteractionCaptureController?.updateImeBounds(resolveImeBounds())
        }
        if (
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            maybeHandleBiometricEvent(safeEvent)
        }
        if (
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            refreshQuickToggleOverlays()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        if (instance === this) {
            instance = null
        }
        touchInteractionCaptureController?.disable()
        updateServiceFlags(captureEnabled = false)
        collectJob?.cancel()
        serviceScope.cancel()
        unregisterAutoRotateObserver()
        detachQuickToggleOverlays()
        stopForegroundNotification()
        super.onDestroy()
    }

    fun requestManualReconcile(reason: String = "manual") {
        val container = appContainer ?: return
        val shouldRequest = container.overlayDesired.value &&
            container.sessionManager.isConnected.value &&
            container.wirelessDebuggingPaired.value == true &&
            container.runtimeStateMachine.mode.value != AppMode.FAULT
        captureRequestedByRuntime = shouldRequest
        reconcileTouchCapture(container, reason = reason)
        refreshQuickToggleOverlays()
    }

    private fun resolveImeBounds(): Rect? {
        val imeWindow = runCatching { windows }
            .getOrDefault(emptyList())
            .firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?: return null
        val bounds = Rect()
        imeWindow.getBoundsInScreen(bounds)
        return bounds
    }

    private fun maybeHandleBiometricEvent(event: AccessibilityEvent) {
        val className = event.className?.toString().orEmpty()
        val packageName = event.packageName?.toString().orEmpty()
        val matchedBiometricClass = className in BIOMETRIC_WINDOW_CLASSES &&
            packageName == SYSTEM_UI_PACKAGE
        val container = appContainer ?: return
        val now = SystemClock.elapsedRealtime()

        if (
            isBiometricPromptActive &&
            !matchedBiometricClass &&
            (now - lastBiometricSeenAtMs) > BIOMETRIC_PROMPT_ACTIVE_TTL_MS
        ) {
            isBiometricPromptActive = false
            Log.i(TAG, "Biometric hold cleared by event ttl; restoring touch capture")
            reconcileTouchCapture(container, reason = "biometric_ttl_event")
        }

        if (matchedBiometricClass) {
            lastBiometricSeenAtMs = now
            if (!isBiometricPromptActive) {
                isBiometricPromptActive = true
                Log.i(
                    TAG,
                    "Biometric prompt detected class=$className package=$packageName; disabling touch capture",
                )
                reconcileTouchCapture(container, reason = "biometric_active")
            }
            return
        }

        if (
            isBiometricPromptActive &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            packageName.isNotBlank() &&
            packageName != SYSTEM_UI_PACKAGE
        ) {
            isBiometricPromptActive = false
            Log.i(
                TAG,
                "Biometric prompt dismissed class=$className package=$packageName; restoring touch capture",
            )
            reconcileTouchCapture(container, reason = "biometric_dismissed")
        }
    }

    private fun reconcileTouchCapture(container: AppContainer, reason: String) {
        if (
            isBiometricPromptActive &&
            (SystemClock.elapsedRealtime() - lastBiometricSeenAtMs) > BIOMETRIC_PROMPT_ACTIVE_TTL_MS
        ) {
            isBiometricPromptActive = false
            Log.i(TAG, "Biometric hold cleared by ttl; restoring touch capture eligibility")
        }
        val shouldEnable = captureRequestedByRuntime && !isBiometricPromptActive
        Log.i(TAG, "reconcile capture shouldEnable=$shouldEnable reason=$reason")
        if (shouldEnable) {
            val touchController = touchInteractionCaptureController
            if (touchController != null) {
                updateServiceFlags(captureEnabled = true)
                val touchInteractionEnabled = touchController.enable()
                if (!touchInteractionEnabled) {
                    updateServiceFlags(captureEnabled = false)
                    container.overlayAttached.value = false
                    container.overlayDesired.value = false
                    Log.w(TAG, "Touch-capture enable failed; disabling desired state")
                }
            } else {
                updateServiceFlags(captureEnabled = false)
                container.overlayAttached.value = false
                container.overlayDesired.value = false
                Log.w(TAG, "Touch-capture unsupported on this Android version; disabling desired state")
            }
            return
        }

        touchInteractionCaptureController?.disable()
        container.overlayAttached.value = false
        updateServiceFlags(captureEnabled = false)
        refreshQuickToggleOverlays()
    }

    private fun refreshQuickToggleOverlays(snapshot: RuntimeGateSnapshot? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            detachQuickToggleOverlays()
            return
        }
        val container = appContainer ?: run {
            detachQuickToggleOverlays()
            return
        }
        val windowInsets = runCatching { windowManager.currentWindowMetrics.windowInsets }.getOrNull()
        val navVisible = windowInsets?.isVisible(WindowInsets.Type.navigationBars()) == true
        val navInsets = windowInsets?.getInsets(WindowInsets.Type.navigationBars()) ?: Insets.NONE
        val navThickness = maxOf(navInsets.bottom, navInsets.top, navInsets.left, navInsets.right)
        if (!navVisible || navThickness <= 0) {
            detachQuickToggleOverlays()
            return
        }

        val currentSnapshot = snapshot ?: RuntimeGateSnapshot(
            desired = container.overlayDesired.value,
            connected = container.sessionManager.isConnected.value,
            paired = container.wirelessDebuggingPaired.value == true,
            mode = container.runtimeStateMachine.mode.value,
            quickToggleOverlayEnabled = container.settingsRepository.quickToggleOverlayEnabled.value,
            quickToggleRightAutoRotateEnabled = container.settingsRepository.quickToggleRightAutoRotateEnabled.value,
        )
        if (!currentSnapshot.quickToggleOverlayEnabled) {
            detachQuickToggleOverlays()
            return
        }
        val captureToggleState = QuickToggleState(
            active = container.overlayDesired.value || container.overlayAttached.value,
            ready = currentSnapshot.connected && currentSnapshot.paired && currentSnapshot.mode != AppMode.FAULT,
        )
        val autoRotateToggleState = QuickToggleState(
            active = readAutoRotateEnabled(),
            ready = true,
        )
        val buttonSizePx = navThickness.coerceAtLeast(dpToPx(48f)).coerceAtMost(dpToPx(72f))
        val placement = resolveQuickTogglePlacement(navInsets)

        val leftView = leftQuickToggleView ?: QuickToggleView(this).also { leftQuickToggleView = it }
        val rightView = rightQuickToggleView ?: QuickToggleView(this).also { rightQuickToggleView = it }

        leftView.configure(
            mode = QuickToggleMode.TOUCH_CAPTURE,
            activeIconRes = R.drawable.quick_toggle_cursor,
            inactiveIconRes = R.drawable.quick_toggle_touch,
            onToggle = ::toggleTouchCaptureFromQuickOverlay,
        )
        leftView.update(captureToggleState)
        if (currentSnapshot.quickToggleRightAutoRotateEnabled) {
            rightView.configure(
                mode = QuickToggleMode.AUTO_ROTATE,
                activeIconRes = R.drawable.quick_toggle_rotate,
                inactiveIconRes = R.drawable.quick_toggle_norotate,
                onToggle = ::toggleAutoRotateFromQuickOverlay,
            )
            rightView.update(autoRotateToggleState)
        } else {
            rightView.configure(
                mode = QuickToggleMode.TOUCH_CAPTURE,
                activeIconRes = R.drawable.quick_toggle_cursor,
                inactiveIconRes = R.drawable.quick_toggle_touch,
                onToggle = ::toggleTouchCaptureFromQuickOverlay,
            )
            rightView.update(captureToggleState)
        }
        ensureQuickToggleView(
            view = leftView,
            slot = QuickToggleSlot.PRIMARY,
            gravity = placement.primaryGravity,
            widthPx = buttonSizePx,
            heightPx = buttonSizePx,
            title = QUICK_TOGGLE_LEFT_TITLE,
        )
        ensureQuickToggleView(
            view = rightView,
            slot = QuickToggleSlot.SECONDARY,
            gravity = placement.secondaryGravity,
            widthPx = buttonSizePx,
            heightPx = buttonSizePx,
            title = QUICK_TOGGLE_RIGHT_TITLE,
        )
    }

    private fun toggleTouchCaptureFromQuickOverlay() {
        val container = appContainer ?: return
        val canEnable = container.sessionManager.isConnected.value &&
            container.wirelessDebuggingPaired.value == true &&
            container.runtimeStateMachine.mode.value != AppMode.FAULT
        val active = container.overlayDesired.value || container.overlayAttached.value
        if (active) {
            container.overlayDesired.value = false
            requestManualReconcile(reason = "quick_toggle_disable")
            return
        }
        if (!canEnable) {
            refreshQuickToggleOverlays()
            return
        }
        container.overlayDesired.value = true
        requestManualReconcile(reason = "quick_toggle_enable")
    }

    private fun toggleAutoRotateFromQuickOverlay() {
        val container = appContainer ?: return
        if (!Settings.System.canWrite(this)) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:$packageName"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed to open WRITE_SETTINGS permission screen", error)
            }
            refreshQuickToggleOverlays()
            return
        }
        val nextEnabled = !readAutoRotateEnabled()
        val success = runCatching {
            val orientationPreserved = nextEnabled ||
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.USER_ROTATION,
                    currentDisplayRotation(),
                )
            orientationPreserved &&
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.ACCELEROMETER_ROTATION,
                    if (nextEnabled) 1 else 0,
                )
        }.getOrElse { error ->
            Log.w(TAG, "Failed to toggle auto-rotate directly", error)
            false
        }
        if (success) {
            container.feedbackModule.onOverlayToggle(nextEnabled)
        }
        refreshQuickToggleOverlays()
    }

    private fun ensureQuickToggleView(
        view: QuickToggleView,
        slot: QuickToggleSlot,
        gravity: Int,
        widthPx: Int,
        heightPx: Int,
        title: String,
    ) {
        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            this.title = title
        }
        val attached = when (slot) {
            QuickToggleSlot.PRIMARY -> leftQuickToggleAttached
            QuickToggleSlot.SECONDARY -> rightQuickToggleAttached
        }
        if (!attached) {
            runCatching {
                windowManager.addView(view, params)
                when (slot) {
                    QuickToggleSlot.PRIMARY -> leftQuickToggleAttached = true
                    QuickToggleSlot.SECONDARY -> rightQuickToggleAttached = true
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to add quick toggle overlay title=$title", error)
            }
            return
        }
        runCatching {
            windowManager.updateViewLayout(view, params)
        }.onFailure { error ->
            Log.w(TAG, "Failed to update quick toggle overlay title=$title", error)
        }
    }

    private fun resolveQuickTogglePlacement(navInsets: Insets): QuickTogglePlacement {
        return when {
            navInsets.right >= navInsets.left &&
                navInsets.right >= navInsets.bottom &&
                navInsets.right >= navInsets.top &&
                navInsets.right > 0 -> {
                QuickTogglePlacement(
                    primaryGravity = Gravity.END or Gravity.TOP,
                    secondaryGravity = Gravity.END or Gravity.BOTTOM,
                )
            }

            navInsets.left >= navInsets.right &&
                navInsets.left >= navInsets.bottom &&
                navInsets.left >= navInsets.top &&
                navInsets.left > 0 -> {
                QuickTogglePlacement(
                    primaryGravity = Gravity.START or Gravity.TOP,
                    secondaryGravity = Gravity.START or Gravity.BOTTOM,
                )
            }

            navInsets.top > 0 && navInsets.top >= navInsets.bottom -> {
                QuickTogglePlacement(
                    primaryGravity = Gravity.TOP or Gravity.START,
                    secondaryGravity = Gravity.TOP or Gravity.END,
                )
            }

            else -> {
                QuickTogglePlacement(
                    primaryGravity = Gravity.BOTTOM or Gravity.START,
                    secondaryGravity = Gravity.BOTTOM or Gravity.END,
                )
            }
        }
    }

    private fun registerAutoRotateObserver() {
        if (autoRotateObserverRegistered) {
            return
        }
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
            false,
            autoRotateObserver,
        )
        autoRotateObserverRegistered = true
    }

    private fun unregisterAutoRotateObserver() {
        if (!autoRotateObserverRegistered) {
            return
        }
        runCatching {
            contentResolver.unregisterContentObserver(autoRotateObserver)
        }
        autoRotateObserverRegistered = false
    }

    private fun detachQuickToggleOverlays() {
        if (leftQuickToggleAttached) {
            runCatching { windowManager.removeView(leftQuickToggleView) }
                .onFailure { error -> Log.w(TAG, "Failed to remove left quick toggle overlay", error) }
            leftQuickToggleAttached = false
        }
        if (rightQuickToggleAttached) {
            runCatching { windowManager.removeView(rightQuickToggleView) }
                .onFailure { error -> Log.w(TAG, "Failed to remove right quick toggle overlay", error) }
            rightQuickToggleAttached = false
        }
        leftQuickToggleView = null
        rightQuickToggleView = null
    }

    private fun dpToPx(valueDp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            valueDp,
            resources.displayMetrics,
        ).toInt()
    }

    private fun readAutoRotateEnabled(): Boolean {
        return runCatching {
            Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1
        }.getOrDefault(true)
    }

    private fun currentDisplayRotation(): Int {
        return display?.rotation ?: 0
    }

    private fun updateServiceFlags(captureEnabled: Boolean) {
        val dynamicMask = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
            AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS or
            AccessibilityServiceInfo.FLAG_REQUEST_MULTI_FINGER_GESTURES
        val baseFlags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        val current = serviceInfo ?: return
        val targetFlags = (current.flags and dynamicMask.inv()) or baseFlags or
            if (captureEnabled) dynamicMask else 0
        if (current.flags == targetFlags) {
            return
        }
        current.flags = targetFlags
        runCatching {
            setServiceInfo(current)
            Log.i(TAG, "service flags captureEnabled=$captureEnabled flags=0x${targetFlags.toString(16)}")
        }.onFailure { error ->
            Log.w(TAG, "Failed to update service flags captureEnabled=$captureEnabled", error)
        }
    }

    private fun ensureForegroundNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (notificationManager == null) {
            Log.w(TAG, "NotificationManager unavailable; cannot enter foreground")
            return
        }
        ensureForegroundNotificationChannel(notificationManager)
        val notification = buildForegroundNotification()

        runCatching {
            if (isForegroundNotificationActive) {
                notificationManager.notify(FOREGROUND_NOTIFICATION_ID, notification)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            }
            isForegroundNotificationActive = true
            Log.i(TAG, "Foreground notification started")
        }.onFailure { error ->
            Log.e(TAG, "Failed to start foreground notification", error)
        }
    }

    private fun stopForegroundNotification() {
        if (!isForegroundNotificationActive) {
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            isForegroundNotificationActive = false
            Log.i(TAG, "Foreground notification stopped")
        }.onFailure { error ->
            Log.w(TAG, "Failed stopping foreground notification", error)
        }
    }

    private fun ensureForegroundNotificationChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            FOREGROUND_NOTIFICATION_CHANNEL_ID,
            getString(R.string.foreground_service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.foreground_service_channel_description)
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification() = NotificationCompat.Builder(
        this,
        FOREGROUND_NOTIFICATION_CHANNEL_ID,
    ).apply {
        setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
        setContentTitle(getString(R.string.foreground_service_notification_title))
        setContentText(getString(R.string.foreground_service_notification_text))
        setCategory(NotificationCompat.CATEGORY_SERVICE)
        setPriority(NotificationCompat.PRIORITY_LOW)
        setOnlyAlertOnce(true)
        setOngoing(true)
        setShowWhen(false)
        setContentIntent(buildNotificationOpenAppIntent())
    }.build()

    private fun buildNotificationOpenAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(
            this,
            FOREGROUND_NOTIFICATION_REQUEST_CODE,
            intent,
            pendingIntentFlags,
        )
    }

    companion object {
        const val TAG = "TouchpadA11yService"
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val BIOMETRIC_PROMPT_ACTIVE_TTL_MS = 2_500L
        const val FOREGROUND_NOTIFICATION_CHANNEL_ID = "touchpad_runtime_service"
        const val FOREGROUND_NOTIFICATION_ID = 1001
        const val FOREGROUND_NOTIFICATION_REQUEST_CODE = 101
        const val QUICK_TOGGLE_LEFT_TITLE = "TouchpadQuickToggleLeft"
        const val QUICK_TOGGLE_RIGHT_TITLE = "TouchpadQuickToggleRight"
        @Volatile
        var instance: TouchpadAccessibilityService? = null
        val BIOMETRIC_WINDOW_CLASSES = setOf(
            "com.android.systemui.biometrics.AuthContainerView",
            "com.android.systemui.biometrics.UdfpsView",
        )
    }

    private class QuickToggleView(
        context: Context,
    ) : View(context) {
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = QUICK_TOGGLE_ICON_ALPHA
        }
        private val iconBounds = RectF()
        private val tapSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private var downX = 0f
        private var downY = 0f
        private var mode = QuickToggleMode.TOUCH_CAPTURE
        private var activeIconRes = 0
        private var inactiveIconRes = 0
        private var activeIcon: Bitmap? = null
        private var inactiveIcon: Bitmap? = null
        private var onToggle: () -> Unit = {}
        private var toggleState = QuickToggleState(active = false, ready = false)

        fun configure(
            mode: QuickToggleMode,
            activeIconRes: Int,
            inactiveIconRes: Int,
            onToggle: () -> Unit,
        ) {
            val iconsChanged = this.activeIconRes != activeIconRes || this.inactiveIconRes != inactiveIconRes
            this.mode = mode
            this.onToggle = onToggle
            if (iconsChanged) {
                this.activeIconRes = activeIconRes
                this.inactiveIconRes = inactiveIconRes
                activeIcon = BitmapFactory.decodeResource(resources, activeIconRes)
                inactiveIcon = BitmapFactory.decodeResource(resources, inactiveIconRes)
            }
        }

        fun update(state: QuickToggleState) {
            if (toggleState == state) {
                return
            }
            toggleState = state
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val icon = if (toggleState.active) activeIcon else inactiveIcon
            if (icon == null) {
                return
            }
            iconPaint.alpha = if (toggleState.ready) QUICK_TOGGLE_ICON_ALPHA else QUICK_TOGGLE_ICON_ALPHA_DIMMED

            val padding = minOf(width, height) * QUICK_TOGGLE_ICON_PADDING_RATIO
            iconBounds.set(
                padding,
                padding,
                width.toFloat() - padding,
                height.toFloat() - padding,
            )
            canvas.drawBitmap(icon, null, iconBounds, iconPaint)
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (!toggleState.ready) {
                        return false
                    }
                    downX = event.x
                    downY = event.y
                    return true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val movedTooFar = kotlin.math.abs(event.x - downX) > tapSlop ||
                        kotlin.math.abs(event.y - downY) > tapSlop
                    if (!movedTooFar) {
                        performClick()
                        onToggle()
                    }
                    return true
                }
                android.view.MotionEvent.ACTION_CANCEL -> return true
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        private companion object {
            const val QUICK_TOGGLE_ICON_PADDING_RATIO = 0.18f
            const val QUICK_TOGGLE_ICON_ALPHA = 77
            const val QUICK_TOGGLE_ICON_ALPHA_DIMMED = 40
        }
    }
}
