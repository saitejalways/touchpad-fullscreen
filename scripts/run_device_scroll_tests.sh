#!/usr/bin/env bash
set -euo pipefail

ADB_BIN="${ADB_BIN:-adb}"
DEVICE_SERIAL="${DEVICE_SERIAL:?Set DEVICE_SERIAL to the target shown by 'adb devices -l'}"
export ADB_BIN DEVICE_SERIAL

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if ! command -v "$ADB_BIN" >/dev/null 2>&1 && [[ ! -x "$ADB_BIN" ]]; then
  echo "adb not found or executable: $ADB_BIN" >&2
  exit 1
fi

./gradlew :app:assembleDebug :app:assembleDebugAndroidTest

"$ADB_BIN" -s "$DEVICE_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB_BIN" -s "$DEVICE_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

./scripts/prepare_device_test_env.sh

"$ADB_BIN" -s "$DEVICE_SERIAL" shell am instrument -w \
  -e class com.alex.touchpad.scroll.ScrollPipelineInstrumentedTest,com.alex.touchpad.scroll.MainActivityScrollSmokeTest,com.alex.touchpad.scroll.TouchpadEngineFlingInstrumentedTest,com.alex.touchpad.scroll.TouchpadHorizontalScrollEventCountInstrumentedTest,com.alex.touchpad.scroll.TouchpadEngineHoldDragInstrumentedTest,com.alex.touchpad.backend.OnDeviceAdbCommandExecutorFollowupFactorInstrumentedTest \
  com.alex.touchpad.test/androidx.test.runner.AndroidJUnitRunner

./scripts/prepare_device_test_env.sh
