package com.alex.touchpad.input

enum class MouseButton {
    LEFT,
    RIGHT,
    MIDDLE,
}

enum class HapticFeedbackKind {
    TOUCH_CONTACT,
    CLICK,
    RIGHT_CLICK,
    DRAG_START,
}

sealed interface InputAction {
    data class MoveBy(val dx: Int, val dy: Int) : InputAction
    data class ScrollBy(val dx: Int, val dy: Int) : InputAction
    data class ScrollFling(
        val velocityXPerSecond: Float,
        val velocityYPerSecond: Float,
        val inertiaMs: Int,
    ) : InputAction
    data class TouchContact(val active: Boolean) : InputAction
    data class TouchDelta(val dx: Int, val dy: Int) : InputAction
    data class ButtonDown(val button: MouseButton) : InputAction
    data class ButtonUp(val button: MouseButton) : InputAction
    data class Click(val button: MouseButton) : InputAction
    data class Haptic(val kind: HapticFeedbackKind) : InputAction
}
