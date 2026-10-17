#!/usr/bin/env bash
set -euo pipefail
cd android
# Debug-signed, minified release is QA-only; it is never uploaded as the release APK.
bash ./gradlew connectedDebugAndroidTest --console=plain
bash ./gradlew connectedReleaseAndroidTest -PnartyaQaRelease=true --console=plain
# Prove a same-key upgrade installs without uninstalling or clearing data.
bash ./gradlew assembleDebug --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
next_version=$(python3 -c 'import json; print(json.load(open("version.json"))["versionCode"] + 1)')
bash ./gradlew assembleDebug -PnartyaVersionCode="$next_version" --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell dumpsys package com.nartya.app | grep "versionCode=$next_version "
