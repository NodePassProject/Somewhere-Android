// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.state

import java.text.NumberFormat
import java.util.Locale

/**
 * Formatting for the values the app measures.
 *
 * Numbers go through [NumberFormat] so the decimal separator follows the locale;
 * the unit symbols do not, because `MB` and `ms` are international symbols
 * rather than words. A value and its unit are returned separately because the
 * design sets them at different sizes — assembling `"1.84 MB/s"` and then
 * splitting it back apart would break in the first locale that formats
 * differently.
 */
object Format {
    private const val STEP = 1024.0

    /**
     * Up to exabytes, which is past anything a phone will move.
     *
     * The table used to stop at `TB`, and that was a layout bug rather than a
     * cosmetic limit: once the largest unit is reached the number keeps growing
     * instead of the unit stepping, so the rendering widens without bound and
     * the tabular-figures promise — a value updating in place does not move the
     * layout — stops holding. Ending at `EB` covers the whole `Long` range, so
     * the rendered value is at most four characters for every input there is.
     */
    private val UNITS = listOf("B", "KB", "MB", "GB", "TB", "PB", "EB")

    data class Measured(
        val value: String,
        val unit: String,
    )

    private fun number(
        value: Double,
        locale: Locale,
        fractionDigits: Int,
    ): String =
        NumberFormat
            .getNumberInstance(locale)
            .apply {
                minimumFractionDigits = fractionDigits
                maximumFractionDigits = fractionDigits
                // No thousands separator. A measured value here is at most four
                // digits — the unit steps before it can be more — so grouping
                // never aids reading, and it costs a character of width that
                // the fixed-width promise does not have to give. Counts that
                // *are* large, such as a rule set's entry count, are grouped
                // where they are rendered; this is only for measured values.
                isGroupingUsed = false
            }.format(value)

    /** A byte count, scaled to the largest unit that leaves a readable number. */
    fun bytes(
        count: Long,
        locale: Locale = Locale.getDefault(),
    ): Measured {
        // A negative byte count is not a small measurement, it is a broken
        // counter — and rendered literally it is twenty characters wide, which
        // reflows the row it sits in. Clamped here so a producer's arithmetic
        // bug cannot become a layout bug; preventing the negative is the
        // producer's job, and this is the floor under it.
        var scaled = count.coerceAtLeast(0).toDouble()
        var unit = 0
        while (scaled >= STEP && unit < UNITS.lastIndex) {
            scaled /= STEP
            unit++
        }
        // Three significant figures, which is what the design draws: `1.84`,
        // `12.6`, `126`. Bytes are whole numbers and never get a fraction.
        val digits =
            when {
                unit == 0 -> 0
                scaled >= 100 -> 0
                scaled >= 10 -> 1
                else -> 2
            }
        return Measured(number(scaled, locale, digits), UNITS[unit])
    }

    /** A byte count as one string, for use inside a sentence. */
    fun bytesText(
        count: Long,
        locale: Locale = Locale.getDefault(),
    ): String = bytes(count, locale).let { "${it.value} ${it.unit}" }

    /** Throughput. The unit carries `/s`; the number is separate for the design. */
    fun throughput(
        bytesPerSecond: Long,
        locale: Locale = Locale.getDefault(),
    ): Measured = bytes(bytesPerSecond, locale).let { Measured(it.value, "${it.unit}/s") }

    /**
     * An elapsed time, machine-formatted on purpose.
     *
     * `docs/i18n.md`: a duration next to a connection state is a value to
     * compare, not prose. It stays `HH:MM:SS` in every locale.
     */
    fun elapsed(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainder = seconds % 60
        return "%02d:%02d:%02d".format(Locale.ROOT, hours, minutes, remainder)
    }

    /** A latency or handshake figure, always milliseconds at this scale. */
    fun millis(value: Int): Measured = Measured(value.toString(), "ms")
}
