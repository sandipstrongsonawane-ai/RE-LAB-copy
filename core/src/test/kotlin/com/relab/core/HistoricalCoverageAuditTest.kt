package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class HistoricalCoverageAuditTest {
    private fun observation(
        date: String,
        source: String,
        status: ObservationStatus,
        result: String? = null
    ) = SourceObservation(
        market = Market.KALYAN,
        sessionId = "main",
        date = LocalDate.parse(date),
        sourceId = source,
        retrievedAt = Instant.parse("2026-11-17T12:00:00Z"),
        rawText = result ?: status.name,
        normalizedResult = result,
        status = status
    )

    @Test fun auditsKalyanTradingDaysAndKeepsHolidayPendingAndMissingDistinct() {
        val rows = listOf(
            observation("2026-11-02", "a", ObservationStatus.PUBLISHED, "789-46-150"),
            observation("2026-11-03", "a", ObservationStatus.PENDING),
            observation("2026-11-04", "a", ObservationStatus.HOLIDAY_CLOSED),
            observation("2026-11-05", "a", ObservationStatus.MISSING_FROM_SOURCE),
            observation("2026-11-06", "a", ObservationStatus.MALFORMED)
        )
        val report = HistoricalCoverageAudit.analyze(
            Market.KALYAN, "main",
            LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-08"), rows
        )
        assertEquals(6, report.expectedTradingDays)
        assertEquals(1, report.publishedDays)
        assertEquals(1, report.singleSourceDays)
        assertEquals(0, report.consensusDays)
        assertEquals(1, report.pendingDays)
        assertEquals(1, report.holidayDays)
        assertEquals(1, report.sourceMissingDays)
        assertEquals(1, report.noObservationDays)
        assertEquals(1, report.malformedOnlyDays)
        assertEquals(CoverageState.NO_OBSERVATION, report.days.last().state)
        assertEquals(CoverageState.SINGLE_SOURCE_UNVERIFIED, report.days.first().state)
        assertEquals(1, report.days.first().publishedSourceCount)
    }

    @Test fun coverageCountsDistinctPublishersNotRepeatedSnapshots() {
        val first = observation(
            "2026-11-02", "publisher-a", ObservationStatus.PUBLISHED, "789-46-150"
        )
        val laterSnapshot = first.copy(
            retrievedAt = Instant.parse("2026-11-17T13:00:00Z"),
            sourceRecordId = "second-page-capture"
        )
        val otherPublisherPending = observation(
            "2026-11-02", "publisher-b", ObservationStatus.PENDING
        )
        val report = HistoricalCoverageAudit.analyze(
            Market.KALYAN, "main",
            LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-02"),
            listOf(first, laterSnapshot, otherPublisherPending)
        )
        val day = report.days.single()
        assertEquals(3, day.observationCount)
        assertEquals(1, day.publishedSourceCount)
        assertEquals(1, day.pendingSourceCount)
        assertEquals(CoverageState.SINGLE_SOURCE_UNVERIFIED, day.state)
    }

    @Test fun matchingResultsFromTwoPublishersAreConsensusInCoverage() {
        val rows = listOf(
            observation("2026-11-02", "publisher-a", ObservationStatus.PUBLISHED, "789-46-150"),
            observation("2026-11-02", "publisher-b", ObservationStatus.PUBLISHED, "789-46-150")
        )
        val report = HistoricalCoverageAudit.analyze(
            Market.KALYAN, "main",
            LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-02"), rows
        )
        assertEquals(CoverageState.CONSENSUS, report.days.single().state)
        assertEquals(1, report.consensusDays)
        assertEquals(0, report.singleSourceDays)
        assertEquals(2, report.days.single().publishedSourceCount)
    }

    @Test fun conflictingPublishedSourcesRemainAConflict() {
        val rows = listOf(
            observation("2026-11-02", "a", ObservationStatus.PUBLISHED, "789-46-150"),
            observation("2026-11-02", "b", ObservationStatus.PUBLISHED, "678-17-124")
        )
        val report = HistoricalCoverageAudit.analyze(
            Market.KALYAN, "main",
            LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-02"), rows
        )
        assertEquals(CoverageState.CONFLICT, report.days.single().state)
        assertEquals(listOf("678-17-124", "789-46-150"), report.days.single().distinctPublishedResults)
    }

    @Test fun malformedOnlyDateIsNotReportedAsNoObservation() {
        val report = HistoricalCoverageAudit.analyze(
            Market.KALYAN, "main",
            LocalDate.parse("2026-11-06"), LocalDate.parse("2026-11-06"),
            listOf(observation("2026-11-06", "source-a", ObservationStatus.MALFORMED))
        )
        assertEquals(CoverageState.MALFORMED_ONLY, report.days.single().state)
        assertEquals(1, report.malformedOnlyDays)
        assertEquals(0, report.noObservationDays)
        assertEquals(1, report.days.single().malformedSourceCount)
    }

    @Test fun ignoresOtherMarketsAndSessionsAndRejectsInvalidRange() {
        val otherSession = observation("2026-11-02", "a", ObservationStatus.PENDING)
            .copy(sessionId = "day")
        val report = HistoricalCoverageAudit.analyze(
            Market.KALYAN, "main",
            LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-02"),
            listOf(otherSession)
        )
        assertEquals(CoverageState.NO_OBSERVATION, report.days.single().state)
        try {
            HistoricalCoverageAudit.analyze(
                Market.KALYAN, "main",
                LocalDate.parse("2026-11-03"), LocalDate.parse("2026-11-02"), emptyList()
            )
            fail("Expected invalid date range")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
