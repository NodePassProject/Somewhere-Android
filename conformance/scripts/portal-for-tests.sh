#!/usr/bin/env bash
# Runs a Portal for the live tests and prints the environment they need.
#
# The live tests answer what no fake can: whether Conscrypt exports the bytes
# Nowhere's authentication needs, whether the Morph mask is the one a Portal
# expects, and whether a pin matches the certificate a Portal actually serves.
# Each can only be settled by a real handshake against a real Portal.
#
# The Portal listens on TLS and QUIC on the same port (v3's `HOST:PORT` form),
# with Morph on — its default. NOWHERE_E2E_PIN is the fingerprint the Portal
# itself logs at startup: the most independent value available, since neither
# the client nor a connection the client made produced it.
#
# Usage:
#   eval "$(scripts/portal-for-tests.sh)"    # start, export the variables
#   ./gradlew testDebugUnitTest
#   scripts/portal-for-tests.sh --stop
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NOWHERE_CLONE="${NOWHERE_CLONE:-$ROOT/../../Nowhere}"
BIN="${NOWHERE_BIN:-$NOWHERE_CLONE/target/release/nowhere}"
PORT="${PORTAL_PORT:-22078}"
# v3 requires exactly 32 lowercase hex characters. A fixed key, because this is
# a test Portal on loopback and a fixed key makes a failing run reproducible.
KEY="${NOWHERE_E2E_KEY:-0123456789abcdef0123456789abcdef}"
LOG="${TMPDIR:-/tmp}/somewhere-test-portal.log"
PIDFILE="${TMPDIR:-/tmp}/somewhere-test-portal.pid"

if [ "${1:-}" = "--stop" ]; then
    if [ -f "$PIDFILE" ]; then
        kill "$(cat "$PIDFILE")" 2>/dev/null && echo "stopped Portal $(cat "$PIDFILE")" >&2
        rm -f "$PIDFILE"
    else
        echo "no Portal running from this script" >&2
    fi
    exit 0
fi

[ -x "$BIN" ] || {
    echo "no nowhere binary at $BIN — run cargo build --release in the Nowhere clone" >&2
    exit 2
}

if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
    echo "# reusing Portal $(cat "$PIDFILE") on $PORT" >&2
else
    "$BIN" "portal://${KEY}@127.0.0.1:${PORT}" > "$LOG" 2>&1 &
    echo $! > "$PIDFILE"
    for _ in $(seq 1 50); do
        if nc -z 127.0.0.1 "$PORT" 2>/dev/null; then break; fi
        sleep 0.1
    done
    nc -z 127.0.0.1 "$PORT" 2>/dev/null || {
        echo "Portal did not come up on $PORT; see $LOG" >&2
        exit 2
    }
    echo "# started Portal $(cat "$PIDFILE") on $PORT" >&2
fi

PIN=$(sed -n 's/.*tls certificate fingerprint: \([0-9a-f]\{64\}\).*/\1/p' "$LOG" | head -1)
[ -n "$PIN" ] || { echo "the Portal did not log its certificate fingerprint; see $LOG" >&2; exit 2; }

echo "export NOWHERE_E2E_PORTAL=127.0.0.1:${PORT}"
echo "export NOWHERE_E2E_KEY=${KEY}"
echo "export NOWHERE_E2E_PIN=${PIN}"
