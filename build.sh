#!/bin/bash
# build.sh — 一键构建两个 APK（app + 测试壳）
set -e
cd "$(dirname "$0")"
export ANDROID_HOME=/home/chenbin/Android
~/gradle-8.10.2/bin/gradle --no-daemon assembleDebug assembleRelease
cp app/build/outputs/apk/debug/app-debug.apk wa-translate-app-debug.apk
cp app/build/outputs/apk/release/app-release.apk wa-translate-app-release.apk
cp testhost/build/outputs/apk/debug/testhost-debug.apk wa-testhost-debug.apk
ls -la wa-translate-app-debug.apk wa-translate-app-release.apk wa-testhost-debug.apk
