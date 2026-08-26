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
BLOB = os.urandom(SIZE)
DIGEST = hashlib.sha256(BLOB).hexdigest()
print("serving %d bytes, sha256=%s" % (SIZE, DIGEST), flush=True)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(BLOB)))
        self.send_header("X-Content-Sha256", DIGEST)
        self.end_headers()
        self.wfile.write(BLOB)

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
BEFORE="$(docker logs "$PORTAL_CONTAINER" 2>&1 | wc -l | tr -d ' ')"
( cd "$PROJECT" && ./gradlew --no-daemon connectedDebugAndroidTest \
    -PnowhereE2ePortal="${HOST_FROM_DEVICE}:${PORTAL_PORT}" \
    -PnowhereE2eKey="$KEY" \
    -PnowhereE2eOrigin="${ORIGIN_NAME}:${ORIGIN_PORT}" \
    -PnowhereE2eTarget="${ORIGIN_IP}:${ORIGIN_PORT}" \
    -Pandroid.testInstrumentationRunnerArguments.class=eu.nodepass.somewhere.vpn.FakeIpTunnelTest \
    ) 2>&1 | tail -30
STATUS=${PIPESTATUS[0]}

# --- What the Portal saw ---------------------------------------------------
# The device-side half of the claim is asserted in the test. This is the other
# half, and it is the one that cannot be faked from the device: a client that
# resolved the name locally would appear here as an address.
step "what the Portal was asked to dial"
DIALLED="$(docker logs "$PORTAL_CONTAINER" 2>&1 | tail -n "+$BEFORE" \
    | grep -o -- "-> ${ORIGIN_NAME}:${ORIGIN_PORT}" | head -1)"
docker logs "$PORTAL_CONTAINER" 2>&1 | tail -n "+$BEFORE" | grep "exchange starting" | head -3

[ "$STATUS" -eq 0 ] || fail "the instrumentation test failed (exit $STATUS)"
[ -n "$DIALLED" ] || fail "the Portal was never asked to dial ${ORIGIN_NAME} — the name did not survive the client"

echo
echo "PASS: the device asked for a name, and the Portal was asked to dial that name"
