package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ReconciledChartBuilderTest {
    private fun row(
        source: String,
        session: String = "main",
        result: String = "668-07-449",
        status: ObservationStatus = ObservationStatus.PUBLISHED
    ) = SourceObservation(
        market = Market.KALYAN,
        sessionId = session,
        date = LocalDate.parse("2026-08-24"),
        sourceId = source,
        retrievedAt = Instant.parse("2026-08-24T12:00:00Z"),
        publishedAt = if (status == ObservationStatus.PUBLISHED) Instant.parse("2026-08-24T12:01:00Z") else null,
        rawText = result,
        normalizedResult = if (status == ObservationStatus.PUBLISHED) result else null,
        status = status
    )

    @Test fun buildsOnlyConsensusRecordsAndPreservesLeadingZeroJodi() {
        val report = ReconciledChartBuilder.build(
            listOf(row("source-a"), row("source-b"))
        )
        assertEquals(1, report.records.size)
        assertEquals("07", report.records.single().jodi)
        assertEquals("668", report.records.single().openPanna)
        assertEquals("449", report.records.single().closePanna)
        assertEquals("source-a+source-b", report.records.single().sourceId)
        assertTrue(report.conflictGroups.isEmpty())
        assertTrue(report.invalidGroups.isEmpty())
    }

    @Test fun excludesConflictingAndUnpublishedDatesFromAnalysisRecords() {
        val report = ReconciledChartBuilder.build(
            listOf(
                row("source-a", result = "668-07-449"),
                row("source-b", result = "449-07-668"),
                row("source-c", result = "", status = ObservationStatus.PENDING).copy(date = LocalDate.parse("2026-08-25"))
            )
        )
        assertTrue(report.records.isEmpty())
        assertEquals(1, report.conflictGroups.size)
        assertEquals(1, report.noPublishedResultGroups.size)
    }

    @Test fun singleSourceRowsAreNotAddedToAnalysisRecords() {
        val report = ReconciledChartBuilder.build(listOf(row("source-a")))
        assertTrue(report.records.isEmpty())
        assertEquals(1, report.singleSourceUnverifiedGroups.size)
        assertTrue(report.invalidGroups.isEmpty())
    }

    @Test fun keepsSessionsSeparateAndRejectsMathematicallyInvalidConsensus() {
        val invalidA = row("source-a", result = "111-11-111")
        val invalidB = row("source-b", result = "111-11-111")
        val otherSessionA = row("source-c", session = "night", result = "668-07-449")
        val otherSessionB = row("source-d", session = "night", result = "668-07-449")
        val report = ReconciledChartBuilder.build(
            listOf(invalidA, invalidB, otherSessionA, otherSessionB)
        )
        assertEquals(1, report.records.size)
        assertEquals("night", report.records.single().sessionId)
        assertEquals(1, report.invalidGroups.size)
    }
}
