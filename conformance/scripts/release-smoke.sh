#!/usr/bin/env bash
# Installs the release APK and starts it.
#
# `checkReleaseArtifact` reads the file and answers "are the names still there".
# This answers the other half: does the thing run. R8 breaks startup in ways a
# name check cannot see — a reflective lookup with no keep, a Compose entry
# point shrunk away, a resource the shrinker decided was unused — and all of
# them present as a process that dies before anything is on screen.
#
# Not a substitute for the device suite, which runs against the debug build for
# reasons recorded in app/build.gradle.kts. This is the minimum that can be said
# about the artifact that would actually ship.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="$(cd "$ROOT/.." && pwd)"
APP_ID="eu.nodepass.somewhere"
DEVICE="${NOWHERE_DEVICE:-}"

fail() { echo "FAIL: $*" >&2; exit 1; }
step() { printf '\n== %s ==\n' "$*"; }

command -v adb >/dev/null || fail "adb is not on PATH"
[ -n "$DEVICE" ] || DEVICE="$(adb devices | awk '$2=="device"{print $1; exit}')"
[ -n "$DEVICE" ] || fail "no device; see scripts/device-connect.sh"

APK="$PROJECT/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || APK="$PROJECT/app/build/outputs/apk/release/app-release-unsigned.apk"
[ -f "$APK" ] || fail "no release APK; run ./gradlew assembleRelease"

step "install $(basename "$APK")"
adb -s "$DEVICE" uninstall "$APP_ID" >/dev/null 2>&1
adb -s "$DEVICE" install -r "$APK" 2>&1 | tail -1 | grep -q Success \
    || fail "the release APK would not install. An unsigned APK cannot be; see the signing config."

step "start it"
adb -s "$DEVICE" logcat -c
adb -s "$DEVICE" shell am start -n "$APP_ID/.MainActivity" >/dev/null 2>&1 \
    || fail "the launcher activity would not start"
sleep 6

step "did it survive"
CRASH="$(adb -s "$DEVICE" logcat -d 2>/dev/null | grep -E "FATAL EXCEPTION|AndroidRuntime: .*$APP_ID" | head -5)"
[ -z "$CRASH" ] || { echo "$CRASH"; fail "the release build crashed on startup"; }

PID="$(adb -s "$DEVICE" shell pidof "$APP_ID" 2>/dev/null | tr -d '\r ')"
[ -n "$PID" ] || fail "the process is not running six seconds after start"
echo "OK  running as pid $PID"

# Deliberately not checked here, twice over.
#
# `System.loadLibrary` runs when the tunnel starts or the QUIC stack is first
# touched, so a freshly opened screen has none of it mapped -- there is nothing
# to look for. And a modern APK does not extract its native libraries at all:
# they stay page-aligned inside the file and are mapped from there, so
# `lib/arm64` under the install directory is empty on a working install. Both of
# those were checked here first and both reported a failure that was the check's
# rather than the app's.
#
# What the APK carries is `checkReleaseArtifact`'s, which reads the file.

echo
echo "PASS: the release APK installs, starts, and is still running six seconds"
echo "      later."
