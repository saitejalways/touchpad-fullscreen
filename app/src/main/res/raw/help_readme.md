# ADB Touchpad Setup Guide

Use this phone as a touchpad for its own mouse cursor.

What you need:
- Developer options enabled
- USB debugging enabled
- Wireless debugging enabled
- Accessibility service enabled for this app

First-time pairing:
1. Open Android Settings -> Developer options.
2. Turn on USB debugging and Wireless debugging.
3. Open Wireless debugging.
4. Tap Pair device with pairing code.
5. Keep that Android popup open.
6. In this app, enter the pairing port and the 6-digit pairing code.
7. Tap Pair Wireless Debugging.
8. Tap Open Accessibility Settings and enable ADB Touchpad Service.
9. Return to the app.
10. Tap Connect Backend + Enable Touch Capture.

Healthy state:
- Runtime Mode: ARMED or ACTIVE
- ADB Session: ONLINE
- Touch Capture: ACTIVE after you enable it

Daily use:
1. Open the app.
2. Tap Connect Backend + Enable Touch Capture.
3. Use the screen like a touchpad.

If Android forgets pairing after a reboot or after Wireless debugging changes, pair again with a fresh code.

Useful settings:
- Mouse sensitivity controls cursor speed.
- Vertical and horizontal scroll pixels per event control two-finger scroll speed.
- Edge scroll activation distance in px controls how far you must push past an edge.
- Invert horizontal scroll direction and Invert vertical scroll direction change scroll feel.
- Enable edge-push scroll on left and right edges lets you disable side-entry scrolling.
- Restore cursor to pre-scroll position after edge scroll returns the cursor after one-finger edge scrolling ends.

One-finger edge scroll:
1. Move the cursor to a screen edge.
2. Keep pushing your finger farther in that same direction.
3. After the activation distance is reached, edge scroll starts.

Notes:
- Edge activation uses push distance, not a timer.
- Once edge scroll starts, you can scroll in any direction.
- Edge highlights only show when the debug diagnostics overlay is enabled.

Cursor calibration:
- Use Calibrate Cursor Center Mapping if center snap or restore feels off.
- The app stores separate calibration for portrait and landscape.
- During calibration, do not touch the screen until it finishes.

Troubleshooting:
- If pairing fails, reopen Pair device with pairing code and enter the fresh port and code quickly.
- If connect fails, make sure Wireless debugging is still enabled and re-pair if needed.
- If touch capture does not start, confirm the accessibility service is still enabled.
- If the app is killed in the background, set battery usage to Unrestricted if your phone offers that option.

Technical notes:
- The app uses a bundled on-device adb binary.
- Cursor movement and scroll output are sent through an ADB/HID path.
- No root is required.
