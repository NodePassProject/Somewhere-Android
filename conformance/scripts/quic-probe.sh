#!/usr/bin/env bash
# D-15: what provides QUIC, decided by making the hard part work.
#
# One requirement decides the shape of L3. NW-P-01 authenticates a QUIC
# connection with transport byte 0x02 and an exporter under the label
# `EXPORTER-Nowhere-Auth`, so whatever provides QUIC must expose RFC 5705
# keying material from its TLS 1.3 handshake. That single line eliminates the
# obvious Android answer — Cronet gives neither raw streams nor an exporter —
# and it is why the Apple client wrapped ngtcp2 in a TLS handler of its own
# rather than using the platform's QUIC, which it does not reference anywhere.
#
# This script answers three questions, in the order that makes a failure cheap:
#
#   1. Does ngtcp2 with a stock crypto backend build for the ABIs this app
#      ships? (`arm64-v8a` and `x86_64`.)
#   2. Does the exporter come out of that stack?
#   3. Does a real Portal accept an AuthFrame built from it?
#
# Question 3 has a control, and the control is the point: a wrong shared key
# must **not** reach READY. Without it, a READY proves the Portal answers, not
# that the exporter is the thing it answered to.
#
# The sources are fetched, not vendored. Vendoring is C1's job and a decision
# of its own; this is a spike that must stay reproducible without committing a
# TLS library to a repository that has not agreed to carry one.
#
# Usage: conformance/scripts/quic-probe.sh [--host-only]
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="$(cd "$ROOT/.." && pwd)"
WORK="${QUIC_PROBE_WORK:-${TMPDIR:-/tmp}/somewhere-quic-probe}"

# Pinned, both of them. An unpinned dependency turns "does it build" into a
# question with a different answer every week.
NGTCP2_TAG="${NGTCP2_TAG:-v1.17.0}"
AWSLC_TAG="${AWSLC_TAG:-v1.68.0}"

NOWHERE_CLONE="${NOWHERE_CLONE:-$PROJECT/../Nowhere}"
BIN="${NOWHERE_BIN:-$NOWHERE_CLONE/target/release/nowhere}"

KEY="${QUIC_PROBE_KEY:-c0-probe-key}"
PORTAL_PORT="${QUIC_PROBE_PORTAL_PORT:-22091}"
ORIGIN_PORT="${QUIC_PROBE_ORIGIN_PORT:-28090}"

# The NDK and the SDK's CMake. Both are what the Gradle build already uses, so
# a probe that builds here is a probe that builds where the app does.
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
CMAKE="${QUIC_PROBE_CMAKE:-$SDK/cmake/3.22.1/bin/cmake}"
NDK_DIR="${QUIC_PROBE_NDK:-$(ls -d "$SDK"/ndk/* 2>/dev/null | tail -1)}"

PIDS=()
cleanup() {
    for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done
    wait 2>/dev/null || true
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
step() { printf '\n== %s ==\n' "$*"; }

command -v go >/dev/null 2>&1 || fail "aws-lc needs Go on PATH"
command -v perl >/dev/null 2>&1 || fail "aws-lc needs perl on PATH"
[ -x "$CMAKE" ] || fail "no cmake at $CMAKE (set QUIC_PROBE_CMAKE)"

mkdir -p "$WORK"

# --- 1. Sources ------------------------------------------------------------
step "sources"
[ -d "$WORK/ngtcp2" ] || git clone --depth 1 --branch "$NGTCP2_TAG" \
    https://github.com/ngtcp2/ngtcp2 "$WORK/ngtcp2" >/dev/null 2>&1 ||
    fail "could not fetch ngtcp2 $NGTCP2_TAG"
[ -d "$WORK/aws-lc" ] || git clone --depth 1 --branch "$AWSLC_TAG" \
    https://github.com/aws/aws-lc "$WORK/aws-lc" >/dev/null 2>&1 ||
    fail "could not fetch aws-lc $AWSLC_TAG"
echo "OK  ngtcp2 $NGTCP2_TAG (MIT), aws-lc $AWSLC_TAG (Apache-2.0 OR ISC)"

build_pair() {
    local tag=$1; shift
    "$CMAKE" -S "$WORK/aws-lc" -B "$WORK/awslc-$tag" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$(dirname "$CMAKE")/ninja" \
        -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF -DBUILD_TOOL=OFF \
        -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
        "$@" >/dev/null 2>&1 || return 1
    "$CMAKE" --build "$WORK/awslc-$tag" --parallel >/dev/null 2>&1 || return 1

    "$CMAKE" -S "$WORK/ngtcp2" -B "$WORK/ngtcp2-$tag" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$(dirname "$CMAKE")/ninja" \
        -DCMAKE_BUILD_TYPE=Release -DENABLE_LIB_ONLY=ON -DENABLE_SHARED_LIB=OFF \
        -DENABLE_STATIC_LIB=ON -DBUILD_TESTING=OFF \
        -DENABLE_OPENSSL=OFF -DENABLE_BORINGSSL=ON \
        -DBORINGSSL_INCLUDE_DIR="$WORK/aws-lc/include" \
        -DBORINGSSL_LIBRARIES="$WORK/awslc-$tag/ssl/libssl.a;$WORK/awslc-$tag/crypto/libcrypto.a" \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON "$@" >/dev/null 2>&1 || return 1
    "$CMAKE" --build "$WORK/ngtcp2-$tag" --parallel >/dev/null 2>&1 || return 1
}

# --- 2. Does it build for the ABIs this app ships? -------------------------
if [ "${1:-}" != "--host-only" ]; then
    [ -d "$NDK_DIR" ] || fail "no NDK under $SDK/ndk (set QUIC_PROBE_NDK)"
    for abi in arm64-v8a x86_64; do
        step "cross-compiling for $abi"
        build_pair "$abi" \
            -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
            -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-26 ||
            fail "ngtcp2 + aws-lc did not build for $abi"
        format="$("$NDK_DIR"/toolchains/llvm/prebuilt/*/bin/llvm-objdump -f \
            "$WORK/ngtcp2-$abi/lib/libngtcp2.a" 2>/dev/null | grep -m1 "file format")"
        echo "OK  $abi: ${format#*file format }"
    done
fi

# --- 3. The exporter, and what a Portal makes of it ------------------------
step "building the probe for this host"
build_pair host || fail "ngtcp2 + aws-lc did not build for the host"

cc -o "$WORK/probe" "$ROOT/quic-probe/probe.c" \
    -I"$WORK/ngtcp2/lib/includes" -I"$WORK/ngtcp2-host/lib/includes" \
    -I"$WORK/ngtcp2/crypto/includes" -I"$WORK/aws-lc/include" \
    "$WORK/ngtcp2-host/crypto/boringssl/libngtcp2_crypto_boringssl.a" \
    "$WORK/ngtcp2-host/lib/libngtcp2.a" \
    "$WORK/awslc-host/ssl/libssl.a" "$WORK/awslc-host/crypto/libcrypto.a" \
    -lpthread -lc++ 2>&1 | grep -E "error" && fail "the probe did not compile"
echo "OK  probe built"

[ -x "$BIN" ] || fail "no nowhere binary at $BIN — run cargo build --release in the Nowhere clone"

step "one Portal, one target"
python3 -m http.server "$ORIGIN_PORT" --bind 127.0.0.1 > "$WORK/origin.log" 2>&1 &
PIDS+=($!)
# net=mix is the default listener: TCP and QUIC on one port, which is why this
# needs no server-side configuration at all.
"$BIN" "portal://${KEY}@127.0.0.1:${PORTAL_PORT}?net=mix&log=debug" > "$WORK/portal.log" 2>&1 &
PIDS+=($!)
sleep 2
grep -q "STATE=READY" "$WORK/portal.log" || { tail -5 "$WORK/portal.log"; fail "the Portal did not come up"; }
echo "OK  Portal on :$PORTAL_PORT, target on :$ORIGIN_PORT"

step "the right key"
RIGHT="$("$WORK/probe" 127.0.0.1 "$PORTAL_PORT" "$KEY" 127.0.0.1 "$ORIGIN_PORT" 2>/dev/null)"
echo "$RIGHT"
echo "$RIGHT" | grep -q "^handshake=complete" || fail "the QUIC handshake did not complete"
echo "$RIGHT" | grep -qE "^exporter=[0-9a-f]{64}$" || fail "no 32-byte exporter came out of the stack"
echo "$RIGHT" | grep -q "setup_result=0x00 READY" || fail "the Portal did not accept the AuthFrame"

step "the wrong key, which is what makes the right one mean something"
WRONG="$("$WORK/probe" 127.0.0.1 "$PORTAL_PORT" not-the-shared-key 127.0.0.1 "$ORIGIN_PORT" 2>/dev/null)"
echo "$WRONG"
echo "$WRONG" | grep -q "^handshake=complete" ||
    fail "the handshake should still complete: authentication is above TLS, not part of it"
echo "$WRONG" | grep -q "setup_result" &&
    fail "a wrong key reached a setup result; the READY above proves nothing"
echo "OK  silence, which is what upstream answers a bad tag with"

grep -q "invalid authentication frame" "$WORK/portal.log" ||
    fail "the Portal never reported a bad frame, so the wrong-key run never arrived"

echo
echo "PASS: ngtcp2 + aws-lc builds for both ABIs, exports keying material, and"
echo "      a real Portal answers READY to the AuthFrame built from it."
