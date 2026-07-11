package com.alex.touchpad.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity

class LandscapeTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this))
    }
}
