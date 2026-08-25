# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 The Somewhere Authors
#
# One shared library, one job: run a TCP/IP stack against the packets a
# VpnService TUN hands us.
#
# The donor's equivalent file also built BLAKE3 and libyaml. Neither is here:
# BLAKE3 serves a protocol this client does not speak, and libyaml parses Clash
# configuration this client does not accept. Vendored C is the most expensive
# code in the repository to review and the least visible when it goes wrong, so
# it is carried only where something needs it.

cmake_minimum_required(VERSION 3.22.1)
project("somewhere_native" C)

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

add_library(somewhere_native SHARED
    ${LWIP_SOURCES}
    ${CMAKE_CURRENT_SOURCE_DIR}/lwip_jni_bridge.c
)

target_include_directories(somewhere_native PRIVATE
    ${LWIP_DIR}/src/include
    ${LWIP_DIR}/port
    ${LWIP_DIR}/port/arch
    ${CMAKE_CURRENT_SOURCE_DIR}/compat
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(somewhere_native log)

target_compile_options(somewhere_native PRIVATE
    -Wall
    -Wno-unused-variable
    -Wno-unused-function
    -O2
)
