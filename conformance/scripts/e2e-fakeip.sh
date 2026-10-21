#!/usr/bin/env bash
# Remote resolution, end to end, on a device: an app asks for a NAME and the
# Portal is asked to dial that name.
#
# The claim is not provable with an address, and it is not provable with a name
# the device can resolve for itself — either way the fetch succeeds with the
# fake-IP layer removed. So the origin server is given a name that exists only
# where the Portal runs:
#
#     device ── 10.0.2.2:PORTAL_PORT ──▶ portal container ──▶ origin container
#                  (published to host)      (docker network, embedded DNS)
#
# The device cannot resolve `origin.somewhere.test`. The Portal can, because
# Docker's resolver on that network knows it. If the client resolved names
# locally the fetch would fail with an unknown host, which is the point.
#
# Docker rather than a host process because the Portal has to resolve a name in
# a namespace we control, and the alternatives are editing /etc/hosts on
# somebody's machine or depending on a public wildcard DNS service.
#
# ## What v3 changed here
#
# - The Portal is the pinned oracle binary itself, mounted into a stock image,
#   rather than an image built from the clone: the thing tested against is the
#   thing PROTOCOL_BASELINE names, byte for byte.
# - Every node is pinned to the fingerprint the Portal logs at startup, since
#   v3 has no unverified mode.
# - The two phases are the two carriers, TLS and QUIC. v1's were Mux off and
#   on; v3 removed Mux.
# - A v3 Portal does not log the targets it dials or per-flow counters. The
#   evidence moved: the Portal's relay totals come from `nowhere status`, the
#   origin logs each request with the peer that made it — which is the Portal's
#   address on the docker network, and so proves who dialled the name — and the
#   connection count is sampled from the Portal's own socket table while a burst
#   of flows is held open together.
#
# Usage:  conformance/scripts/e2e-fakeip.sh
#         KEEP=1 conformance/scripts/e2e-fakeip.sh    # leave containers up
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="$(cd "$ROOT/.." && pwd)"
NOWHERE_CLONE="${NOWHERE_CLONE:-$PROJECT/../Nowhere}"
BIN="${NOWHERE_BIN:-$NOWHERE_CLONE/target/release/nowhere}"
NETWORK="${E2E_NETWORK:-somewhere-e2e}"
IMAGE="${E2E_IMAGE:-python:3-slim}"
ORIGIN_CONTAINER="somewhere-e2e-origin"
PORTAL_CONTAINER="somewhere-e2e-portal"
ORIGIN_NAME="${E2E_ORIGIN_NAME:-origin.somewhere.test}"
ORIGIN_PORT="${E2E_ORIGIN_PORT:-8080}"
PORTAL_PORT="${E2E_PORTAL_PORT:-22077}"
KEY="${E2E_KEY:-0123456789abcdef0123456789abcdef}"
BLOB_BYTES="${E2E_BLOB_BYTES:-20971520}"
BURST_WIDTH=16
APP_ID="eu.nodepass.somewhere"
# 10.0.2.2 is the host as seen from a QEMU-based emulator, which covers the
# AOSP emulator and the common third-party ones. Override for a physical device.
HOST_FROM_DEVICE="${E2E_HOST_FROM_DEVICE:-10.0.2.2}"

fail() { echo "FAIL: $*" >&2; exit 1; }
step() { echo; echo "== $* =="; }

cleanup() {
    [ -n "${WORK_FETCH:-}" ] && rm -rf "$WORK_FETCH"
    [ -n "${ORIGIN_SCRIPT:-}" ] && rm -f "$ORIGIN_SCRIPT"
    [ -n "${KEEP:-}" ] && { echo "KEEP set; containers left running"; return; }
    docker rm -f "$ORIGIN_CONTAINER" "$PORTAL_CONTAINER" >/dev/null 2>&1
}
trap cleanup EXIT

command -v docker >/dev/null 2>&1 || fail "docker is required; see the header for why"
docker info >/dev/null 2>&1 || fail "the docker daemon is not running"
[ -x "$BIN" ] || fail "no nowhere binary at $BIN — run cargo build --release in the Nowhere clone"

step "Portal binary"
echo "OK  $("$BIN" --version 2>&1 | head -1), run inside $IMAGE"
docker network inspect "$NETWORK" >/dev/null 2>&1 || docker network create "$NETWORK" >/dev/null

# --- The origin server -----------------------------------------------------
# It publishes its own SHA-256 in a header, so a transfer that is truncated on
# a block boundary is still caught — a length check would not catch it — and it
# logs the peer of every request, which is how the Portal's dial is observed.
step "origin server"
ORIGIN_SCRIPT="$(mktemp)"
cat > "$ORIGIN_SCRIPT" <<'PY'
import hashlib, os, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SIZE = int(os.environ.get("BLOB_BYTES", "20971520"))
SMALL = int(os.environ.get("SMALL_BYTES", "65536"))
WIDTH = int(os.environ.get("HOLD_WIDTH", "16"))

# The large one is for "a transfer survives the tunnel intact", where the size
# is the point. The small one is for the burst, where the size is only a cost.
BODIES = {
    "/blob.bin": os.urandom(SIZE),
    "/small.bin": os.urandom(SMALL),
}
DIGESTS = {path: hashlib.sha256(body).hexdigest() for path, body in BODIES.items()}
print("serving %d bytes, sha256=%s" % (SIZE, DIGESTS["/blob.bin"]), flush=True)

# /hold answers nobody until WIDTH requests are waiting, so a burst's flows are
# provably open at the same moment — which is when connections are counted —
# and then keeps them all open a little longer. Without that the overlap lasts
# only as long as the last arrival takes to be answered, which was shorter than
# the sampler's interval: sixteen held flows were counted as three connections.
HOLD_AFTER = float(os.environ.get("HOLD_AFTER_SECONDS", "2"))
lock = threading.Lock()
barrier = [threading.Barrier(WIDTH, timeout=60)]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        print("request %s from %s" % (self.path, self.client_address[0]), flush=True)
        if self.path == "/hold":
            try:
                barrier[0].wait()
                time.sleep(HOLD_AFTER)
            except threading.BrokenBarrierError:
                with lock:
                    barrier[0] = threading.Barrier(WIDTH, timeout=60)
                self.send_response(503)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            body = BODIES["/small.bin"]
            digest = DIGESTS["/small.bin"]
        else:
            body = BODIES.get(self.path)
            digest = DIGESTS.get(self.path)
        if body is None:
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Content-Sha256", digest)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
PY
docker rm -f "$ORIGIN_CONTAINER" >/dev/null 2>&1
docker run -d --name "$ORIGIN_CONTAINER" --network "$NETWORK" \
    --network-alias "$ORIGIN_NAME" \
    -v "$ORIGIN_SCRIPT:/origin.py:ro" \
    -e "BLOB_BYTES=$BLOB_BYTES" -e "HOLD_WIDTH=$BURST_WIDTH" \
    "$IMAGE" python -u /origin.py >/dev/null || fail "could not start the origin server"

for _ in $(seq 1 60); do
    docker logs "$ORIGIN_CONTAINER" 2>&1 | grep -q "^serving " && break
    sleep 0.5
done
docker logs "$ORIGIN_CONTAINER" 2>&1 | grep "^serving " || fail "the origin server did not come up"

# The name must resolve on the Portal's network and nowhere else.
docker run --rm --network "$NETWORK" "$IMAGE" getent hosts "$ORIGIN_NAME" >/dev/null 2>&1 \
    || fail "$ORIGIN_NAME does not resolve on the $NETWORK network"
echo "OK  $ORIGIN_NAME resolves inside the network, and only there"

# --- The Portal ------------------------------------------------------------
# Both protocols published, because the two carriers use different ones and
# publishing only TCP is invisible: a QUIC case against this Portal does not
# fail with "refused", it waits out the handshake deadline and reports a
# timeout, which reads as a broken client.
step "Portal"
docker rm -f "$PORTAL_CONTAINER" >/dev/null 2>&1
docker run -d --name "$PORTAL_CONTAINER" --network "$NETWORK" \
    -p "$PORTAL_PORT:$PORTAL_PORT/tcp" -p "$PORTAL_PORT:$PORTAL_PORT/udp" \
    -v "$BIN:/usr/local/bin/nowhere:ro" \
    "$IMAGE" nowhere "portal://${KEY}@0.0.0.0:${PORTAL_PORT}" >/dev/null \
    || fail "could not start the Portal"

for _ in $(seq 1 60); do
    nc -z 127.0.0.1 "$PORTAL_PORT" 2>/dev/null && break
    sleep 0.5
done
nc -z 127.0.0.1 "$PORTAL_PORT" 2>/dev/null || fail "the Portal is not listening on $PORTAL_PORT"
PIN="$(docker logs "$PORTAL_CONTAINER" 2>&1 | sed -n 's/.*tls certificate fingerprint: \([0-9a-f]\{64\}\).*/\1/p' | head -1)"
[ -n "$PIN" ] || fail "the Portal did not log its certificate fingerprint"
PORTAL_IP="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$PORTAL_CONTAINER")"
echo "OK  Portal on :$PORTAL_PORT, reachable from the device as $HOST_FROM_DEVICE:$PORTAL_PORT"
echo "    pin $PIN; on the docker network as $PORTAL_IP"

# --- The device ------------------------------------------------------------
step "device"
DEVICE="$("$ROOT/scripts/device-connect.sh")" || fail "no usable device"
export ANDROID_SERIAL="$DEVICE"
SDK_LEVEL="$(adb -s "$DEVICE" shell getprop ro.build.version.sdk | tr -d '\r')"
echo "OK  $DEVICE  API $SDK_LEVEL  $(adb -s "$DEVICE" shell getprop ro.product.cpu.abi | tr -d '\r')"

# VpnService shows a consent dialog. Instrumentation cannot dismiss it, so it
# is pre-granted. Two spellings, because images differ in which they ship.
grant_vpn_consent() {
    adb -s "$DEVICE" shell cmd appops set "$APP_ID" ACTIVATE_VPN allow >/dev/null 2>&1 && return 0
    adb -s "$DEVICE" shell appops set "$APP_ID" ACTIVATE_VPN allow >/dev/null 2>&1 && return 0
    return 1
}

( cd "$PROJECT" && ./gradlew --no-daemon installDebug installDebugAndroidTest ) >/dev/null 2>&1 \
    || fail "could not install the app"
grant_vpn_consent || fail "could not pre-grant VPN consent"
echo "OK  app installed, VPN consent pre-granted"

WORK_FETCH="$(mktemp -d)"
STATUS=0

# Established TCP connections the Portal holds on its listening port, from its
# own socket table, every 100 ms until stopped; the peak is what counts.
sample_connections() {
    local out=$1
    local hex; hex="$(printf '%04X' "$PORTAL_PORT")"
    docker exec "$PORTAL_CONTAINER" python3 -c "
import time, sys
peak = 0
while True:
    n = 0
    for table in ('/proc/net/tcp', '/proc/net/tcp6'):
        try:
            for line in open(table).readlines()[1:]:
                f = line.split()
                if f[1].endswith(':$hex') and f[3] == '01':
                    n += 1
        except OSError:
            pass
    if n > peak:
        peak = n
        open('/tmp/peak', 'w').write(str(peak))
    time.sleep(0.1)
" >/dev/null 2>&1 &
    echo $! > "$out"
}

for CARRIER in tcp udp; do
    echo
    echo "--- carrier $CARRIER ---"
    grant_vpn_consent || fail "could not pre-grant VPN consent for $CARRIER"

    # Driven from the shell rather than from instrumentation. Instrumentation
    # runs in the app's process; this client is forced out of its own tunnel
    # in every mode, so an in-app fetch never enters the TUN. `adb shell` is
    # the shell user, which is inside the tunnel. Both families: v4 is the
    # ordinary path, v6 exercises the half of the fake-IP layer that exists
    # only because a AAAA query was answered.
    for FAMILY in 4 6; do
        if NOWHERE_E2E_PORTAL="${HOST_FROM_DEVICE}:${PORTAL_PORT}" \
           NOWHERE_E2E_KEY="$KEY" \
           NOWHERE_E2E_PIN="$PIN" \
           NOWHERE_E2E_CARRIER="$CARRIER" \
           NOWHERE_E2E_FAMILY="$FAMILY" \
           NOWHERE_STATUS="docker exec $PORTAL_CONTAINER nowhere status" \
           "$ROOT/scripts/e2e-tunnel-fetch.sh" "${ORIGIN_NAME}:${ORIGIN_PORT}" /blob.bin \
           > "$WORK_FETCH/fetch-$CARRIER-v$FAMILY.log" 2>&1; then
            echo "  OK  $CARRIER, IPv$FAMILY: $(grep -o 'the Portal relayed about [0-9]* bytes' "$WORK_FETCH/fetch-$CARRIER-v$FAMILY.log" | head -1)"
        else
            echo "  FAIL  $CARRIER, IPv$FAMILY: $(tail -3 "$WORK_FETCH/fetch-$CARRIER-v$FAMILY.log" | tr '\n' ' ')"
            STATUS=1
        fi
    done

    # Sixteen flows in one tunnel, held open together by the origin, with the
    # Portal's socket table sampled throughout. Over TLS that is sixteen
    # connections — "TLS uses one connection per Flow" — and over QUIC none.
    adb -s "$DEVICE" shell am force-stop "$APP_ID" >/dev/null 2>&1
    sleep 2
    grant_vpn_consent || true
    adb -s "$DEVICE" shell am instrument -w \
        -e class eu.nodepass.somewhere.vpn.TunnelHolderTest \
        -e nowhereHoldSeconds 120 \
        -e nowhereE2ePortal "${HOST_FROM_DEVICE}:${PORTAL_PORT}" \
        -e nowhereE2eKey "$KEY" \
        -e nowhereE2ePin "$PIN" \
        -e nowhereE2eCarrier "$CARRIER" \
        eu.nodepass.somewhere.test/androidx.test.runner.AndroidJUnitRunner \
        > "$WORK_FETCH/holder-$CARRIER.log" 2>&1 &
    HOLDER_PID=$!
    UP=0
    for _ in $(seq 1 60); do
        adb -s "$DEVICE" shell 'ip addr show tun0' >/dev/null 2>&1 && { UP=1; break; }
        sleep 1
    done
    [ "$UP" = 1 ] || fail "the tunnel would not come up for the burst over $CARRIER"
    # The interface exists before the session has authenticated; a warm-up
    # fetch makes the sixteen below concurrent rather than fifteen waiting
    # behind a handshake.
    adb -s "$DEVICE" shell "printf 'GET /small.bin HTTP/1.0\r\nHost: ${ORIGIN_NAME}\r\n\r\n' | nc -w 20 ${ORIGIN_NAME} ${ORIGIN_PORT} > /dev/null" >/dev/null 2>&1

    docker exec "$PORTAL_CONTAINER" rm -f /tmp/peak >/dev/null 2>&1
    sample_connections "$WORK_FETCH/sampler-$CARRIER.pid"
    sleep 1
    adb -s "$DEVICE" shell "for i in \$(seq 1 $BURST_WIDTH); do (printf 'GET /hold HTTP/1.0\r\nHost: ${ORIGIN_NAME}\r\n\r\n' | nc -w 60 ${ORIGIN_NAME} ${ORIGIN_PORT} > /data/local/tmp/cf.\$i) & done; wait" >/dev/null 2>&1
    kill "$(cat "$WORK_FETCH/sampler-$CARRIER.pid")" 2>/dev/null
    docker exec "$PORTAL_CONTAINER" pkill -f '/proc/net/tcp' >/dev/null 2>&1 || true
    PEAK="$(docker exec "$PORTAL_CONTAINER" cat /tmp/peak 2>/dev/null || echo 0)"
    SHORT="$(adb -s "$DEVICE" shell "for i in \$(seq 1 $BURST_WIDTH); do wc -c < /data/local/tmp/cf.\$i; done" 2>/dev/null | tr -d '\r' | awk '$1 < 1000' | wc -l | tr -d ' ')"
    adb -s "$DEVICE" shell 'rm -f /data/local/tmp/cf.*' >/dev/null 2>&1
    kill "$HOLDER_PID" 2>/dev/null; wait "$HOLDER_PID" 2>/dev/null || true
    adb -s "$DEVICE" shell am force-stop "$APP_ID" >/dev/null 2>&1

    printf '  %s: %s flows held open together over a peak of %s TCP connection(s), %s short response(s)\n' \
        "$CARRIER" "$BURST_WIDTH" "${PEAK:-0}" "$SHORT"
    [ "${SHORT:-$BURST_WIDTH}" -eq 0 ] || { echo "  FAIL  $CARRIER: $SHORT of $BURST_WIDTH held fetches came back short"; STATUS=1; }
    # At least, not exactly: the device's own background traffic goes through
    # the tunnel too, and over TLS each of its flows is a connection of its own
    # — measured at four extra on a Google APIs emulator image. The exact count
    # is asserted by oracle-diff.sh, on a host where nothing else is running;
    # what this proves is that a device's TLS flows do not share connections.
    if [ "$CARRIER" = tcp ]; then
        [ "${PEAK:-0}" -ge "$BURST_WIDTH" ] ||
            { echo "  FAIL  TLS held $BURST_WIDTH flows over ${PEAK:-0} connections; v3 is one per flow"; STATUS=1; }
    else
        [ "${PEAK:-0}" -eq 0 ] ||
            { echo "  FAIL  QUIC held $BURST_WIDTH flows with ${PEAK:-0} TCP connections to the Portal; it needs none"; STATUS=1; }
    fi
done

# --- Who dialled the name ---------------------------------------------------
# The device-side half is that the fetch by name succeeded through the tunnel.
# This is the other half, and it is the one the device cannot fake: the origin
# saw the request come from the Portal's address on the docker network, so the
# Portal resolved the name and dialled it — a client that resolved locally
# would have had nothing to send but a fake address the Portal cannot reach.
step "who dialled ${ORIGIN_NAME}"
FROM_PORTAL="$(docker logs "$ORIGIN_CONTAINER" 2>&1 | grep -c "^request .* from ${PORTAL_IP}$" || true)"
OTHERS="$(docker logs "$ORIGIN_CONTAINER" 2>&1 | grep '^request ' | grep -vc " from ${PORTAL_IP}$" || true)"
echo "  requests from the Portal ($PORTAL_IP): $FROM_PORTAL; from anywhere else: $OTHERS"
[ "${FROM_PORTAL:-0}" -gt 0 ] || fail "the origin never heard from the Portal — the name did not survive the client"
[ "${OTHERS:-0}" -eq 0 ] || fail "the origin was reached by something other than the Portal"

[ "$STATUS" -eq 0 ] || fail "at least one case above failed"
echo
echo "PASS: over TLS and QUIC, the device asked for a name and the Portal dialled it,"
echo "      a 20 MB transfer arrived intact over both address families, and sixteen"
echo "      held flows took a TLS connection each, or no TCP connection at all"
