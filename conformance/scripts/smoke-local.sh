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
KEY="conformance-smoke-key"
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

# 2) Portal. tls=1 generates an in-memory self-signed certificate; the default
#    net=mix accepts TLS/TCP and QUIC on the same port.
"$BIN" "portal://${KEY}@127.0.0.1:${PORTAL_PORT}?log=info" \
    > "$RUNDIR/portal.log" 2>&1 &
PIDS+=($!)
wait_for_port "$PORTAL_PORT" "Portal"

# 3) Vector: TLS/TCP in both directions (the L1 target shape), local SOCKS5 entry.
#    Deliberately no sni/pin, matching what NowhereDash currently generates —
#    the configuration for which upstream skips certificate verification
#    entirely. See decision D-11.
"$BIN" "vector://${KEY}@127.0.0.1:${PORTAL_PORT}?up=tcp&down=tcp&socks=127.0.0.1:${SOCKS_PORT}&log=info" \
    > "$RUNDIR/vector.log" 2>&1 &
PIDS+=($!)
wait_for_port "$SOCKS_PORT" "Vector SOCKS5 listener"

# 4) Fetch the payload through the tunnel. socks5h also proxies name resolution,
#    which is closer to real client behaviour.
BODY="$(curl -sS --max-time 15 --proxy "socks5h://127.0.0.1:${SOCKS_PORT}" \
        "http://127.0.0.1:${TARGET_PORT}/probe.txt" || true)"

if [ "$BODY" = "nowhere-conformance-ok" ]; then
    echo "PASS: SOCKS5 -> Vector -> Nowhere(tcp/tcp) -> Portal -> target"
else
    echo "--- portal.log ---"; tail -20 "$RUNDIR/portal.log" || true
    echo "--- vector.log ---"; tail -20 "$RUNDIR/vector.log" || true
    fail "unexpected payload: '${BODY}'"
fi

# 5) Confirm the Portal side recorded the connection; EVENT-level logs are the
#    reference stream for comparing client behaviour against.
grep -qiE "flow|connect|ready|accept" "$RUNDIR/portal.log" \
    && echo "PASS: Portal recorded the connection" \
    || echo "NOTE: no matching connection record in the Portal log (log level may be too low; does not affect the result)"
