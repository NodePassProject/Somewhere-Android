#!/usr/bin/env bash
# Proves the tunnel carries traffic, from a process that is actually inside it.
#
# ## Why this is a script and not a test
#
# This client is forced out of its own tunnel in every mode
# (`AppSelection.ruleFor`), because a VPN inside its own tunnel is a routing
# loop. Instrumentation runs in the app's process, so **its sockets are outside
# the TUN by construction** and no instrumentation test can ever prove that the
# tunnel carried anything. The suite did not notice for two runs: per-app
# selection landed on a day with no device attached, and when the cases next ran
# they were pointed at a host-local origin the emulator reaches directly. They
# passed. The Portal's byte counters had not moved.
#
# `adb shell` runs as the shell user, which is not this app and therefore *is*
# inside the tunnel. That is the whole trick.
#
# Usage:
#   e2e-tunnel-fetch.sh <target-host:port> [path]
#
# ## How this knows the traffic went through the tunnel
#
# Not by choosing an address the device cannot otherwise reach. That works from
# inside the app, where the connection is made by the app and captured by the
# TUN, and it does **not** work from a shell: the kernel routes 127.0.0.0/8 to
# `lo` rather than to the default route, so loopback never enters a VPN's TUN at
# all and a loopback target proves only that loopback is loopback.
#
# So the evidence is the Portal's own byte counters, read either side of the
# fetch. They are what the Portal's `accept()` and its relay actually moved, and
# neither half of this script can fake them. Point NOWHERE_PORTAL_LOG at the
# Portal's log; without it the fetch still runs and says plainly that it proved
# less.
#
# Needs NOWHERE_E2E_PORTAL, because it brings the tunnel up itself. `adb` cannot
# start a VPN -- VpnService needs consent and an application to bind it -- so it
# asks the app to, through an instrumentation entry that exists for exactly this
# and asserts nothing.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="${1:-}"
PATH_ON_ORIGIN="${2:-/blob.bin}"
DEVICE="${NOWHERE_DEVICE:-}"
REMOTE=/data/local/tmp/somewhere-fetch.http
WORK="$(mktemp -d)"

fail() { echo "FAIL: $*" >&2; exit 1; }
step() { printf '\n== %s ==\n' "$*"; }
cleanup() {
    # Silenced: killing a background job prints a job-control line that reads
    # like a failure directly under a PASS.
    [ -n "${HOLDER:-}" ] && { kill "$HOLDER" 2>/dev/null; wait "$HOLDER" 2>/dev/null; } || true
    [ -n "${DEVICE:-}" ] && adb -s "$DEVICE" shell am force-stop eu.nodepass.somewhere >/dev/null 2>&1
    rm -rf "$WORK"
}
trap cleanup EXIT

[ -n "$TARGET" ] || fail "usage: e2e-tunnel-fetch.sh <host:port> [path]"
command -v adb >/dev/null || fail "adb is not on PATH"
if [ -z "$DEVICE" ]; then
    DEVICE="$(adb devices | awk '$2=="device"{print $1; exit}')"
fi
[ -n "$DEVICE" ] || fail "no device; see scripts/device-connect.sh"

HOST="${TARGET%:*}"
PORT="${TARGET##*:}"

# Which address family the fetch should use. Unset means "whatever the device
# picks", which is the ordinary case. `6` is the one that proves something the
# other cannot: that AAAA synthesis, the ::/0 route, lwIP's IPv6 side and the
# fake-IP layer's v6 half all work together, using nothing but this device --
# the synthetic address is local, and the Portal still dials the *name*, over
# whichever family its own network prefers.
FAMILY="${NOWHERE_E2E_FAMILY:-}"

PORTAL="${NOWHERE_E2E_PORTAL:-}"
KEY="${NOWHERE_E2E_KEY:-conformance-smoke-key}"
CARRIER="${NOWHERE_E2E_CARRIER:-udp}"
# Long enough for a twenty-megabyte transfer plus the retries below. A hold that
# expires mid-transfer truncates it, and a truncated file is non-empty -- which
# the first version of the retry loop accepted, then failed on the digest with
# no attempt left, reporting a corrupt tunnel when what it had was a short read.
HOLD="${NOWHERE_HOLD_SECONDS:-300}"
[ -n "$PORTAL" ] || fail "set NOWHERE_E2E_PORTAL to the Portal as the device sees it"

step "consent"
adb -s "$DEVICE" shell cmd appops set eu.nodepass.somewhere ACTIVATE_VPN allow >/dev/null 2>&1 \
    || adb -s "$DEVICE" shell appops set eu.nodepass.somewhere ACTIVATE_VPN allow >/dev/null 2>&1 \
    || fail "could not pre-grant VPN consent"

step "bring the tunnel up ($CARRIER)"
HOLDER_LOG="$WORK/holder.log"
adb -s "$DEVICE" shell am instrument -w \
    -e class eu.nodepass.somewhere.vpn.TunnelHolderTest \
    -e nowhereHoldSeconds "$HOLD" \
    -e nowhereE2ePortal "$PORTAL" \
    -e nowhereE2eKey "$KEY" \
    -e nowhereE2eCarrier "$CARRIER" \
    -e nowhereE2eTarget "$TARGET" \
    eu.nodepass.somewhere.test/androidx.test.runner.AndroidJUnitRunner \
    > "$HOLDER_LOG" 2>&1 &
HOLDER=$!

# Poll for the interface rather than for a message from the test. A `println`
# inside instrumentation is buffered into the run's final status block, so it
# arrives after the thing it announces is already over; `tun0` existing is the
# condition itself and needs no cooperation from the app.
UP=0
for _ in $(seq 1 60); do
    if adb -s "$DEVICE" shell 'ip addr show tun0' >/dev/null 2>&1; then UP=1; break; fi
    grep -qE "FAILURES|Process crashed" "$HOLDER_LOG" 2>/dev/null && {
        grep -E "^eu\.|Error|Exception|assumption" "$HOLDER_LOG" | head -5
        fail "the tunnel would not come up"
    }
    sleep 1
done
[ "$UP" = 1 ] || { grep -E "^eu\.|Error|Exception" "$HOLDER_LOG" | head -5; fail "no tun0 within 60s"; }
echo "OK  tun0 is up, held for ${HOLD}s"

PORTAL_LOG="${NOWHERE_PORTAL_LOG:-}"
counter() {
    [ -n "$PORTAL_LOG" ] && [ -f "$PORTAL_LOG" ] || { echo ""; return; }
    grep -o "TCPTX=[0-9]*" "$PORTAL_LOG" | tail -1 | cut -d= -f2
}

# The address `nc` is pointed at. For family 6 it is resolved on the device,
# through the tunnel's own resolver, which is the only way to obtain a synthetic
# IPv6 address -- it exists because a AAAA query was answered, and for no other
# reason. `ping6` is used as the resolver because Android's shell has no dig and
# toybox has no host; it prints the address it resolved before it sends
# anything, which is all that is wanted here.
CONNECT="$HOST"
if [ "$FAMILY" = "6" ]; then
    step "resolve $HOST over IPv6, through the tunnel"
    RESOLVED="$(adb -s "$DEVICE" shell "ping6 -c1 -w2 $HOST" 2>/dev/null | head -1 | sed -n 's/.*(\([0-9a-fA-F:]*\)).*/\1/p')"
    [ -n "$RESOLVED" ] || fail "no AAAA came back for $HOST; the tunnel's resolver did not synthesise one"
    echo "OK  $HOST resolved to $RESOLVED"
    case "$RESOLVED" in
        fc00:*) ;;
        *) fail "$RESOLVED is not a synthetic address, so this fetch would prove nothing about the fake-IP layer" ;;
    esac
    CONNECT="$RESOLVED"
fi

step "fetch through the tunnel"
BEFORE="$(counter)"
adb -s "$DEVICE" shell "rm -f $REMOTE" >/dev/null 2>&1
# HTTP/1.0 so the origin closes the connection and nc returns. `Connection:
# close` would do the same for 1.1, but 1.0 needs no chunked handling either.
# `tun0` exists as soon as `establish()` returns, which is before the session
# has authenticated and before any flow can open. Polling the interface is
# therefore a readiness signal that fires early, and a fetch issued on it races
# — this failed exactly that way, while the same command by hand a few seconds
# later worked every time.
#
# Retried rather than slept, and the attempt count is printed: a fixed sleep
# long enough to be safe is also long enough to hide a tunnel that took twenty
# seconds to come up, and how long it took is a thing worth seeing on a device
# nobody here has run on before.
verify() {
    adb -s "$DEVICE" pull "$REMOTE" "$WORK/response.http" >/dev/null 2>&1 || return 1
    python3 - "$WORK/response.http" >/dev/null 2>&1 <<'VERIFY_PY'
import hashlib, sys

raw = open(sys.argv[1], 'rb').read()
end = raw.find(b'\r\n\r\n')
if end < 0:
    sys.exit(1)
head = raw[:end].decode('iso-8859-1')
if '200' not in head.splitlines()[0]:
    sys.exit(1)
declared = None
for line in head.splitlines()[1:]:
    if line.lower().startswith('x-content-sha256:'):
        declared = line.split(':', 1)[1].strip()
sys.exit(0 if declared and declared == hashlib.sha256(raw[end + 4:]).hexdigest() else 1)
VERIFY_PY
}

SIZE=""
for attempt in $(seq 1 5); do
    adb -s "$DEVICE" shell "rm -f $REMOTE" >/dev/null 2>&1
    adb -s "$DEVICE" shell \
        "printf 'GET $PATH_ON_ORIGIN HTTP/1.0\r\nHost: $HOST:$PORT\r\n\r\n' | nc -w 120 $CONNECT $PORT > $REMOTE" \
        >/dev/null 2>&1
    SIZE="$(adb -s "$DEVICE" shell "toybox stat -c %s $REMOTE 2>/dev/null || wc -c < $REMOTE" 2>/dev/null | tr -d '\r ')"
    # Verified inside the loop rather than after it. A truncated transfer is
    # non-empty, so a size check accepts one and the digest then fails with no
    # attempt left to correct it.
    if [ -n "$SIZE" ] && [ "$SIZE" -gt 0 ] 2>/dev/null && verify; then
        echo "OK  $SIZE bytes on the device, intact, on attempt $attempt"
        break
    fi
    SIZE=""
    sleep 3
done
[ -n "$SIZE" ] || fail "no intact transfer came through the tunnel in five attempts"

step "did it go through the Portal"
if [ -n "$BEFORE" ]; then
    # The Portal's counters only advance for traffic it relayed. A fetch that
    # took the underlying network instead leaves them exactly where they were,
    # which is what happened to this suite's device cases for two runs without
    # anyone noticing.
    # A Portal emits its counters on a timer, so the last line may still predate
    # the fetch. Waiting for the number to move is the condition; a fixed sleep
    # shorter than that timer reports "relayed nothing" about a transfer that
    # plainly happened.
    MOVED=0
    for _ in $(seq 1 15); do
        AFTER="$(counter)"
        MOVED=$(( ${AFTER:-0} - ${BEFORE:-0} ))
        [ "$MOVED" -gt 0 ] && break
        sleep 1
    done
    [ "$MOVED" -gt 0 ] \
        || fail "the Portal relayed nothing in fifteen seconds: TCPTX stayed at ${BEFORE}. The fetch did not go through the tunnel."
    echo "OK  the Portal relayed $MOVED bytes ($BEFORE -> $AFTER)"
else
    echo "--  NOWHERE_PORTAL_LOG is not set, so nothing here shows the bytes"
    echo "    went through the Portal rather than around it. Set it."
fi

step "digest, split on the host"
adb -s "$DEVICE" pull "$REMOTE" "$WORK/response.http" >/dev/null 2>&1 || fail "could not pull the response"
adb -s "$DEVICE" shell "rm -f $REMOTE" >/dev/null 2>&1

python3 - "$WORK/response.http" <<'PY'
import hashlib, sys

raw = open(sys.argv[1], 'rb').read()
end = raw.find(b'\r\n\r\n')
if end < 0:
    sys.exit("FAIL: the response has no header terminator")
head = raw[:end].decode('iso-8859-1')
body = raw[end + 4:]

status = head.splitlines()[0]
if '200' not in status:
    sys.exit("FAIL: %s" % status)

declared = None
for line in head.splitlines()[1:]:
    if line.lower().startswith('x-content-sha256:'):
        declared = line.split(':', 1)[1].strip()
if declared is None:
    sys.exit("FAIL: the origin declared no digest, so nothing can be compared")

computed = hashlib.sha256(body).hexdigest()
print("    declared %s" % declared)
print("    computed %s  (%d bytes)" % (computed, len(body)))
if declared != computed:
    sys.exit("FAIL: the bytes that came through the tunnel are not the bytes the origin sent")
print("OK  the transfer is intact")
PY
STATUS=$?
[ $STATUS -eq 0 ] || exit $STATUS

echo
echo "PASS: a process inside the tunnel fetched $PATH_ON_ORIGIN from $TARGET and the"
echo "      bytes match what the origin declared."
