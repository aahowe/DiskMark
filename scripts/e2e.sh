#!/usr/bin/env bash
# Build -> unit test -> install/launch (android CLI) -> Maestro UI flows.
set -euo pipefail
cd "$(dirname "$0")/.."

AVD="${AVD:-medium_phone}"
OUT=".maestro/out"

if ! adb devices | grep -qE "\sdevice$"; then
  echo "==> No device connected, starting emulator $AVD"
  android emulator start "$AVD"
fi

echo "==> Unit tests + assemble"
./gradlew --console=plain -q testDebugUnitTest assembleDebug

echo "==> Install & launch"
android run --apks=app/build/outputs/apk/debug/app-debug.apk

# `android layout` leaves a UiAutomation instrumentation server running, which
# blocks Maestro's driver from starting. Stop it before running flows.
adb shell am force-stop com.android.cli.interact.instrumentation || true

echo "==> Maestro flows"
rm -rf "$OUT" && mkdir -p "$OUT"
maestro test --format junit --output "$OUT/report.xml" --test-output-dir "$OUT" .maestro "$@"
echo "==> Report: $OUT/report.xml  Screenshots: $OUT"
