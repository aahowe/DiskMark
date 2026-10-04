#!/usr/bin/env bash
# Build -> unit test -> install/launch (android CLI) -> Maestro UI flows.
#   DEVICE=<serial>  target device (default: first physical device, else first emulator)
#   BENCH=1          also run the full benchmark flow and print results
set -euo pipefail
cd "$(dirname "$0")/.."

AVD="${AVD:-medium_phone}"
OUT=".maestro/out"

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

# `android layout` leaves a UiAutomation instrumentation server running, which
# blocks Maestro's driver from starting. Stop it before running flows.
adb -s "$DEVICE" shell am force-stop com.android.cli.interact.instrumentation || true

echo "==> Maestro smoke flows"
rm -rf "$OUT" && mkdir -p "$OUT"
maestro --device "$DEVICE" test --format junit --output "$OUT/report.xml" --test-output-dir "$OUT" .maestro "$@"

if [[ "${BENCH:-0}" == "1" ]]; then
  echo "==> Maestro full benchmark (takes a few minutes)"
  adb -s "$DEVICE" logcat -c
  maestro --device "$DEVICE" test --test-output-dir "$OUT/bench" .maestro/bench/benchmark.yaml
  echo "==> Results"
  adb -s "$DEVICE" logcat -d -s DiskMark:I | grep -E "CONFIG|RESULT" | sed 's/^.*DiskMark *: //'
fi
echo "==> Report: $OUT/report.xml  Screenshots: $OUT"
