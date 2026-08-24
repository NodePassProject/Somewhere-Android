// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.protocol

/**
 * The outcome of decoding or validating something that arrived from the wire or
 * from user configuration.
 *
 * Protocol decoding uses a result type rather than exceptions for two reasons
 * specific to this layer. Rejection is ordinary control flow here — the
 * specification defines dozens of ways a frame can be invalid, and each one has
 * to reach the user distinctly (NW-P-06). And under fuzzing (NW-Q-03) an
 * exception-based decoder makes "correctly rejected malformed input" and "crashed
 * on malformed input" look identical, which would make the fuzz test meaningless.
 */
sealed interface DecodeResult<out T> {
    data class Ok<out T>(
        val value: T,
    ) : DecodeResult<T>

    data class Invalid(
        val reason: DecodeReason,
    ) : DecodeResult<Nothing>

    fun valueOrNull(): T? = (this as? Ok)?.value

    fun reasonOrNull(): DecodeReason? = (this as? Invalid)?.reason

    val isOk: Boolean get() = this is Ok
}

/**
 * Why something was rejected.
 *
 * Each protocol family declares its own sealed set of reasons, so a `when` over
 * one family's reasons is exhaustive and forgetting a case fails to compile.
 * [detail] is for logs and diagnostics; it must never carry key material,
 * a token, or a real address.
 */
interface DecodeReason {
    val detail: String
}

internal fun <T> T.ok(): DecodeResult<T> = DecodeResult.Ok(this)

internal fun invalid(reason: DecodeReason): DecodeResult<Nothing> = DecodeResult.Invalid(reason)
