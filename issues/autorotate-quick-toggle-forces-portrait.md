# Autorotate quick-toggle forced portrait when disabling auto-rotate

## Symptom

Using the right quick-toggle button to disable auto-rotate could immediately force the phone into portrait, even if the device was currently in landscape.

## Cause

The quick-toggle implementation in `TouchpadAccessibilityService` only changed:

- `Settings.System.ACCELEROMETER_ROTATION`

When auto-rotate was turned off, Android then used:

- `Settings.System.USER_ROTATION`

as the fixed orientation. On affected devices, `USER_ROTATION` was often still set to portrait, so disabling auto-rotate snapped the device to portrait instead of preserving the current orientation.

## Resolution

Before writing `ACCELEROMETER_ROTATION = 0`, the quick-toggle now:

1. reads the current display rotation
2. writes that value into `Settings.System.USER_ROTATION`
3. disables auto-rotate

This preserves the current orientation while still making the button behave as a simple auto-rotate toggle.

## File changed

- `app/src/main/java/com/alex/touchpad/service/TouchpadAccessibilityService.kt`
