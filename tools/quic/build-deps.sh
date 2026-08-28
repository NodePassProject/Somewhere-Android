#!/usr/bin/env bash
# Fetch, verify and build the QUIC stack this app links against.
#
# Usage:
#   build-deps.sh <arm64-v8a|x86_64|host>   build for one ABI, print PREFIX=<dir>
#   build-deps.sh --prefix-only <abi>       print PREFIX=<dir> and build nothing
#   build-deps.sh --bundle-source <out.tar.gz>   corresponding source, D-17
#
# The versions come from DEPENDENCIES beside this file and from nowhere else,
# so the stack the conformance probe proved and the stack that ships are the
# same stack by construction.
#
# Idempotent: a warm cache exits in milliseconds. The stamp records the pin, so
# changing a commit in DEPENDENCIES rebuilds and changing nothing does not.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
PIN="$HERE/DEPENDENCIES"

fail() { echo "build-deps: $*" >&2; exit 1; }
note() { echo "build-deps: $*" >&2; }

[ -f "$PIN" ] || fail "no DEPENDENCIES at $PIN"
# shellcheck disable=SC1090
NGTCP2_REPO=$(grep '^NGTCP2_REPO=' "$PIN" | cut -d= -f2-)
NGTCP2_TAG=$(grep '^NGTCP2_TAG=' "$PIN" | cut -d= -f2-)
NGTCP2_COMMIT=$(grep '^NGTCP2_COMMIT=' "$PIN" | cut -d= -f2-)
AWSLC_REPO=$(grep '^AWSLC_REPO=' "$PIN" | cut -d= -f2-)
AWSLC_TAG=$(grep '^AWSLC_TAG=' "$PIN" | cut -d= -f2-)
AWSLC_COMMIT=$(grep '^AWSLC_COMMIT=' "$PIN" | cut -d= -f2-)
[ -n "$NGTCP2_COMMIT" ] && [ -n "$AWSLC_COMMIT" ] || fail "DEPENDENCIES is missing a pinned commit"

DEPS="${QUIC_DEPS_DIR:-$REPO/.quic-deps}"
SRC="$DEPS/src"
KEY="${NGTCP2_COMMIT:0:12}-${AWSLC_COMMIT:0:12}"

# --- fetching, and the check that makes a tag mean something ---------------

fetch() {
    local name=$1 url=$2 tag=$3 commit=$4 dir="$SRC/$1"
    if [ -d "$dir/.git" ]; then
        local have; have=$(git -C "$dir" rev-parse HEAD 2>/dev/null)
        [ "$have" = "$commit" ] && return 0
        note "$name is at ${have:0:12}, want ${commit:0:12} -- refetching"
        rm -rf "$dir"
    fi
    mkdir -p "$SRC"
    note "fetching $name $tag"
    git clone --depth 1 --branch "$tag" "$url" "$dir" >/dev/null 2>&1 ||
        fail "could not fetch $name $tag from $url"
    local got; got=$(git -C "$dir" rev-parse HEAD)
    # A tag is a name and a name can be moved. This is the line that turns the
    # pin from a label into an identity.
    [ "$got" = "$commit" ] ||
        fail "$name $tag is $got, but DEPENDENCIES pins $commit. Either upstream moved the tag or the pin is wrong; neither is something a build should paper over."
}

bundle_source() {
    local out=$1
    [ -n "$out" ] || fail "--bundle-source needs an output path"
    fetch ngtcp2 "$NGTCP2_REPO" "$NGTCP2_TAG" "$NGTCP2_COMMIT"
    fetch aws-lc "$AWSLC_REPO" "$AWSLC_TAG" "$AWSLC_COMMIT"
    tar -czf "$out" -C "$SRC" \
        --exclude=.git \
        ngtcp2 aws-lc || fail "could not write $out"
    note "corresponding source: $out ($(du -h "$out" | cut -f1))"
    echo "BUNDLE=$out"
}

# --- building ---------------------------------------------------------------

build_abi() {
    local abi=$1
    local out="$DEPS/out/$abi"
    local stamp="$out/.stamp"

    if [ -f "$stamp" ] && [ "$(cat "$stamp")" = "$KEY" ]; then
        echo "PREFIX=$out"
        return 0
    fi

    local cmake="${QUIC_CMAKE:-}"
    if [ -z "$cmake" ]; then
        local sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
        cmake="$(ls "$sdk"/cmake/*/bin/cmake 2>/dev/null | tail -1)"
        [ -n "$cmake" ] || cmake="$(command -v cmake || true)"
    fi
    [ -x "$cmake" ] || fail "no cmake (set QUIC_CMAKE)"
    local ninja="${QUIC_NINJA:-$(dirname "$cmake")/ninja}"
    [ -x "$ninja" ] || ninja="$(command -v ninja || true)"
    [ -x "$ninja" ] || fail "no ninja (set QUIC_NINJA)"

    # aws-lc generates assembly with perl and its build tooling with Go. Both
    # are build-host requirements rather than runtime ones, and CI needs them.
    command -v go   >/dev/null 2>&1 || fail "aws-lc needs Go on PATH"
    command -v perl >/dev/null 2>&1 || fail "aws-lc needs perl on PATH"

    # Expanded below as "${toolchain[@]+...}" rather than plain "${toolchain[@]}":
    # macOS ships bash 3.2, where expanding an empty array under `set -u` is an
    # unbound-variable error. Only the host build leaves it empty, and only the
    # conformance probe builds for the host, so the app's own build could never
    # have found this.
    local toolchain=()
    if [ "$abi" != "host" ]; then
        local ndk="${QUIC_NDK:-}"
        if [ -z "$ndk" ]; then
            local sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
            ndk="$(ls -d "$sdk"/ndk/* 2>/dev/null | tail -1)"
        fi
        [ -d "$ndk" ] || fail "no NDK (set QUIC_NDK)"
        toolchain=(
            -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake"
            -DANDROID_ABI="$abi"
            # Matches minSdk. See docs/adr-0001-tls-exporter.md for why 26.
            -DANDROID_PLATFORM=android-26
            # aws-lc's ssl/ is C++. The app's own CMake passes ANDROID_STL for
            # the same reason; lwIP is pure C and is unaffected either way,
            # which was measured rather than assumed.
            -DANDROID_STL=c++_static
        )
    fi

    fetch ngtcp2 "$NGTCP2_REPO" "$NGTCP2_TAG" "$NGTCP2_COMMIT"
    fetch aws-lc "$AWSLC_REPO" "$AWSLC_TAG" "$AWSLC_COMMIT"

    local bawslc="$DEPS/build/aws-lc-$abi-$KEY"
    local bngtcp2="$DEPS/build/ngtcp2-$abi-$KEY"

    note "building aws-lc for $abi (this is the slow one)"
    "$cmake" -S "$SRC/aws-lc" -B "$bawslc" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$ninja" \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_TESTING=OFF -DBUILD_TOOL=OFF -DBUILD_SHARED_LIBS=OFF \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
        ${toolchain[@]+"${toolchain[@]}"} >"$DEPS/aws-lc-$abi.configure.log" 2>&1 ||
        { tail -20 "$DEPS/aws-lc-$abi.configure.log" >&2; fail "aws-lc did not configure for $abi"; }
    "$cmake" --build "$bawslc" --parallel >"$DEPS/aws-lc-$abi.build.log" 2>&1 ||
        { tail -20 "$DEPS/aws-lc-$abi.build.log" >&2; fail "aws-lc did not build for $abi"; }

    note "building ngtcp2 for $abi"
    "$cmake" -S "$SRC/ngtcp2" -B "$bngtcp2" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$ninja" \
        -DCMAKE_BUILD_TYPE=Release \
        -DENABLE_LIB_ONLY=ON -DENABLE_SHARED_LIB=OFF -DENABLE_STATIC_LIB=ON \
        -DBUILD_TESTING=OFF \
        -DENABLE_OPENSSL=OFF -DENABLE_BORINGSSL=ON \
        -DBORINGSSL_INCLUDE_DIR="$SRC/aws-lc/include" \
        -DBORINGSSL_LIBRARIES="$bawslc/ssl/libssl.a;$bawslc/crypto/libcrypto.a" \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
        ${toolchain[@]+"${toolchain[@]}"} >"$DEPS/ngtcp2-$abi.configure.log" 2>&1 ||
        { tail -20 "$DEPS/ngtcp2-$abi.configure.log" >&2; fail "ngtcp2 did not configure for $abi"; }
    "$cmake" --build "$bngtcp2" --parallel >"$DEPS/ngtcp2-$abi.build.log" 2>&1 ||
        { tail -20 "$DEPS/ngtcp2-$abi.build.log" >&2; fail "ngtcp2 did not build for $abi"; }

    # One prefix, so that what links is visible in one directory listing rather
    # than assembled from four build trees at every call site.
    rm -rf "$out"
    mkdir -p "$out/lib" "$out/include"
    for a in "$bngtcp2/crypto/boringssl/libngtcp2_crypto_boringssl.a" \
             "$bngtcp2/lib/libngtcp2.a" \
             "$bawslc/ssl/libssl.a" "$bawslc/crypto/libcrypto.a"; do
        [ -f "$a" ] || fail "expected $a and it is not there"
        cp "$a" "$out/lib/"
    done
    cp -R "$SRC/ngtcp2/lib/includes/"* "$out/include/"
    cp -R "$bngtcp2/lib/includes/"* "$out/include/"
    cp -R "$SRC/ngtcp2/crypto/includes/"* "$out/include/"
    cp -R "$SRC/aws-lc/include/"* "$out/include/"

    echo "$KEY" > "$stamp"
    echo "PREFIX=$out"
}

case "${1:-}" in
    --bundle-source) bundle_source "${2:-}" ;;
    --prefix-only)
        [ -n "${2:-}" ] || fail "--prefix-only needs an ABI"
        echo "PREFIX=$DEPS/out/$2" ;;
    arm64-v8a|x86_64|host) build_abi "$1" ;;
    *) fail "usage: build-deps.sh <arm64-v8a|x86_64|host> | --prefix-only <abi> | --bundle-source <out.tar.gz>" ;;
esac
