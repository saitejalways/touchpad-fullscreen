package com.alex.touchpad.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.alex.touchpad.core.AppLog as Log
import com.alex.touchpad.settings.SettingsRepository
import com.alex.touchpad.ui.MainActivity

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_USER_UNLOCKED
        ) {
            return
        }
        val settingsRepository = SettingsRepository(context.applicationContext)
        if (!settingsRepository.autoStartOnBootEnabled.value) {
            return
        }
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        if (userManager?.isUserUnlocked == false) {
            Log.i(TAG, "Boot auto-start skipped: user is locked")
            return
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching {
            context.startActivity(launchIntent)
            Log.i(TAG, "Boot auto-start launched MainActivity")
        }.onFailure { error ->
            Log.w(TAG, "Boot auto-start failed to launch MainActivity", error)
        }
    }

    private companion object {
        const val TAG = "BootCompletedReceiver"
    }
}
