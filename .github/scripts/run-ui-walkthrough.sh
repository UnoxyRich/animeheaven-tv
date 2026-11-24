#!/usr/bin/env bash
set -euo pipefail

capture_artifacts() {
  adb pull /data/local/tmp/ci-screenshots/. screenshots/ || true
  adb logcat -d -t 1000 > ui-test-results/logcat.txt || true
}
mkdir -p screenshots ui-test-results
trap capture_artifacts EXIT

adb shell mkdir -p /data/local/tmp/ci-screenshots
adb install -t app/build/outputs/apk/debug/app-debug.apk
adb install -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r "$1.test/androidx.test.runner.AndroidJUnitRunner" \
  | tee ui-test-results/instrumentation.txt
grep -Eq 'OK \([1-9][0-9]* tests?\)' ui-test-results/instrumentation.txt
adb pull /data/local/tmp/ci-screenshots/. screenshots/
for number in 01 02 03 04 05 06 07 08; do
  compgen -G "screenshots/$number-*.png" >/dev/null
done
