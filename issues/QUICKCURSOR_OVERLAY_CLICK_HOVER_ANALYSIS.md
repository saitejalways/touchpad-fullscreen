# Quick Cursor: Why It Can Click "Under" an Overlay + Hover Feasibility

Date: 2026-03-05  
Analyzed from a locally decompiled Quick Cursor APK.

## 1. Short Answer

Quick Cursor is not physically forwarding your finger touch through the overlay.  
It captures touch in overlay views, computes a target screen coordinate, and then asks `AccessibilityService` to perform the click at that coordinate.

So the click under overlay happens via **accessibility-injected action**, not normal touch pass-through.

## 2. Evidence in Code (Click Path)

## 2.1 Accessibility service is configured for gestures

- `AndroidManifest.xml` declares `CursorAccessibilityService`: `android.permission.BIND_ACCESSIBILITY_SERVICE`.  
  File: `AndroidManifest.xml:93-98`
- Accessibility config enables gesture injection capability: `android:canPerformGestures="true"`.  
  File: `res/xml/cursor_accessibility_service.xml:2`

## 2.2 Click action goes through `services/statics/i.a(...)`

- Main click call path (tracker/cursor manager) invokes:
  - `invoke-static {service, x, y, true}, i->a(...)`
  - File: `smali/d7/b.smali:1379-1412`

## 2.3 `i.a(...)` does one of two click strategies at target `(x,y)`

From `smali/com/quickcursor/services/statics/i.smali`:

1. Node action path:
- If preference `K0` is enabled, it calls `i.f(service, ACTION_CLICK(16), x, y)`.
- File: `i.smali:78-90`
- `i.f(...)` walks windows and nodes at coordinate; `i.g(...)` calls `AccessibilityNodeInfo.performAction(action)`.
- Files: `i.smali:650-710` and specifically `performAction` at `698`

2. Gesture injection path:
- Builds a `GestureDescription` with a tiny stroke at `(x,y)` and dispatches it.
- Files: `i.smali:93-124`, then `i.h(...)` at `712-755`
- Dispatch call: `AccessibilityService.dispatchGesture(...)`.

This is the core reason it can click UI below an overlay window.

## 3. Overlay Layer Behavior (What sits on top)

Quick Cursor creates overlay windows with type value `0x7f0` (accessibility overlay type):

- Tracker overlay: `smali/f7/l.smali:278-292` (type `0x7f0`, flags `0x40328`)
- Trigger overlay: `smali/f7/n.smali:111-137` (type `0x7f0`, flags `0x328`)
- Global drawing overlay: `smali/o7/a.smali:234-251` (type `0x7f0`, flags `0x318`)

It also toggles tracker flags between:
- `0x40328` and `0x40338`  
  File: `smali/f7/l.smali:626-654`

Difference is `+0x10` (commonly `FLAG_NOT_TOUCHABLE`), so in some states it intentionally changes whether that overlay captures touch.

## 4. Why "Click Under Overlay" Still Works

Even if overlay is on top visually:

1. Quick Cursor already knows cursor/target coordinates.
2. It sends an accessibility click (node action or dispatched gesture) to system accessibility pipeline.
3. Android executes action on underlying app/window at those coordinates.

So this is **injected interaction**, not direct touch penetration.

## 5. Hover Under Overlay: What Is Feasible

## 5.1 True mouse hover (real pointer hover events)

With Quick Cursor-style accessibility-only pipeline, true OS-level mouse hover is generally not available:

- `dispatchGesture` injects touch-style gestures, not a persistent mouse hover stream.
- Node `performAction` also triggers semantic actions, not continuous pointer hover events.

## 5.2 Pseudo-hover options (feasible now)

1. Accessibility-focus hover approximation
- Hit-test node under current cursor coordinate (`getWindows/getRoot`) and apply accessibility focus.
- Useful for reading/highlighting flows, not equal to app-native mouse hover.

2. Internal UI hover approximation
- Keep your own hover state machine in overlay engine.
- Trigger dwell-based preview/tooltip behavior in your app logic.

## 5.3 True hover option (requires mouse-source injection path)

To get real hover semantics in target apps, use a mouse-source injection channel (your ADB mouse-emulation direction):

- Send continuous mouse move events (not touch gestures) at cursor coordinates.
- Then target apps that handle pointer hover can receive actual hover-like behavior.

This aligns with your ADB-first architecture direction; it is the realistic path to true hover behavior.

## 6. Practical Recommendation

1. Keep current accessibility click path for reliable click/tap compatibility.
2. If hover is a hard requirement, implement it only in ADB mouse-emulation mode.
3. Treat accessibility-only hover as approximation, not true mouse hover.

## 7. Key Files Referenced

- `AndroidManifest.xml`
- `res/xml/cursor_accessibility_service.xml`
- `smali/d7/b.smali`
- `smali/com/quickcursor/services/statics/i.smali`
- `smali/f7/l.smali`
- `smali/f7/n.smali`
- `smali/o7/a.smali`
