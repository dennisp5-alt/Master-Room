#!/usr/bin/env bash
# Installs the app and its test on the running emulator, runs the flow, collects screenshots.
set -u
APK=app/build/outputs/apk
PKG=com.dennis.masterroom.trial
mkdir -p ui-screens
adb install -r "$APK/debug/app-debug.apk"
adb install -r "$APK/androidTest/debug/app-debug-androidTest.apk"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb logcat -c
adb shell am instrument -w -r "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee ui-screens/result.txt
adb pull "/sdcard/Android/data/$PKG/files/screens/." ui-screens/ || true
adb logcat -d -t 1500 > ui-screens/logcat.txt || true
grep -q "^OK (1 test)" ui-screens/result.txt
