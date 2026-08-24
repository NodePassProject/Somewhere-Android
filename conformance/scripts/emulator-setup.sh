#!/usr/bin/env bash
# One-time preparation: install the SDK components an AVD needs and create one.
#
# Only needed if you want to use an AVD. If you already have a usable device -
# a physical phone or any third-party emulator - skip this; device-connect.sh
# will find it.
#
# Downloads roughly 1-2 GB. Run once.
set -euo pipefail

API="${ANDROID_API:-34}"
TAG="${ANDROID_TAG:-google_apis}"
ABI="${ANDROID_ABI:-x86_64}"
AVD="${AVD_NAME:-nowhere-conformance-$API}"
IMAGE="system-images;android-$API;$TAG;$ABI"

command -v sdkmanager  >/dev/null || { echo "sdkmanager not found; install the Android cmdline-tools first" >&2; exit 1; }
command -v avdmanager  >/dev/null || { echo "avdmanager not found" >&2; exit 1; }

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[ -n "$SDK_ROOT" ] || { echo "set ANDROID_SDK_ROOT or ANDROID_HOME" >&2; exit 1; }

echo "Installing: platform-tools, emulator, platforms;android-$API, $IMAGE"
yes | sdkmanager --install "platform-tools" "emulator" "platforms;android-$API" "$IMAGE" >/dev/null

if avdmanager list avd 2>/dev/null | grep -q "Name: $AVD"; then
    echo "AVD $AVD already exists; skipping creation"
else
    echo "Creating AVD: $AVD"
    echo "no" | avdmanager create avd --name "$AVD" --package "$IMAGE" --device "pixel_6" --force >/dev/null
fi

CFG="$HOME/.android/avd/$AVD.avd/config.ini"
if [ -f "$CFG" ]; then
    # Disable snapshots and audio, raise memory: more stable and faster to boot in CI.
    {
        echo "hw.audioInput=no"
        echo "hw.audioOutput=no"
        echo "hw.ramSize=3072"
        echo "disk.dataPartition.size=4096M"
    } >> "$CFG"
fi

echo "Done. Emulator binary: $SDK_ROOT/emulator/emulator"
echo "Next: scripts/e2e-android.sh"
