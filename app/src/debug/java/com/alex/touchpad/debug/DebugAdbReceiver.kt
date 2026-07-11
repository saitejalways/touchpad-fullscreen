package com.alex.touchpad.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.alex.touchpad.BuildConfig
import com.alex.touchpad.TouchpadApplication
import com.alex.touchpad.core.AppLog
import com.alex.touchpad.input.InputAction
import com.alex.touchpad.ui.LandscapeTestActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DebugAdbReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.GLOBAL_DEBUG) {
            return
        }
        val app = context.applicationContext as? TouchpadApplication ?: return
        when (intent.getStringExtra(EXTRA_COMMAND)) {
            COMMAND_PIN_LANDSCAPE -> {
                val launchIntent = Intent(context, LandscapeTestActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(launchIntent)
                AppLog.i(TAG, "adb debug pinned landscape activity")
            }

            COMMAND_LOG_STATE -> {
                CoroutineScope(Dispatchers.Default).launch {
                    val cursor = app.appContainer.debugQueryCursorGroundTruth()
                    val settings = app.appContainer.settingsRepository
                    AppLog.i(
                        TAG,
                        "adb debug state cursor=$cursor calibPortrait=${settings.cursorCalibrationUnitsPerPxX.value},${settings.cursorCalibrationUnitsPerPxY.value} " +
                            "calibLandscape=${settings.cursorCalibrationLandscapeUnitsPerPxX.value},${settings.cursorCalibrationLandscapeUnitsPerPxY.value}",
                    )
                }
            }

            COMMAND_SWEEP_TOP_LEFT -> {
                val repeat = intent.getIntExtra(EXTRA_REPEAT, 6).coerceAtLeast(1)
                CoroutineScope(Dispatchers.Default).launch {
                    repeat(repeat) {
                        app.appContainer.actionRouter.route(InputAction.MoveBy(-8_000, -8_000))
                    }
                }
                AppLog.i(TAG, "adb debug sweep top-left repeat=$repeat")
            }

            COMMAND_CALIBRATION_CORNER_SWEEP -> {
                CoroutineScope(Dispatchers.Default).launch {
                    val ok = app.appContainer.debugMoveCursorToTopLeftForCalibration()
                    val cursor = app.appContainer.debugQueryCursorGroundTruth()
                    AppLog.i(TAG, "adb debug calibration corner sweep ok=$ok cursor=$cursor")
                }
            }

            COMMAND_CALIBRATION_PROBE -> {
                CoroutineScope(Dispatchers.Default).launch {
                    val probe = app.appContainer.debugProbeCalibration()
                    AppLog.i(TAG, "adb debug calibration probe result=$probe")
                }
            }

            COMMAND_SHELL_DAEMON_LAUNCH -> {
                CoroutineScope(Dispatchers.Default).launch {
                    val result = app.appContainer.launchExperimentalShellDaemon()
                    AppLog.i(TAG, "adb debug shell daemon launch result=$result")
                }
            }

            COMMAND_SHELL_DAEMON_PING -> {
                CoroutineScope(Dispatchers.Default).launch {
                    val result = app.appContainer.pingExperimentalShellDaemon()
                    AppLog.i(TAG, "adb debug shell daemon ping result=$result")
                }
            }

            COMMAND_SHELL_DAEMON_QUERY_CURSOR -> {
                CoroutineScope(Dispatchers.Default).launch {
                    val result = app.appContainer.queryExperimentalShellDaemonGroundTruth()
                    AppLog.i(TAG, "adb debug shell daemon cursor result=$result")
                }
            }
        }
    }

    private companion object {
        const val TAG = "DebugAdbReceiver"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_REPEAT = "repeat"
        const val COMMAND_PIN_LANDSCAPE = "pin_landscape"
        const val COMMAND_LOG_STATE = "log_state"
        const val COMMAND_SWEEP_TOP_LEFT = "sweep_top_left"
        const val COMMAND_CALIBRATION_CORNER_SWEEP = "calibration_corner_sweep"
        const val COMMAND_CALIBRATION_PROBE = "calibration_probe"
        const val COMMAND_SHELL_DAEMON_LAUNCH = "shell_daemon_launch"
        const val COMMAND_SHELL_DAEMON_PING = "shell_daemon_ping"
        const val COMMAND_SHELL_DAEMON_QUERY_CURSOR = "shell_daemon_query_cursor"
    }
}
