#!/usr/bin/env bash
# Discover one usable Android device and print its serial to stdout
# (all other output goes to stderr).
#
# Which device to use is the developer's own choice; this script only tries, in
# order, the things that are cheapest to find:
#   1. an already-connected device (physical or emulator) — fastest, just use it;
#   2. a running local emulator whose adb port can be probed;
#   3. an AVD — requires scripts/emulator-setup.sh to have been run once.
#
# Override with NOWHERE_DEVICE=<serial> to force a specific device.
#
# Usage: DEVICE="$(scripts/device-connect.sh)" && adb -s "$DEVICE" shell ...
set -uo pipefail

log() { echo "$*" >&2; }

first_device() {
    adb devices 2>/dev/null | awk '/\tdevice$/ {print $1; exit}'
}

# --- 0. Explicit override --------------------------------------------------
if [ -n "${NOWHERE_DEVICE:-}" ]; then
    log "Using NOWHERE_DEVICE=$NOWHERE_DEVICE"
    echo "$NOWHERE_DEVICE"; exit 0
fi

# --- 1. Already-connected device -------------------------------------------
DEV="$(first_device)"
if [ -n "$DEV" ]; then
    log "Using already-connected device: $DEV"
    echo "$DEV"; exit 0
fi

# --- 2. Running local emulator --------------------------------------------
# Third-party emulators expose adb on a TCP port that varies by product and by
# instance index. Rather than hardcoding a table, read the ports the emulator
# process is actually listening on, then fall back to well-known defaults.
EMULATOR_PROCESS_PATTERNS="${EMULATOR_PROCESS_PATTERNS:-MuMuEmulator MuMuPlayer BlueStacks Nox LDPlayer qemu-system}"
WELL_KNOWN_ADB_PORTS="${WELL_KNOWN_ADB_PORTS:-5555 7555 16384 21503 62001 5037}"

emulator_running() {
    for pattern in $EMULATOR_PROCESS_PATTERNS; do
        pgrep -f -i "$pattern" >/dev/null 2>&1 && { echo "$pattern"; return 0; }
    done
    return 1
}

if RUNNING="$(emulator_running)"; then
    log "Detected a running emulator process ($RUNNING); probing adb ports ..."
    LISTENING=""
    if command -v lsof >/dev/null 2>&1; then
        LISTENING="$(lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null \
            | awk -v pat="$RUNNING" 'tolower($1) ~ tolower(substr(pat,1,9)) {print $9}' \
            | sed 's/.*://' | sort -un)"
    fi
    for port in $LISTENING $WELL_KNOWN_ADB_PORTS; do
        case "$port" in ''|*[!0-9]*) continue ;; esac
        adb connect "127.0.0.1:$port" >/dev/null 2>&1
        sleep 0.3
        if adb devices 2>/dev/null | grep -q "^127.0.0.1:$port[[:space:]]*device$"; then
            log "Connected: 127.0.0.1:$port"
            echo "127.0.0.1:$port"; exit 0
        fi
        adb disconnect "127.0.0.1:$port" >/dev/null 2>&1
    done
    log "An emulator is running but no usable adb port was found."
    log "Check that ADB / USB debugging is enabled in its settings."
fi

# --- 3. AVD ---------------------------------------------------------------
AVD="${AVD_NAME:-nowhere-conformance-34}"
EMU="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/emulator/emulator"
if [ -x "$EMU" ]; then
    log "Booting AVD: $AVD (headless)"
    "$EMU" -avd "$AVD" -no-window -no-audio -no-snapshot > /tmp/avd-boot.log 2>&1 &
    adb wait-for-device
    tries=0
    until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
        tries=$((tries + 1))
        [ "$tries" -gt 600 ] && { log "AVD did not finish booting within 5 minutes"; exit 1; }
        sleep 0.5
    done
    DEV="$(first_device)"
    [ -n "$DEV" ] && { log "AVD ready: $DEV"; echo "$DEV"; exit 0; }
fi

log "No usable device found. Any one of these works:"
log "  - start your emulator of choice and enable ADB debugging in it"
log "  - run scripts/emulator-setup.sh once to prepare an AVD"
log "  - attach a physical device with USB debugging enabled"
log "  - set NOWHERE_DEVICE=<serial> to point at a device directly"
exit 1
