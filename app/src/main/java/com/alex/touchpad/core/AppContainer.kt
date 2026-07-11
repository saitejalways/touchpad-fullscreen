package com.alex.touchpad.core

import android.content.Context
import android.provider.Settings
import com.alex.touchpad.BuildConfig
import com.alex.touchpad.core.AppLog as Log
import com.alex.touchpad.adb.AdbCommand
import com.alex.touchpad.adb.AdbSessionManager
import com.alex.touchpad.adb.PlainTextSocketAdbTransport
import com.alex.touchpad.adb.QueuedAdbInjectionBackend
import com.alex.touchpad.adb.SafetyController
import com.alex.touchpad.adb.SafetyEvent
import com.alex.touchpad.backend.LocalBackendServer
import com.alex.touchpad.backend.LocalBackendState
import com.alex.touchpad.backend.ExecutorHapticEvents
import com.alex.touchpad.backend.CursorCenterCalibrationResult
import com.alex.touchpad.backend.CursorCalibrationProgress
import com.alex.touchpad.backend.CursorGroundTruth
import com.alex.touchpad.backend.DaemonRuntimeBridge
import com.alex.touchpad.backend.OnDeviceAdbCommandExecutor
import com.alex.touchpad.backend.ShellDaemonBootstrapper
import com.alex.touchpad.backend.ShellDaemonRuntimeClient
import com.alex.touchpad.backend.WireCommand
import com.alex.touchpad.backend.WireCommandExecutor
import com.alex.touchpad.input.ActionRouter
import com.alex.touchpad.input.MouseButton
import com.alex.touchpad.input.TouchpadEngine
import com.alex.touchpad.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val daemonPrefs = appContext.getSharedPreferences(SHELL_DAEMON_PREFS_NAME, Context.MODE_PRIVATE)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val autoConnectMutex = Mutex()
    private val wirelessDebuggingMutex = Mutex()
    private val shellDaemonMutex = Mutex()

    val settingsRepository = SettingsRepository(appContext)
    val feedbackModule = FeedbackModule(appContext, settingsRepository)
    val runtimeStateMachine = RuntimeStateMachine()
    val cursorStateStore = CursorStateStore()
    val touchpadEngine = TouchpadEngine(cursorStateStore, settingsRepository)
    private val _shellDaemonActive = MutableStateFlow(false)
    val shellDaemonActive: StateFlow<Boolean> = _shellDaemonActive.asStateFlow()

    private val transport = PlainTextSocketAdbTransport()
    val sessionManager = AdbSessionManager(appScope, transport, settingsRepository)
    val safetyController = SafetyController(failureThreshold = 3)
    private val shellDaemonRuntimeClient = ShellDaemonRuntimeClient()

    private val injectionBackend = QueuedAdbInjectionBackend(appScope, transport, safetyController)
    val actionRouter = ActionRouter(injectionBackend)

    private val shellDaemonRuntimeBridge = object : DaemonRuntimeBridge {
        override fun isActive(): Boolean = _shellDaemonActive.value

        override suspend fun sendMove(
            dx: Int,
            dy: Int,
            hidMoveChunkSize: Int,
            mouseAccelerationEnabled: Boolean,
        ): Boolean {
            return runCatching {
                shellDaemonRuntimeClient.executeMove(
                    port = ShellDaemonBootstrapper.DEFAULT_PORT,
                    token = shellDaemonToken,
                    dx = dx,
                    dy = dy,
                    hidMoveChunkSize = hidMoveChunkSize,
                    mouseAccelerationEnabled = mouseAccelerationEnabled,
                )
                true
            }.getOrElse { error ->
                Log.w(TAG, "shell-daemon move failed", error)
                false
            }
        }

        override suspend fun sendClick(button: MouseButton): Boolean {
            return runCatching {
                shellDaemonRuntimeClient.executeClick(
                    port = ShellDaemonBootstrapper.DEFAULT_PORT,
                    token = shellDaemonToken,
                    button = button,
                )
                true
            }.getOrElse { error ->
                Log.w(TAG, "shell-daemon click failed", error)
                false
            }
        }

        override suspend fun sendButton(button: MouseButton, isDown: Boolean): Boolean {
            return runCatching {
                shellDaemonRuntimeClient.executeButton(
                    port = ShellDaemonBootstrapper.DEFAULT_PORT,
                    token = shellDaemonToken,
                    button = button,
                    isDown = isDown,
                )
                updateShellDaemonButtonMask(button = button, isDown = isDown)
                true
            }.getOrElse { error ->
                Log.w(TAG, "shell-daemon button failed", error)
                false
            }
        }

        override suspend fun sendScrollWheel(vWheel: Int, hWheel: Int): Boolean {
            return runCatching {
                shellDaemonRuntimeClient.executeScrollWheel(
                    port = ShellDaemonBootstrapper.DEFAULT_PORT,
                    token = shellDaemonToken,
                    vWheel = vWheel,
                    hWheel = hWheel,
                )
                true
            }.getOrElse { error ->
                Log.w(TAG, "shell-daemon scroll failed", error)
                false
            }
        }
    }

    private val localExecutor = OnDeviceAdbCommandExecutor(
        context = appContext,
        settingsRepository = settingsRepository,
        hapticEvents = object : ExecutorHapticEvents {
            override fun onScrollStep() {
                feedbackModule.onScrollStep()
            }

            override fun onEdgeHit() {
                feedbackModule.onEdgeHit()
            }

            override fun onEdgeScrollStart() {
                feedbackModule.onEdgeScrollStart()
            }
        },
        daemonRuntimeBridge = shellDaemonRuntimeBridge,
    )
    private val shellDaemonBootstrapper = ShellDaemonBootstrapper(appContext)
    private var shellDaemonToken = loadOrCreateShellDaemonToken()
    @Volatile
    private var shellDaemonCursorQueryFailureCount = 0
    @Volatile
    private var shellDaemonButtonMask = 0

    private val _localBackendState = MutableStateFlow(LocalBackendState(running = false, port = null, lastError = null))
    val localBackendState: StateFlow<LocalBackendState> = _localBackendState.asStateFlow()
    val localExecutorStatus = localExecutor.status
    val edgeHighlightState = localExecutor.edgeHighlightState
    private val _cursorGroundTruth = MutableStateFlow(localExecutor.cursorGroundTruth.value)
    val cursorGroundTruth: StateFlow<CursorGroundTruth?> = _cursorGroundTruth.asStateFlow()
    val hidScrollDebugHistory = localExecutor.hidScrollDebugHistory
    val edgeSwipeDebugHistory = localExecutor.edgeSwipeDebugHistory
    private val _wirelessDebuggingPaired = MutableStateFlow<Boolean?>(null)
    val wirelessDebuggingPaired: StateFlow<Boolean?> = _wirelessDebuggingPaired.asStateFlow()
    private val _wirelessAdbEnabled = MutableStateFlow(readWirelessAdbEnabled())
    val wirelessAdbEnabled: StateFlow<Boolean> = _wirelessAdbEnabled.asStateFlow()
    private val backendExecutor = object : WireCommandExecutor {
        override val status: StateFlow<com.alex.touchpad.backend.ExecutorStatus> = localExecutor.status

        override suspend fun execute(command: WireCommand): Boolean {
            return localExecutor.execute(command)
        }
    }
    private val localBackendServer = LocalBackendServer(appScope, backendExecutor)

    val autoConnectEnabled = MutableStateFlow(true)
    val overlayDesired = MutableStateFlow(false)
    val overlayAttached = MutableStateFlow(false)

    suspend fun sendDirectCommand(command: AdbCommand): Boolean {
        if (!sessionManager.isConnected.value) {
            if (!sessionManager.connect()) {
                return false
            }
        }
        return transport.send(command)
    }

    suspend fun isWirelessDebuggingPaired(): Boolean {
        return wirelessDebuggingMutex.withLock {
            localExecutor.isWirelessDebuggingPaired()
        }
    }

    suspend fun pairWirelessDebugging(host: String, port: Int, code: String): Boolean {
        return wirelessDebuggingMutex.withLock {
            _wirelessDebuggingPaired.value = null
            val success = localExecutor.execute(
                WireCommand.Pair(
                    host = host,
                    port = port,
                    code = code,
                    seq = System.currentTimeMillis(),
                    ts = System.currentTimeMillis(),
                )
            )
            _wirelessDebuggingPaired.value = if (success) {
                runCatching { localExecutor.isWirelessDebuggingPaired() }.getOrDefault(false)
            } else {
                false
            }
            success
        }
    }

    suspend fun calibrateCursorCenterMapping(
        onProgress: ((CursorCalibrationProgress) -> Unit)? = null,
    ): CursorCenterCalibrationResult? {
        val result = localExecutor.calibrateCursorCenterMapping(onProgress = onProgress) ?: return null
        settingsRepository.setCursorCalibrationUnitsPerPx(
            xUnitsPerPx = result.unitsPerPxX,
            yUnitsPerPx = result.unitsPerPxY,
            isLandscape = result.isLandscape,
        )
        return result
    }

    suspend fun debugMoveCursorToTopLeftForCalibration(): Boolean {
        if (!DebugFlags.ENABLED) {
            return false
        }
        return localExecutor.debugMoveCursorToTopLeftForCalibration()
    }

    suspend fun debugQueryCursorGroundTruth(): CursorGroundTruth? {
        if (!DebugFlags.ENABLED) {
            return null
        }
        return localExecutor.debugQueryCursorGroundTruth()
    }

    suspend fun debugSendLogicalMove(dx: Int, dy: Int): Boolean {
        if (!DebugFlags.ENABLED) {
            return false
        }
        return localExecutor.debugSendLogicalMove(dx, dy)
    }

    suspend fun debugCurrentInputViewportOrientation(): Int {
        if (!DebugFlags.ENABLED) {
            return 0
        }
        return localExecutor.debugCurrentInputViewportOrientation()
    }

    suspend fun debugProbeCalibration(): String {
        if (!DebugFlags.ENABLED) {
            return "debug_disabled"
        }
        return localExecutor.debugProbeCalibration()
    }

    suspend fun launchExperimentalShellDaemon(): String {
        return shellDaemonMutex.withLock {
            shellDaemonRuntimeClient.close()
            val existingPing = runCatching {
                shellDaemonBootstrapper.pingDaemon(token = shellDaemonToken)
            }.getOrNull()
            if (existingPing != null && isCurrentDaemonBuild(existingPing)) {
                _shellDaemonActive.value = true
                shellDaemonButtonMask = 0
                shellDaemonCursorQueryFailureCount = 0
                runCatching {
                    shellDaemonBootstrapper.queryCursorGroundTruth(token = shellDaemonToken)
                }.getOrNull()?.let { groundTruth ->
                    _cursorGroundTruth.value = groundTruth
                    localExecutor.setExternalGroundTruthOverride(groundTruth)
                }
                return@withLock "Daemon already running: $existingPing"
            } else if (existingPing != null) {
                Log.w(TAG, "Ignoring stale shell daemon ping reply=$existingPing")
            }
            if (!_wirelessAdbEnabled.value) {
                return@withLock "Wireless ADB is OFF in Android settings"
            }
            val result = shellDaemonBootstrapper.launchDaemon(token = shellDaemonToken)
            _shellDaemonActive.value = result.success
            if (result.success) {
                shellDaemonButtonMask = 0
                shellDaemonCursorQueryFailureCount = 0
                runCatching {
                    shellDaemonBootstrapper.queryCursorGroundTruth(token = shellDaemonToken)
                }.getOrNull()?.let { groundTruth ->
                    _cursorGroundTruth.value = groundTruth
                    localExecutor.setExternalGroundTruthOverride(groundTruth)
                }
            }
            result.message
        }
    }

    suspend fun pingExperimentalShellDaemon(): String {
        return shellDaemonMutex.withLock {
            runCatching {
                shellDaemonBootstrapper.pingDaemon(token = shellDaemonToken)
            }.getOrElse { error ->
                error.message ?: "Shell daemon ping failed"
            }
        }
    }

    suspend fun queryExperimentalShellDaemonGroundTruth(): String {
        return shellDaemonMutex.withLock {
            runCatching {
                shellDaemonBootstrapper.queryCursorGroundTruth(token = shellDaemonToken)
            }.map { groundTruth ->
                _shellDaemonActive.value = true
                _cursorGroundTruth.value = groundTruth
                localExecutor.setExternalGroundTruthOverride(groundTruth)
                "Cursor x=${"%.1f".format(groundTruth.x)} y=${"%.1f".format(groundTruth.y)} size=${groundTruth.widthPx}x${groundTruth.heightPx}"
            }.getOrElse { error ->
                error.message ?: "Shell daemon cursor query failed"
            }
        }
    }

    suspend fun setAutoRotateEnabledViaShellDaemon(enabled: Boolean): Boolean {
        return shellDaemonMutex.withLock {
            runCatching {
                shellDaemonBootstrapper.setAutoRotateEnabled(token = shellDaemonToken, enabled = enabled)
            }.getOrElse { error ->
                Log.w(TAG, "shell-daemon auto-rotate toggle failed", error)
                false
            }
        }
    }

    suspend fun stopExperimentalShellDaemon(): String {
        return shellDaemonMutex.withLock {
            shellDaemonRuntimeClient.close()
            val result = shellDaemonBootstrapper.stopDaemon(token = shellDaemonToken)
            _shellDaemonActive.value = false
            shellDaemonButtonMask = 0
            shellDaemonCursorQueryFailureCount = 0
            localExecutor.setExternalGroundTruthOverride(null)
            _cursorGroundTruth.value = localExecutor.cursorGroundTruth.value
            result.message
        }
    }

    init {
        appScope.launch {
            sessionManager.isConnected.collect { connected ->
                Log.i(TAG, "session connected=$connected")
                if (connected) {
                    _wirelessDebuggingPaired.value = true
                }
                runtimeStateMachine.onAdbConnectionChanged(connected)
                if (!connected) {
                    overlayDesired.value = false
                }
            }
        }

        appScope.launch {
            sessionManager.lastError.collect { error ->
                if (!error.isNullOrBlank()) {
                    Log.recordIssue(AppLogLevel.ERROR, "AdbSessionManager", error)
                }
            }
        }

        appScope.launch {
            while (true) {
                _wirelessAdbEnabled.value = readWirelessAdbEnabled()
                val paired = wirelessDebuggingMutex.withLock {
                    runCatching { localExecutor.isWirelessDebuggingPaired() }.getOrDefault(false)
                }
                _wirelessDebuggingPaired.value = paired
                if (!paired && overlayDesired.value) {
                    overlayDesired.value = false
                }
                delay(1000L)
            }
        }

        appScope.launch {
            ensureShellDaemonConnectedSilently()
        }

        appScope.launch {
            while (true) {
                if (!_shellDaemonActive.value) {
                    ensureShellDaemonConnectedSilently()
                }
                delay(SHELL_DAEMON_AUTOCONNECT_INTERVAL_MS)
            }
        }

        appScope.launch {
            localExecutor.cursorGroundTruth.collect { groundTruth ->
                if (!_shellDaemonActive.value) {
                    _cursorGroundTruth.value = groundTruth
                }
            }
        }

        appScope.launch {
            while (true) {
                if (_shellDaemonActive.value) {
                    var attemptedQuery = false
                    val groundTruth = if (shellDaemonButtonMask == 0 && shellDaemonMutex.tryLock()) {
                        attemptedQuery = true
                        try {
                            runCatching {
                                shellDaemonBootstrapper.queryCursorGroundTruth(token = shellDaemonToken)
                            }.getOrNull()
                        } finally {
                            shellDaemonMutex.unlock()
                        }
                    } else {
                        null
                    }
                    if (attemptedQuery) {
                        if (groundTruth != null) {
                            shellDaemonCursorQueryFailureCount = 0
                            _cursorGroundTruth.value = groundTruth
                            localExecutor.setExternalGroundTruthOverride(groundTruth)
                        } else {
                            shellDaemonCursorQueryFailureCount += 1
                            if (shellDaemonCursorQueryFailureCount >= SHELL_DAEMON_CURSOR_QUERY_FAILURES_BEFORE_PING) {
                                val pingOk = shellDaemonMutex.withLock {
                                    runCatching {
                                        shellDaemonBootstrapper.pingDaemon(token = shellDaemonToken)
                                    }.isSuccess
                                }
                                if (pingOk) {
                                    shellDaemonCursorQueryFailureCount = 0
                                } else {
                                    shellDaemonRuntimeClient.close()
                                    _shellDaemonActive.value = false
                                    shellDaemonButtonMask = 0
                                    shellDaemonCursorQueryFailureCount = 0
                                    localExecutor.setExternalGroundTruthOverride(null)
                                    _cursorGroundTruth.value = localExecutor.cursorGroundTruth.value
                                }
                            }
                        }
                    }
                }
                delay(SHELL_DAEMON_CURSOR_POLL_MS)
            }
        }

        appScope.launch {
            overlayAttached.collect { attached ->
                Log.i(TAG, "overlay attached=$attached")
                runtimeStateMachine.onOverlayCaptureChanged(attached)
            }
        }

        appScope.launch {
            settingsRepository.endpointConfig.collect { endpoint ->
                val isLoopback = endpoint.host == "127.0.0.1" || endpoint.host.equals("localhost", ignoreCase = true)
                Log.i(TAG, "endpoint=${endpoint.host}:${endpoint.port} loopback=$isLoopback")
                if (isLoopback) {
                    localBackendServer.start(endpoint.port)
                } else {
                    localBackendServer.stop()
                }
            }
        }

        appScope.launch {
            localBackendServer.state.collect { backendState ->
                Log.i(TAG, "local backend running=${backendState.running} port=${backendState.port} error=${backendState.lastError}")
                backendState.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                    Log.recordIssue(AppLogLevel.ERROR, "LocalBackendServer", error)
                }
                _localBackendState.value = backendState
            }
        }

        appScope.launch {
            localExecutorStatus.collect { status ->
                status.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                    Log.recordIssue(AppLogLevel.ERROR, "OnDeviceAdbExecutor", error)
                }
            }
        }

        appScope.launch {
            combine(
                autoConnectEnabled,
                settingsRepository.endpointConfig,
                localBackendServer.state,
                sessionManager.isConnected,
            ) { enabled, endpoint, backendState, connected ->
                AutoConnectSnapshot(
                    enabled = enabled,
                    endpointHost = endpoint.host,
                    endpointPort = endpoint.port,
                    backendState = backendState,
                    connected = connected,
                )
            }.collect { snapshot ->
                if (!snapshot.shouldAutoConnect()) {
                    return@collect
                }
                autoConnectMutex.withLock {
                    for (attempt in 1..AUTO_CONNECT_MAX_ATTEMPTS) {
                        val endpoint = settingsRepository.endpointConfig.value
                        val backend = _localBackendState.value
                        val loopback = endpoint.host == "127.0.0.1" || endpoint.host.equals("localhost", ignoreCase = true)
                        if (
                            !autoConnectEnabled.value ||
                            sessionManager.isConnected.value ||
                            !backend.running ||
                            backend.port != endpoint.port ||
                            !loopback
                        ) {
                            return@withLock
                        }
                        Log.i(
                            TAG,
                            "auto-connect attempt=$attempt endpoint=${endpoint.host}:${endpoint.port}",
                        )
                        if (sessionManager.connect()) {
                            Log.i(TAG, "auto-connect success")
                            return@withLock
                        }
                        delay(AUTO_CONNECT_RETRY_MS)
                    }
                    Log.w(TAG, "auto-connect exhausted attempts without success")
                }
            }
        }

        appScope.launch {
            safetyController.events.collect { event ->
                when (event) {
                    SafetyEvent.FaultEntered -> {
                        Log.w(TAG, "safety fault entered")
                        runtimeStateMachine.onFaultDetected()
                        overlayDesired.value = false
                        feedbackModule.onFaultEntered()
                    }

                    SafetyEvent.FaultRecovered -> {
                        Log.i(TAG, "safety fault recovered")
                        runtimeStateMachine.onFaultRecovered()
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "AppContainer"
        const val AUTO_CONNECT_MAX_ATTEMPTS = 8
        const val AUTO_CONNECT_RETRY_MS = 250L
        const val SHELL_DAEMON_CURSOR_POLL_MS = 75L
        const val SHELL_DAEMON_CURSOR_QUERY_FAILURES_BEFORE_PING = 8
        const val SHELL_DAEMON_AUTOCONNECT_INTERVAL_MS = 2_000L
        const val SHELL_DAEMON_PREFS_NAME = "shell_daemon"
        const val KEY_SHELL_DAEMON_TOKEN = "token"
        const val KEY_SHELL_DAEMON_APP_UPDATE_TIME = "app_update_time"
    }

    private data class AutoConnectSnapshot(
        val enabled: Boolean,
        val endpointHost: String,
        val endpointPort: Int,
        val backendState: LocalBackendState,
        val connected: Boolean,
    ) {
        fun shouldAutoConnect(): Boolean {
            if (!enabled || connected || !backendState.running) {
                return false
            }
            if (backendState.port != endpointPort) {
                return false
            }
            return endpointHost == "127.0.0.1" || endpointHost.equals("localhost", ignoreCase = true)
        }
    }

    private fun readWirelessAdbEnabled(): Boolean {
        return runCatching {
            Settings.Global.getInt(appContext.contentResolver, "adb_wifi_enabled", 0) == 1
        }.getOrDefault(false)
    }

    private fun loadOrCreateShellDaemonToken(): String {
        val currentAppUpdateTime = runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        val storedAppUpdateTime = daemonPrefs.getLong(KEY_SHELL_DAEMON_APP_UPDATE_TIME, Long.MIN_VALUE)
        val existing = daemonPrefs.getString(KEY_SHELL_DAEMON_TOKEN, null)?.takeIf { it.isNotBlank() }
        if (existing != null && storedAppUpdateTime == currentAppUpdateTime) {
            return existing
        }
        val created = ShellDaemonBootstrapper.newToken()
        daemonPrefs.edit()
            .putString(KEY_SHELL_DAEMON_TOKEN, created)
            .putLong(KEY_SHELL_DAEMON_APP_UPDATE_TIME, currentAppUpdateTime)
            .apply()
        return created
    }

    private fun isCurrentDaemonBuild(pingReply: String): Boolean {
        return pingReply.contains("versionCode=${BuildConfig.VERSION_CODE}")
    }

    private fun applyShellDaemonGroundTruth(groundTruth: CursorGroundTruth) {
        _cursorGroundTruth.value = groundTruth
        localExecutor.setExternalGroundTruthOverride(groundTruth)
    }

    private fun updateShellDaemonButtonMask(button: MouseButton, isDown: Boolean) {
        val bit = when (button) {
            MouseButton.LEFT -> 0x01
            MouseButton.RIGHT -> 0x02
            MouseButton.MIDDLE -> 0x04
        }
        shellDaemonButtonMask = if (isDown) {
            shellDaemonButtonMask or bit
        } else {
            shellDaemonButtonMask and bit.inv()
        }
    }

    private suspend fun ensureShellDaemonConnectedSilently() {
        shellDaemonMutex.withLock {
            if (_shellDaemonActive.value) {
                return
            }

            shellDaemonRuntimeClient.close()
            val ping = runCatching {
                shellDaemonBootstrapper.pingDaemon(token = shellDaemonToken)
            }.getOrNull()
            if (ping != null && isCurrentDaemonBuild(ping)) {
                _shellDaemonActive.value = true
                shellDaemonButtonMask = 0
                shellDaemonCursorQueryFailureCount = 0
                runCatching {
                    shellDaemonBootstrapper.queryCursorGroundTruth(token = shellDaemonToken)
                }.getOrNull()?.let(::applyShellDaemonGroundTruth)
                Log.i(TAG, "reconnected to existing shell daemon: $ping")
                return
            } else if (ping != null) {
                Log.w(TAG, "existing shell daemon is from a different build: $ping")
            }

            if (!_wirelessAdbEnabled.value || _wirelessDebuggingPaired.value != true) {
                return
            }

            val result = runCatching {
                shellDaemonBootstrapper.launchDaemon(token = shellDaemonToken)
            }.getOrNull() ?: return
            if (!result.success) {
                return
            }

            _shellDaemonActive.value = true
            shellDaemonButtonMask = 0
            shellDaemonCursorQueryFailureCount = 0
            runCatching {
                shellDaemonBootstrapper.queryCursorGroundTruth(token = shellDaemonToken)
            }.getOrNull()?.let(::applyShellDaemonGroundTruth)
            Log.i(TAG, "auto-launched shell daemon: ${result.message}")
        }
    }

}
