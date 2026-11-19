package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CandidateRankingEngineTest {
    private fun observations(): List<SourceObservation> {
        val results = listOf(
            "277-65-249", "578-07-368", "800-81-137",
            "566-73-148", "288-82-679", "279-81-236"
        )
        return results.flatMapIndexed { index, result ->
            val date = LocalDate.of(2026, 11, 1).plusDays(index.toLong())
            listOf("publisher-a", "publisher-b").map { source ->
                SourceObservation(
                    market = Market.SRIDEVI,
                    sessionId = "main",
                    date = date,
                    sourceId = source,
                    retrievedAt = Instant.parse("2026-11-17T10:00:00Z"),
                    rawText = result,
                    normalizedResult = result,
                    status = ObservationStatus.PUBLISHED
                )
            }
        }
    }

    @Test fun ranksOnlyConsensusRowsStrictlyBeforeTargetDate() {
        val rows = observations()
        val ranked = CandidateRankingEngine.rank(
            rows, Market.SRIDEVI, "main",
            LocalDate.of(2026, 11, 8), PannaSide.OPEN, limit = 20, minimumHistory = 5
        )
        assertFalse(ranked.abstained)
        assertEquals(6, ranked.trainingRecords)
        assertEquals(20, ranked.candidates.size)
        assertTrue(ranked.candidates.all { it.panna.length == 3 && it.score in 0.0..100.0 })
    }

    @Test fun abstainsWhenVerifiedHistoryIsTooSmall() {
        val ranked = CandidateRankingEngine.rank(
            observations().take(4), Market.SRIDEVI, "main",
            LocalDate.of(2026, 11, 8), PannaSide.CLOSE, minimumHistory = 5
        )
        assertTrue(ranked.abstained)
        assertTrue(ranked.candidates.isEmpty())
        assertEquals(2, ranked.trainingRecords)
    }

    @Test fun excludesSingleSourceAndConflictingDates() {
        val base = observations()
        val singleSource = base.filterNot { it.date == LocalDate.of(2026, 11, 1) && it.sourceId == "publisher-b" }
        val conflict = singleSource + SourceObservation(
            market = Market.SRIDEVI, sessionId = "main",
            date = LocalDate.of(2026, 11, 2), sourceId = "publisher-c",
            retrievedAt = Instant.parse("2026-11-17T10:00:00Z"),
            rawText = "111-33-222", normalizedResult = "111-33-222",
            status = ObservationStatus.PUBLISHED
        )
        val ranked = CandidateRankingEngine.rank(
            conflict, Market.SRIDEVI, "main",
            LocalDate.of(2026, 11, 8), PannaSide.OPEN, minimumHistory = 1
        )
        assertEquals(4, ranked.trainingRecords)
    }

    @Test fun derivesJodiFromPannaAnkSums() {
        val open = listOf(PannaCandidate("277", "222", PannaType.DP, 80.0, 2, 3, 1, 1))
        val close = listOf(PannaCandidate("249", "244", PannaType.SP, 70.0, 1, 2, 1, 0))
        val jodi = CandidateRankingEngine.deriveJodiCandidates(open, close).single()
        assertEquals("65", jodi.jodi)
        assertEquals(1, jodi.supportingPannaPairs)
    }

    @Test fun exploratoryRankingCanUseSinglePublisherWithoutCallingItConsensus() {
        val singlePublisher = observations().filter { it.sourceId == "publisher-a" }
        val strict = CandidateRankingEngine.rank(
            singlePublisher, Market.SRIDEVI, "main",
            LocalDate.of(2026, 11, 8), PannaSide.OPEN, minimumHistory = 5
        )
        assertTrue(strict.abstained)
        val exploratory = CandidateRankingEngine.rank(
            singlePublisher, Market.SRIDEVI, "main",
            LocalDate.of(2026, 11, 8), PannaSide.OPEN,
            minimumHistory = 5, allowSingleSourceExploration = true
        )
        assertFalse(exploratory.abstained)
        assertEquals(6, exploratory.trainingRecords)
        assertEquals(10, exploratory.candidates.size)
        assertTrue(ReconciledChartBuilder.build(singlePublisher).records.isEmpty())
        assertEquals(6, ReconciledChartBuilder.buildExploratoryRecords(singlePublisher).size)
    }

    @Test fun futureRecordsDoNotChangeEarlierRanking() {
        val base = observations()
        val target = LocalDate.of(2026, 11, 7)
        val first = CandidateRankingEngine.rank(base, Market.SRIDEVI, "main", target, PannaSide.OPEN)
        val future = base + listOf("999-87-888", "999-87-888").mapIndexed { index, result ->
            SourceObservation(
                market = Market.SRIDEVI, sessionId = "main",
                date = LocalDate.of(2026, 11, 10), sourceId = "future-$index",
                retrievedAt = Instant.parse("2026-11-17T10:00:00Z"),
                rawText = result, normalizedResult = result,
                status = ObservationStatus.PUBLISHED
            )
        }
        val second = CandidateRankingEngine.rank(future, Market.SRIDEVI, "main", target, PannaSide.OPEN)
        assertEquals(first.candidates, second.candidates)
    }
}
