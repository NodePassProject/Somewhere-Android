#!/usr/bin/env bash
# Builds the QUIC bridge for this machine, so JVM tests can drive it.
#
# ## Why a second library exists
#
# The app's native library is `libsomewhere_native`, and it contains lwIP as
# well as the QUIC bridge. lwIP is built NO_SYS against an Android port and has
# no business running on a build host; the QUIC bridge, on the other hand,
# depends on nothing Android at all -- it is JNI, ngtcp2 and aws-lc.
#
# Splitting the two lets the differential compare this implementation with the
# oracle over QUIC without an emulator in the loop, which matters because the
# differential is where a fact that has grown two shapes gets caught. It also
# means the QUIC layer stops being instrumentation-only.
#
# The sources are the same files the app ships. Nothing is duplicated: if this
# builds and the app's does not, or the reverse, the two have diverged and the
# gates say so.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="$(cd "$ROOT/.." && pwd)"
OUT="${HOST_QUIC_OUT:-$PROJECT/.quic-deps/host-lib}"

fail() { echo "build-host-quic: $*" >&2; exit 1; }

PREFIX="$("$PROJECT/tools/quic/build-deps.sh" host 2>/dev/null | sed -n 's/^PREFIX=//p')"
[ -n "$PREFIX" ] && [ -f "$PREFIX/lib/libngtcp2.a" ] || fail "the host QUIC stack did not build"

JAVA_HOME_DIR="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null)}"
[ -n "$JAVA_HOME_DIR" ] && [ -d "$JAVA_HOME_DIR/include" ] || fail "no JDK headers; set JAVA_HOME"

case "$(uname -s)" in
    Darwin) SUFFIX=dylib; PLATFORM_INCLUDE="$JAVA_HOME_DIR/include/darwin"; SHARED="-dynamiclib" ;;
    Linux)  SUFFIX=so;    PLATFORM_INCLUDE="$JAVA_HOME_DIR/include/linux";  SHARED="-shared -fPIC" ;;
    *) fail "unsupported host $(uname -s)" ;;
esac

mkdir -p "$OUT"
LIB="$OUT/libsomewhere_quic_host.$SUFFIX"

# shellcheck disable=SC2086
cc -O2 $SHARED -o "$LIB" \
    "$ROOT/../app/src/main/jni/quic/quic_conn_jni.c" \
    "$ROOT/../app/src/main/jni/quic/quic_version_jni.c" \
    -I"$JAVA_HOME_DIR/include" -I"$PLATFORM_INCLUDE" -I"$PREFIX/include" \
    "$PREFIX/lib/libngtcp2_crypto_boringssl.a" \
    "$PREFIX/lib/libngtcp2.a" \
    "$PREFIX/lib/libssl.a" "$PREFIX/lib/libcrypto.a" \
    -lpthread -lc++ 2>&1 | grep -E "error|warning: .*\[-W(unused|implicit)" && fail "the host bridge did not compile"

[ -f "$LIB" ] || fail "no library at $LIB"
echo "LIBRARY=$LIB"
