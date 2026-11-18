package com.relab.core

import org.junit.Assert.*
import org.junit.Test

class SangamCandidateEngineTest {
    private fun candidate(panna: String, score: Double) = PannaCandidate(
        panna = panna,
        familySequence = panna.map { ChartMath.family(it.digitToInt()).toString() }.joinToString(""),
        pannaType = ChartMath.pannaType(panna),
        score = score,
        exactHistoricalCount = 1,
        familyHistoricalCount = 2,
        weekdayFamilyCount = 1,
        previousFamilyTransitionCount = 1
    )

    @Test fun derivesBothHalfSangamFormsAndFullSangam() {
        val result = SangamCandidateEngine.derive(
            listOf(candidate("277", 80.0)),
            listOf(candidate("249", 60.0))
        )
        assertEquals(2, result.halfSangam.size)
        assertTrue(result.halfSangam.any {
            it.kind == HalfSangamKind.OPEN_PANNA_CLOSE_ANK && it.label == "277-5"
        })
        assertTrue(result.halfSangam.any {
            it.kind == HalfSangamKind.OPEN_ANK_CLOSE_PANNA && it.label == "6-249"
        })
        assertEquals("277-249", result.fullSangam.single().label)
        assertEquals(48.0, result.fullSangam.single().score, 0.0001)
    }

    @Test fun aggregatesRepeatedHalfCombinationsWithoutLosingSupportCount() {
        val result = SangamCandidateEngine.derive(
            listOf(candidate("277", 80.0), candidate("118", 60.0)),
            listOf(candidate("249", 60.0), candidate("168", 40.0))
        )
        val openPannaCloseAnk = result.halfSangam.single {
            it.kind == HalfSangamKind.OPEN_PANNA_CLOSE_ANK && it.label == "277-5"
        }
        assertEquals(2, openPannaCloseAnk.supportingPairs)
        assertEquals(40.0, openPannaCloseAnk.score, 0.0001)
    }

    @Test fun emptyInputsAbstainAndInvalidLimitIsRejected() {
        assertTrue(SangamCandidateEngine.derive(emptyList(), listOf(candidate("249", 50.0))).fullSangam.isEmpty())
        try {
            SangamCandidateEngine.derive(listOf(candidate("277", 50.0)), listOf(candidate("249", 50.0)), 0)
            fail("Expected invalid limit")
        } catch (_: IllegalArgumentException) { }
    }

    private fun datedObservations(): List<SourceObservation> {
        val results = listOf("277-65-249", "578-07-368", "800-81-137", "566-74-148", "288-82-679")
        return results.flatMapIndexed { index, result ->
            listOf("publisher-a", "publisher-b").map { source ->
                SourceObservation(
                    market = Market.SRIDEVI,
                    sessionId = "main",
                    date = java.time.LocalDate.of(2026, 11, 1).plusDays(index.toLong()),
                    sourceId = source,
                    retrievedAt = java.time.Instant.parse("2026-11-18T10:00:00Z"),
                    rawText = result,
                    normalizedResult = result,
                    status = ObservationStatus.PUBLISHED
                )
            }
        }
    }

    @Test fun sangamWalkForwardUsesOnlyEarlierConsensusRows() {
        val report = SangamWalkForwardEvaluator.evaluate(
            datedObservations(), Market.SRIDEVI, "main",
            minimumHistory = 2, topN = 10
        )
        assertEquals(5, report.totalTargetDates)
        assertEquals(3, report.evaluatedDates)
        assertEquals(2, report.insufficientHistoryDates)
        assertTrue(report.warning.contains("Low sample"))
    }

    @Test fun sangamWalkForwardAbstainsOnSinglePublisherUnlessExplorationIsExplicit() {
        val onePublisher = datedObservations().filter { it.sourceId == "publisher-a" }
        val strict = SangamWalkForwardEvaluator.evaluate(
            onePublisher, Market.SRIDEVI, "main", minimumHistory = 2
        )
        assertEquals(0, strict.evaluatedDates)
        val exploratory = SangamWalkForwardEvaluator.evaluate(
            onePublisher, Market.SRIDEVI, "main",
            minimumHistory = 2, allowSingleSourceExploration = true
        )
        assertEquals(3, exploratory.evaluatedDates)
        assertTrue(exploratory.warning.contains("Exploratory"))
    }

    @Test fun sangamWalkForwardRejectsDuplicateEligibleDatesAndInvalidWindows() {
        val rows = datedObservations()
        val duplicated = rows + rows.first()
        try {
            SangamWalkForwardEvaluator.evaluate(duplicated, Market.SRIDEVI, "main")
            fail("Expected duplicate-date guard")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Duplicate dates"))
        }
        try {
            SangamWalkForwardEvaluator.evaluate(
                rows, Market.SRIDEVI, "main",
                startDateInclusive = java.time.LocalDate.of(2026, 11, 5),
                endDateExclusive = java.time.LocalDate.of(2026, 11, 4)
            )
            fail("Expected invalid date window")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("End date"))
        }
    }

}
