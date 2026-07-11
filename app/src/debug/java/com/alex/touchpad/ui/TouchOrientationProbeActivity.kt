package com.alex.touchpad.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import java.util.concurrent.atomic.AtomicReference

class TouchOrientationProbeActivity : ComponentActivity() {

    data class TapSample(
        val rawX: Float,
        val rawY: Float,
        val localX: Float,
        val localY: Float,
    )

    private val latestTap = AtomicReference<TapSample?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = intent.getIntExtra(
            EXTRA_REQUESTED_ORIENTATION,
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        )
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    latestTap.set(
                        TapSample(
                            rawX = event.rawX,
                            rawY = event.rawY,
                            localX = event.x,
                            localY = event.y,
                        ),
                    )
                }
                true
            }
            requestFocus()
        }
        setContentView(root)
        hideSystemUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
        }
    }

    fun consumeLatestTap(): TapSample? = latestTap.getAndSet(null)

    fun clearLatestTap() {
        latestTap.set(null)
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }

    companion object {
        const val EXTRA_REQUESTED_ORIENTATION = "requested_orientation"
    }
}
