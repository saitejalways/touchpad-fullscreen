package com.alex.daemonspike

import android.content.Context
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class SpikeProcessResult(
    val exitCode: Int,
    val output: String,
)

data class SpikeConnectedDevice(
    val serial: String,
    val state: String,
    val rawLine: String,
)

class SpikeAdbProcessManager(private val context: Context) {
    private val binaryMutex = Mutex()

    suspend fun pairLocalhost(pairPort: Int, pairingCode: String): SpikeProcessResult {
        val adbBinary = ensureAdbBinary() ?: return SpikeProcessResult(-1, "adb missing")
        return runProcess(
            listOf(
                adbBinary.absolutePath,
                "pair",
                "127.0.0.1:$pairPort",
                pairingCode.trim(),
            ),
            timeoutMs = PROCESS_TIMEOUT_PAIR_MS,
        )
    }

    suspend fun ensureTargetConnected(requestedSerial: String): String? {
        val adbBinary = ensureAdbBinary() ?: return null
        val normalizedRequested = requestedSerial.trim()
        if (normalizedRequested.isEmpty()) {
            return null
        }
        if (isSerialReady(adbBinary, normalizedRequested)) {
            return normalizedRequested
        }
        val connectResult = runProcess(
            listOf(adbBinary.absolutePath, "connect", normalizedRequested),
            timeoutMs = PROCESS_TIMEOUT_CONNECT_MS,
        )
        return if (connectResult.exitCode == 0 && isSerialReady(adbBinary, normalizedRequested)) {
            normalizedRequested
        } else {
            null
        }
    }

    suspend fun resolveRuntimeSerial(): String? {
        val adbBinary = ensureAdbBinary() ?: return null
        if (isSerialReady(adbBinary, FIXED_TARGET_SERIAL)) {
            return FIXED_TARGET_SERIAL
        }
        val devices = listConnectedDevices(adbBinary)
        return devices.firstOrNull { it.state == "device" && it.serial.startsWith("adb-") }?.serial
            ?: devices.firstOrNull { it.state == "device" }?.serial
    }

    suspend fun inspectTarget(requestedSerial: String): String {
        val adbBinary = ensureAdbBinary()
            ?: return "adb missing"
        val normalizedRequested = requestedSerial.trim()
        if (normalizedRequested.isEmpty()) {
            return "requested serial is empty"
        }
        val devicesResult = runProcess(
            listOf(adbBinary.absolutePath, "devices", "-l"),
            timeoutMs = PROCESS_TIMEOUT_DEVICES_MS,
        )
        val connectResult = runProcess(
            listOf(adbBinary.absolutePath, "connect", normalizedRequested),
            timeoutMs = PROCESS_TIMEOUT_CONNECT_MS,
        )
        val stateResult = runProcess(
            listOf(adbBinary.absolutePath, "-s", normalizedRequested, "get-state"),
            timeoutMs = PROCESS_TIMEOUT_STATE_MS,
        )
        val runtimeSerial = resolveRuntimeSerial()
        return buildString {
            appendLine("Binary: ${adbBinary.absolutePath}")
            appendLine("Target: $normalizedRequested")
            appendLine("Resolved runtime serial: ${runtimeSerial ?: "<none>"}")
            appendLine()
            appendLine("adb devices -l")
            appendLine("exit=${devicesResult.exitCode}")
            appendLine(devicesResult.output.ifBlank { "<empty>" })
            appendLine()
            appendLine("adb connect $normalizedRequested")
            appendLine("exit=${connectResult.exitCode}")
            appendLine(connectResult.output.ifBlank { "<empty>" })
            appendLine()
            appendLine("adb -s $normalizedRequested get-state")
            appendLine("exit=${stateResult.exitCode}")
            append(stateResult.output.ifBlank { "<empty>" })
        }
    }

    suspend fun runShell(serial: String, shellCommand: String, timeoutMs: Long = PROCESS_TIMEOUT_SHELL_MS): SpikeProcessResult {
        val adbBinary = ensureAdbBinary() ?: return SpikeProcessResult(-1, "adb missing")
        return runProcess(
            listOf(adbBinary.absolutePath, "-s", serial, "shell", shellCommand),
            timeoutMs = timeoutMs,
        )
    }

    suspend fun ensureAdbBinary(): File? = binaryMutex.withLock {
        val packagedExec = findBundledAdbBinary()
        if (packagedExec != null) {
            return packagedExec
        }
        val assets = context.assets
        val supportedAbi = Build.SUPPORTED_ABIS.firstOrNull { abi ->
            assets.list("bin")?.contains(abi) == true
        } ?: return null
        val destinationDir = File(context.filesDir, "adb-bin")
        if (!destinationDir.exists()) {
            destinationDir.mkdirs()
        }
        val destination = File(destinationDir, "adb")
        if (!destination.exists()) {
            val extracted = withContext(Dispatchers.IO) {
                runCatching {
                    assets.open("bin/$supportedAbi/adb").use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    }
                    destination.setExecutable(true, false)
                }.isSuccess
            }
            if (!extracted) {
                return null
            }
        }
        destination
    }

    private suspend fun isSerialReady(adbBinary: File, serial: String): Boolean {
        val stateResult = runProcess(
            listOf(adbBinary.absolutePath, "-s", serial, "get-state"),
            timeoutMs = PROCESS_TIMEOUT_STATE_MS,
        )
        return stateResult.exitCode == 0 && stateResult.output.lineSequence().any { it.trim() == "device" }
    }

    private suspend fun listConnectedDevices(adbBinary: File): List<SpikeConnectedDevice> {
        val devicesResult = runProcess(
            listOf(adbBinary.absolutePath, "devices", "-l"),
            timeoutMs = PROCESS_TIMEOUT_DEVICES_MS,
        )
        if (devicesResult.exitCode != 0) {
            return emptyList()
        }
        return devicesResult.output
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("List of devices attached") }
            .mapNotNull { line ->
                val columns = line.split(Regex("\\s+"))
                if (columns.size < 2) {
                    return@mapNotNull null
                }
                SpikeConnectedDevice(
                    serial = columns[0],
                    state = columns[1],
                    rawLine = line,
                )
            }
            .toList()
    }

    private suspend fun runProcess(
        args: List<String>,
        timeoutMs: Long,
    ): SpikeProcessResult = withContext(Dispatchers.IO) {
        runCatching {
            val startedAt = SystemClock.elapsedRealtime()
            val process = newProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { process.destroy() }
                process.waitFor(PROCESS_DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)
                if (process.isAlive) {
                    runCatching { process.destroyForcibly() }
                }
                return@runCatching SpikeProcessResult(
                    PROCESS_TIMEOUT_EXIT_CODE,
                    "timeout after ${SystemClock.elapsedRealtime() - startedAt}ms",
                )
            }
            SpikeProcessResult(
                exitCode = process.exitValue(),
                output = process.inputStream.bufferedReader().readText().trim(),
            )
        }.getOrElse { error ->
            SpikeProcessResult(exitCode = -1, output = error.message.orEmpty())
        }
    }

    private fun newProcessBuilder(args: List<String>): ProcessBuilder {
        val adbHome = File(context.noBackupFilesDir, "adb-home")
        val androidUserHome = File(adbHome, ".android")
        adbHome.mkdirs()
        androidUserHome.mkdirs()
        args.firstOrNull()
            ?.let(::File)
            ?.takeIf { it.exists() }
            ?.let { adbBinary -> AdbAuthKeyManager.ensureKeys(androidUserHome, adbBinary) }
        return ProcessBuilder(args).apply {
            environment()["HOME"] = adbHome.absolutePath
            environment()["ANDROID_USER_HOME"] = adbHome.absolutePath
            environment()["ANDROID_SDK_HOME"] = adbHome.absolutePath
            environment()["ADB_VENDOR_KEYS"] = androidUserHome.absolutePath
            environment()["TMPDIR"] = context.cacheDir.absolutePath
        }
    }

    private fun findBundledAdbBinary(): File? {
        val candidates = linkedSetOf<String>()
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        if (!nativeLibraryDir.isNullOrBlank()) {
            candidates += File(nativeLibraryDir, LIBADB_EXEC_NAME).absolutePath
        }
        val sourceDir = context.applicationInfo.sourceDir
        val apkParent = if (sourceDir.isNullOrBlank()) null else File(sourceDir).parentFile
        if (apkParent != null) {
            val abiCandidates = (Build.SUPPORTED_ABIS.toList() + COMMON_ABI_DIR_NAMES).distinct()
            for (abi in abiCandidates) {
                candidates += File(apkParent, "lib/$abi/$LIBADB_EXEC_NAME").absolutePath
            }
        }
        return candidates.asSequence().map(::File).firstOrNull { it.exists() }
    }

    companion object {
        const val FIXED_TARGET_SERIAL = "127.0.0.1:5555"

        private const val LIBADB_EXEC_NAME = "libadbexec.so"
        private const val PROCESS_TIMEOUT_EXIT_CODE = -2
        private const val PROCESS_TIMEOUT_SHELL_MS = 2_500L
        private const val PROCESS_TIMEOUT_CONNECT_MS = 2_200L
        private const val PROCESS_TIMEOUT_DEVICES_MS = 1_500L
        private const val PROCESS_TIMEOUT_PAIR_MS = 6_000L
        private const val PROCESS_TIMEOUT_STATE_MS = 900L
        private const val PROCESS_DESTROY_GRACE_MS = 150L
        private val COMMON_ABI_DIR_NAMES = listOf("arm64", "arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    }
}
