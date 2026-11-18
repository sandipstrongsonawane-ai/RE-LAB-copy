package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

/**
 * Historical-data integration validation from two independently hosted public
 * Kalyan archives. Rows are transcribed from indexed historical chart excerpts;
 * they are test evidence only, not production-imported observations or raw HTML captures.
 */
class HistoricalArchiveWalkForwardValidationTest {
    private val weekRows = listOf(
        "2024-11-04|||369-80-136|348-52-147|170-84-590|350-87-179",
        "2024-11-11|140-51-489|577-91-128|257-41-137|456-57-377|588-19-478|379-95-258",
        "2024-11-18|460-05-168|667-90-479|557-74-770|136-06-259|119-19-234|268-63-689",
        "2024-11-25|379-90-280|346-34-400|260-82-200|370-09-144|589-28-260|137-19-577",
        "2024-12-02|467-72-480|337-34-347|138-21-380|580-32-156|156-21-489|158-42-129",
        "2024-12-09|168-58-170|224-84-347|488-00-299|114-67-359|677-06-277|667-98-378",
        "2024-12-16|479-03-157|468-82-589|679-29-667|379-90-226|560-14-455|114-66-367",
        "2024-12-23|578-02-589|699-40-235|199-92-345|116-85-780|569-00-136|800-88-260",
        "2024-12-30|580-39-144|379-94-257|229-33-599|447-58-125|344-11-470|178-63-337"
    )

    private fun historicalObservations(): List<SourceObservation> {
        val captureClockForTest = Instant.parse("2026-11-18T00:00:00Z")
        val sources = listOf(
            "matkaflash" to "https://matkaflash.com/kalyan/panel-chart/2024",
            "bharatmatka" to "https://bharatmatka.si/charts/main/kalyan-panel-chart/"
        )
        return weekRows.flatMap { week ->
            val cells = week.split("|")
            val monday = LocalDate.parse(cells.first())
            cells.drop(1).mapIndexed { offset, raw ->
                val date = monday.plusDays(offset.toLong())
                val result = raw.trim().takeIf { it.isNotEmpty() }
                sources.map { (sourceId, url) ->
                    SourceObservation(
                        market = Market.KALYAN,
                        sessionId = "main",
                        date = date,
                        sourceId = sourceId,
                        sourceRecordId = date.toString(),
                        sourceUrl = url,
                        // Fixed test clock only; not represented as the archive's true retrieval time.
                        retrievedAt = captureClockForTest,
                        rawText = result ?: "—",
                        normalizedResult = result,
                        status = if (result == null) ObservationStatus.MISSING_FROM_SOURCE
                            else ObservationStatus.PUBLISHED
                    )
                }
            }.flatten()
        }
    }

    @Test fun validatesRealHistoricalRowsAcrossTwoArchivePublishersAndRunsStrictWalkForward() {
        val observations = historicalObservations()
        val reconciliation = SourceReconciliation.reconcile(observations)
        val build = ReconciledChartBuilder.build(observations)

        assertEquals(108, observations.size)
        assertEquals(52, build.records.size)
        assertEquals(0, build.conflictGroups.size)
        assertEquals(0, build.singleSourceUnverifiedGroups.size)
        assertEquals(2, build.noPublishedResultGroups.size)
        assertEquals(0, build.invalidGroups.size)
        assertEquals(54, reconciliation.size)
        assertEquals(52, reconciliation.count { it.state == ReconciliationState.CONSENSUS })

        build.records.forEach { record ->
            assertTrue(
                "Invalid historical result on " + record.date + ": " + record,
                ChartMath.validate(record) is ValidationResult.Valid
            )
        }

        val pattern = PatternAnalysis.analyze(build.records).single()
        assertEquals(52, pattern.acceptedRecords)
        assertEquals(0, pattern.rejectedRecords)
        assertEquals(51, pattern.walkForwardRows.size)
        assertTrue(pattern.globalModeBaseline.evaluated > 0)
        assertTrue(pattern.weekdayModeBaseline.evaluated > 0)
        assertTrue(pattern.previousStateBaseline.evaluated > 0)

        val open = CandidateWalkForwardEvaluator.evaluate(
            observations, Market.KALYAN, "main", PannaSide.OPEN,
            minimumHistory = 5, topN = 10
        )
        val close = CandidateWalkForwardEvaluator.evaluate(
            observations, Market.KALYAN, "main", PannaSide.CLOSE,
            minimumHistory = 5, topN = 10
        )
        assertEquals(52, open.totalTargetDates)
        assertEquals(47, open.evaluatedDates)
        assertEquals(5, open.insufficientHistoryDates)
        assertEquals(52, close.totalTargetDates)
        assertEquals(47, close.evaluatedDates)
        assertEquals(5, close.insufficientHistoryDates)
        assertEquals(open.baselineMetrics.evaluatedDates, open.learningEnabledMetrics.evaluatedDates)
        assertEquals(close.baselineMetrics.evaluatedDates, close.learningEnabledMetrics.evaluatedDates)
    }

    @Test fun futureHistoricalRowsCannotLeakIntoAnEarlierEvaluationWindow() {
        val base = historicalObservations()
        val cutoff = LocalDate.parse("2024-12-16")
        val before = CandidateWalkForwardEvaluator.evaluate(
            base, Market.KALYAN, "main", PannaSide.OPEN,
            minimumHistory = 5, endDateExclusive = cutoff
        )
        val future = base + listOf("matkaflash", "bharatmatka").map { source ->
            SourceObservation(
                market = Market.KALYAN,
                sessionId = "main",
                date = LocalDate.parse("2025-01-06"),
                sourceId = source,
                sourceUrl = "historical-fixture-only",
                retrievedAt = Instant.parse("2026-11-18T00:00:00Z"),
                rawText = "999-87-888",
                normalizedResult = "999-87-888",
                status = ObservationStatus.PUBLISHED
            )
        }
        val after = CandidateWalkForwardEvaluator.evaluate(
            future, Market.KALYAN, "main", PannaSide.OPEN,
            minimumHistory = 5, endDateExclusive = cutoff
        )
        assertEquals(before, after)
    }
}
