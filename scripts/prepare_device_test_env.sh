#!/usr/bin/env bash
set -euo pipefail

ADB_BIN="${ADB_BIN:-adb}"
DEVICE_SERIAL="${DEVICE_SERIAL:?Set DEVICE_SERIAL to the target shown by 'adb devices -l'}"
export ADB_BIN DEVICE_SERIAL
APP_PACKAGE="com.alex.touchpad"
SERVICE_COMPONENT="${APP_PACKAGE}/com.alex.touchpad.service.TouchpadAccessibilityService"
MAIN_ACTIVITY_COMPONENT="${APP_PACKAGE}/.ui.MainActivity"

if ! command -v "$ADB_BIN" >/dev/null 2>&1 && [[ ! -x "$ADB_BIN" ]]; then
  echo "adb not found or executable: $ADB_BIN" >&2
  exit 1
fi

adb_cmd() {
  "$ADB_BIN" -s "$DEVICE_SERIAL" "$@"
}

start_main_activity() {
  # NEW_TASK | CLEAR_TOP to ensure MainActivity is foreground even if probe/testing activities are open.
  adb_cmd shell am start -n "$MAIN_ACTIVITY_COMPONENT" -f 0x14000000 >/dev/null
}

is_service_bound_once() {
  local dump
  dump="$(adb_cmd shell dumpsys accessibility | tr -d '\r' || true)"
  [[ "$dump" == *"id=com.alex.touchpad/.service.TouchpadAccessibilityService"* ]]
}

wait_for_service_bound() {
  local attempts=25
  while (( attempts > 0 )); do
    if is_service_bound_once; then
      return 0
    fi
    sleep 0.2
    attempts=$((attempts - 1))
  done
  return 1
}

toggle_accessibility_cycle() {
  local merged_services="$1"
  adb_cmd shell settings put secure accessibility_enabled 0
  adb_cmd shell settings delete secure enabled_accessibility_services
  sleep 1
  adb_cmd shell settings put secure enabled_accessibility_services "$merged_services"
  adb_cmd shell settings put secure accessibility_enabled 1
  start_main_activity
  sleep 1
}

extract_toggle_bounds() {
  local node_line="$1"
  local line="$node_line"
  if [[ -z "$line" ]]; then
    return 1
  fi
  if [[ "$line" =~ bounds=\"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]\" ]]; then
    echo "${BASH_REMATCH[1]} ${BASH_REMATCH[2]} ${BASH_REMATCH[3]} ${BASH_REMATCH[4]}"
    return 0
  fi
  return 1
}

if ! adb_cmd get-state >/dev/null 2>&1; then
  echo "Device not reachable: $DEVICE_SERIAL" >&2
  exit 1
fi

# Keep local loopback transport in classic TCP mode so on-device adb can use 127.0.0.1:5555.
adb_cmd tcpip 5555 >/dev/null 2>&1 || true
# adbd restart is asynchronous; give it time to settle before app-side adb commands.
sleep 2
# Keep touch debug overlay disabled because it can interfere with touch-capture diagnostics.
adb_cmd shell settings put system pointer_location 0 >/dev/null 2>&1 || true

current_services="$(adb_cmd shell settings get secure enabled_accessibility_services | tr -d '\r')"
if [[ "$current_services" == "null" ]]; then
  current_services=""
fi

if [[ -z "$current_services" ]]; then
  merged_services="$SERVICE_COMPONENT"
elif [[ ":$current_services:" == *":$SERVICE_COMPONENT:"* ]]; then
  merged_services="$current_services"
else
  merged_services="${current_services}:$SERVICE_COMPONENT"
fi

# Explicitly toggle accessibility OFF -> ON after app launch and verify binding.
start_main_activity
sleep 1
toggle_accessibility_cycle "$merged_services"
if ! wait_for_service_bound; then
  toggle_accessibility_cycle "$merged_services"
fi
if ! wait_for_service_bound; then
  echo "WARNING: accessibility service not bound after retries" >&2
fi

# If the UI currently shows "Enable Touch Capture", tap the toggle button center.
UI_DUMP_FILE="/sdcard/touchpad_window_dump.xml"
adb_cmd shell uiautomator dump "$UI_DUMP_FILE" >/dev/null
connection_line="$(
  adb_cmd shell cat "$UI_DUMP_FILE" \
    | tr -d '\r' \
    | tr '>' '\n' \
    | grep 'resource-id="com.alex.touchpad:id/toggleConnectionButton"' \
    | head -n 1 || true
)"
connection_text="$(echo "$connection_line" | sed -n 's/.*text="\([^"]*\)".*/\1/p')"
connection_text_upper="$(echo "${connection_text:-}" | tr '[:lower:]' '[:upper:]')"
if [[ "$connection_text_upper" != *"DISCONNECT BACKEND"* ]]; then
  connection_bounds="$(extract_toggle_bounds "$connection_line" || true)"
  if [[ -n "$connection_bounds" ]]; then
    read -r x1 y1 x2 y2 <<<"$connection_bounds"
    cx=$(( (x1 + x2) / 2 ))
    cy=$(( (y1 + y2) / 2 ))
    adb_cmd shell input tap "$cx" "$cy"
    sleep 1
    adb_cmd shell uiautomator dump "$UI_DUMP_FILE" >/dev/null
  fi
fi

toggle_line="$(
  adb_cmd shell cat "$UI_DUMP_FILE" \
    | tr -d '\r' \
    | tr '>' '\n' \
    | grep 'resource-id="com.alex.touchpad:id/toggleOverlayButton"' \
    | head -n 1 || true
)"
toggle_text="$(echo "$toggle_line" | sed -n 's/.*text="\([^"]*\)".*/\1/p')"
toggle_text_upper="$(echo "${toggle_text:-}" | tr '[:lower:]' '[:upper:]')"
if [[ "$toggle_text_upper" != *"DISABLE TOUCH CAPTURE"* ]]; then
  bounds="$(extract_toggle_bounds "$toggle_line" || true)"
  if [[ -n "$bounds" ]]; then
    read -r x1 y1 x2 y2 <<<"$bounds"
    cx=$(( (x1 + x2) / 2 ))
    cy=$(( (y1 + y2) / 2 ))
    adb_cmd shell input tap "$cx" "$cy"
    sleep 1
  fi
fi

enabled_flag="$(adb_cmd shell settings get secure accessibility_enabled | tr -d '\r')"
enabled_services="$(adb_cmd shell settings get secure enabled_accessibility_services | tr -d '\r')"
pointer_location="$(adb_cmd shell settings get system pointer_location | tr -d '\r')"
service_bound="false"
if is_service_bound_once; then
  service_bound="true"
fi
adb_cmd shell uiautomator dump "$UI_DUMP_FILE" >/dev/null
post_toggle_line="$(
  adb_cmd shell cat "$UI_DUMP_FILE" \
    | tr -d '\r' \
    | tr '>' '\n' \
    | grep 'resource-id="com.alex.touchpad:id/toggleOverlayButton"' \
    | head -n 1 || true
)"
touch_capture_button_text="$(echo "$post_toggle_line" | sed -n 's/.*text="\([^"]*\)".*/\1/p')"
post_connection_line="$(
  adb_cmd shell cat "$UI_DUMP_FILE" \
    | tr -d '\r' \
    | tr '>' '\n' \
    | grep 'resource-id="com.alex.touchpad:id/toggleConnectionButton"' \
    | head -n 1 || true
)"
backend_button_text="$(echo "$post_connection_line" | sed -n 's/.*text="\([^"]*\)".*/\1/p')"

echo "Prepared device test environment."
echo "accessibility_enabled=$enabled_flag"
echo "enabled_accessibility_services=$enabled_services"
echo "pointer_location=$pointer_location"
echo "service_bound=$service_bound"
echo "opened_activity=$MAIN_ACTIVITY_COMPONENT"
echo "backend_button_text=${backend_button_text:-unknown}"
echo "touch_capture_button_text=${touch_capture_button_text:-unknown}"
