package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CandidateWalkForwardEvaluatorTest {
    private fun observations(): List<SourceObservation> {
        val results = listOf(
            "277-65-249", "578-07-368", "800-81-137",
            "566-73-148", "288-82-679", "279-81-236",
            "277-65-249", "578-07-368", "800-81-137"
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

    @Test fun evaluatesOnlyTargetsWithEnoughStrictlyEarlierConsensusHistory() {
        val report = CandidateWalkForwardEvaluator.evaluate(
            observations(), Market.SRIDEVI, "main", PannaSide.OPEN,
            minimumHistory = 5, topN = 10
        )
        assertEquals(9, report.totalTargetDates)
        assertEquals(4, report.evaluatedDates)
        assertEquals(5, report.insufficientHistoryDates)
        assertEquals(report.evaluatedDates * 10, report.candidates.sumOf { it.selectedCount })
    }

    @Test fun futureRowsCannotChangeEarlierEvaluationWindow() {
        val base = observations()
        val endExclusive = LocalDate.of(2026, 11, 8)
        val first = CandidateWalkForwardEvaluator.evaluate(
            base, Market.SRIDEVI, "main", PannaSide.CLOSE,
            minimumHistory = 5, endDateExclusive = endExclusive
        )
        val future = base + listOf("999-87-888", "999-87-888").mapIndexed { index, result ->
            SourceObservation(
                market = Market.SRIDEVI, sessionId = "main",
                date = LocalDate.of(2026, 11, 10), sourceId = "future-$index",
                retrievedAt = Instant.parse("2026-11-17T10:00:00Z"),
                rawText = result, normalizedResult = result,
                status = ObservationStatus.PUBLISHED
            )
        }
        val second = CandidateWalkForwardEvaluator.evaluate(
            future, Market.SRIDEVI, "main", PannaSide.CLOSE,
            minimumHistory = 5, endDateExclusive = endExclusive
        )
        assertEquals(first, second)
    }

    @Test fun conflictsAreExcludedAndExploratoryRowsRemainExplicitlyUnverified() {
        val singleSource = observations().filter { it.sourceId == "publisher-a" }
        val strict = CandidateWalkForwardEvaluator.evaluate(
            singleSource, Market.SRIDEVI, "main", PannaSide.OPEN,
            minimumHistory = 5
        )
        val exploratory = CandidateWalkForwardEvaluator.evaluate(
            singleSource, Market.SRIDEVI, "main", PannaSide.OPEN,
            minimumHistory = 5, allowSingleSourceExploration = true
        )
        assertEquals(0, strict.totalTargetDates)
        assertEquals(0, strict.evaluatedDates)
        assertTrue(exploratory.exploratory)
        assertEquals(9, exploratory.totalTargetDates)
        assertEquals(4, exploratory.evaluatedDates)
    }

    @Test fun conflictingPublishedDateIsExcludedFromTargetsAndTraining() {
        val rows = observations() + SourceObservation(
            market = Market.SRIDEVI,
            sessionId = "main",
            date = LocalDate.of(2026, 11, 2),
            sourceId = "publisher-c",
            retrievedAt = Instant.parse("2026-11-17T10:00:00Z"),
            rawText = "111-33-222",
            normalizedResult = "111-33-222",
            status = ObservationStatus.PUBLISHED
        )
        val report = CandidateWalkForwardEvaluator.evaluate(
            rows, Market.SRIDEVI, "main", PannaSide.OPEN,
            minimumHistory = 5, topN = 10
        )
        assertEquals(8, report.totalTargetDates)
        assertEquals(3, report.evaluatedDates)
    }

    @Test fun dateWindowIsAppliedToTargetDatesWithoutChangingEarlierTrainingRule() {
        val report = CandidateWalkForwardEvaluator.evaluate(
            observations(), Market.SRIDEVI, "main", PannaSide.OPEN,
            minimumHistory = 5,
            startDateInclusive = LocalDate.of(2026, 11, 7),
            endDateExclusive = LocalDate.of(2026, 11, 10)
        )
        assertEquals(3, report.totalTargetDates)
        assertEquals(3, report.evaluatedDates)
    }

    @Test fun reportsCandidateExposureAndExactHitsSeparately() {
        val report = CandidateWalkForwardEvaluator.evaluate(
            observations(), Market.SRIDEVI, "main", PannaSide.OPEN,
            minimumHistory = 5, topN = 10
        )
        assertTrue(report.candidates.isNotEmpty())
        assertTrue(report.candidates.all { it.selectedCount > 0 })
        assertTrue(report.candidates.all { it.hitRate in 0.0..1.0 })
        assertTrue(report.top1Hits in 0..report.evaluatedDates)
        assertTrue(report.warning.contains("Low sample"))
    }
}
