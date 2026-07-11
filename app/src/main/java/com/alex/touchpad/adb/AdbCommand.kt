package com.alex.touchpad.adb

import com.alex.touchpad.input.MouseButton

sealed interface AdbCommand {
    val seq: Long
    val ts: Long

    data class MoveRel(
        val dx: Int,
        val dy: Int,
        override val seq: Long,
        override val ts: Long,
    ) : AdbCommand

    data class ButtonDown(
        val button: MouseButton,
        override val seq: Long,
        override val ts: Long,
    ) : AdbCommand

    data class ButtonUp(
        val button: MouseButton,
        override val seq: Long,
        override val ts: Long,
    ) : AdbCommand

    data class Click(
        val button: MouseButton,
        override val seq: Long,
        override val ts: Long,
    ) : AdbCommand

    data class Scroll(
        val dx: Int,
        val dy: Int,
        override val seq: Long,
        override val ts: Long,
    ) : AdbCommand

    data class ScrollWheel(
        val vWheel: Int,
        val hWheel: Int,
        override val seq: Long,
        override val ts: Long,
    ) : AdbCommand

    data class TouchContact(
        val active: Boolean,
        override val seq: Long,
        override val ts: Long,
    ) : AdbCommand

    data class TouchDelta(
        val dx: Int,
        val dy: Int,
        override val seq: Long,
        override val ts: Long,
    ) : AdbCommand

    data class Ping(
        override val seq: Long,
        override val ts: Long,
    ) : AdbCommand

    data class Pair(
        val host: String,
        val port: Int,
        val code: String,
        override val seq: Long,
        override val ts: Long,
    ) : AdbCommand
}
