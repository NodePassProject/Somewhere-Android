#!/usr/bin/env bash
# End-to-end instrumentation test: Portal on the host, connectedAndroidTest on a device.
#
# Topology:
#   app on device  --(10.0.2.2:PORT)-->  Nowhere Portal on host  -->  target service on host
#
# The device is chosen by scripts/device-connect.sh (already-connected device >
# running local emulator > AVD). Which device to use is the developer's choice.
#
# Prerequisites:
#   1. one usable device;
#   2. the client Gradle project exists and its abiFilters cover the device ABI;
#   3. the Nowhere binary is built (see PROTOCOL_BASELINE).
#
# Usage: scripts/e2e-android.sh /path/to/android-project [applicationId]
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="${1:-}"
APP_ID="${2:-}"
# Upstream clone location. This suite is published, so it must not assume any
# particular layout outside its own repository: set NOWHERE_CLONE to a checkout
# of NodePassProject/Nowhere at the pinned baseline. The default suits a
# side-by-side checkout next to the client repository.
NOWHERE_CLONE="${NOWHERE_CLONE:-$ROOT/../../Nowhere}"
BIN="${NOWHERE_BIN:-$NOWHERE_CLONE/target/release/nowhere}"
KEY="e2e-key"
PORTAL_PORT="${PORTAL_PORT:-22077}"
TARGET_PORT="${TARGET_PORT:-28000}"
RUNDIR="$(mktemp -d)"
PIDS=()

cleanup() {
    for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done
    rm -rf "$RUNDIR"
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
[ -n "$PROJECT" ] || fail "usage: $0 /path/to/android-project [applicationId]"
[ -x "$BIN" ] || fail "nowhere binary not found: $BIN"

wait_for_port() {
    local port=$1 name=$2 tries=0
    until nc -z 127.0.0.1 "$port" 2>/dev/null; do
        tries=$((tries + 1)); [ "$tries" -gt 100 ] && fail "$name did not listen on $port within 10s"
        sleep 0.1
    done
}

# --- Host side: target service + Portal ------------------------------------
printf 'nowhere-e2e-ok' > "$RUNDIR/probe.txt"
(cd "$RUNDIR" && exec python3 -m http.server "$TARGET_PORT" --bind 0.0.0.0) > "$RUNDIR/target.log" 2>&1 &
PIDS+=($!); wait_for_port "$TARGET_PORT" "target service"

# Bind 0.0.0.0 so the device can reach it through 10.0.2.2
"$BIN" "portal://${KEY}@0.0.0.0:${PORTAL_PORT}?log=event" > "$RUNDIR/portal.log" 2>&1 &
PIDS+=($!); wait_for_port "$PORTAL_PORT" "Portal"
echo "OK  host: Portal :$PORTAL_PORT, target service :$TARGET_PORT"

# --- Device ----------------------------------------------------------------
DEVICE="$(NOWHERE_DEVICE="${NOWHERE_DEVICE:-}" "$ROOT/scripts/device-connect.sh")" \
    || fail "no usable device"
export ANDROID_SERIAL="$DEVICE"
SDK_LEVEL="$(adb -s "$DEVICE" shell getprop ro.build.version.sdk | tr -d '\r')"
DEV_ABI="$(adb -s "$DEVICE" shell getprop ro.product.cpu.abi | tr -d '\r')"
echo "OK  device: $DEVICE  API $SDK_LEVEL  $DEV_ABI"

# android.net.ssl.SSLSockets.exportKeyingMaterial() is public API only from API 31.
# Below 31 the platform TLS exporter path cannot run (see decision D-03).
if [ "${SDK_LEVEL:-0}" -lt 31 ]; then
    echo "NOTE: API $SDK_LEVEL < 31 - platform exporter unavailable, related cases will be skipped"
fi

# Pass the host-side Portal address to the test code. 10.0.2.2 is the host as seen
# from QEMU-based emulators, including the AOSP emulator. Override for a physical
# device or bridged networking.
export NOWHERE_E2E_PORTAL="10.0.2.2:${PORTAL_PORT}"
export NOWHERE_E2E_KEY="$KEY"
export NOWHERE_E2E_TARGET="10.0.2.2:${TARGET_PORT}"

# VpnService shows a consent dialog. Instrumentation must pre-grant it, otherwise
# the test hangs on the dialog.
if [ -n "$APP_ID" ]; then
    adb shell appops set "$APP_ID" ACTIVATE_VPN allow >/dev/null 2>&1 \
        && echo "OK  pre-granted VPN consent for $APP_ID" \
        || echo "NOTE: pre-grant failed (app may not be installed yet; retry after install)"
fi

# --- Run the tests ---------------------------------------------------------
echo "Running connectedAndroidTest ..."
( cd "$PROJECT" && ./gradlew --no-daemon connectedAndroidTest \
    -PnowhereE2ePortal="$NOWHERE_E2E_PORTAL" \
    -PnowhereE2eKey="$NOWHERE_E2E_KEY" \
    -PnowhereE2eTarget="$NOWHERE_E2E_TARGET" ) 2>&1 | tail -40
STATUS=${PIPESTATUS[0]}

echo
echo "--- Portal EVENT log (reference for comparing client behaviour) ---"
tail -30 "$RUNDIR/portal.log" || true
cp "$RUNDIR/portal.log" "$ROOT/last-portal-e2e.log" 2>/dev/null || true

[ "$STATUS" -eq 0 ] && echo "PASS: end-to-end instrumentation test" || fail "instrumentation test failed (exit $STATUS)"
