#!/usr/bin/env bash
# Runs a Portal for the exporter tests and prints the environment they need.
#
# The exporter tests answer the one question ADR-0001 could not: whether
# Conscrypt exports the bytes Nowhere's authentication actually needs. That can
# only be settled by a real handshake against a real Portal, so this starts one.
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
KEY="${NOWHERE_E2E_KEY:-conformance-smoke-key}"
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
    "$BIN" "portal://${KEY}@127.0.0.1:${PORT}?log=info" > "${TMPDIR:-/tmp}/somewhere-test-portal.log" 2>&1 &
    echo $! > "$PIDFILE"
    for _ in $(seq 1 50); do
        if nc -z 127.0.0.1 "$PORT" 2>/dev/null; then break; fi
        sleep 0.1
    done
    nc -z 127.0.0.1 "$PORT" 2>/dev/null || {
        echo "Portal did not come up on $PORT; see ${TMPDIR:-/tmp}/somewhere-test-portal.log" >&2
        exit 2
    }
    echo "# started Portal $(cat "$PIDFILE") on $PORT" >&2
fi

echo "export NOWHERE_E2E_PORTAL=127.0.0.1:${PORT}"
echo "export NOWHERE_E2E_KEY=${KEY}"
