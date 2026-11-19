package com.relab.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfLearningHypothesisEngineTest {
    private fun rows(market: Market = Market.SRIDEVI): List<ChartRecord> =
        (0 until 32).map { index ->
            val open = if (index % 2 == 0) "123" else "678"
            val close = if (index % 2 == 0) "456" else "901"
            val openAnk = open.sumOf { it.digitToInt() } % 10
            val closeAnk = close.sumOf { it.digitToInt() } % 10
            ChartRecord(
                market = market,
                date = LocalDate.of(2026, 1, 5).plusDays(index.toLong() * 7),
                openPanna = open,
                jodi = "$openAnk$closeAnk",
                closePanna = close,
                sourceId = "consensus",
                sessionId = "main"
            )
        }

    @Test
    fun minesRulesFromOneMarketAndKeepsMarketSessionsIndependent() {
        val sridevi = rows()
        val kalyan = rows(Market.KALYAN)
        val report = SelfLearningHypothesisEngine.discover(
            sridevi + kalyan, Market.SRIDEVI, "main", HypothesisSide.OPEN,
            minimumHistory = 8, minimumSupport = 3
        )
        assertEquals(24, report.targetDates)
        assertEquals(24, report.evaluatedDates)
        assertFalse(report.hypotheses.isEmpty())
        assertTrue(report.hypotheses.all { it.market == Market.SRIDEVI })
        assertTrue(report.hypotheses.all { it.sessionId == "main" })
        assertTrue(report.hypotheses.all { it.hitRate in 0.0..1.0 })
    }

    @Test
    fun dateWindowLimitsTargetsAndUsesOnlyEarlierRows() {
        val data = rows()
        val end = data[20].date
        val report = SelfLearningHypothesisEngine.discover(
            data, Market.SRIDEVI, "main", HypothesisSide.CLOSE,
            minimumHistory = 8, minimumSupport = 3,
            startDateInclusive = data[12].date,
            endDateExclusive = end
        )
        assertEquals(8, report.targetDates)
        assertEquals(8, report.evaluatedDates)
    }

    @Test
    fun duplicateDatesAreRejectedInsteadOfSilentlyMixed() {
        val data = rows()
        var rejected = false
        try {
            SelfLearningHypothesisEngine.discover(
                data + data.first(), Market.SRIDEVI, "main", HypothesisSide.OPEN
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
