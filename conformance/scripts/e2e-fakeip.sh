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
# Usage:  conformance/scripts/e2e-fakeip.sh
#         KEEP=1 conformance/scripts/e2e-fakeip.sh    # leave containers up
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="$(cd "$ROOT/.." && pwd)"
NOWHERE_CLONE="${NOWHERE_CLONE:-$PROJECT/../Nowhere}"

NETWORK="${E2E_NETWORK:-somewhere-e2e}"
IMAGE="${E2E_IMAGE:-somewhere-conformance/nowhere:v1.8.2}"
ORIGIN_CONTAINER="somewhere-e2e-origin"
PORTAL_CONTAINER="somewhere-e2e-portal"
ORIGIN_NAME="${E2E_ORIGIN_NAME:-origin.somewhere.test}"
ORIGIN_PORT="${E2E_ORIGIN_PORT:-8080}"
PORTAL_PORT="${E2E_PORTAL_PORT:-22077}"
KEY="${E2E_KEY:-e2e-key}"
BLOB_BYTES="${E2E_BLOB_BYTES:-20971520}"
APP_ID="eu.nodepass.somewhere"

# 10.0.2.2 is the host as seen from a QEMU-based emulator, which covers the
# AOSP emulator and the common third-party ones. Override for a physical device.
HOST_FROM_DEVICE="${E2E_HOST_FROM_DEVICE:-10.0.2.2}"

fail() { echo "FAIL: $*" >&2; exit 1; }
step() { echo; echo "== $* =="; }

cleanup() {
    [ -n "${WORK_FETCH:-}" ] && rm -rf "$WORK_FETCH"
    [ -n "${KEEP:-}" ] && { echo "KEEP set; containers left running"; return; }
    docker rm -f "$ORIGIN_CONTAINER" "$PORTAL_CONTAINER" >/dev/null 2>&1
}
trap cleanup EXIT

command -v docker >/dev/null 2>&1 || fail "docker is required; see the header for why"
docker info >/dev/null 2>&1 || fail "the docker daemon is not running"

# --- The Portal image ------------------------------------------------------
step "Portal image"
if docker image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "OK  $IMAGE already built"
else
    [ -f "$NOWHERE_CLONE/Dockerfile" ] || fail "no Nowhere clone at $NOWHERE_CLONE (set NOWHERE_CLONE)"
    echo "building $IMAGE from $NOWHERE_CLONE (a few minutes, once)"
    docker build -t "$IMAGE" "$NOWHERE_CLONE" >/dev/null || fail "could not build the Portal image"
fi

docker network inspect "$NETWORK" >/dev/null 2>&1 || docker network create "$NETWORK" >/dev/null

# --- The origin server -----------------------------------------------------
# It publishes its own SHA-256 in a header, so a transfer that is truncated on
# a block boundary is still caught — a length check would not catch it.
step "origin server"
ORIGIN_SCRIPT="$(mktemp)"
cat > "$ORIGIN_SCRIPT" <<'PY'
import hashlib, os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SIZE = int(os.environ.get("BLOB_BYTES", "20971520"))
SMALL = int(os.environ.get("SMALL_BYTES", "65536"))

# Two payloads, because the two claims need different sizes. The large one is
# for "a transfer survives the tunnel intact", where the size is the point. The
# small one is for "sixteen flows cost four connections", where the size is
# only a cost — sixteen concurrent 20 MB fetches saturate an emulator and time
# out, proving nothing about connection counts.
BODIES = {
    "/blob.bin": os.urandom(SIZE),
    "/small.bin": os.urandom(SMALL),
}
DIGESTS = {path: hashlib.sha256(body).hexdigest() for path, body in BODIES.items()}
print("serving %d bytes, sha256=%s" % (SIZE, DIGESTS["/blob.bin"]), flush=True)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        body = BODIES.get(self.path)
        if body is None:
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Content-Sha256", DIGESTS[self.path])
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
    -e "BLOB_BYTES=$BLOB_BYTES" \
    python:3-alpine python /origin.py >/dev/null || fail "could not start the origin server"

for _ in $(seq 1 60); do
    docker logs "$ORIGIN_CONTAINER" 2>&1 | grep -q "^serving " && break
    sleep 0.5
done
docker logs "$ORIGIN_CONTAINER" 2>&1 | grep "^serving " || fail "the origin server did not come up"

# The origin's address on that network, for the regression half of the test:
# a flow to a literal must still open as a literal. The device has no route to
# it either, and does not need one — it goes into the tunnel like everything
# else and the Portal, which is on that network, dials it.
ORIGIN_IP="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$ORIGIN_CONTAINER")"
[ -n "$ORIGIN_IP" ] || fail "could not read the origin container's address"

# The name must resolve on the Portal's network and nowhere else.
docker run --rm --network "$NETWORK" alpine getent hosts "$ORIGIN_NAME" >/dev/null 2>&1 \
    || fail "$ORIGIN_NAME does not resolve on the $NETWORK network"
echo "OK  $ORIGIN_NAME resolves inside the network, and only there (at $ORIGIN_IP)"

# --- The Portal ------------------------------------------------------------
# log=debug because that is the level that names the dialled target. At
# log=event the exchange is counted and not described, and the one thing this
# script exists to observe would be invisible.
step "Portal"
docker rm -f "$PORTAL_CONTAINER" >/dev/null 2>&1
docker run -d --name "$PORTAL_CONTAINER" --network "$NETWORK" -p "$PORTAL_PORT:$PORTAL_PORT" \
    "$IMAGE" "portal://${KEY}@0.0.0.0:${PORTAL_PORT}?log=debug" >/dev/null \
    || fail "could not start the Portal"

for _ in $(seq 1 60); do
    nc -z 127.0.0.1 "$PORTAL_PORT" 2>/dev/null && break
    sleep 0.5
done
nc -z 127.0.0.1 "$PORTAL_PORT" 2>/dev/null || fail "the Portal is not listening on $PORTAL_PORT"
echo "OK  Portal on :$PORTAL_PORT, reachable from the device as $HOST_FROM_DEVICE:$PORTAL_PORT"

# --- The device ------------------------------------------------------------
step "device"
DEVICE="$("$ROOT/scripts/device-connect.sh")" || fail "no usable device"
export ANDROID_SERIAL="$DEVICE"
SDK_LEVEL="$(adb -s "$DEVICE" shell getprop ro.build.version.sdk | tr -d '\r')"
echo "OK  $DEVICE  API $SDK_LEVEL  $(adb -s "$DEVICE" shell getprop ro.product.cpu.abi | tr -d '\r')"
echo "    locale: $(adb -s "$DEVICE" shell am get-config | head -1 | tr -d '\r' | tr '-' '\n' | grep -E '^[a-z]{2}$|^b\+' | head -1)"

( cd "$PROJECT" && ./gradlew --no-daemon installDebug ) >/dev/null 2>&1 \
    || fail "could not install the app"

# VpnService shows a consent dialog. Instrumentation cannot dismiss it, so it
# is pre-granted; without this the test hangs on a dialog nobody will tap.
# Two spellings, because they are not the same command and images differ in
# which they ship: `appops` is a standalone binary that some builds omit, while
# `cmd appops` reaches the same service through the shell command dispatcher.
# The bare form failing is not a reason to stop — it is a reason to try the
# other one, and only a failure of both means no consent.
grant_vpn_consent() {
    adb -s "$DEVICE" shell cmd appops set "$APP_ID" ACTIVATE_VPN allow >/dev/null 2>&1 && return 0
    adb -s "$DEVICE" shell appops set "$APP_ID" ACTIVATE_VPN allow >/dev/null 2>&1 && return 0
    return 1
}
grant_vpn_consent || fail "could not pre-grant VPN consent with either 'cmd appops' or 'appops'"
adb -s "$DEVICE" shell cmd appops get "$APP_ID" ACTIVATE_VPN 2>/dev/null | grep -q allow \
    || fail "VPN consent did not stick"
echo "OK  VPN consent pre-granted"

# --- Run -------------------------------------------------------------------
step "instrumentation"
# The instrumentation task installs the app itself when the APK has changed,
# and a reinstall clears the grant made above. Granting once more here costs
# nothing and closes the window; the tests fail loudly rather than skipping if
# it is somehow still missing.
BEFORE="$(docker logs "$PORTAL_CONTAINER" 2>&1 | wc -l | tr -d ' ')"
grant_vpn_consent || true
# ALL_CLASSES=1 runs the whole instrumentation suite rather than the device
# cases alone — what the end of a phase needs, under each locale in turn.
if [ -n "${ALL_CLASSES:-}" ]; then
    CLASSES=""
else
    CLASSES="eu.nodepass.somewhere.vpn.FakeIpTunnelTest,eu.nodepass.somewhere.vpn.ThroughputOnDeviceTest,eu.nodepass.somewhere.vpn.ConcurrentFlowsTest"
fi

# MUX=0 and MUX=1 are the same case set over the two carriers. Both are run by
# default: rule 2 of the overnight run is that engaging Mux must not change what
# happens when it is off, and the only way to know is to run both every time.
MODES="${MUX_MODES:-0 1}"
STATUS=0

# The Portal's log has to be *streamed*: e2e-tunnel-fetch.sh reads the byte
# counters either side of a fetch, and a `docker logs` snapshot never changes,
# so a static file reports that the Portal relayed nothing.
WORK_FETCH="$(mktemp -d)"
PORTAL_LOG_FILE="$WORK_FETCH/portal.log"

for MODE in $MODES; do
    echo
    echo "--- mux=$MODE ---"
    # Reinstall before granting, not after. The instrumentation task uninstalls
    # both APKs when it finishes, so the second mode used to start with no app
    # on the device at all — the grant then failed against a package that was
    # not there, and the run reported a missing consent rather than a missing
    # install. `leaveApksInstalledAfterRun` below stops the uninstall; this
    # covers the first pass and anything else that removes the app.
    ( cd "$PROJECT" && ./gradlew --no-daemon installDebug ) >/dev/null 2>&1 \
        || fail "could not install the app for mux=$MODE"
    grant_vpn_consent || fail "could not pre-grant VPN consent for mux=$MODE"
    BEFORE="$(docker logs "$PORTAL_CONTAINER" 2>&1 | wc -l | tr -d ' ')"

    # Runtime arguments rather than -PnowhereE2e*: these reach `am instrument`
    # without entering the APK, so switching mux mode between runs does not
    # rebuild and reinstall — and a reinstall clears the consent grant, which is
    # exactly what happened the first time this loop ran.
    # Driven from the shell rather than from instrumentation, and this is the
    # correction that mattered most in this suite's history. Instrumentation
    # runs in the app's process; this client is forced out of its own tunnel in
    # every mode, so an in-app fetch never enters the TUN and proves only that
    # the destination was reachable some other way. Every case here passed that
    # way once, with the Portal's byte counters unmoved.
    #
    # `adb shell` is the shell user, which is not this app and therefore is
    # inside the tunnel. Both families are fetched: the v4 one is the ordinary
    # path, and the v6 one exercises the half of the fake-IP layer that only
    # exists because a AAAA query was answered.
    docker logs -f "$PORTAL_CONTAINER" > "$PORTAL_LOG_FILE" 2>&1 &
    LOG_TAIL=$!
    sleep 1
    for FAMILY in 4 6; do
        if NOWHERE_E2E_PORTAL="${HOST_FROM_DEVICE}:${PORTAL_PORT}" \
           NOWHERE_E2E_KEY="$KEY" \
           NOWHERE_E2E_CARRIER=tcp \
           NOWHERE_E2E_FAMILY="$FAMILY" \
           NOWHERE_PORTAL_LOG="$PORTAL_LOG_FILE" \
           "$ROOT/scripts/e2e-tunnel-fetch.sh" "${ORIGIN_NAME}:${ORIGIN_PORT}" /blob.bin \
           > "$WORK_FETCH/fetch-mux$MODE-v$FAMILY.log" 2>&1; then
            echo "  OK  mux=$MODE, IPv$FAMILY: $(grep -o 'the Portal relayed [0-9]* bytes' "$WORK_FETCH/fetch-mux$MODE-v$FAMILY.log" | head -1)"
        else
            echo "  FAIL  mux=$MODE, IPv$FAMILY: $(tail -3 "$WORK_FETCH/fetch-mux$MODE-v$FAMILY.log" | tr '\n' ' ')"
            STATUS=1
        fi
    done
    kill "$LOG_TAIL" 2>/dev/null; wait "$LOG_TAIL" 2>/dev/null || true

    # How many TLS connections the Portal really accepted, counted from the
    # source ports in its own exchange lines. Neither side can fake this: the
    # addresses are the ones the Portal's own accept() returned.
    #
    #   UP[TCP] 192.168.65.1:57188 -> 172.24.0.3:22077 -> ...
    #
    # With Mux, flows share a port; without, each flow has its own.
    LINES="$(docker logs "$PORTAL_CONTAINER" 2>&1 | tail -n "+$BEFORE" | grep -c "exchange starting" || true)"
    PORTS="$(docker logs "$PORTAL_CONTAINER" 2>&1 | tail -n "+$BEFORE" \
        | grep -o "UP\[TCP\] [0-9.]*:[0-9]*" | sort -u | wc -l | tr -d ' ')"
    echo "mux=$MODE: the Portal served $LINES flow(s) over $PORTS TLS connection(s)"
    eval "FLOWS_$MODE=$LINES"
    eval "PORTS_$MODE=$PORTS"
done

# --- What the Portal saw ---------------------------------------------------
# The device-side half of the claim is asserted in the test. This is the other
# half, and it is the one that cannot be faked from the device: a client that
# resolved the name locally would appear here as an address.
step "what the Portal was asked to dial"
DIALLED="$(docker logs "$PORTAL_CONTAINER" 2>&1 | grep -o -- "-> ${ORIGIN_NAME}:${ORIGIN_PORT}" | head -1)"
docker logs "$PORTAL_CONTAINER" 2>&1 | grep "exchange starting" | tail -2

[ "$STATUS" -eq 0 ] || fail "the instrumentation test failed (exit $STATUS)"
[ -n "$DIALLED" ] || fail "the Portal was never asked to dial ${ORIGIN_NAME} — the name did not survive the client"

# --- The shard arithmetic, measured on its own -----------------------------
# The per-mode figures above cover whatever case set was run, and each case
# stops the tunnel when it finishes — which closes that session's carriers, so
# the totals mix several tunnels and the ratio drifts with the case list.
#
# This is the claim stated exactly: sixteen flows *in one tunnel, at the same
# time*, over both carriers. At a shard density of four the answer is four.
if [ "$MODES" = "0 1" ]; then
    step "sixteen concurrent flows, one tunnel, each carrier"
    # Driven from the shell for the same reason as everything else here: an
    # in-app fetch never enters the TUN, so an instrumentation case counting
    # connections would be counting connections the Portal never saw. Sixteen
    # `nc` processes are started at once and waited for together, which is what
    # makes the flows concurrent rather than merely numerous -- a shard density
    # measured over sequential flows is one, whatever the density really is.
    for MODE in 0 1; do
        grant_vpn_consent || fail "could not pre-grant VPN consent"
        adb -s "$DEVICE" shell am force-stop "$APP_ID" >/dev/null 2>&1
        sleep 2
        adb -s "$DEVICE" shell am instrument -w \
            -e class eu.nodepass.somewhere.vpn.TunnelHolderTest \
            -e nowhereHoldSeconds 120 \
            -e nowhereE2ePortal "${HOST_FROM_DEVICE}:${PORTAL_PORT}" \
            -e nowhereE2eKey "$KEY" \
            -e nowhereE2eCarrier tcp \
            -e nowhereE2eMux "$MODE" \
            eu.nodepass.somewhere.test/androidx.test.runner.AndroidJUnitRunner \
            > "$WORK_FETCH/holder-$MODE.log" 2>&1 &
        HOLDER_PID=$!
        UP=0
        for _ in $(seq 1 60); do
            adb -s "$DEVICE" shell 'ip addr show tun0' >/dev/null 2>&1 && { UP=1; break; }
            sleep 1
        done
        [ "$UP" = 1 ] || fail "the tunnel would not come up for the concurrency measurement at mux=$MODE"
        # The interface exists before the session has authenticated; one warm-up
        # fetch is what makes the sixteen below concurrent rather than fifteen
        # waiting behind a handshake.
        adb -s "$DEVICE" shell "printf 'GET /small.bin HTTP/1.0\r\nHost: ${ORIGIN_NAME}:${ORIGIN_PORT}\r\n\r\n' | nc -w 20 ${ORIGIN_NAME} ${ORIGIN_PORT} > /dev/null" >/dev/null 2>&1

        MARK="$(docker logs "$PORTAL_CONTAINER" 2>&1 | wc -l | tr -d ' ')"
        adb -s "$DEVICE" shell "for i in \$(seq 1 16); do (printf 'GET /small.bin HTTP/1.0\r\nHost: ${ORIGIN_NAME}:${ORIGIN_PORT}\r\n\r\n' | nc -w 30 ${ORIGIN_NAME} ${ORIGIN_PORT} > /data/local/tmp/cf.\$i) & done; wait" >/dev/null 2>&1
        SHORT="$(adb -s "$DEVICE" shell 'for i in $(seq 1 16); do wc -c < /data/local/tmp/cf.$i; done' 2>/dev/null | tr -d '\r' | awk '$1 < 1000' | wc -l | tr -d ' ')"
        adb -s "$DEVICE" shell 'rm -f /data/local/tmp/cf.*' >/dev/null 2>&1
        kill "$HOLDER_PID" 2>/dev/null; wait "$HOLDER_PID" 2>/dev/null || true
        adb -s "$DEVICE" shell am force-stop "$APP_ID" >/dev/null 2>&1

        N="$(docker logs "$PORTAL_CONTAINER" 2>&1 | tail -n "+$MARK" | grep -c "exchange starting" || true)"
        C="$(docker logs "$PORTAL_CONTAINER" 2>&1 | tail -n "+$MARK" \
            | grep -o "UP\[TCP\] [0-9.]*:[0-9]*" | sort -u | wc -l | tr -d ' ')"
        printf '  mux=%s: %s concurrent flows over %s TLS connection(s), %s short response(s)\n' "$MODE" "$N" "$C" "$SHORT"
        [ "${SHORT:-16}" -eq 0 ] || fail "mux=$MODE: $SHORT of 16 concurrent fetches came back short"
        eval "ONE_TUNNEL_FLOWS_$MODE=$N"
        eval "ONE_TUNNEL_PORTS_$MODE=$C"
    done

    # Zero flows satisfies every comparison below, and that is exactly how this
    # arithmetic came to pass while proving nothing: the case it drives had been
    # disabled, the run skipped it, and 0 == 0 and 0 <= 0 both held.
    for MODE in 0 1; do
        eval "OBSERVED=\${ONE_TUNNEL_FLOWS_$MODE:-0}"
        [ "$OBSERVED" -gt 0 ] \
            || fail "mux=$MODE opened no flows at all, so the shard arithmetic below would pass without measuring anything"
    done

    [ "${ONE_TUNNEL_PORTS_0:-0}" -eq "${ONE_TUNNEL_FLOWS_0:-0}" ] \
        || fail "mux=0 used ${ONE_TUNNEL_PORTS_0} connections for ${ONE_TUNNEL_FLOWS_0} flows; L1 is one per flow"

    # ceil(N / 4), with a little room: a flow that finishes before the last one
    # starts frees a slot, so the true figure can be lower and never higher.
    EXPECTED=$(( (${ONE_TUNNEL_FLOWS_1:-0} + 3) / 4 ))
    [ "${ONE_TUNNEL_PORTS_1:-99}" -le "$EXPECTED" ] \
        || fail "mux=1 used ${ONE_TUNNEL_PORTS_1} connections for ${ONE_TUNNEL_FLOWS_1} flows; a shard density of four allows $EXPECTED"
    echo "  ceil(${ONE_TUNNEL_FLOWS_1}/4) = $EXPECTED, and mux=1 used ${ONE_TUNNEL_PORTS_1}"
fi

# --- Did multiplexing actually multiplex? ----------------------------------
# The arithmetic L2 exists for. Asserted rather than printed, because "fewer
# connections" is the entire claim and a run that quietly stopped multiplexing
# would otherwise pass every other check in this script.
if [ "$MODES" = "0 1" ]; then
    step "connections per flow"
    printf '  mux=0: %s flows over %s connections\n' "${FLOWS_0:-?}" "${PORTS_0:-?}"
    printf '  mux=1: %s flows over %s connections\n' "${FLOWS_1:-?}" "${PORTS_1:-?}"

    [ "${PORTS_1:-0}" -gt 0 ] || fail "no Mux connections were observed at all"
    [ "${PORTS_1:-0}" -lt "${PORTS_0:-0}" ] \
        || fail "mux=1 used ${PORTS_1} connections and mux=0 used ${PORTS_0} — multiplexing did nothing"
    [ "${PORTS_1:-0}" -lt "${FLOWS_1:-0}" ] \
        || fail "mux=1 opened ${PORTS_1} connections for ${FLOWS_1} flows — that is one per flow"
fi

echo
echo "PASS: the device asked for a name, the Portal was asked to dial that name,"
echo "      and mux=1 carried the same case set over fewer connections than flows"
