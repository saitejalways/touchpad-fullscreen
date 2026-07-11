package com.alex.touchpad.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppLogLevel {
    WARNING,
    ERROR,
}

data class AppLogEntry(
    val timestampMs: Long,
    val level: AppLogLevel,
    val tag: String,
    val message: String,
)

object AppLog {
    private const val MAX_RECENT_ISSUES = 24

    private val lock = Any()
    private val _recentIssues = MutableStateFlow<List<AppLogEntry>>(emptyList())
    val recentIssues: StateFlow<List<AppLogEntry>> = _recentIssues.asStateFlow()

    fun v(tag: String, msg: String): Int = if (DebugFlags.ENABLED) Log.v(tag, msg) else 0
    fun v(tag: String, msg: String, tr: Throwable): Int =
        if (DebugFlags.ENABLED) Log.v(tag, msg, tr) else 0

    fun d(tag: String, msg: String): Int = if (DebugFlags.ENABLED) Log.d(tag, msg) else 0
    fun d(tag: String, msg: String, tr: Throwable): Int =
        if (DebugFlags.ENABLED) Log.d(tag, msg, tr) else 0

    fun i(tag: String, msg: String): Int = if (DebugFlags.ENABLED) Log.i(tag, msg) else 0
    fun i(tag: String, msg: String, tr: Throwable): Int =
        if (DebugFlags.ENABLED) Log.i(tag, msg, tr) else 0

    fun w(tag: String, msg: String): Int {
        appendIssue(level = AppLogLevel.WARNING, tag = tag, message = msg)
        return if (DebugFlags.ENABLED) Log.w(tag, msg) else 0
    }

    fun w(tag: String, msg: String, tr: Throwable): Int {
        appendIssue(level = AppLogLevel.WARNING, tag = tag, message = formatMessage(msg, tr))
        return if (DebugFlags.ENABLED) Log.w(tag, msg, tr) else 0
    }

    fun w(tag: String, tr: Throwable): Int {
        appendIssue(level = AppLogLevel.WARNING, tag = tag, message = formatMessage("", tr))
        return if (DebugFlags.ENABLED) Log.w(tag, tr) else 0
    }

    fun e(tag: String, msg: String): Int {
        appendIssue(level = AppLogLevel.ERROR, tag = tag, message = msg)
        return if (DebugFlags.ENABLED) Log.e(tag, msg) else 0
    }

    fun e(tag: String, msg: String, tr: Throwable): Int {
        appendIssue(level = AppLogLevel.ERROR, tag = tag, message = formatMessage(msg, tr))
        return if (DebugFlags.ENABLED) Log.e(tag, msg, tr) else 0
    }

    fun e(tag: String, tr: Throwable): Int {
        appendIssue(level = AppLogLevel.ERROR, tag = tag, message = formatMessage("", tr))
        return if (DebugFlags.ENABLED) Log.e(tag, "", tr) else 0
    }

    fun recordIssue(level: AppLogLevel, tag: String, message: String) {
        appendIssue(level = level, tag = tag, message = message)
    }

    private fun appendIssue(level: AppLogLevel, tag: String, message: String) {
        val trimmedMessage = message.trim().ifEmpty { "Unknown issue" }
        synchronized(lock) {
            val lastEntry = _recentIssues.value.lastOrNull()
            if (lastEntry?.level == level && lastEntry.tag == tag && lastEntry.message == trimmedMessage) {
                return
            }
            _recentIssues.value = (_recentIssues.value + AppLogEntry(
                timestampMs = System.currentTimeMillis(),
                level = level,
                tag = tag,
                message = trimmedMessage,
            )).takeLast(MAX_RECENT_ISSUES)
        }
    }

    private fun formatMessage(message: String, tr: Throwable): String {
        val throwableSummary = buildString {
            append(tr::class.java.simpleName)
            tr.message?.takeIf { it.isNotBlank() }?.let {
                append(": ")
                append(it)
            }
        }
        return when {
            message.isBlank() -> throwableSummary
            throwableSummary.isBlank() -> message
            else -> "$message ($throwableSummary)"
        }
    }
}
