package com.alex.touchpad.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import com.alex.touchpad.core.AppLog as Log
import android.app.Dialog
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.alex.touchpad.R
import com.alex.touchpad.TouchpadApplication
import com.alex.touchpad.adb.AdbCommand
import com.alex.touchpad.core.AppContainer
import com.alex.touchpad.service.TouchpadAccessibilityService
import android.view.accessibility.AccessibilityManager
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class MainActivity : FragmentActivity() {
    private lateinit var container: AppContainer
    private lateinit var wirelessPairingStatusCard: LinearLayout
    private lateinit var wirelessPairingStatusValue: TextView
    private lateinit var wirelessAdbStatusCard: LinearLayout
    private lateinit var wirelessAdbStatusValue: TextView
    private lateinit var daemonStatusCard: LinearLayout
    private lateinit var daemonStatusValue: TextView
    private lateinit var daemonLaunchStatusValue: TextView
    private lateinit var accessibilityStatusCard: LinearLayout
    private lateinit var accessibilityStatusValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val container = (application as TouchpadApplication).appContainer.also { this.container = it }
        var pendingExportFileName: String? = null
        val exportConfigLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val fileName = pendingExportFileName
            pendingExportFileName = null
            if (uri == null || fileName == null) {
                return@registerForActivityResult
            }
            val exportContent = container.settingsRepository.exportCurrentConfigAsIni()
            val exported = runCatching {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(exportContent)
                } ?: error("Unable to open export destination")
            }.isSuccess
            val message = if (exported) {
                getString(R.string.export_config_success, fileName)
            } else {
                getString(R.string.export_config_failed)
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        val tabSpecs = listOf(
            SettingsTabSpec(title = getString(R.string.settings_tab_general), layoutResId = R.layout.fragment_settings_general),
            SettingsTabSpec(title = getString(R.string.settings_tab_pointer), layoutResId = R.layout.fragment_settings_pointer),
            SettingsTabSpec(title = getString(R.string.settings_tab_scroll), layoutResId = R.layout.fragment_settings_scroll),
            SettingsTabSpec(title = getString(R.string.settings_tab_drag), layoutResId = R.layout.fragment_settings_drag),
            SettingsTabSpec(title = getString(R.string.settings_tab_haptics), layoutResId = R.layout.fragment_settings_haptics),
            SettingsTabSpec(title = getString(R.string.settings_tab_system), layoutResId = R.layout.fragment_settings_system),
        )
        val tabLayout = findViewById<TabLayout>(R.id.settingsTabLayout)
        val viewPager = findViewById<ViewPager2>(R.id.settingsViewPager)
        viewPager.adapter = SettingsTabsAdapter(this, tabSpecs)
        viewPager.offscreenPageLimit = tabSpecs.size
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = tabSpecs[position].title
        }.attach()

        var lastPagerWheelSwitchAtMs = 0L
        viewPager.post {
        viewPager.getChildAt(0)?.setOnGenericMotionListener { _, event ->
            if (event.action != MotionEvent.ACTION_SCROLL) {
                return@setOnGenericMotionListener false
            }

            val horizontalScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val verticalScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (abs(horizontalScroll) < VIEW_PAGER_MOUSE_SCROLL_THRESHOLD || abs(horizontalScroll) <= abs(verticalScroll)) {
                return@setOnGenericMotionListener false
            }

            val now = SystemClock.elapsedRealtime()
            if ((now - lastPagerWheelSwitchAtMs) < VIEW_PAGER_MOUSE_SCROLL_DEBOUNCE_MS) {
                return@setOnGenericMotionListener true
            }

            val direction = if (horizontalScroll > 0f) -1 else 1
            val nextItem = (viewPager.currentItem + direction).coerceIn(0, tabSpecs.lastIndex)
            lastPagerWheelSwitchAtMs = now
            if (nextItem != viewPager.currentItem) {
                viewPager.setCurrentItem(nextItem, true)
            }
            true
        }

        wirelessPairingStatusCard = findViewById(R.id.wirelessPairingStatusCard)
        wirelessPairingStatusValue = findViewById(R.id.wirelessPairingStatusValue)
        wirelessAdbStatusCard = findViewById(R.id.wirelessAdbStatusCard)
        wirelessAdbStatusValue = findViewById(R.id.wirelessAdbStatusValue)
        daemonStatusCard = findViewById(R.id.daemonStatusCard)
        daemonStatusValue = findViewById(R.id.daemonStatusValue)
        daemonLaunchStatusValue = findViewById(R.id.daemonLaunchStatusValue)
        accessibilityStatusCard = findViewById(R.id.accessibilityStatusCard)
        accessibilityStatusValue = findViewById(R.id.accessibilityStatusValue)

        val pairingPortInput = findViewById<EditText>(R.id.pairingPortInput)
        val pairingCodeInput = findViewById<EditText>(R.id.pairingCodeInput)
        val mouseSensitivityInput = findViewById<EditText>(R.id.mouseSensitivityInput)
        val scrollSensitivityInput = findViewById<EditText>(R.id.scrollSensitivityInput)
        val verticalFollowupFactorInput = findViewById<EditText>(R.id.verticalFollowupFactorInput)
        val horizontalScrollSensitivityInput = findViewById<EditText>(R.id.horizontalScrollSensitivityInput)
        val horizontalFollowupFactorInput = findViewById<EditText>(R.id.horizontalFollowupFactorInput)
        val syncIntervalInput = findViewById<EditText>(R.id.syncIntervalInput)
        val edgeScrollRepeatDelayInput = findViewById<EditText>(R.id.edgeScrollRepeatDelayInput)
        val edgeScrollJumpMultiplierInput = findViewById<EditText>(R.id.edgeScrollJumpMultiplierInput)
        val scrollDispatchIntervalInput = findViewById<EditText>(R.id.scrollDispatchIntervalInput)
        val flingDurationInput = findViewById<EditText>(R.id.flingDurationInput)
        val holdDragDelayInput = findViewById<EditText>(R.id.holdDragDelayInput)
        val dragFlickKickInput = findViewById<EditText>(R.id.dragFlickKickInput)
        val twoFingerScrollGraceInput = findViewById<EditText>(R.id.twoFingerScrollGraceInput)
        val holdDragSlopInput = findViewById<EditText>(R.id.holdDragSlopInput)
        val doubleClickGuardInput = findViewById<EditText>(R.id.doubleClickGuardInput)
        val dragStartSlopInput = findViewById<EditText>(R.id.dragStartSlopInput)
        val hidMoveChunkSizeInput = findViewById<EditText>(R.id.hidMoveChunkSizeInput)
        val pointerMoveDeadbandInput = findViewById<EditText>(R.id.pointerMoveDeadbandInput)
        val hapticTouchContactInput = findViewById<EditText>(R.id.hapticTouchContactInput)
        val hapticClickInput = findViewById<EditText>(R.id.hapticClickInput)
        val hapticRightClickInput = findViewById<EditText>(R.id.hapticRightClickInput)
        val hapticDragStartInput = findViewById<EditText>(R.id.hapticDragStartInput)
        val hapticOverlayToggleInput = findViewById<EditText>(R.id.hapticOverlayToggleInput)
        val hapticFaultInput = findViewById<EditText>(R.id.hapticFaultInput)
        val hapticScrollStepInput = findViewById<EditText>(R.id.hapticScrollStepInput)
        val hapticEdgeHitInput = findViewById<EditText>(R.id.hapticEdgeHitInput)
        val hapticEdgeScrollStartInput = findViewById<EditText>(R.id.hapticEdgeScrollStartInput)
        val mouseAccelerationSwitch = findViewById<SwitchCompat>(R.id.mouseAccelerationSwitch)
        val oneFingerEdgeScrollSwitch = findViewById<SwitchCompat>(R.id.oneFingerEdgeScrollSwitch)
        val tapToDragSwitch = findViewById<SwitchCompat>(R.id.tapToDragSwitch)
        val invertHorizontalScrollSwitch = findViewById<SwitchCompat>(R.id.invertHorizontalScrollSwitch)
        val invertVerticalScrollSwitch = findViewById<SwitchCompat>(R.id.invertVerticalScrollSwitch)
        val invertEdgeHorizontalScrollSwitch = findViewById<SwitchCompat>(R.id.invertEdgeHorizontalScrollSwitch)
        val invertEdgeVerticalScrollSwitch = findViewById<SwitchCompat>(R.id.invertEdgeVerticalScrollSwitch)
        val sideEdgeScrollSwitch = findViewById<SwitchCompat>(R.id.sideEdgeScrollSwitch)
        val bottomNavPassthroughSwitch = findViewById<SwitchCompat>(R.id.bottomNavPassthroughSwitch)
        val autoStartOnBootSwitch = findViewById<SwitchCompat>(R.id.autoStartOnBootSwitch)
        val debugOverlaySwitch = findViewById<SwitchCompat>(R.id.debugOverlaySwitch)
        val quickToggleOverlaySwitch = findViewById<SwitchCompat>(R.id.quickToggleOverlaySwitch)
        val quickToggleRightAutoRotateSwitch = findViewById<SwitchCompat>(R.id.quickToggleRightAutoRotateSwitch)
        val restoreCursorAfterEdgeScrollSwitch = findViewById<SwitchCompat>(R.id.restoreCursorAfterEdgeScrollSwitch)

        val accessibilitySettingsButton = findViewById<Button>(R.id.accessibilitySettingsButton)
        val openHelpButton = findViewById<Button>(R.id.openHelpButton)
        val pairWirelessButton = findViewById<Button>(R.id.pairWirelessButton)
        val launchShellDaemonButton = findViewById<Button>(R.id.launchShellDaemonButton)
        val calibrateCursorButton = findViewById<Button>(R.id.calibrateCursorButton)
        val clearCalibrationButton = findViewById<Button>(R.id.clearCalibrationButton)
        val exportConfigButton = findViewById<Button>(R.id.exportConfigButton)
        val saveEndpointButton = findViewById<Button>(R.id.saveEndpointButton)
        val toggleConnectionButton = findViewById<Button>(R.id.toggleConnectionButton)

        mouseSensitivityInput.setText(container.settingsRepository.speedMultiplier.value.toString())
        scrollSensitivityInput.setText(container.settingsRepository.verticalScrollPixelsPerStep.value.toString())
        verticalFollowupFactorInput.setText(container.settingsRepository.verticalFollowupStepFactor.value.toString())
        horizontalScrollSensitivityInput.setText(container.settingsRepository.horizontalScrollPixelsPerStep.value.toString())
        horizontalFollowupFactorInput.setText(container.settingsRepository.horizontalFollowupStepFactor.value.toString())
        syncIntervalInput.setText(container.settingsRepository.syncIntervalMs.value.toString())
        edgeScrollRepeatDelayInput.setText(container.settingsRepository.edgeScrollRepeatDelayMs.value.toString())
        edgeScrollJumpMultiplierInput.setText(container.settingsRepository.edgeScrollJumpMultiplier.value.toString())
        scrollDispatchIntervalInput.setText(container.settingsRepository.scrollDispatchIntervalMs.value.toString())
        flingDurationInput.setText(container.settingsRepository.flingDurationMs.value.toString())
        holdDragDelayInput.setText(container.settingsRepository.holdDragDelayMs.value.toString())
        dragFlickKickInput.setText(container.settingsRepository.dragFlickKickMs.value.toString())
        twoFingerScrollGraceInput.setText(container.settingsRepository.twoFingerScrollGraceMs.value.toString())
        holdDragSlopInput.setText(container.settingsRepository.holdDragSlopPx.value.toString())
        doubleClickGuardInput.setText(container.settingsRepository.doubleClickGuardMs.value.toString())
        dragStartSlopInput.setText(container.settingsRepository.dragStartSlopPx.value.toString())
        hidMoveChunkSizeInput.setText(container.settingsRepository.hidMoveChunkSize.value.toString())
        pointerMoveDeadbandInput.setText(container.settingsRepository.pointerMoveDeadbandPx.value.toString())
        hapticTouchContactInput.setText(container.settingsRepository.hapticTouchContactIntensity.value.toString())
        hapticClickInput.setText(container.settingsRepository.hapticClickIntensity.value.toString())
        hapticRightClickInput.setText(container.settingsRepository.hapticRightClickIntensity.value.toString())
        hapticDragStartInput.setText(container.settingsRepository.hapticDragStartIntensity.value.toString())
        hapticOverlayToggleInput.setText(container.settingsRepository.hapticOverlayToggleIntensity.value.toString())
        hapticFaultInput.setText(container.settingsRepository.hapticFaultIntensity.value.toString())
        hapticScrollStepInput.setText(container.settingsRepository.hapticScrollStepIntensity.value.toString())
        hapticEdgeHitInput.setText(container.settingsRepository.hapticEdgeHitIntensity.value.toString())
        hapticEdgeScrollStartInput.setText(container.settingsRepository.hapticEdgeScrollStartIntensity.value.toString())
        mouseAccelerationSwitch.isChecked = container.settingsRepository.mouseAccelerationEnabled.value
        oneFingerEdgeScrollSwitch.isChecked = container.settingsRepository.oneFingerEdgeScrollEnabled.value
        tapToDragSwitch.isChecked = container.settingsRepository.tapToDragEnabled.value
        invertHorizontalScrollSwitch.isChecked = container.settingsRepository.horizontalScrollInverted.value
        invertVerticalScrollSwitch.isChecked = container.settingsRepository.verticalScrollInverted.value
        invertEdgeHorizontalScrollSwitch.isChecked = container.settingsRepository.edgeHorizontalScrollInverted.value
        invertEdgeVerticalScrollSwitch.isChecked = container.settingsRepository.edgeVerticalScrollInverted.value
        sideEdgeScrollSwitch.isChecked = container.settingsRepository.sideEdgeScrollEnabled.value
        bottomNavPassthroughSwitch.isChecked = container.settingsRepository.bottomNavBarPassthroughEnabled.value
        autoStartOnBootSwitch.isChecked = container.settingsRepository.autoStartOnBootEnabled.value
        debugOverlaySwitch.isChecked = container.settingsRepository.debugOverlayEnabled.value
        quickToggleOverlaySwitch.isChecked = container.settingsRepository.quickToggleOverlayEnabled.value
        quickToggleRightAutoRotateSwitch.isChecked = container.settingsRepository.quickToggleRightAutoRotateEnabled.value
        restoreCursorAfterEdgeScrollSwitch.isChecked = container.settingsRepository.restoreCursorAfterEdgeScroll.value
        wirelessAdbStatusValue.text = getString(R.string.status_off)
        daemonStatusValue.text = getString(R.string.status_checking)
        daemonLaunchStatusValue.text = getString(R.string.experimental_shell_daemon_status_idle)
        updatePrimaryToggleButton(
            toggleConnectionButton,
            active = container.overlayDesired.value || container.overlayAttached.value || container.sessionManager.isConnected.value,
        )

        var debugOverlaySwitchSyncing = false
        debugOverlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            if (debugOverlaySwitchSyncing) return@setOnCheckedChangeListener
            container.settingsRepository.setDebugOverlayEnabled(isChecked)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    container.wirelessAdbEnabled,
                    container.shellDaemonActive,
                ) { enabled, daemonConnected ->
                    enabled to daemonConnected
                }.collect { (enabled, daemonConnected) ->
                    refreshWirelessAdbStatus(enabled = enabled, daemonConnected = daemonConnected)
                    refreshDaemonStatus(connected = daemonConnected)
                }
            }
        }

        accessibilitySettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        openHelpButton.setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }

        pairWirelessButton.setOnClickListener {
            lifecycleScope.launch {
                val pairingPort = pairingPortInput.text?.toString()?.trim()?.toIntOrNull()
                val pairingCode = pairingCodeInput.text?.toString()?.trim().orEmpty()
                if (pairingPort == null || pairingCode.isBlank()) {
                    Toast.makeText(this@MainActivity, R.string.pair_requires_port_code, Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val success = container.pairWirelessDebugging(
                    host = LOOPBACK_HOST,
                    port = pairingPort,
                    code = pairingCode,
                )
                val message = if (success) R.string.pair_success else R.string.pair_failed
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                if (success) {
                    pairingCodeInput.setText("")
                }
                refreshWirelessPairingStatus(null)
            }
        }

        calibrateCursorButton.setOnClickListener {
            lifecycleScope.launch {
                calibrateCursorButton.isEnabled = false
                val shouldRestoreTouchCapture = container.overlayDesired.value || container.overlayAttached.value
                if (shouldRestoreTouchCapture) {
                    container.overlayDesired.value = false
                    TouchpadAccessibilityService.instance?.requestManualReconcile(reason = "calibration_start")
                }
                val overlay = createCalibrationOverlay(
                    message = "Preparing calibration...",
                    progressFraction = 0f,
                )
                overlay.show()
                val result = runCatching {
                    container.calibrateCursorCenterMapping { progress ->
                        runOnUiThread {
                            updateCalibrationOverlay(
                                dialog = overlay,
                                message = progress.message,
                                progressFraction = progress.progressFraction,
                            )
                        }
                    }
                }.getOrNull()
                overlay.dismiss()
                if (shouldRestoreTouchCapture) {
                    container.overlayDesired.value = true
                    TouchpadAccessibilityService.instance?.requestManualReconcile(reason = "calibration_end")
                }
                val message = if (result != null) {
                    getString(
                        R.string.calibrate_cursor_success,
                        result.unitsPerPxX,
                        result.unitsPerPxY,
                        result.maxErrorPx,
                    )
                } else {
                    getString(R.string.calibrate_cursor_failed)
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                calibrateCursorButton.isEnabled = true
            }
        }

        clearCalibrationButton.setOnClickListener {
            container.settingsRepository.clearCursorCalibration()
            Toast.makeText(this, R.string.calibrate_cursor_cleared, Toast.LENGTH_SHORT).show()
        }

        exportConfigButton.setOnClickListener {
            val fileName = "adb-touchpad-config-${System.currentTimeMillis()}.ini"
            pendingExportFileName = fileName
            exportConfigLauncher.launch(fileName)
        }

        saveEndpointButton.setOnClickListener {
            val mouseSensitivity = mouseSensitivityInput.text?.toString()?.toFloatOrNull()
                ?: container.settingsRepository.speedMultiplier.value
            val verticalScrollPixelsPerStep = scrollSensitivityInput.text?.toString()?.toFloatOrNull()
                ?: container.settingsRepository.verticalScrollPixelsPerStep.value
            val verticalFollowupStepFactor = verticalFollowupFactorInput.text?.toString()?.toFloatOrNull()
                ?: container.settingsRepository.verticalFollowupStepFactor.value
            val horizontalScrollPixelsPerStep = horizontalScrollSensitivityInput.text?.toString()?.toFloatOrNull()
                ?: container.settingsRepository.horizontalScrollPixelsPerStep.value
            val horizontalFollowupStepFactor = horizontalFollowupFactorInput.text?.toString()?.toFloatOrNull()
                ?: container.settingsRepository.horizontalFollowupStepFactor.value
            val syncIntervalMs = syncIntervalInput.text?.toString()?.toIntOrNull()
                ?: container.settingsRepository.syncIntervalMs.value
            val edgeScrollRepeatDelayMs = edgeScrollRepeatDelayInput.text?.toString()?.toIntOrNull()
                ?: container.settingsRepository.edgeScrollRepeatDelayMs.value
            val edgeScrollJumpMultiplier = edgeScrollJumpMultiplierInput.text?.toString()?.toFloatOrNull()
                ?: container.settingsRepository.edgeScrollJumpMultiplier.value
            val scrollDispatchIntervalMs = scrollDispatchIntervalInput.text?.toString()?.toIntOrNull()
                ?: container.settingsRepository.scrollDispatchIntervalMs.value
            val flingDurationMs = flingDurationInput.text?.toString()?.toIntOrNull()
                ?: container.settingsRepository.flingDurationMs.value
            val holdDragDelayMs = holdDragDelayInput.text?.toString()?.toIntOrNull()
                ?: container.settingsRepository.holdDragDelayMs.value
            val dragFlickKickMs = dragFlickKickInput.text?.toString()?.toIntOrNull()
                ?: container.settingsRepository.dragFlickKickMs.value
            val twoFingerScrollGraceMs = twoFingerScrollGraceInput.text?.toString()?.toIntOrNull()
                ?: container.settingsRepository.twoFingerScrollGraceMs.value
            val holdDragSlopPx = holdDragSlopInput.text?.toString()?.toFloatOrNull()
                ?: container.settingsRepository.holdDragSlopPx.value
            val doubleClickGuardMs = doubleClickGuardInput.text?.toString()?.toIntOrNull()
                ?: container.settingsRepository.doubleClickGuardMs.value
            val dragStartSlopPx = dragStartSlopInput.text?.toString()?.toFloatOrNull()
                ?: container.settingsRepository.dragStartSlopPx.value
            val hidMoveChunkSize = hidMoveChunkSizeInput.text?.toString()?.toIntOrNull()
                ?: container.settingsRepository.hidMoveChunkSize.value
            val pointerMoveDeadbandPx = pointerMoveDeadbandInput.text?.toString()?.toFloatOrNull()
                ?: container.settingsRepository.pointerMoveDeadbandPx.value
            val hapticTouchContactIntensity = hapticTouchContactInput.text?.toString()?.toIntOrNull()
                ?: container.settingsRepository.hapticTouchContactIntensity.value
            val hapticClickIntensity = hapticClickInput.text?.toString()?.toIntOrNull()
                ?: container.settingsRepository.hapticClickIntensity.value
            val hapticRightClickIntensity = hapticRightClickInput.text?.toString()?.toIntOrNull()
                ?: container.settingsRepository.hapticRightClickIntensity.value
            val hapticDragStartIntensity = hapticDragStartInput.text?.toString()?.toIntOrNull()
                ?: container.settingsRepository.hapticDragStartIntensity.value
            val hapticOverlayToggleIntensity = hapticOverlayToggleInput.text?.toString()?.toIntOrNull()
                ?: container.settingsRepository.hapticOverlayToggleIntensity.value
            val hapticFaultIntensity = hapticFaultInput.text?.toString()?.toIntOrNull()
                ?: container.settingsRepository.hapticFaultIntensity.value
            val hapticScrollStepIntensity = hapticScrollStepInput.text?.toString()?.toIntOrNull()
                ?: container.settingsRepository.hapticScrollStepIntensity.value
            val hapticEdgeHitIntensity = hapticEdgeHitInput.text?.toString()?.toIntOrNull()
                ?: container.settingsRepository.hapticEdgeHitIntensity.value
            val hapticEdgeScrollStartIntensity = hapticEdgeScrollStartInput.text?.toString()?.toIntOrNull()
                ?: container.settingsRepository.hapticEdgeScrollStartIntensity.value
            val mouseAccelerationEnabled = mouseAccelerationSwitch.isChecked
            val oneFingerEdgeScrollEnabled = oneFingerEdgeScrollSwitch.isChecked
            val tapToDragEnabled = tapToDragSwitch.isChecked
            val horizontalScrollInverted = invertHorizontalScrollSwitch.isChecked
            val verticalScrollInverted = invertVerticalScrollSwitch.isChecked
            val edgeHorizontalScrollInverted = invertEdgeHorizontalScrollSwitch.isChecked
            val edgeVerticalScrollInverted = invertEdgeVerticalScrollSwitch.isChecked
            val sideEdgeScrollEnabled = sideEdgeScrollSwitch.isChecked
            val bottomNavPassthroughEnabled = bottomNavPassthroughSwitch.isChecked
            val autoStartOnBootEnabled = autoStartOnBootSwitch.isChecked
            val debugOverlayEnabled = debugOverlaySwitch.isChecked
            val quickToggleOverlayEnabled = quickToggleOverlaySwitch.isChecked
            val quickToggleRightAutoRotateEnabled = quickToggleRightAutoRotateSwitch.isChecked
            val restoreCursorAfterEdgeScroll = restoreCursorAfterEdgeScrollSwitch.isChecked

            container.settingsRepository.setSpeedMultiplier(mouseSensitivity)
            container.settingsRepository.setVerticalScrollPixelsPerStep(verticalScrollPixelsPerStep)
            container.settingsRepository.setVerticalFollowupStepFactor(verticalFollowupStepFactor)
            container.settingsRepository.setHorizontalScrollPixelsPerStep(horizontalScrollPixelsPerStep)
            container.settingsRepository.setHorizontalFollowupStepFactor(horizontalFollowupStepFactor)
            container.settingsRepository.setSyncIntervalMs(syncIntervalMs)
            container.settingsRepository.setEdgeScrollRepeatDelayMs(edgeScrollRepeatDelayMs)
            container.settingsRepository.setEdgeScrollJumpMultiplier(edgeScrollJumpMultiplier)
            container.settingsRepository.setScrollDispatchIntervalMs(scrollDispatchIntervalMs)
            container.settingsRepository.setFlingDurationMs(flingDurationMs)
            container.settingsRepository.setHoldDragDelayMs(holdDragDelayMs)
            container.settingsRepository.setDragFlickKickMs(dragFlickKickMs)
            container.settingsRepository.setTwoFingerScrollGraceMs(twoFingerScrollGraceMs)
            container.settingsRepository.setHoldDragSlopPx(holdDragSlopPx)
            container.settingsRepository.setDoubleClickGuardMs(doubleClickGuardMs)
            container.settingsRepository.setDragStartSlopPx(dragStartSlopPx)
            container.settingsRepository.setHidMoveChunkSize(hidMoveChunkSize)
            container.settingsRepository.setPointerMoveDeadbandPx(pointerMoveDeadbandPx)
            container.settingsRepository.setHapticTouchContactIntensity(hapticTouchContactIntensity)
            container.settingsRepository.setHapticClickIntensity(hapticClickIntensity)
            container.settingsRepository.setHapticRightClickIntensity(hapticRightClickIntensity)
            container.settingsRepository.setHapticDragStartIntensity(hapticDragStartIntensity)
            container.settingsRepository.setHapticOverlayToggleIntensity(hapticOverlayToggleIntensity)
            container.settingsRepository.setHapticFaultIntensity(hapticFaultIntensity)
            container.settingsRepository.setHapticScrollStepIntensity(hapticScrollStepIntensity)
            container.settingsRepository.setHapticEdgeHitIntensity(hapticEdgeHitIntensity)
            container.settingsRepository.setHapticEdgeScrollStartIntensity(hapticEdgeScrollStartIntensity)
            container.settingsRepository.setMouseAccelerationEnabled(mouseAccelerationEnabled)
            container.settingsRepository.setOneFingerEdgeScrollEnabled(oneFingerEdgeScrollEnabled)
            container.settingsRepository.setTapToDragEnabled(tapToDragEnabled)
            container.settingsRepository.setHorizontalScrollInverted(horizontalScrollInverted)
            container.settingsRepository.setVerticalScrollInverted(verticalScrollInverted)
            container.settingsRepository.setEdgeHorizontalScrollInverted(edgeHorizontalScrollInverted)
            container.settingsRepository.setEdgeVerticalScrollInverted(edgeVerticalScrollInverted)
            container.settingsRepository.setSideEdgeScrollEnabled(sideEdgeScrollEnabled)
            container.settingsRepository.setBottomNavBarPassthroughEnabled(bottomNavPassthroughEnabled)
            container.settingsRepository.setAutoStartOnBootEnabled(autoStartOnBootEnabled)
            container.settingsRepository.setDebugOverlayEnabled(debugOverlayEnabled)
            container.settingsRepository.setQuickToggleOverlayEnabled(quickToggleOverlayEnabled)
            container.settingsRepository.setQuickToggleRightAutoRotateEnabled(quickToggleRightAutoRotateEnabled)
            container.settingsRepository.setRestoreCursorAfterEdgeScroll(restoreCursorAfterEdgeScroll)
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            Log.i(
                TAG,
                "save mouseSensitivity=$mouseSensitivity verticalScrollPixelsPerStep=$verticalScrollPixelsPerStep " +
                    "verticalFollowupStepFactor=$verticalFollowupStepFactor " +
                    "horizontalScrollPixelsPerStep=$horizontalScrollPixelsPerStep " +
                    "horizontalFollowupStepFactor=$horizontalFollowupStepFactor " +
                    "syncIntervalMs=$syncIntervalMs " +
                    "edgeScrollRepeatDelayMs=$edgeScrollRepeatDelayMs " +
                    "edgeScrollJumpMultiplier=$edgeScrollJumpMultiplier " +
                    "scrollDispatchIntervalMs=$scrollDispatchIntervalMs " +
                    "flingDurationMs=$flingDurationMs " +
                    "holdDragDelayMs=$holdDragDelayMs " +
                    "dragFlickKickMs=$dragFlickKickMs " +
                    "twoFingerScrollGraceMs=$twoFingerScrollGraceMs " +
                    "holdDragSlopPx=$holdDragSlopPx doubleClickGuardMs=$doubleClickGuardMs " +
                    "dragStartSlopPx=$dragStartSlopPx " +
                    "hidMoveChunkSize=$hidMoveChunkSize pointerMoveDeadbandPx=$pointerMoveDeadbandPx " +
                    "hapticTouchContactIntensity=$hapticTouchContactIntensity " +
                    "hapticClickIntensity=$hapticClickIntensity " +
                    "hapticRightClickIntensity=$hapticRightClickIntensity " +
                    "hapticDragStartIntensity=$hapticDragStartIntensity " +
                    "hapticOverlayToggleIntensity=$hapticOverlayToggleIntensity " +
                    "hapticFaultIntensity=$hapticFaultIntensity " +
                    "hapticScrollStepIntensity=$hapticScrollStepIntensity " +
                    "hapticEdgeHitIntensity=$hapticEdgeHitIntensity " +
                    "hapticEdgeScrollStartIntensity=$hapticEdgeScrollStartIntensity " +
                    "mouseAccelerationEnabled=$mouseAccelerationEnabled " +
                    "oneFingerEdgeScrollEnabled=$oneFingerEdgeScrollEnabled " +
                    "tapToDragEnabled=$tapToDragEnabled " +
                    "horizontalScrollInverted=$horizontalScrollInverted " +
                    "verticalScrollInverted=$verticalScrollInverted " +
                    "edgeHorizontalScrollInverted=$edgeHorizontalScrollInverted " +
                    "edgeVerticalScrollInverted=$edgeVerticalScrollInverted " +
                    "sideEdgeScrollEnabled=$sideEdgeScrollEnabled " +
                    "bottomNavPassthroughEnabled=$bottomNavPassthroughEnabled " +
                    "autoStartOnBootEnabled=$autoStartOnBootEnabled " +
                    "debugOverlayEnabled=$debugOverlayEnabled " +
                    "quickToggleOverlayEnabled=$quickToggleOverlayEnabled " +
                    "quickToggleRightAutoRotateEnabled=$quickToggleRightAutoRotateEnabled " +
                    "restoreCursorAfterEdgeScroll=$restoreCursorAfterEdgeScroll",
            )
        }

        toggleConnectionButton.setOnClickListener {
            val enabling = !(
                container.overlayDesired.value ||
                    container.overlayAttached.value ||
                    container.sessionManager.isConnected.value
                )
            if (
                enabling &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            lifecycleScope.launch {
                val active = container.overlayDesired.value || container.overlayAttached.value || container.sessionManager.isConnected.value
                if (active) {
                    container.autoConnectEnabled.value = false
                    container.overlayDesired.value = false
                    TouchpadAccessibilityService.instance?.requestManualReconcile(reason = "ui_toggle_off")
                    container.sessionManager.disconnect()
                } else {
                    container.autoConnectEnabled.value = true
                    val connected = container.sessionManager.connect()
                    if (connected) {
                        container.overlayDesired.value = true
                        TouchpadAccessibilityService.instance?.requestManualReconcile(reason = "ui_toggle_on")
                    }
                }
            }
        }

        launchShellDaemonButton.setOnClickListener {
            lifecycleScope.launch {
                daemonLaunchStatusValue.text = getString(R.string.experimental_shell_daemon_status_launching)
                daemonLaunchStatusValue.text = container.launchExperimentalShellDaemon()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    container.settingsRepository.speedMultiplier.collect { value ->
                        val current = mouseSensitivityInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            mouseSensitivityInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.verticalScrollPixelsPerStep.collect { value ->
                        val current = scrollSensitivityInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            scrollSensitivityInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.verticalFollowupStepFactor.collect { value ->
                        val current = verticalFollowupFactorInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            verticalFollowupFactorInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.horizontalScrollPixelsPerStep.collect { value ->
                        val current = horizontalScrollSensitivityInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            horizontalScrollSensitivityInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.horizontalFollowupStepFactor.collect { value ->
                        val current = horizontalFollowupFactorInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            horizontalFollowupFactorInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.syncIntervalMs.collect { value ->
                        val current = syncIntervalInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            syncIntervalInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.edgeScrollRepeatDelayMs.collect { value ->
                        val current = edgeScrollRepeatDelayInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            edgeScrollRepeatDelayInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.edgeScrollJumpMultiplier.collect { value ->
                        val current = edgeScrollJumpMultiplierInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            edgeScrollJumpMultiplierInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.scrollDispatchIntervalMs.collect { value ->
                        val current = scrollDispatchIntervalInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            scrollDispatchIntervalInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.flingDurationMs.collect { value ->
                        val current = flingDurationInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            flingDurationInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.holdDragDelayMs.collect { value ->
                        val current = holdDragDelayInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            holdDragDelayInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.dragFlickKickMs.collect { value ->
                        val current = dragFlickKickInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            dragFlickKickInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.twoFingerScrollGraceMs.collect { value ->
                        val current = twoFingerScrollGraceInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            twoFingerScrollGraceInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.holdDragSlopPx.collect { value ->
                        val current = holdDragSlopInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            holdDragSlopInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.doubleClickGuardMs.collect { value ->
                        val current = doubleClickGuardInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            doubleClickGuardInput.setText(formatted)
                        }
                    }
                }

                launch {
                    combine(
                        container.sessionManager.isConnected,
                        container.overlayDesired,
                        container.overlayAttached,
                    ) { connected, desired, attached -> Triple(connected, desired, attached) }
                        .collect { (connected, desired, attached) ->
                            val active = attached || desired || connected
                            toggleConnectionButton.text = if (active) {
                                "Disconnect Backend + Disable Touch Capture"
                            } else {
                                "Connect Backend + Enable Touch Capture"
                            }
                            updatePrimaryToggleButton(toggleConnectionButton, active)
                        }
                }

                launch {
                    container.settingsRepository.mouseAccelerationEnabled.collect { enabled ->
                        if (mouseAccelerationSwitch.isChecked != enabled) {
                            mouseAccelerationSwitch.isChecked = enabled
                        }
                    }
                }

                launch {
                    container.settingsRepository.oneFingerEdgeScrollEnabled.collect { enabled ->
                        if (oneFingerEdgeScrollSwitch.isChecked != enabled) {
                            oneFingerEdgeScrollSwitch.isChecked = enabled
                        }
                    }
                }

                launch {
                    container.settingsRepository.tapToDragEnabled.collect { enabled ->
                        if (tapToDragSwitch.isChecked != enabled) {
                            tapToDragSwitch.isChecked = enabled
                        }
                    }
                }

                launch {
                    container.settingsRepository.dragStartSlopPx.collect { value ->
                        val current = dragStartSlopInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            dragStartSlopInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.hidMoveChunkSize.collect { value ->
                        val current = hidMoveChunkSizeInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            hidMoveChunkSizeInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.pointerMoveDeadbandPx.collect { value ->
                        val current = pointerMoveDeadbandInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            pointerMoveDeadbandInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.hapticTouchContactIntensity.collect { value ->
                        val current = hapticTouchContactInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            hapticTouchContactInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.hapticClickIntensity.collect { value ->
                        val current = hapticClickInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            hapticClickInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.hapticRightClickIntensity.collect { value ->
                        val current = hapticRightClickInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            hapticRightClickInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.hapticDragStartIntensity.collect { value ->
                        val current = hapticDragStartInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            hapticDragStartInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.hapticOverlayToggleIntensity.collect { value ->
                        val current = hapticOverlayToggleInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            hapticOverlayToggleInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.hapticFaultIntensity.collect { value ->
                        val current = hapticFaultInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            hapticFaultInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.hapticScrollStepIntensity.collect { value ->
                        val current = hapticScrollStepInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            hapticScrollStepInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.hapticEdgeHitIntensity.collect { value ->
                        val current = hapticEdgeHitInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            hapticEdgeHitInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.hapticEdgeScrollStartIntensity.collect { value ->
                        val current = hapticEdgeScrollStartInput.text?.toString().orEmpty()
                        val formatted = value.toString()
                        if (current != formatted) {
                            hapticEdgeScrollStartInput.setText(formatted)
                        }
                    }
                }

                launch {
                    container.settingsRepository.horizontalScrollInverted.collect { enabled ->
                        if (invertHorizontalScrollSwitch.isChecked != enabled) {
                            invertHorizontalScrollSwitch.isChecked = enabled
                        }
                    }
                }

                launch {
                    container.settingsRepository.verticalScrollInverted.collect { enabled ->
                        if (invertVerticalScrollSwitch.isChecked != enabled) {
                            invertVerticalScrollSwitch.isChecked = enabled
                        }
                    }
                }

                launch {
                    container.settingsRepository.edgeHorizontalScrollInverted.collect { enabled ->
                        if (invertEdgeHorizontalScrollSwitch.isChecked != enabled) {
                            invertEdgeHorizontalScrollSwitch.isChecked = enabled
                        }
                    }
                }

                launch {
                    container.settingsRepository.edgeVerticalScrollInverted.collect { enabled ->
                        if (invertEdgeVerticalScrollSwitch.isChecked != enabled) {
                            invertEdgeVerticalScrollSwitch.isChecked = enabled
                        }
                    }
                }

                launch {
                    container.settingsRepository.sideEdgeScrollEnabled.collect { enabled ->
                        if (sideEdgeScrollSwitch.isChecked != enabled) {
                            sideEdgeScrollSwitch.isChecked = enabled
                        }
                    }
                }

                launch {
                    container.settingsRepository.bottomNavBarPassthroughEnabled.collect { enabled ->
                        if (bottomNavPassthroughSwitch.isChecked != enabled) {
                            bottomNavPassthroughSwitch.isChecked = enabled
                        }
                    }
                }

                launch {
                    container.settingsRepository.autoStartOnBootEnabled.collect { enabled ->
                        if (autoStartOnBootSwitch.isChecked != enabled) {
                            autoStartOnBootSwitch.isChecked = enabled
                        }
                    }
                }

                launch {
                    container.settingsRepository.debugOverlayEnabled.collect { enabled ->
                        if (debugOverlaySwitch.isChecked != enabled) {
                            debugOverlaySwitchSyncing = true
                            debugOverlaySwitch.isChecked = enabled
                            debugOverlaySwitchSyncing = false
                        }
                    }
                }

                launch {
                    container.settingsRepository.quickToggleOverlayEnabled.collect { enabled ->
                        if (quickToggleOverlaySwitch.isChecked != enabled) {
                            quickToggleOverlaySwitch.isChecked = enabled
                        }
                    }
                }

                launch {
                    container.settingsRepository.quickToggleRightAutoRotateEnabled.collect { enabled ->
                        if (quickToggleRightAutoRotateSwitch.isChecked != enabled) {
                            quickToggleRightAutoRotateSwitch.isChecked = enabled
                        }
                    }
                }

                launch {
                    container.settingsRepository.restoreCursorAfterEdgeScroll.collect { enabled ->
                        if (restoreCursorAfterEdgeScrollSwitch.isChecked != enabled) {
                            restoreCursorAfterEdgeScrollSwitch.isChecked = enabled
                        }
                    }
                }

                launch {
                    combine(
                        container.wirelessDebuggingPaired,
                        container.sessionManager.isConnected,
                    ) { paired, connected -> paired to connected }
                        .collect { (paired, connected) ->
                            refreshWirelessPairingStatus(paired = paired, connected = connected)
                        }
                }
            }
        }
        refreshSanityChecks()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onResume() {
        super.onResume()
        if (::accessibilityStatusCard.isInitialized) {
            refreshSanityChecks()
        }
    }

    private fun refreshSanityChecks() {
        refreshAccessibilityStatus()
        refreshDaemonStatus()
        refreshWirelessAdbStatus()
        refreshWirelessPairingStatus(
            paired = container.wirelessDebuggingPaired.value,
            connected = container.sessionManager.isConnected.value,
        )
    }

    private fun refreshAccessibilityStatus() {
        val enabled = isAccessibilityServiceEnabled()
        renderStatusCard(
            card = accessibilityStatusCard,
            valueView = accessibilityStatusValue,
            isPositive = enabled,
            text = getString(if (enabled) R.string.status_on else R.string.status_off),
        )
    }

    private fun refreshWirelessPairingStatus(
        paired: Boolean? = container.wirelessDebuggingPaired.value,
        connected: Boolean = container.sessionManager.isConnected.value,
    ) {
        val effectivePaired = if (connected) true else paired
        if (effectivePaired != null) {
            renderStatusCard(
                card = wirelessPairingStatusCard,
                valueView = wirelessPairingStatusValue,
                isPositive = effectivePaired,
                text = getString(if (effectivePaired) R.string.status_yes else R.string.status_no),
            )
            return
        }
        renderPendingStatusCard(
            card = wirelessPairingStatusCard,
            valueView = wirelessPairingStatusValue,
            text = getString(R.string.status_checking),
        )
    }

    private fun refreshWirelessAdbStatus(
        enabled: Boolean = container.wirelessAdbEnabled.value,
        daemonConnected: Boolean = container.shellDaemonActive.value,
    ) {
        val text = getString(if (enabled) R.string.status_on else R.string.status_off)
        when {
            enabled -> renderStatusCard(
                card = wirelessAdbStatusCard,
                valueView = wirelessAdbStatusValue,
                isPositive = true,
                text = text,
            )
            daemonConnected -> renderPendingStatusCard(
                card = wirelessAdbStatusCard,
                valueView = wirelessAdbStatusValue,
                text = text,
            )
            else -> renderStatusCard(
                card = wirelessAdbStatusCard,
                valueView = wirelessAdbStatusValue,
                isPositive = false,
                text = text,
            )
        }
    }

    private fun refreshDaemonStatus(connected: Boolean = container.shellDaemonActive.value) {
        renderStatusCard(
            card = daemonStatusCard,
            valueView = daemonStatusValue,
            isPositive = connected,
            text = getString(if (connected) R.string.status_yes else R.string.status_no),
        )
    }

    private fun renderPendingStatusCard(card: LinearLayout, valueView: TextView, text: String) {
        card.setBackgroundResource(R.drawable.bg_status_pending)
        valueView.text = text
    }

    private fun renderStatusCard(card: LinearLayout, valueView: TextView, isPositive: Boolean, text: String) {
        card.setBackgroundResource(
            if (isPositive) R.drawable.bg_status_positive else R.drawable.bg_status_negative,
        )
        valueView.text = text
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java) ?: return false
        val targetClassName = TouchpadAccessibilityService::class.java.name
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = info.resolveInfo.serviceInfo
                serviceInfo.packageName == packageName && serviceInfo.name == targetClassName
            }
    }

    private fun nextCommandSeq(): Long {
        return directCommandSeq.incrementAndGet()
    }

    private fun createCalibrationOverlay(message: String, progressFraction: Float): Dialog {
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        dialog.setCancelable(false)
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            isClickable = true
            isFocusable = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        val messageView = TextView(this).apply {
            tag = CALIBRATION_OVERLAY_TEXT_TAG
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            text = calibrationOverlayText(message, progressFraction)
        }
        val progressView = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            tag = CALIBRATION_OVERLAY_PROGRESS_TAG
            max = CALIBRATION_PROGRESS_MAX
            progress = (progressFraction.coerceIn(0f, 1f) * CALIBRATION_PROGRESS_MAX).toInt()
            isIndeterminate = false
        }
        content.addView(
            messageView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            progressView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 24
            },
        )
        container.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        dialog.setContentView(container)
        return dialog
    }

    private fun updateCalibrationOverlay(dialog: Dialog, message: String, progressFraction: Float) {
        val textView = dialog.window?.decorView?.findViewWithTag<TextView>(CALIBRATION_OVERLAY_TEXT_TAG) ?: return
        val progressView = dialog.window?.decorView?.findViewWithTag<ProgressBar>(CALIBRATION_OVERLAY_PROGRESS_TAG)
        textView.text = calibrationOverlayText(message, progressFraction)
        progressView?.progress = (progressFraction.coerceIn(0f, 1f) * CALIBRATION_PROGRESS_MAX).toInt()
    }

    private fun calibrationOverlayText(message: String, progressFraction: Float): String {
        val percent = (progressFraction.coerceIn(0f, 1f) * 100f).toInt()
        return "Calibrating cursor center mapping...\nPlease do not touch the screen.\n$message\n$percent%"
    }

    private fun updatePrimaryToggleButton(button: Button, active: Boolean) {
        button.setBackgroundResource(
            if (active) R.drawable.bg_tech_button_active else R.drawable.bg_tech_button_idle,
        )
        button.setTextColor(Color.parseColor("#F7FAFD"))
    }

    private companion object {
        const val TAG = "MainActivity"
        const val LOOPBACK_HOST = "127.0.0.1"
        const val CALIBRATION_OVERLAY_TEXT_TAG = "calibration_overlay_text"
        const val CALIBRATION_OVERLAY_PROGRESS_TAG = "calibration_overlay_progress"
        const val CALIBRATION_PROGRESS_MAX = 1000
        const val VIEW_PAGER_MOUSE_SCROLL_THRESHOLD = 0.5f
        const val VIEW_PAGER_MOUSE_SCROLL_DEBOUNCE_MS = 180L
        val directCommandSeq = AtomicLong(System.currentTimeMillis())
    }
}
