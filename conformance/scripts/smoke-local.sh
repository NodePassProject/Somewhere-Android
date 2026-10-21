#!/usr/bin/env bash
# Local end-to-end smoke test: run Portal, Vector and a local target service on
# one machine, so traffic really traverses
# SOCKS5 -> Vector -> Nowhere tunnel -> Portal -> target.
#
# It exists to:
#   1. confirm the protocol baseline binary works (Portal and Vector interoperate);
#   2. give the Kotlin implementation a reference environment to compare against;
#   3. serve as a CI health check for "is upstream still working".
#
# Requires: a built nowhere binary, curl with socks5h support, python3.
# Usage: scripts/smoke-local.sh [/path/to/nowhere]
#        NOWHERE_BIN=/path/to/nowhere scripts/smoke-local.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Upstream clone location. This suite is published, so it must not assume any
# particular layout outside its own repository: set NOWHERE_CLONE to a checkout
# of NodePassProject/Nowhere at the pinned baseline. The default suits a
# side-by-side checkout next to the client repository.
NOWHERE_CLONE="${NOWHERE_CLONE:-$ROOT/../../Nowhere}"
BIN="${1:-${NOWHERE_BIN:-$NOWHERE_CLONE/target/release/nowhere}}"
KEY="0123456789abcdef0123456789abcdef"
PORTAL_PORT=22077
SOCKS_PORT=21080
TARGET_PORT=28000
RUNDIR="$(mktemp -d)"
PIDS=()

cleanup() {
    for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done
    wait 2>/dev/null || true
    rm -rf "$RUNDIR"
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }

wait_for_port() {
    local port=$1 name=$2 tries=0
    until nc -z 127.0.0.1 "$port" 2>/dev/null; do
        tries=$((tries + 1))
        [ "$tries" -gt 100 ] && fail "$name did not listen on $port within 10s"
        sleep 0.1
    done
}

[ -x "$BIN" ] || fail "no executable nowhere binary at $BIN (run cargo build --release in Nowhere/)"

echo "Baseline binary: $("$BIN" --version 2>&1 | head -1)"

# 1) Local target service with a fixed response, to prove the payload really
#    made it end to end.
printf 'nowhere-conformance-ok' > "$RUNDIR/probe.txt"
(cd "$RUNDIR" && exec python3 -m http.server "$TARGET_PORT" --bind 127.0.0.1) \
    > "$RUNDIR/target.log" 2>&1 &
PIDS+=($!)
wait_for_port "$TARGET_PORT" "target service"

# 2) Portal. tls=1 generates a certificate at startup and logs its SHA-256;
#    HOST:PORT is TLS and QUIC on the same port, and Morph is on by default.
"$BIN" "portal://${KEY}@127.0.0.1:${PORTAL_PORT}" \
    > "$RUNDIR/portal.log" 2>&1 &
PIDS+=($!)
wait_for_port "$PORTAL_PORT" "Portal"
PIN="$(sed -n 's/.*tls certificate fingerprint: \([0-9a-f]\{64\}\).*/\1/p' "$RUNDIR/portal.log" | head -1)"
[ -n "$PIN" ] || fail "the Portal did not log its certificate fingerprint"

# 3) Vectors: TLS and QUIC, local SOCKS5 entries. v3 has no unverified mode, so
#    each pins the certificate the Portal itself reported.
"$BIN" "vector://${KEY}@127.0.0.1:${PORTAL_PORT}?in=127.0.0.1:${SOCKS_PORT}&pin=${PIN}" \
    > "$RUNDIR/vector.log" 2>&1 &
PIDS+=($!)
wait_for_port "$SOCKS_PORT" "Vector SOCKS5 listener (TLS)"
QUIC_SOCKS_PORT=$((SOCKS_PORT + 1))
"$BIN" "vector://${KEY}@127.0.0.1/udp:${PORTAL_PORT}?in=127.0.0.1:${QUIC_SOCKS_PORT}&pin=${PIN}" \
    > "$RUNDIR/vector-quic.log" 2>&1 &
PIDS+=($!)
wait_for_port "$QUIC_SOCKS_PORT" "Vector SOCKS5 listener (QUIC)"

# 4) Fetch the payload through the tunnel. socks5h also proxies name resolution,
#    which is closer to real client behaviour.
for carrier in "TLS:$SOCKS_PORT" "QUIC:$QUIC_SOCKS_PORT"; do
    name="${carrier%%:*}"; port="${carrier##*:}"
    BODY="$(curl -sS --max-time 15 --proxy "socks5h://127.0.0.1:${port}" \
            "http://127.0.0.1:${TARGET_PORT}/probe.txt" || true)"
    if [ "$BODY" = "nowhere-conformance-ok" ]; then
        echo "PASS: SOCKS5 -> Vector -> Nowhere v3 ($name, morph) -> Portal -> target"
    else
        echo "--- portal.log ---"; tail -20 "$RUNDIR/portal.log" || true
        echo "--- vector logs ---"; tail -20 "$RUNDIR"/vector*.log || true
        fail "unexpected payload over $name: '${BODY}'"
    fi
done
