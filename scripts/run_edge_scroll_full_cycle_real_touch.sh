#!/usr/bin/env bash
set -euo pipefail

ADB_BIN="${ADB_BIN:-adb}"
DEVICE_SERIAL="${DEVICE_SERIAL:?Set DEVICE_SERIAL to the target shown by 'adb devices -l'}"
export ADB_BIN DEVICE_SERIAL
APP_PACKAGE="com.alex.touchpad"
PROBE_ACTIVITY="${APP_PACKAGE}/.ui.ScrollAnchorProbeActivity"

if ! command -v "$ADB_BIN" >/dev/null 2>&1 && [[ ! -x "$ADB_BIN" ]]; then
  echo "adb not found or executable: $ADB_BIN" >&2
  exit 1
fi

adb_cmd() {
  "$ADB_BIN" -s "$DEVICE_SERIAL" "$@"
}

read_display_size() {
  local wm
  wm="$(adb_cmd shell wm size | tr -d '\r')"
  local size
  size="$(echo "$wm" | awk -F'[: x]+' '/size/ {print $(NF-1), $NF}' | tail -n 1)"
  if [[ -z "$size" ]]; then
    echo "Failed to parse display size from: $wm" >&2
    return 1
  fi
  echo "$size"
}

read_cursor_xy() {
  local line
  line="$(
    adb_cmd shell \
      "dumpsys input | grep -m 1 -E 'hoveringPointers|Pointer\\(id=[0-9]+, *MOUSE\\)'" \
      | tr -d '\r'
  )"
  if [[ -z "$line" ]]; then
    return 1
  fi
  local xy
  xy="$(echo "$line" | sed -n 's/.*at (\([-0-9.]\+\), *\([-0-9.]\+\)).*/\1 \2/p' | tail -n 1)"
  if [[ -z "$xy" ]]; then
    return 1
  fi
  echo "$xy"
}

wait_cursor_edge() {
  local edge="$1"
  local display_h="$2"
  local attempts=80
  while (( attempts > 0 )); do
    local xy
    if xy="$(read_cursor_xy 2>/dev/null || true)" && [[ -n "$xy" ]]; then
      local x y
      read -r x y <<< "$xy"
      local yi
      yi="$(printf '%.0f' "$y")"
      if [[ "$edge" == "top" && "$yi" -le 1 ]]; then
        echo "$x $y"
        return 0
      fi
      if [[ "$edge" == "bottom" && "$yi" -ge $((display_h - 1)) ]]; then
        echo "$x $y"
        return 0
      fi
    fi
    sleep 0.06
    attempts=$((attempts - 1))
  done
  return 1
}

count_scroll_step_logs() {
  adb_cmd shell logcat -d -s OnDeviceAdbExecutor:I \
    | tr -d '\r' \
    | grep -E 'scroll step emit|edge vertical swipe step emit' \
    | wc -l \
    | tr -d ' '
}

count_probe_scroll_logs() {
  adb_cmd shell logcat -d -s ScrollAnchorProbe:I \
    | tr -d '\r' \
    | grep -E 'leftScrollY=|rightScrollY=' \
    | wc -l \
    | tr -d ' '
}

inject_swipe() {
  local x="$1"
  local y1="$2"
  local y2="$3"
  local duration_ms="${4:-36}"
  adb_cmd shell input touchscreen swipe "$x" "$y1" "$x" "$y2" "$duration_ms" >/dev/null
}

echo "[full-cycle] preparing environment"
./scripts/prepare_device_test_env.sh >/dev/null

echo "[full-cycle] opening probe activity"
adb_cmd shell am start -n "$PROBE_ACTIVITY" >/dev/null
sleep 1

adb_cmd shell logcat -c

display_size="$(read_display_size)" || exit 1
read -r display_w display_h <<< "$display_size"
if [[ -z "${display_w:-}" || -z "${display_h:-}" ]]; then
  echo "Failed to parse display size tuple: $display_size" >&2
  exit 1
fi
x=$((display_w / 2))
mid_y=$((display_h / 2))
bottom_y=$((display_h - 48))
top_y=48

echo "[full-cycle] display=${display_w}x${display_h} centerX=$x"

echo "[full-cycle] move cursor to top edge via real touch input"
for _ in $(seq 1 20); do
  inject_swipe "$x" "$mid_y" "$top_y" 40
  sleep 0.04
done
if ! top_cursor="$(wait_cursor_edge top "$display_h")"; then
  echo "[full-cycle] FAIL: cursor never reached top edge" >&2
  exit 2
fi
echo "[full-cycle] top cursor=$top_cursor"

top_steps_before="$(count_scroll_step_logs)"
top_probe_before="$(count_probe_scroll_logs)"
for _ in $(seq 1 14); do
  inject_swipe "$x" "$mid_y" "$top_y" 38
  sleep 0.04
done
sleep 0.6
top_steps_after="$(count_scroll_step_logs)"
top_probe_after="$(count_probe_scroll_logs)"

echo "[full-cycle] top edge metrics: stepLogs $top_steps_before->$top_steps_after probeLogs $top_probe_before->$top_probe_after"
if (( top_steps_after <= top_steps_before )); then
  echo "[full-cycle] FAIL: no top-edge scroll step emissions" >&2
  exit 3
fi
if (( top_probe_after <= top_probe_before )); then
  echo "[full-cycle] FAIL: no top-edge probe scroll events" >&2
  exit 4
fi

echo "[full-cycle] move cursor to bottom edge via real touch input"
for _ in $(seq 1 20); do
  inject_swipe "$x" "$mid_y" "$bottom_y" 40
  sleep 0.04
done
if ! bottom_cursor="$(wait_cursor_edge bottom "$display_h")"; then
  echo "[full-cycle] FAIL: cursor never reached bottom edge" >&2
  exit 5
fi
echo "[full-cycle] bottom cursor=$bottom_cursor"

bottom_steps_before="$(count_scroll_step_logs)"
bottom_probe_before="$(count_probe_scroll_logs)"
for _ in $(seq 1 14); do
  inject_swipe "$x" "$mid_y" "$bottom_y" 38
  sleep 0.04
done
sleep 0.6
bottom_steps_after="$(count_scroll_step_logs)"
bottom_probe_after="$(count_probe_scroll_logs)"

echo "[full-cycle] bottom edge metrics: stepLogs $bottom_steps_before->$bottom_steps_after probeLogs $bottom_probe_before->$bottom_probe_after"
if (( bottom_steps_after <= bottom_steps_before )); then
  echo "[full-cycle] FAIL: no bottom-edge scroll step emissions" >&2
  exit 6
fi
if (( bottom_probe_after <= bottom_probe_before )); then
  echo "[full-cycle] FAIL: no bottom-edge probe scroll events" >&2
  exit 7
fi

echo "[full-cycle] PASS: real-touch full cycle validated (touch -> cursor edge -> scroll step emit -> probe scroll events)"
