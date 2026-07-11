package com.alex.touchpad.backend

import com.alex.touchpad.input.MouseButton

sealed interface WireCommand {
    data class MoveRel(val dx: Int, val dy: Int, val seq: Long, val ts: Long) : WireCommand
    data class ButtonDown(val button: MouseButton, val seq: Long, val ts: Long) : WireCommand
    data class ButtonUp(val button: MouseButton, val seq: Long, val ts: Long) : WireCommand
    data class Click(val button: MouseButton, val seq: Long, val ts: Long) : WireCommand
    data class Scroll(val dx: Int, val dy: Int, val seq: Long, val ts: Long) : WireCommand
    data class ScrollWheel(val vWheel: Int, val hWheel: Int, val seq: Long, val ts: Long) : WireCommand
    data class TouchContact(val active: Boolean, val seq: Long, val ts: Long) : WireCommand
    data class TouchDelta(val dx: Int, val dy: Int, val seq: Long, val ts: Long) : WireCommand
    data class Ping(val seq: Long, val ts: Long) : WireCommand
    data class Pair(val host: String, val port: Int, val code: String, val seq: Long, val ts: Long) : WireCommand
}
