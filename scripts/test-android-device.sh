#!/usr/bin/env bash
set -euo pipefail
cd android
# Debug-signed, minified release is QA-only; it is never uploaded as the release APK.
bash ./gradlew :app:connectedDebugAndroidTest --console=plain
# AndroidX test's Kotlin runtime can collide with the application's optimized runtime.
# The framework-only instrumentation probe exercises the actual minified app via its JS bridge.
bash ./gradlew :app:assembleRelease :app:assembleReleaseAndroidTest -PnartyaQaRelease=true -PnartyaProxyProbe=true --console=plain
adb install -r app/build/outputs/apk/release/app-release.apk
adb install -r -t app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
mkdir -p app/build/reports/androidTests
adb shell am instrument -w -r com.nartya.app.test/com.nartya.app.proxy.NartyaProxyProbeInstrumentation > app/build/reports/androidTests/release-probe.txt
cat app/build/reports/androidTests/release-probe.txt
grep -q 'INSTRUMENTATION_CODE: -1' app/build/reports/androidTests/release-probe.txt
grep -q 'NartyaProxy permission + player + updater bridge OK' app/build/reports/androidTests/release-probe.txt
# Prove a same-key upgrade installs without uninstalling or clearing data.
bash ./gradlew assembleDebug --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
next_version=$(python3 -c 'import json; print(json.load(open("version.json"))["versionCode"] + 1)')
bash ./gradlew assembleDebug -PnartyaVersionCode="$next_version" --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell dumpsys package com.nartya.app | grep "versionCode=$next_version "
