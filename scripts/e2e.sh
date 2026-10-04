#!/usr/bin/env bash
# Build -> unit test -> install/launch (android CLI) -> UI tests (android CLI, scripts/uitest.py).
#   DEVICE=<serial>  target device (default: first physical device, else first emulator)
#   BENCH=1          also run the full benchmark and print results
set -euo pipefail
cd "$(dirname "$0")/.."

AVD="${AVD:-medium_phone}"

devices() { adb devices | awk 'NR>1 && $2=="device" {print $1}'; }

if [[ -z "${DEVICE:-}" ]]; then
  if [[ -z "$(devices)" ]]; then
    echo "==> No device connected, starting emulator $AVD"
    android emulator start "$AVD"
  fi
  DEVICE="$(devices | grep -v '^emulator-' | head -1 || true)"
  DEVICE="${DEVICE:-$(devices | head -1)}"
fi
echo "==> Device: $DEVICE ($(adb -s "$DEVICE" shell getprop ro.product.model | tr -d '\r'))"

echo "==> Unit tests + assemble"
./gradlew --console=plain -q testDebugUnitTest assembleDebug

echo "==> Install & launch"
android run --device="$DEVICE" --apks=app/build/outputs/apk/debug/app-debug.apk

echo "==> UI tests"
args=(--device "$DEVICE")
[[ "${BENCH:-0}" == "1" ]] && args+=(--bench)
python3 scripts/uitest.py "${args[@]}" "$@"
