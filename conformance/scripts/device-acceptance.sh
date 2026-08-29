#!/usr/bin/env bash
# Phase D: what only a physical device can answer.
#
# Everything else in this suite runs on an emulator, and an emulator is not a
# phone in five specific ways that a VPN meets on its first real device. This
# runs the checks that can be run, records the observations that can only be
# looked at, and prints a report either way -- because "each failure has a
# recorded symptom and a hypothesis" is a result and a run that never happened
# is not.
#
# Usage:
#   NOWHERE_E2E_PORTAL=host:port NOWHERE_E2E_TARGET=host:port \
#   NOWHERE_PORTAL_LOG=/path/to/portal.log \
#   conformance/scripts/device-acceptance.sh
#
# The target must be an HTTP origin the **Portal** can reach, serving /blob.bin
# and /small.bin with an X-Content-Sha256 header -- the one e2e-fakeip.sh
# starts, or the extracted copy of it.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
DEVICE="${NOWHERE_DEVICE:-}"
PORTAL="${NOWHERE_E2E_PORTAL:-}"
TARGET="${NOWHERE_E2E_TARGET:-}"
APP_ID="eu.nodepass.somewhere"

PASSES=0; FAILS=0; NOTES=0
pass() { echo "  PASS  $*"; PASSES=$((PASSES+1)); }
miss() { echo "  FAIL  $*"; FAILS=$((FAILS+1)); }
note() { echo "  NOTE  $*"; NOTES=$((NOTES+1)); }
head2() { printf '\n── %s\n' "$*"; }
die()  { echo "cannot start: $*" >&2; exit 2; }

command -v adb >/dev/null || die "adb is not on PATH"
[ -n "$DEVICE" ] || DEVICE="$(adb devices | awk '$2=="device"{print $1; exit}')"
[ -n "$DEVICE" ] || die "no device"
[ -n "$PORTAL" ] || die "set NOWHERE_E2E_PORTAL"
[ -n "$TARGET" ] || die "set NOWHERE_E2E_TARGET"

sh() { adb -s "$DEVICE" shell "$@" 2>/dev/null | tr -d '\r'; }

MODEL="$(sh getprop ro.product.model)"
SDK="$(sh getprop ro.build.version.sdk)"
ABI="$(sh getprop ro.product.cpu.abi)"
EMULATED="$(sh getprop ro.kernel.qemu)"

echo "Somewhere · device acceptance"
echo "  device   $DEVICE"
echo "  model    $MODEL"
echo "  android  API $SDK, $ABI"
if [ "$EMULATED" = "1" ]; then
    echo
    echo "  !! This is an emulator. Every check below will run and none of them"
    echo "     answers the question Phase D exists for, which is what a phone"
    echo "     does. Use it to check the script, not the client."
else
    # The address a device reaches the build host at is not the same one an
    # emulator does, and getting it wrong looks like a broken tunnel rather
    # than a wrong argument. 10.0.2.2 is QEMU's alias for the host and means
    # nothing on a phone; a phone needs the host's address on the network they
    # share, and the Portal has to be listening on it rather than on loopback.
    case "$PORTAL" in
        10.0.2.2:*|127.0.0.1:*|localhost:*)
            die "NOWHERE_E2E_PORTAL is '$PORTAL', which is an emulator's or this machine's own address. A phone cannot reach either. Use the build host's address on the network the phone is on, and start the Portal bound to 0.0.0.0."
            ;;
    esac
    case "$TARGET" in
        10.0.2.2:*)
            die "NOWHERE_E2E_TARGET is '$TARGET'. 10.0.2.2 is QEMU's host alias; a Portal on a real network cannot dial it."
            ;;
    esac
    echo
    echo "  Physical hardware. This is the run Phase D was written for."
fi

# ── 1. Private DNS ──────────────────────────────────────────────────────────
# Modern Android defaults to Automatic, and the resolver then speaks DoT to a
# resolver of its own rather than sending UDP/53 to the address the TUN
# announces. Fake-IP depends on seeing those queries; if it does not, remote
# name resolution silently stops happening.
head2 "1. Private DNS"
PRIVATE_DNS="$(sh settings get global private_dns_mode)"
case "$PRIVATE_DNS" in
    off) pass "Private DNS is off; the resolver will use the TUN's" ;;
    opportunistic|"null"|"") note "Private DNS is '$PRIVATE_DNS' (Automatic). The resolver may speak DoT and bypass the TUN's resolver. Watch whether names resolve below." ;;
    hostname) miss "Private DNS is set to a specific hostname ($(sh settings get global private_dns_specifier)). DoT will bypass the TUN's resolver." ;;
    *) note "Private DNS mode is '$PRIVATE_DNS', which this script does not know" ;;
esac

# ── 2. The app list, which is D-16 ──────────────────────────────────────────
# The manifest declares <queries> for launcher-visible apps instead of asking
# for QUERY_ALL_PACKAGES. Whether that is enough has never been measured
# against a device with real applications on it. This is the measurement.
head2 "2. Package visibility (D-16)"
INSTALLED="$(sh 'pm list packages -3' | wc -l | tr -d ' ')"
LAUNCHABLE="$(sh 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER' | grep -c '/' || true)"
echo "  third-party packages installed:      $INSTALLED"
echo "  packages with a launcher activity:   $LAUNCHABLE"
if [ "${INSTALLED:-0}" -lt 5 ]; then
    note "only $INSTALLED third-party apps here, so this measures nothing. Run it on a device someone uses."
elif [ "${LAUNCHABLE:-0}" -gt 0 ] && [ "$INSTALLED" -gt 0 ]; then
    note "compare these two by hand against the per-app screen. D-16 closes as 'neither' if the screen's list is close to $INSTALLED, and reopens with a number if it is not."
fi

# ── 3. Address families ─────────────────────────────────────────────────────
# The TUN carries both families since L4: an address and a default route for
# each, and the DNS layer synthesises AAAA behind that route. Two things can go
# wrong on a device and neither raises anything: the platform can decline a
# family it did not like the look of, and the network can be one this tunnel has
# never met.
head2 "3. Address families on the current network"
V4="$(sh 'ip -4 addr show scope global' | grep -c 'inet ' || true)"
V6="$(sh 'ip -6 addr show scope global' | grep -c 'inet6' || true)"
echo "  global IPv4 addresses: $V4"
echo "  global IPv6 addresses: $V6"
if [ "${V4:-0}" -eq 0 ] && [ "${V6:-0}" -gt 0 ]; then
    note "IPv6-only network. The tunnel itself carries both families, but the socket to the Portal must still reach it — over IPv6, or over NAT64 if the Portal is v4-only. Watch for a tunnel that comes up and carries nothing."
elif [ "${V6:-0}" -gt 0 ]; then
    pass "dual-stack, which is the case the v6 route exists for"
else
    note "IPv4 only, so nothing here exercises the v6 half. Re-run on a network that has it."
fi

head2 "3b. What the platform actually built"
# Read from the device rather than from the source: Builder.addAddress accepts
# what it is given and establish() returns null rather than explaining itself,
# so a family Android declined looks exactly like one it did not.
TUN_IFACE="$(sh 'ip -4 addr show' | grep -B2 '10\.66\.0\.2' | head -1 | sed 's/^[0-9]*: \([^:]*\).*/\1/')"
if [ -z "$TUN_IFACE" ]; then
    note "no interface carries 10.66.0.2, so the tunnel is not up. Start it and re-run; §4 brings it up on its own."
else
    echo "  tunnel interface: $TUN_IFACE"
    TUN_V4="$(sh "ip -4 addr show dev $TUN_IFACE" | grep -c 'inet ' || true)"
    TUN_V6="$(sh "ip -6 addr show dev $TUN_IFACE" | grep -c 'inet6' || true)"
    TUN_ROUTE6="$(sh "ip -6 route show dev $TUN_IFACE" | grep -c 'default\|^::/0' || true)"
    if [ "${TUN_V4:-0}" -gt 0 ] && [ "${TUN_V6:-0}" -gt 0 ]; then
        pass "$TUN_IFACE carries both families"
    else
        miss "$TUN_IFACE carries v4=$TUN_V4 v6=$TUN_V6 — the platform declined a family this client declares, and the DNS layer is minting placeholders for it"
    fi
    if [ "${TUN_ROUTE6:-0}" -gt 0 ]; then
        pass "an IPv6 default route points into $TUN_IFACE"
    else
        miss "no IPv6 default route into $TUN_IFACE, so v6 traffic leaves the device outside the tunnel"
    fi
fi

head2 "3c. An IPv6 destination through the tunnel"
# Needs an origin the Portal can reach over IPv6. The docker origin
# e2e-fakeip.sh starts is v4-only, so this is supplied or it is a note --
# never a silent pass.
if [ -z "${NOWHERE_E2E_TARGET6:-}" ]; then
    note "no NOWHERE_E2E_TARGET6 given, so nothing checked that a v6 destination is carried. Set it to [addr]:port of an HTTP origin reachable over IPv6 from the Portal, serving /blob.bin with X-Content-Sha256."
elif NOWHERE_E2E_CARRIER=tcp "$ROOT/e2e-tunnel-fetch.sh" "$NOWHERE_E2E_TARGET6" /blob.bin > /tmp/somewhere-fetch-v6.log 2>&1; then
    RELAYED6="$(grep -o 'the Portal relayed [0-9]* bytes' /tmp/somewhere-fetch-v6.log | head -1)"
    pass "an IPv6 destination is carried — ${RELAYED6:-no counter}"
else
    miss "IPv6 destination: $(tail -3 /tmp/somewhere-fetch-v6.log | tr '\n' ' ')"
fi

# ── 4. The tunnel actually carries traffic ──────────────────────────────────
# Both carriers, because a device is where a carrier difference would first
# show as something other than a test failure.
for CARRIER in tcp udp; do
    head2 "4. Traffic over the tunnel ($CARRIER)"
    if NOWHERE_E2E_CARRIER="$CARRIER" "$ROOT/e2e-tunnel-fetch.sh" "$TARGET" /blob.bin > "/tmp/somewhere-fetch-$CARRIER.log" 2>&1; then
        RELAYED="$(grep -o 'the Portal relayed [0-9]* bytes' "/tmp/somewhere-fetch-$CARRIER.log" | head -1)"
        ATTEMPT="$(grep -o 'on attempt [0-9]*' "/tmp/somewhere-fetch-$CARRIER.log" | head -1)"
        pass "20 MB intact over $CARRIER — ${RELAYED:-no counter}, ${ATTEMPT:-first attempt}"
    else
        miss "$CARRIER: $(tail -3 "/tmp/somewhere-fetch-$CARRIER.log" | tr '\n' ' ')"
    fi
done

# ── 5. Doze and battery management ──────────────────────────────────────────
# Vendor battery managers kill foreground services that survive indefinitely on
# an emulator. There is no way to assert the outcome from here; what can be
# reported is whether the app is exempt, which is what a user would have to
# change.
head2 "5. Battery management"
STANDBY="$(sh "am get-standby-bucket $APP_ID")"
IGNORING="$(sh "dumpsys deviceidle whitelist" | grep -c "$APP_ID" || true)"
echo "  standby bucket:                 ${STANDBY:-unknown}"
echo "  exempt from battery optimisation: $([ "${IGNORING:-0}" -gt 0 ] && echo yes || echo no)"
note "leave the tunnel running and the screen off for thirty minutes, then check it is still up. Nothing here can do that for you."

# ── 6. MTU ──────────────────────────────────────────────────────────────────
# TUN_MTU is 1500. Real cellular paths are frequently smaller, and there is TLS
# or QUIC framing on top. QUIC makes this sharper: a datagram that does not fit
# is not fragmented by the path.
head2 "6. Path MTU"
UNDERLYING="$(sh "ip link show" | grep -E "wlan0|rmnet|eth0" | grep -o "mtu [0-9]*" | head -1 | cut -d' ' -f2)"
echo "  underlying interface MTU: ${UNDERLYING:-unknown}"
if [ -n "$UNDERLYING" ] && [ "$UNDERLYING" -lt 1500 ] 2>/dev/null; then
    miss "the path MTU is $UNDERLYING, below the TUN's 1500. Large packets will need fragmenting somewhere, and this client does not do it."
else
    pass "the underlying MTU is ${UNDERLYING:-1500}, so the TUN's 1500 fits"
fi

# ── Report ──────────────────────────────────────────────────────────────────
printf '\n%s\n' "──────────────────────────────────────────"
echo "$PASSES passed, $FAILS failed, $NOTES to look at by hand"
echo
echo "A NOTE is not a pass. It is something only a person watching the device"
echo "can settle, and leaving it unsettled is the honest state until they do."
[ "$FAILS" -eq 0 ] || exit 1

# ── Running this on a phone ─────────────────────────────────────────────────
#
#   1. Start a Portal on this machine, bound to every interface rather than
#      loopback, and an origin beside it:
#
#        nowhere "portal://<key>@0.0.0.0:22095?net=mix&log=info" > portal.log &
#
#   2. Find this machine's address on the network the phone is on:
#
#        ipconfig getifaddr en0        # macOS
#        hostname -I | awk '{print $1}' # Linux
#
#   3. Attach the phone with USB debugging on, then:
#
#        NOWHERE_E2E_PORTAL=<that address>:22095 \
#        NOWHERE_E2E_KEY=<key> \
#        NOWHERE_E2E_TARGET=<that address>:28091 \
#        NOWHERE_PORTAL_LOG=portal.log \
#        conformance/scripts/device-acceptance.sh
#
# The phone and this machine must be on the same network, and this machine's
# firewall must let the phone reach both ports. A tunnel that cannot reach its
# Portal looks exactly like a tunnel that does not work.
