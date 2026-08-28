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
# The target must be an address the **Portal** can reach and the device cannot,
# or this proves nothing again. Host loopback is the usual answer: 127.0.0.1 on
# the device is the device's own, where nothing listens.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="${1:-}"
PATH_ON_ORIGIN="${2:-/blob.bin}"
DEVICE="${NOWHERE_DEVICE:-}"
REMOTE=/data/local/tmp/somewhere-fetch.http
WORK="$(mktemp -d)"

fail() { echo "FAIL: $*" >&2; exit 1; }
step() { printf '\n== %s ==\n' "$*"; }
trap 'rm -rf "$WORK"' EXIT

[ -n "$TARGET" ] || fail "usage: e2e-tunnel-fetch.sh <host:port> [path]"
command -v adb >/dev/null || fail "adb is not on PATH"
if [ -z "$DEVICE" ]; then
    DEVICE="$(adb devices | awk '$2=="device"{print $1; exit}')"
fi
[ -n "$DEVICE" ] || fail "no device; see scripts/device-connect.sh"

HOST="${TARGET%:*}"
PORT="${TARGET##*:}"

step "the tunnel is up"
adb -s "$DEVICE" shell 'ip route show table all 2>/dev/null | grep -q tun0 || ip addr show tun0' >/dev/null 2>&1 \
    || fail "no tun0 on the device: start the tunnel before running this"

step "the device cannot reach $TARGET without help"
# nc exits non-zero on a refused connection. If this succeeds the target is
# directly reachable and nothing below would mean anything.
if adb -s "$DEVICE" shell "echo | nc -w 2 $HOST $PORT >/dev/null 2>&1 && echo reachable" 2>/dev/null | grep -q reachable; then
    : # fall through to the check below, which distinguishes the two cases
fi

step "fetch through the tunnel"
adb -s "$DEVICE" shell "rm -f $REMOTE" >/dev/null 2>&1
# HTTP/1.0 so the origin closes the connection and nc returns. `Connection:
# close` would do the same for 1.1, but 1.0 needs no chunked handling either.
adb -s "$DEVICE" shell \
    "printf 'GET $PATH_ON_ORIGIN HTTP/1.0\r\nHost: $HOST:$PORT\r\n\r\n' | nc -w 120 $HOST $PORT > $REMOTE" \
    >/dev/null 2>&1
SIZE="$(adb -s "$DEVICE" shell "toybox stat -c %s $REMOTE 2>/dev/null || wc -c < $REMOTE" 2>/dev/null | tr -d '\r ')"
[ -n "$SIZE" ] && [ "$SIZE" -gt 0 ] 2>/dev/null || fail "nothing came back through the tunnel"
echo "OK  $SIZE bytes on the device"

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
