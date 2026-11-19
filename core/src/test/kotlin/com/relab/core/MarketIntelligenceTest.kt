package com.relab.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MarketIntelligenceTest {
    private fun record(date: LocalDate, open: String, close: String): ChartRecord {
        val openAnk = open.sumOf { it.digitToInt() } % 10
        val closeAnk = close.sumOf { it.digitToInt() } % 10
        return ChartRecord(
            market = Market.SRIDEVI,
            date = date,
            openPanna = open,
            jodi = "$openAnk$closeAnk",
            closePanna = close,
            sourceId = "test"
        )
    }

    @Test
    fun separatesMarketsAndReportsTheirOwnTransitionHistory() {
        val start = LocalDate.of(2025, 1, 1)
        val kalyan = (0 until 12).map { i ->
            record(start.plusDays(i.toLong()), if (i % 2 == 0) "123" else "678", if (i % 2 == 0) "234" else "789")
        }
        val other = kalyan.map { it.copy(market = Market.SRIDEVI_NIGHT) }
        val reports = MarketIntelligence.analyze(kalyan + other)
        assertEquals(2, reports.size)
        assertEquals(Market.SRIDEVI, reports[0].market)
        assertEquals(Market.SRIDEVI_NIGHT, reports[1].market)
        assertEquals(12, reports[0].validRecords)
        assertTrue(reports[0].topOpenTransitions.isNotEmpty())
    }

    @Test
    fun insufficientHistoryDoesNotInventARegimeScore() {
        val start = LocalDate.of(2025, 1, 1)
        val rows = (0 until 5).map { i ->
            record(start.plusDays(i.toLong()), "123", "456")
        }
        val report = MarketIntelligence.analyze(rows).single()
        assertEquals(MarketRegime.INSUFFICIENT_HISTORY, report.regime)
        assertEquals(null, report.distributionShift)
    }

    @Test
    fun duplicateDatesMustBeReconciledBeforeMarketAnalysis() {
        val date = LocalDate.of(2025, 1, 1)
        val duplicates = listOf(record(date, "123", "234"), record(date, "678", "789"))
        var rejected = false
        try {
            MarketIntelligence.analyze(duplicates)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun changedRecentDistributionRaisesShiftFlag() {
        val start = LocalDate.of(2025, 1, 1)
        val old = (0 until 15).map { i -> record(start.plusDays(i.toLong()), "123", "234") }
        val recent = (15 until 30).map { i -> record(start.plusDays(i.toLong()), "999", "888") }
        val report = MarketIntelligence.analyze(old + recent).single()
        assertNotNull(report.distributionShift)
        assertTrue(report.distributionShift!! >= 0.25)
        assertTrue(report.regime == MarketRegime.WATCH || report.regime == MarketRegime.SHIFT_DETECTED)
    }
}
