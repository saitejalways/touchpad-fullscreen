package com.alex.touchpad.ui

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.alex.touchpad.core.AppLog as Log

class ScrollAnchorProbeActivity : ComponentActivity() {
    lateinit var leftScrollView: ScrollView
        private set
    lateinit var rightScrollView: ScrollView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        leftScrollView = buildProbeScroll(prefix = "LEFT")
        rightScrollView = buildProbeScroll(prefix = "RIGHT")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(
                leftScrollView,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
            )
            addView(
                rightScrollView,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
            )
        }
        setContentView(root)
        leftScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            Log.i(TAG, "leftScrollY=$scrollY")
        }
        rightScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            Log.i(TAG, "rightScrollY=$scrollY")
        }
        Log.i(TAG, "probe_ready")
    }

    private fun buildProbeScroll(prefix: String): ScrollView {
        val content = TextView(this).apply {
            text = buildString {
                for (i in 1..240) {
                    append(prefix)
                    append(" row ")
                    append(i)
                    append('\n')
                }
            }
            textSize = 20f
        }
        return ScrollView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private companion object {
        const val TAG = "ScrollAnchorProbe"
    }
}
