package com.alex.daemonspike

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SpikeMainActivity : AppCompatActivity() {
    private val bootstrapper by lazy { DaemonBootstrapper(this) }
    private val daemonPort = 53536
    private var daemonToken = DaemonBootstrapper.newToken()
    private var monitorJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_spike_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val tokenText = findViewById<TextView>(R.id.tokenText)
        val pairPortInput = findViewById<EditText>(R.id.pairPortInput)
        val pairCodeInput = findViewById<EditText>(R.id.pairCodeInput)
        val pairButton = findViewById<Button>(R.id.pairButton)
        val launchButton = findViewById<Button>(R.id.launchButton)
        val checkTargetButton = findViewById<Button>(R.id.checkTargetButton)
        val pingButton = findViewById<Button>(R.id.pingButton)
        val startMonitorButton = findViewById<Button>(R.id.startMonitorButton)
        val stopMonitorButton = findViewById<Button>(R.id.stopMonitorButton)
        val stopDaemonButton = findViewById<Button>(R.id.stopDaemonButton)
        val resetTokenButton = findViewById<Button>(R.id.resetTokenButton)

        fun renderStatus(line: String) {
            statusText.text = buildString {
                append(line)
                append("\n\nManual proof flow:\n")
                append("1. Open Wireless Debugging > Pair device with pairing code.\n")
                append("2. Enter that port and code here, then tap Pair Over ADB.\n")
                append("3. Tap Launch daemon.\n")
                append("4. Start monitor.\n")
                append("5. Turn hotspot off manually.\n")
                append("6. Watch whether PING keeps succeeding.\n")
            }
        }

        fun renderToken() {
            tokenText.text = getString(R.string.current_token, daemonToken, daemonPort)
        }

        renderToken()
        renderStatus("Ready")

        pairButton.setOnClickListener {
            lifecycleScope.launch {
                val pairPort = pairPortInput.text?.toString()?.trim().orEmpty().toIntOrNull()
                val pairCode = pairCodeInput.text?.toString()?.trim().orEmpty()
                if (pairPort == null) {
                    renderStatus("Enter a valid pairing port")
                    return@launch
                }
                if (pairCode.isBlank()) {
                    renderStatus("Enter the pairing code")
                    return@launch
                }
                renderStatus("Pairing over localhost ADB...")
                val result = bootstrapper.pairLocalhost(pairPort = pairPort, pairingCode = pairCode)
                renderStatus(result.message)
            }
        }

        launchButton.setOnClickListener {
            lifecycleScope.launch {
                renderStatus("Launching daemon over ADB...")
                val result = bootstrapper.launchDaemon(port = daemonPort, token = daemonToken)
                renderStatus(result.message)
            }
        }

        checkTargetButton.setOnClickListener {
            lifecycleScope.launch {
                renderStatus("Checking ADB target...")
                val result = bootstrapper.inspectFixedTarget()
                renderStatus(result.message)
            }
        }

        pingButton.setOnClickListener {
            lifecycleScope.launch {
                val result = runCatching { DaemonSocketClient.ping(port = daemonPort, token = daemonToken) }
                    .getOrElse { error -> "PING failed: ${error.message}" }
                renderStatus(result)
            }
        }

        startMonitorButton.setOnClickListener {
            monitorJob?.cancel()
            monitorJob = lifecycleScope.launch {
                while (isActive) {
                    val result = runCatching { DaemonSocketClient.ping(port = daemonPort, token = daemonToken) }
                        .getOrElse { error -> "PING failed: ${error.message}" }
                    renderStatus(result)
                    delay(1000L)
                }
            }
        }

        stopMonitorButton.setOnClickListener {
            monitorJob?.cancel()
            monitorJob = null
            renderStatus("Monitor stopped")
        }

        stopDaemonButton.setOnClickListener {
            lifecycleScope.launch {
                val result = bootstrapper.stopDaemon(port = daemonPort, token = daemonToken)
                renderStatus(result.message)
            }
        }

        resetTokenButton.setOnClickListener {
            daemonToken = DaemonBootstrapper.newToken()
            renderToken()
            renderStatus("Token rotated")
        }
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        super.onDestroy()
    }
}
