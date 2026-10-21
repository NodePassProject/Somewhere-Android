// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.net

import android.net.http.X509TrustManagerExtensions
import android.os.Build
import eu.nodepass.somewhere.protocol.tls.ServerTrust
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The device's own trust decision: its store, the user's anchors, the admin's
 * policy, and the blocklists the platform ships.
 *
 * ADR-0002's option A, for both carriers. Nothing is copied into this process's
 * TLS libraries; the platform is asked, so every OS update to the store and
 * every certificate a user installs is honoured the day it lands.
 *
 * On Android the check goes through [X509TrustManagerExtensions] with the
 * host, which is what lets a network security configuration that names the
 * host take part. `GENERIC` is the authentication type Conscrypt itself passes
 * for TLS 1.3, where the cipher suite no longer implies one. On a JVM — unit
 * tests — there is no such class, and the plain trust manager is used with the
 * JDK's spelling of the same idea.
 */
object PlatformServerTrust : ServerTrust {
    private val manager: X509TrustManager by lazy {
        TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
    }

    override fun check(
        chain: Array<X509Certificate>,
        host: String,
    ) {
        if (Build.VERSION.SDK_INT > 0) {
            X509TrustManagerExtensions(manager).checkServerTrusted(chain, "GENERIC", host)
        } else {
            manager.checkServerTrusted(chain, "UNKNOWN")
        }
    }
}
