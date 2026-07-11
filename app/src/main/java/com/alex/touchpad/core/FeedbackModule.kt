package com.alex.touchpad.core

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.alex.touchpad.settings.SettingsRepository

class FeedbackModule(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    fun onOverlayToggle(enabled: Boolean) {
        val baseIntensity = settingsRepository.hapticOverlayToggleIntensity.value
        if (baseIntensity <= 0) {
            return
        }
        val duration = if (enabled) 25L else 15L
        val amplitude = if (enabled) baseIntensity else ((baseIntensity * 0.78f).toInt().coerceAtLeast(1))
        vibrate(duration, amplitude)
    }

    fun onTouchContact() {
        val intensity = settingsRepository.hapticTouchContactIntensity.value
        if (intensity > 0) {
            vibrate(8L, intensity)
        }
    }

    fun onClick() {
        val intensity = settingsRepository.hapticClickIntensity.value
        if (intensity > 0) {
            vibrate(14L, intensity)
        }
    }

    fun onRightClick() {
        val intensity = settingsRepository.hapticRightClickIntensity.value
        if (intensity > 0) {
            vibrate(22L, intensity)
        }
    }

    fun onDragStart() {
        val intensity = settingsRepository.hapticDragStartIntensity.value
        if (intensity > 0) {
            vibrate(20L, intensity)
        }
    }

    fun onFaultEntered() {
        val intensity = settingsRepository.hapticFaultIntensity.value
        if (intensity > 0) {
            vibrate(70L, intensity)
        }
    }

    fun onScrollStep() {
        val intensity = settingsRepository.hapticScrollStepIntensity.value
        if (intensity > 0) {
            vibrate(6L, intensity)
        }
    }

    fun onEdgeHit() {
        val intensity = settingsRepository.hapticEdgeHitIntensity.value
        if (intensity > 0) {
            vibrate(10L, intensity)
        }
    }

    fun onEdgeScrollStart() {
        val intensity = settingsRepository.hapticEdgeScrollStartIntensity.value
        if (intensity > 0) {
            vibrate(14L, intensity)
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(durationMs: Long, amplitude: Int) {
        val effect = VibrationEffect.createOneShot(durationMs, amplitude)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(VibratorManager::class.java)
            vm?.defaultVibrator?.vibrate(effect)
        } else {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.vibrate(effect)
        }
    }
}
