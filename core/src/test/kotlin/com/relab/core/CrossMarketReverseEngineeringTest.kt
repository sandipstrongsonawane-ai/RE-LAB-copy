package com.relab.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossMarketReverseEngineeringTest {
    private val pannas = listOf("000", "111", "222", "333", "444")

    private fun records(
        market: Market,
        session: String,
        start: LocalDate,
        count: Int,
        valueAt: (Int) -> Int = { it }
    ): List<ChartRecord> = (0 until count).map { index ->
        val value = pannas[valueAt(index).mod(pannas.size)]
        val close = pannas[(valueAt(index) + 2).mod(pannas.size)]
        val openAnk = value.sumOf { it.digitToInt() } % 10
        val closeAnk = close.sumOf { it.digitToInt() } % 10
        ChartRecord(
            market = market,
            date = start.plusDays(index.toLong()),
            openPanna = value,
            jodi = "$openAnk$closeAnk",
            closePanna = close,
            sourceId = "test-$session",
            sessionId = session
        )
    }

    @Test
    fun detectsStrongSameDateCrossMarketAssociationWithPermutationEvidence() {
        val start = LocalDate.of(2025, 1, 1)
        val first = records(Market.valueOf("RE_TEST_ALPHA"), "main", start, 50)
        val second = records(Market.valueOf("RE_TEST_BETA"), "main", start, 50)

        val report = CrossMarketReverseEngineering.analyze(
            first + second, minimumOverlap = 20, maxLagDays = 0, permutations = 199
        )

        assertEquals(2, report.seriesAnalyzed)
        assertEquals(4, report.pairComparisons)
        val openFamily = report.associations.single { it.metric == CrossMarketMetric.OPEN_FAMILY }
        assertEquals(50, openFamily.pairedRecords)
        assertEquals(1.0, openFamily.normalizedMutualInformation, 1e-9)
        assertTrue(openFamily.adjustedPValue <= 0.05)
        assertTrue(openFamily.statisticallyUnusual)
        assertTrue(report.note.contains("not proof"))
    }

    @Test
    fun comparesSeparateSessionsWithoutMergingTheirHistories() {
        val start = LocalDate.of(2025, 1, 1)
        val market = Market.valueOf("RE_TEST_SESSION")
        val day = records(market, "day", start, 30)
        val night = records(market, "night", start, 30) { (it + 1) % 5 }

        val report = CrossMarketReverseEngineering.analyze(
            day + night, minimumOverlap = 20, maxLagDays = 0, permutations = 99
        )

        assertEquals(2, report.seriesAnalyzed)
        assertTrue(report.associations.all { it.firstSessionId == "day" && it.secondSessionId == "night" })
    }

    @Test
    fun insufficientOverlapDoesNotProduceAssociations() {
        val start = LocalDate.of(2025, 1, 1)
        val first = records(Market.valueOf("RE_TEST_SHORT_A"), "main", start, 8)
        val second = records(Market.valueOf("RE_TEST_SHORT_B"), "main", start.plusDays(20), 8)

        val report = CrossMarketReverseEngineering.analyze(
            first + second, minimumOverlap = 12, maxLagDays = 2, permutations = 99
        )

        assertTrue(report.associations.isEmpty())
        assertEquals(0, report.pairComparisons)
    }

    @Test
    fun rejectsUnreconciledDuplicateDates() {
        val start = LocalDate.of(2025, 1, 1)
        val first = records(Market.valueOf("RE_TEST_DUPLICATE"), "main", start, 2)
        val duplicate = first.first().copy(sourceId = "another-publisher")
        val error = runCatching {
            CrossMarketReverseEngineering.analyze(first + duplicate, permutations = 99)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertFalse(error?.message.isNullOrBlank())
    }
}
