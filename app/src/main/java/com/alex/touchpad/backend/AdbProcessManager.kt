package com.alex.touchpad.backend

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.alex.touchpad.core.AppLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class AdbProcessResult(
    val exitCode: Int,
    val output: String,
)

class AdbProcessManager(private val context: Context) {
    private val binaryMutex = Mutex()

    suspend fun executePair(host: String, port: Int, code: String): Result<Unit> {
        val normalizedHost = host.trim().ifEmpty { "127.0.0.1" }
        val normalizedPort = port.coerceAtLeast(1)
        val normalizedCode = code.trim()
        if (normalizedCode.isEmpty()) {
            return Result.failure(Exception("Pairing code is empty"))
        }

        val adbBinary = ensureAdbBinary() ?: return Result.failure(Exception("ADB binary not available"))
        val result = runProcess(
            listOf(
                adbBinary.absolutePath,
                "pair",
                "$normalizedHost:$normalizedPort",
                normalizedCode,
            ),
            timeoutMs = PROCESS_TIMEOUT_PAIR_MS,
        )
        if (result.exitCode == 0) {
            Log.i(TAG, "adb pair succeeded target=$normalizedHost:$normalizedPort")
            return Result.success(Unit)
        }

        val msg = "adb pair failed target=$normalizedHost:$normalizedPort code=${result.exitCode} output=${result.output}"
        Log.w(TAG, msg)
        return Result.failure(Exception("adb pair failed code=${result.exitCode} output=${result.output.take(180)}"))
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

        var connectResult: AdbProcessResult? = null
        if (normalizedRequested.contains(":")) {
            connectResult = runProcess(
                listOf(
                    adbBinary.absolutePath,
                    "connect",
                    normalizedRequested,
                ),
                timeoutMs = PROCESS_TIMEOUT_CONNECT_MS,
            )
            if (connectResult.exitCode == 0 && isSerialReady(adbBinary, normalizedRequested)) {
                return normalizedRequested
            }
        }

        val availableSerials = listConnectedDeviceSerials(adbBinary)
        if (availableSerials.contains(normalizedRequested)) {
            return normalizedRequested
        }
        if (availableSerials.size == 1) {
            val fallbackSerial = availableSerials.first()
            if (connectResult != null) {
                Log.i(
                    TAG,
                    "Requested serial unavailable requested=$normalizedRequested; " +
                        "using detected serial=$fallbackSerial after connect result " +
                        "code=${connectResult.exitCode} output=${connectResult.output}",
                )
            }
            return fallbackSerial
        }

        val availableSummary = if (availableSerials.isEmpty()) "none" else availableSerials.joinToString(", ")
        if (connectResult != null) {
            Log.w(
                TAG,
                "adb connect did not yield ready requestedSerial=$normalizedRequested " +
                    "code=${connectResult.exitCode} output=${connectResult.output} available=$availableSummary",
            )
        }
        Log.w(
            TAG,
            "Unable to resolve target serial requested=$normalizedRequested available=$availableSummary",
        )
        return null
    }

    suspend fun isSerialReady(serial: String): Boolean {
        val adbBinary = ensureAdbBinary() ?: return false
        return isSerialReady(adbBinary, serial)
    }

    private suspend fun isSerialReady(adbBinary: File, serial: String): Boolean {
        val stateResult = runProcess(
            listOf(
                adbBinary.absolutePath,
                "-s",
                serial,
                "get-state",
            ),
            timeoutMs = PROCESS_TIMEOUT_STATE_MS,
        )
        if (stateResult.exitCode != 0) {
            return false
        }
        return stateResult.output
            .lineSequence()
            .map { it.trim() }
            .any { it == "device" }
    }

    suspend fun listConnectedDeviceSerials(): List<String> {
        val adbBinary = ensureAdbBinary() ?: return emptyList()
        return listConnectedDeviceSerials(adbBinary)
    }

    private suspend fun listConnectedDeviceSerials(adbBinary: File): List<String> {
        val devicesResult = runProcess(
            listOf(
                adbBinary.absolutePath,
                "devices",
                "-l",
            ),
            timeoutMs = PROCESS_TIMEOUT_STATE_MS,
        )
        if (devicesResult.exitCode != 0) {
            return emptyList()
        }
        return devicesResult.output
            .lineSequence()
            .map { it.trim() }
            .filter { line -> line.isNotBlank() && !line.startsWith("List of devices attached") }
            .mapNotNull { line ->
                val columns = line.split(Regex("\\s+"))
                if (columns.size < 2) {
                    return@mapNotNull null
                }
                val serial = columns[0]
                val state = columns[1]
                if (state == "device") serial else null
            }
            .toList()
    }

    suspend fun ensureAdbBinary(): File? = binaryMutex.withLock {
        // Preferred path: packaged native library directories are executable on Android.
        val packagedExec = findBundledAdbBinary()
        if (packagedExec != null) {
            return packagedExec
        }

        val assets = context.assets
        val supportedAbi = Build.SUPPORTED_ABIS.firstOrNull { abi ->
            assets.list("bin")?.contains(abi) == true
        }

        if (supportedAbi != null) {
            val destinationDir = File(context.filesDir, "adb-bin")
            if (!destinationDir.exists()) {
                destinationDir.mkdirs()
            }

            val destination = File(destinationDir, "adb")
            if (!destination.exists()) {
                val extracted = withContext(Dispatchers.IO) {
                    runCatching {
                        assets.open("bin/$supportedAbi/adb").use { input ->
                            destination.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        destination.setExecutable(true, false)
                    }.isSuccess
                }
                if (!extracted) {
                    return null
                }
            }
            return destination
        }

        return null
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

        return candidates.asSequence()
            .map(::File)
            .firstOrNull { it.exists() }
    }

    suspend fun runProcess(
        args: List<String>,
        timeoutMs: Long = PROCESS_TIMEOUT_SHELL_MS,
    ): AdbProcessResult = withContext(Dispatchers.IO) {
        runCatching {
            val startedAt = SystemClock.elapsedRealtime()
            val process = newProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val outputReader = thread(
                start = true,
                isDaemon = true,
                name = "adb-process-output",
            ) {
                runCatching {
                    process.inputStream.bufferedReader().use { reader ->
                        val buffer = CharArray(PROCESS_READ_BUFFER_CHARS)
                        while (true) {
                            val read = reader.read(buffer)
                            if (read <= 0) {
                                break
                            }
                            synchronized(output) {
                                output.append(buffer, 0, read)
                            }
                        }
                    }
                }
            }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { process.destroy() }
                process.waitFor(PROCESS_DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)
                if (process.isAlive) {
                    runCatching { process.destroyForcibly() }
                    process.waitFor(PROCESS_DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)
                }
                outputReader.join(PROCESS_READER_JOIN_MS)
                val timedOutMs = SystemClock.elapsedRealtime() - startedAt
                val timeoutOutput = synchronized(output) { output.toString().trim() }
                Log.w(TAG, "Process timeout (${timedOutMs}ms >= ${timeoutMs}ms) args=$args out=$timeoutOutput")
                return@runCatching AdbProcessResult(
                    exitCode = PROCESS_TIMEOUT_EXIT_CODE,
                    output = "timeout after ${timedOutMs}ms ${timeoutOutput}".trim(),
                )
            }

            outputReader.join(PROCESS_READER_JOIN_MS)
            val exitCode = process.exitValue()
            val finalOutput = synchronized(output) { output.toString().trim() }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            if (elapsedMs >= PROCESS_SLOW_LOG_MS) {
                Log.w(TAG, "Slow process ${elapsedMs}ms args=$args exit=$exitCode out=$finalOutput")
            }
            AdbProcessResult(exitCode = exitCode, output = finalOutput)
        }.getOrElse { error ->
            AdbProcessResult(
                exitCode = -1,
                output = error.message.orEmpty(),
            )
        }
    }

    suspend fun startProcess(args: List<String>): Process? = withContext(Dispatchers.IO) {
        runCatching {
            newProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
        }.getOrElse { error ->
            Log.w(TAG, "Failed to start process args=$args", error)
            null
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

    companion object {
        private const val TAG = "AdbProcessManager"
        private const val LIBADB_EXEC_NAME = "libadbexec.so"
        
        const val PROCESS_TIMEOUT_EXIT_CODE = -2
        const val PROCESS_TIMEOUT_SHELL_MS = 700L
        const val PROCESS_TIMEOUT_PAIR_MS = 6_000L
        const val PROCESS_TIMEOUT_CONNECT_MS = 2200L
        const val PROCESS_TIMEOUT_STATE_MS = 900L
        const val PROCESS_TIMEOUT_POINTER_DUMPSYS_MS = 900L
        const val PROCESS_TIMEOUT_WM_SIZE_MS = 900L
        const val PROCESS_DESTROY_GRACE_MS = 150L
        const val PROCESS_SLOW_LOG_MS = 350L
        const val PROCESS_READER_JOIN_MS = 200L
        const val PROCESS_READ_BUFFER_CHARS = 4_096

        private val COMMON_ABI_DIR_NAMES = listOf(
            "arm64",
            "arm64-v8a",
            "armeabi-v7a",
            "x86_64",
            "x86",
        )
    }
}
