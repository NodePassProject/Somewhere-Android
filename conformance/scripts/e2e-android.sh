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
APP_ID="${2:-eu.nodepass.somewhere}"
# Upstream clone location. This suite is published, so it must not assume any
# particular layout outside its own repository: set NOWHERE_CLONE to a checkout
# of NodePassProject/Nowhere at the pinned baseline. The default suits a
# side-by-side checkout next to the client repository.
NOWHERE_CLONE="${NOWHERE_CLONE:-$ROOT/../../Nowhere}"
BIN="${NOWHERE_BIN:-$NOWHERE_CLONE/target/release/nowhere}"
KEY="0123456789abcdef0123456789abcdef"
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

# The QUIC cases' own fixture: an origin on 28091 and a UDP echo on 28092, on
# loopback, because those addresses are the Portal's view and the Portal runs
# here. Without it every QUIC case reports DIAL_FAILED, which reads as a client
# defect and is not one.
python3 "$ROOT/scripts/quic-origin.py" > "$RUNDIR/quic-origin.log" 2>&1 &
PIDS+=($!); wait_for_port 28091 "QUIC origin"

# Bind 0.0.0.0 so the device can reach it through 10.0.2.2
"$BIN" "portal://${KEY}@0.0.0.0:${PORTAL_PORT}" > "$RUNDIR/portal.log" 2>&1 &
PIDS+=($!); wait_for_port "$PORTAL_PORT" "Portal"
PIN="$(sed -n 's/.*tls certificate fingerprint: \([0-9a-f]\{64\}\).*/\1/p' "$RUNDIR/portal.log" | head -1)"
[ -n "$PIN" ] || fail "the Portal did not log its certificate fingerprint"
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

# Pass the host-side Portal address to the test code. 10.0.2.2 is the host as seen
# from QEMU-based emulators, including the AOSP emulator. Override for a physical
# device or bridged networking.
export NOWHERE_E2E_PORTAL="10.0.2.2:${PORTAL_PORT}"
export NOWHERE_E2E_KEY="$KEY"
export NOWHERE_E2E_TARGET="10.0.2.2:${TARGET_PORT}"

# --- Install, grant, run -----------------------------------------------------
# Installed first and run with `am instrument`, not `connectedAndroidTest`:
# that task reinstalls the app as it starts, which drops a VPN consent granted
# before it, and every tunnel case then fails on consent instead of testing
# anything. VpnService shows a consent dialog that instrumentation cannot
# dismiss, so it is pre-granted between the install and the run. `appops` is a
# standalone binary some images omit; `cmd appops` reaches the same service.
( cd "$PROJECT" && ./gradlew --no-daemon -q installDebug installDebugAndroidTest ) >/dev/null 2>&1 \
    || fail "could not install the app and its tests"
adb shell cmd appops set "$APP_ID" ACTIVATE_VPN allow >/dev/null 2>&1 ||
    adb shell appops set "$APP_ID" ACTIVATE_VPN allow >/dev/null 2>&1 ||
    fail "could not pre-grant VPN consent for $APP_ID"
echo "OK  installed, VPN consent pre-granted for $APP_ID"

echo "Running the instrumentation suite ..."
adb shell am instrument -w \
    -e nowhereE2ePortal "$NOWHERE_E2E_PORTAL" \
    -e nowhereE2eKey "$NOWHERE_E2E_KEY" \
    -e nowhereE2ePin "$PIN" \
    -e nowhereE2eTarget "$NOWHERE_E2E_TARGET" \
    "$APP_ID.test/androidx.test.runner.AndroidJUnitRunner" > "$RUNDIR/instrument.log" 2>&1
grep -E '^(OK|FAILURES|Tests run)|^[0-9]+\) ' "$RUNDIR/instrument.log" | tail -40
grep -q '^OK (' "$RUNDIR/instrument.log"
STATUS=$?

echo
echo "--- Portal log ---"
tail -30 "$RUNDIR/portal.log" || true
cp "$RUNDIR/portal.log" "$ROOT/last-portal-e2e.log" 2>/dev/null || true

[ "$STATUS" -eq 0 ] && echo "PASS: end-to-end instrumentation test" || fail "instrumentation test failed (exit $STATUS)"
