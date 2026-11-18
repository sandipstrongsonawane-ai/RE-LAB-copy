package com.relab.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MarketRegimeChangeDetectorTest {
    private fun row(day: Int, open: String, close: String, market: Market = Market.SRIDEVI, session: String = "main"): ChartRecord {
        val openAnk = open.sumOf { it.digitToInt() } % 10
        val closeAnk = close.sumOf { it.digitToInt() } % 10
        return ChartRecord(
            market, LocalDate.of(2025, 1, 1).plusDays(day.toLong()),
            open, "$openAnk$closeAnk", close, "fixture", sessionId = session
        )
    }

    @Test fun detectsStrongRecentShiftWithPermutationEvidence() {
        val old = (0 until 20).map { row(it, "123", "234") }
        val recent = (20 until 40).map { row(it, "999", "888") }
        val findings = MarketRegimeChangeDetector.analyze(old + recent, windowSize = 20, permutations = 499)
        val open = findings.single { it.feature == "open" }
        assertEquals(RegimeChangeStatus.SHIFT_DETECTED, open.status)
        assertEquals(1.0, open.totalVariation!!, 0.0)
        assertNotNull(open.permutationPValue)
        assertNotNull(open.adjustedQValue)
        assertTrue(open.adjustedQValue!! <= 0.05)
    }

    @Test fun stableWindowsDoNotRaiseShiftFlag() {
        val rows = (0 until 40).map { i ->
            if (i % 2 == 0) row(i, "123", "234") else row(i, "678", "789")
        }
        val findings = MarketRegimeChangeDetector.analyze(rows, windowSize = 20, permutations = 199)
        assertTrue(findings.all { it.status == RegimeChangeStatus.STABLE })
    }

    @Test fun insufficientHistoryIsExplicitAndHasNoPValue() {
        val findings = MarketRegimeChangeDetector.analyze(
            (0 until 12).map { row(it, "123", "234") }, windowSize = 10, permutations = 99
        )
        assertEquals(2, findings.size)
        assertTrue(findings.all { it.status == RegimeChangeStatus.INSUFFICIENT_HISTORY })
        assertTrue(findings.all { it.permutationPValue == null && it.adjustedQValue == null })
    }

    @Test fun marketAndSessionHistoriesRemainIndependent() {
        val base = (0 until 40).map { i -> row(i, if (i < 20) "123" else "999", "234") }
        val otherMarket = base.map { it.copy(market = Market.KALYAN) }
        val otherSession = base.map { it.copy(sessionId = "night") }
        val findings = MarketRegimeChangeDetector.analyze(base + otherMarket + otherSession, windowSize = 20, permutations = 99)
        assertEquals(6, findings.size)
        assertEquals(3, findings.map { it.market to it.sessionId }.distinct().size)
    }

    @Test fun duplicateDatesAreRejectedBeforeTesting() {
        val duplicate = row(0, "123", "234")
        try {
            MarketRegimeChangeDetector.analyze(listOf(duplicate, duplicate), windowSize = 2, permutations = 99)
            throw AssertionError("Expected duplicate-date rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("reconciled"))
        }
    }
}
