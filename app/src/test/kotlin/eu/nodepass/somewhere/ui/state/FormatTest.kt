// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Somewhere Authors

package eu.nodepass.somewhere.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

/**
 * The formatter, attacked at its edges.
 *
 * Every number on the home screen goes through here, and the design's promise
 * is that **a value updating in place does not move the layout**. A formatter
 * that returns three characters at one moment and five at the next breaks that
 * promise no matter how good the typography is, so the width of what comes out
 * is as much a requirement as the value.
 */
class FormatTest {
    private val locale = Locale.UK

    @Test
    fun zeroIsBytesAndNotAnEmptyString() {
        // A disconnected session is the app's most common state. It has to read
        // as a measured zero, not as a blank.
        val measured = Format.bytes(0, locale)
        assertEquals("0", measured.value)
        assertEquals("B", measured.unit)
    }

    @Test
    fun theUnitStepsExactlyAtTheBoundary() {
        assertEquals("B", Format.bytes(1023, locale).unit)
        assertEquals("KB", Format.bytes(1024, locale).unit)
        assertEquals("KB", Format.bytes(1024L * 1024 - 1, locale).unit)
        assertEquals("MB", Format.bytes(1024L * 1024, locale).unit)
        assertEquals("GB", Format.bytes(1024L * 1024 * 1024, locale).unit)
        assertEquals("TB", Format.bytes(1024L * 1024 * 1024 * 1024, locale).unit)
    }

    @Test
    fun theLargestCountThereIsStillFormats() {
        val measured = Format.bytes(Long.MAX_VALUE, locale)
        assertEquals("EB", measured.unit)
        assertTrue(measured.value.isNotEmpty())
    }

    @Test
    fun theRenderedValueIsNeverWiderThanFourCharacters() {
        // The property the design actually depends on. Three significant
        // figures means `1.84`, `12.6`, `126` — four characters, four, three —
        // so a value updating in place moves by at most one digit and never
        // reflows the row.
        //
        // This failed before the unit table reached EB: past the largest unit
        // the number grows instead of the unit stepping, and a session total in
        // the petabytes rendered as `1,024` and then wider still. Unreachable in
        // practice, and a bound that only holds for values you happened to try
        // is not a bound.
        val boundaries =
            buildList {
                var step = 1L
                repeat(7) {
                    add(step)
                    if (step <= Long.MAX_VALUE / 1023) add(step * 1023)
                    if (step <= Long.MAX_VALUE / 1024) {
                        add(step * 1024 - 1)
                        step *= 1024
                    }
                }
                add(Long.MAX_VALUE)
                add(0L)
            }
        val random = Random(20260828)
        (boundaries + List(5_000) { random.nextLong(0, Long.MAX_VALUE) }).forEach { count ->
            val rendered = Format.bytes(count, Locale.UK).value
            assertTrue(
                "$count rendered as '$rendered', which is ${rendered.length} characters",
                rendered.length <= 4,
            )
        }
    }

    @Test
    fun theLargestValuesStepTheUnitRatherThanWideningTheNumber() {
        assertEquals("PB", Format.bytes(1024L * 1024 * 1024 * 1024 * 1024, Locale.UK).unit)
        assertEquals("EB", Format.bytes(1024L * 1024 * 1024 * 1024 * 1024 * 1024, Locale.UK).unit)
    }

    @Test
    fun throughputCarriesTheRateInTheUnitAndNotInTheNumber() {
        val measured = Format.throughput(1_929_379, locale)
        assertEquals("MB/s", measured.unit)
        assertTrue("the number must not carry the unit", measured.value.none { it.isLetter() })
    }

    @Test
    fun elapsedIsMachineFormattedInEveryLocale() {
        // docs/i18n.md: a duration beside a connection state is a value to
        // compare, not prose. It is the same string in every locale, and it
        // does not change width for the first hundred hours.
        assertEquals("02:14:37", Format.elapsed(8077))
        assertEquals("00:00:00", Format.elapsed(0))
        assertEquals("00:00:59", Format.elapsed(59))
        assertEquals("01:00:00", Format.elapsed(3600))
        assertEquals("99:59:59", Format.elapsed(99 * 3600 + 59 * 60 + 59))
    }

    @Test
    fun elapsedKeepsGrowingRatherThanWrappingAtADay() {
        // A session that has been up for two days is a good session, not a
        // session that has been up for two hours.
        assertEquals("48:00:00", Format.elapsed(48 * 3600))
    }

    @Test
    fun aBrokenCounterCannotWidenTheLayout() {
        // A negative count is not a measurement — it means whoever produced it
        // subtracted wrongly. Rendered literally it is twenty characters and
        // reflows the row, so it reads as the zero it should have been.
        assertEquals("0", Format.bytes(-1, locale).value)
        assertEquals("0", Format.bytes(Long.MIN_VALUE, locale).value)
        assertEquals("B", Format.bytes(Long.MIN_VALUE, locale).unit)
    }

    @Test
    fun aMeasuredValueCarriesNoThousandsSeparator() {
        // 1023 B rendered as "1,023" — five characters, breaking the width
        // bound for the one unit that can hold four digits. Grouping never
        // helps at this scale because the unit steps first.
        assertEquals("1023", Format.bytes(1023, locale).value)
    }

    @Test
    fun aFormattedNumberFollowsTheLocaleButTheUnitDoesNot() {
        // Grouping and decimal separators are prose-adjacent and translate; the
        // symbols KB and MB are international and do not.
        val german = Format.bytes(1_500_000, Locale.GERMANY)
        val british = Format.bytes(1_500_000, Locale.UK)
        assertEquals(german.unit, british.unit)
        assertNotEquals("a locale with a comma separator must differ", german.value, british.value)
    }

    @Test
    fun noInputProducesAnEmptyOrBlankRendering() {
        val random = Random(20260825)
        repeat(2_000) {
            val count = random.nextLong(0, Long.MAX_VALUE)
            val measured = Format.bytes(count, locale)
            assertTrue("value was blank for $count", measured.value.isNotBlank())
            assertTrue("unit was blank for $count", measured.unit.isNotBlank())
        }
    }
}
