# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 The Somewhere Authors
#
# One shared library, two jobs: run a TCP/IP stack against the packets a
# VpnService TUN hands us, and carry the QUIC stack the Nowhere protocol layer
# drives over JNI.
#
# The donor's equivalent file also built BLAKE3 and libyaml. Neither is here:
# BLAKE3 serves a protocol this client does not speak, and libyaml parses Clash
# configuration this client does not accept. Vendored C is the most expensive
# code in the repository to review and the least visible when it goes wrong, so
# it is carried only where something needs it.
#
# ngtcp2 and aws-lc are the exception, and the case for them is not preference.
# NW-P-01 authenticates a QUIC connection with an RFC 5705 exporter, and no
# option that satisfies it avoids a TLS library. See tools/quic/DEPENDENCIES.

cmake_minimum_required(VERSION 3.22.1)

# CXX is enabled for aws-lc's ssl/, which is C++. Nothing in this repository's
# own sources is: lwIP is C and so is every bridge here.
project("somewhere_native" C CXX)

set(LWIP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/lwip)

# Named individually rather than globbed. A glob would silently start compiling
# whatever an upstream sync drops into the tree, and this list is the record of
# what was reviewed.
set(LWIP_SOURCES
    ${LWIP_DIR}/src/core/init.c
    ${LWIP_DIR}/src/core/def.c
    ${LWIP_DIR}/src/core/ip.c
    ${LWIP_DIR}/src/core/mem.c
    ${LWIP_DIR}/src/core/memp.c
    ${LWIP_DIR}/src/core/netif.c
    ${LWIP_DIR}/src/core/pbuf.c
    ${LWIP_DIR}/src/core/tcp.c
    ${LWIP_DIR}/src/core/tcp_in.c
    ${LWIP_DIR}/src/core/tcp_out.c
    ${LWIP_DIR}/src/core/udp.c
    ${LWIP_DIR}/src/core/inet_chksum.c
    ${LWIP_DIR}/src/core/timeouts.c
    ${LWIP_DIR}/src/core/ipv4/ip4.c
    ${LWIP_DIR}/src/core/ipv4/ip4_addr.c
    ${LWIP_DIR}/src/core/ipv4/ip4_frag.c
    ${LWIP_DIR}/src/core/ipv4/icmp.c
    ${LWIP_DIR}/src/core/ipv6/ip6.c
    ${LWIP_DIR}/src/core/ipv6/ip6_addr.c
    ${LWIP_DIR}/src/core/ipv6/ip6_frag.c
    ${LWIP_DIR}/src/core/ipv6/icmp6.c
    ${LWIP_DIR}/src/core/ipv6/nd6.c
    ${LWIP_DIR}/src/core/ipv6/inet6.c
    ${LWIP_DIR}/port/sys_arch.c
    ${LWIP_DIR}/lwip_bridge.c
)

# --- the QUIC stack ---------------------------------------------------------
#
# Fetched at pinned commits and built by tools/quic/build-deps.sh, not committed
# to this repository (D-17). The script verifies that each tag really is the
# commit DEPENDENCIES names before anything is compiled, and a warm cache costs
# milliseconds, so this runs on every configure rather than being something a
# developer has to remember.
#
# The versions live in exactly one file, which the conformance probe reads too.
# That is why there is no check here comparing this build with that one: they
# cannot disagree.

set(QUIC_TOOLS ${CMAKE_CURRENT_SOURCE_DIR}/../../../../tools/quic)

if(ANDROID_ABI)
    set(QUIC_TARGET_ABI ${ANDROID_ABI})
else()
    set(QUIC_TARGET_ABI host)
endif()

execute_process(
    COMMAND ${CMAKE_COMMAND} -E env
            QUIC_CMAKE=${CMAKE_COMMAND}
            QUIC_NINJA=${CMAKE_MAKE_PROGRAM}
            QUIC_NDK=${CMAKE_ANDROID_NDK}
            ${QUIC_TOOLS}/build-deps.sh ${QUIC_TARGET_ABI}
    OUTPUT_VARIABLE QUIC_DEPS_STDOUT
    RESULT_VARIABLE QUIC_DEPS_STATUS
    OUTPUT_STRIP_TRAILING_WHITESPACE
)
if(NOT QUIC_DEPS_STATUS EQUAL 0)
    message(FATAL_ERROR "tools/quic/build-deps.sh failed for ${QUIC_TARGET_ABI}")
endif()
# The script keeps its progress notes on stderr, so stdout is this one line.
string(REPLACE "PREFIX=" "" QUIC_PREFIX "${QUIC_DEPS_STDOUT}")
if(NOT EXISTS "${QUIC_PREFIX}/lib/libngtcp2.a")
    message(FATAL_ERROR "no QUIC stack at ${QUIC_PREFIX}")
endif()
message(STATUS "QUIC stack for ${QUIC_TARGET_ABI}: ${QUIC_PREFIX}")

# Named individually for the same reason the lwIP sources are: this list is the
# record of what links into the shipped library.
foreach(_lib ngtcp2_crypto_boringssl ngtcp2 ssl crypto)
    add_library(quic_${_lib} STATIC IMPORTED)
    set_target_properties(quic_${_lib} PROPERTIES
        IMPORTED_LOCATION ${QUIC_PREFIX}/lib/lib${_lib}.a)
endforeach()

add_library(somewhere_native SHARED
    ${LWIP_SOURCES}
    ${CMAKE_CURRENT_SOURCE_DIR}/lwip_jni_bridge.c
    ${CMAKE_CURRENT_SOURCE_DIR}/quic/quic_version_jni.c
)

# The target has no C++ source of its own, so CMake would pick the C linker and
# leave libc++ off a link line that needs it for aws-lc's ssl/.
set_target_properties(somewhere_native PROPERTIES LINKER_LANGUAGE CXX)

target_include_directories(somewhere_native PRIVATE
    ${LWIP_DIR}/src/include
    ${LWIP_DIR}/port
    ${LWIP_DIR}/port/arch
    ${CMAKE_CURRENT_SOURCE_DIR}/compat
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${QUIC_PREFIX}/include
)

# Order is not decoration with static archives: the crypto shim references
# ngtcp2, ngtcp2 references ssl, and ssl references crypto.
target_link_libraries(somewhere_native
    log
    quic_ngtcp2_crypto_boringssl
    quic_ngtcp2
    quic_ssl
    quic_crypto
)

# Only the JNI entry points leave this object. See exports.map for what a
# statically linked BoringSSL does to a symbol table otherwise, and why it
# matters in a process that already runs Conscrypt's.
target_link_options(somewhere_native PRIVATE
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map
)

target_compile_options(somewhere_native PRIVATE
    -Wall
    -Wno-unused-variable
    -Wno-unused-function
    -O2
)
