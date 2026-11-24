#!/usr/bin/env bash
set -euo pipefail

capture_artifacts() {
  timeout 30s adb pull /data/local/tmp/ci-screenshots/. screenshots/ || true
  timeout 20s adb logcat -d -t 1000 > ui-test-results/logcat.txt || true
}
mkdir -p screenshots ui-test-results
trap capture_artifacts EXIT

timeout 30s adb shell mkdir -p /data/local/tmp/ci-screenshots
timeout 90s adb install -t app/build/outputs/apk/debug/app-debug.apk
timeout 90s adb install -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
timeout 5m adb shell am instrument -w -r "$1.test/androidx.test.runner.AndroidJUnitRunner" \
  | tee ui-test-results/instrumentation.txt
grep -Eq 'OK \([1-9][0-9]* tests?\)' ui-test-results/instrumentation.txt
timeout 45s adb pull /data/local/tmp/ci-screenshots/. screenshots/
for number in 01 02 03 04 05 06 07 08; do
  compgen -G "screenshots/$number-*.png" >/dev/null
done
