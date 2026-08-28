// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors
//
// What the running binary says about the QUIC stack linked into it.
//
// This is not diagnostics for its own sake. A static archive contributes
// nothing to a shared library until some symbol in it is referenced, so a build
// that "links" ngtcp2 and aws-lc while calling neither produces a byte-identical
// .so and proves nothing at all. These two calls are the first real references,
// which is what makes the size figures, the ABI symbol check and the alignment
// check statements about the stack rather than about an unused link line.
//
// They are also the only honest form of the version check. Comparing two files
// that both say "1.68.0" compares two files; asking the linked code what it is
// asks the thing that will actually run.

#include <jni.h>
#include <ngtcp2/ngtcp2.h>
#include <openssl/service_indicator.h>

// The QUIC transport. `least_version` of 0 means "whatever you are".
JNIEXPORT jstring JNICALL
Java_eu_nodepass_somewhere_quic_QuicStack_nativeNgtcp2Version(
    JNIEnv *env, jclass clazz) {
    (void)clazz;
    const ngtcp2_info *info = ngtcp2_version(0);
    if (info == NULL || info->version_str == NULL) {
        return NULL;
    }
    return (*env)->NewStringUTF(env, info->version_str);
}

// The TLS backend underneath it. ngtcp2 is built against aws-lc's BoringSSL
// interface, so this is the library that supplies the RFC 5705 exporter
// NW-P-01 authenticates with.
JNIEXPORT jstring JNICALL
Java_eu_nodepass_somewhere_quic_QuicStack_nativeCryptoVersion(
    JNIEnv *env, jclass clazz) {
    (void)clazz;
    const char *version = awslc_version_string();
    if (version == NULL) {
        return NULL;
    }
    return (*env)->NewStringUTF(env, version);
}
