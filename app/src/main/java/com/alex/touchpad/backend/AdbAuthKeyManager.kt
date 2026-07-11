package com.alex.touchpad.backend

import java.io.File
import java.util.concurrent.TimeUnit

internal object AdbAuthKeyManager {
    @Synchronized
    fun ensureKeys(androidUserHome: File, adbBinary: File): Boolean {
        if (!androidUserHome.exists() && !androidUserHome.mkdirs()) {
            return false
        }

        val privateKeyFile = File(androidUserHome, PRIVATE_KEY_NAME)
        val publicKeyFile = File(androidUserHome, PUBLIC_KEY_NAME)
        val generatedMarkerFile = File(androidUserHome, GENERATED_MARKER_NAME)

        if (generatedMarkerFile.exists() && privateKeyFile.exists() && publicKeyFile.exists()) {
            return true
        }

        // Builds before the public release bundled one shared key. Rotate it once,
        // then let the packaged adb executable create a per-installation key pair.
        privateKeyFile.delete()
        publicKeyFile.delete()
        generatedMarkerFile.delete()

        val process = runCatching {
            ProcessBuilder(adbBinary.absolutePath, "keygen", privateKeyFile.absolutePath)
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return false

        val finished = runCatching {
            process.waitFor(KEYGEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!finished) {
            runCatching { process.destroy() }
            runCatching { process.waitFor(PROCESS_DESTROY_GRACE_MS, TimeUnit.MILLISECONDS) }
            if (process.isAlive) {
                runCatching { process.destroyForcibly() }
            }
        }

        val generated = finished &&
            runCatching { process.exitValue() == 0 }.getOrDefault(false) &&
            privateKeyFile.exists() &&
            publicKeyFile.exists()
        if (!generated) {
            privateKeyFile.delete()
            publicKeyFile.delete()
            return false
        }

        privateKeyFile.setReadable(false, false)
        privateKeyFile.setWritable(false, false)
        privateKeyFile.setExecutable(false, false)
        privateKeyFile.setReadable(true, true)
        privateKeyFile.setWritable(true, true)
        publicKeyFile.setReadable(true, false)
        publicKeyFile.setWritable(false, false)
        publicKeyFile.setExecutable(false, false)

        return runCatching {
            generatedMarkerFile.writeText("Generated locally by ADB Touchpad.\n")
            generatedMarkerFile.setReadable(true, true)
            generatedMarkerFile.setWritable(true, true)
            true
        }.getOrDefault(false)
    }

    private const val PRIVATE_KEY_NAME = "adbkey"
    private const val PUBLIC_KEY_NAME = "adbkey.pub"
    private const val GENERATED_MARKER_NAME = ".adb-touchpad-generated-v1"
    private const val KEYGEN_TIMEOUT_MS = 5_000L
    private const val PROCESS_DESTROY_GRACE_MS = 150L
}
