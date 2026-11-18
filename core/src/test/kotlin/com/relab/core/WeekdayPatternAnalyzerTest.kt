package com.relab.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WeekdayPatternAnalyzerTest {
    private fun row(date: LocalDate, open: String, close: String = "234"): ChartRecord {
        val openAnk = open.sumOf { it.digitToInt() } % 10
        val closeAnk = close.sumOf { it.digitToInt() } % 10
        return ChartRecord(Market.SRIDEVI, date, open, "$openAnk$closeAnk", close, "fixture")
    }

    @Test fun detectsStrongSyntheticWeekdayAssociation() {
        val start = LocalDate.of(2024, 1, 1)
        val rows = (0 until 140).map { offset ->
            val date = start.plusDays(offset.toLong())
            val open = if (date.dayOfWeek.value % 2 == 0) "123" else "999"
            row(date, open)
        }
        val findings = WeekdayPatternAnalyzer.analyze(rows, permutations = 499, minimumRecords = 30)
        val open = findings.single { it.feature == "open_family_sequence" }
        assertEquals(WeekdayPatternStatus.UNUSUAL_ASSOCIATION, open.status)
        assertNotNull(open.permutationPValue)
        assertNotNull(open.adjustedQValue)
        assertTrue(open.normalizedMutualInformation!! >= 0.15)
    }

    @Test fun insufficientHistoryIsExplicit() {
        val rows = (0 until 10).map { row(LocalDate.of(2025, 1, 1).plusDays(it.toLong()), "123") }
        val findings = WeekdayPatternAnalyzer.analyze(rows, permutations = 99, minimumRecords = 30)
        assertTrue(findings.all { it.status == WeekdayPatternStatus.INSUFFICIENT_HISTORY })
        assertTrue(findings.all { it.permutationPValue == null && it.adjustedQValue == null })
    }

    @Test fun marketAndSessionAreKeptSeparate() {
        val rows = (0 until 70).map { offset ->
            val date = LocalDate.of(2024, 1, 1).plusDays(offset.toLong())
            row(date, if (date.dayOfWeek.value % 2 == 0) "123" else "999")
        }
        val split = rows.map { it.copy(market = Market.KALYAN) } +
            rows.map { it.copy(sessionId = "night") }
        val findings = WeekdayPatternAnalyzer.analyze(rows + split, permutations = 99, minimumRecords = 30)
        assertEquals(3, findings.map { it.market to it.sessionId }.distinct().size)
        assertEquals(18, findings.size)
    }

    @Test fun duplicateDatesAreRejected() {
        val duplicate = row(LocalDate.of(2025, 1, 1), "123")
        try {
            WeekdayPatternAnalyzer.analyze(listOf(duplicate, duplicate), permutations = 99)
            throw AssertionError("Expected duplicate-date rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("reconciled"))
        }
    }
}
