#!/usr/bin/env bash
set -euo pipefail

capture_artifacts() {
  if [[ -n ${logcat_pid:-} ]]; then kill "$logcat_pid" 2>/dev/null || true; fi
  timeout 30s adb pull /data/local/tmp/ci-screenshots/. screenshots/ || true
  timeout 20s adb logcat -d -t 1000 > ui-test-results/logcat-tail.txt || true
  adb devices -l > ui-test-results/devices.txt
}
mkdir -p screenshots ui-test-results
trap capture_artifacts EXIT

# The API 36 TV emulator can briefly drop ADB just after sys.boot_completed
# becomes 1. Wait for the package manager and several consecutive shell replies.
stable=0
for attempt in {1..30}; do
  if [[ "$(timeout 5s adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" == 1 ]] \
    && timeout 8s adb shell pm path android >/dev/null 2>&1; then
    stable=$((stable + 1))
    [[ $stable -lt 3 ]] || break
  else
    stable=0
    if (( attempt % 5 == 0 )); then timeout 5s adb reconnect offline || true; fi
  fi
  sleep 2
done
if [[ $stable -lt 3 ]]; then
  echo "Android TV emulator did not stabilize after boot" >&2
  exit 1
fi

timeout 30s adb shell mkdir -p /data/local/tmp/ci-screenshots
timeout 90s adb install -t app/build/outputs/apk/debug/app-debug.apk
timeout 90s adb install -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
timeout 5m adb logcat -T 1 > ui-test-results/logcat.txt 2>&1 &
logcat_pid=$!
timeout 5m adb shell am instrument -w -r "$1.test/androidx.test.runner.AndroidJUnitRunner" \
  | tee ui-test-results/instrumentation.txt
grep -Eq 'OK \([1-9][0-9]* tests?\)' ui-test-results/instrumentation.txt
timeout 45s adb pull /data/local/tmp/ci-screenshots/. screenshots/
for number in 01 02 03 04 05 06 07 08 09 10; do
  compgen -G "screenshots/$number-*.png" >/dev/null
done
