package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CoverageCsvExporterTest {
    @Test
    fun exportsEveryExpectedTradingDayAndPreservesCoverageStates() {
        val observations = listOf(
            SourceObservation(
                market = Market.KALYAN,
                sessionId = "main",
                date = LocalDate.parse("2026-11-02"),
                sourceId = "publisher-a",
                retrievedAt = Instant.parse("2026-11-17T12:00:00Z"),
                rawText = "789-46-150",
                normalizedResult = "789-46-150",
                status = ObservationStatus.PUBLISHED
            ),
            SourceObservation(
                market = Market.KALYAN,
                sessionId = "main",
                date = LocalDate.parse("2026-11-03"),
                sourceId = "publisher-a",
                retrievedAt = Instant.parse("2026-11-17T12:00:00Z"),
                rawText = "pending",
                status = ObservationStatus.PENDING
            )
        )
        val report = HistoricalCoverageAudit.analyze(
            Market.KALYAN, "main",
            LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-04"),
            observations
        )

        val csv = CoverageCsvExporter.export(listOf(report))
        assertTrue(csv.startsWith("market,session_id,start_date,end_date,expected_trading_days"))
        assertTrue(csv.contains("2026-11-02,SINGLE_SOURCE_UNVERIFIED,1,1"))
        assertTrue(csv.contains("2026-11-03,PENDING,1,0,1"))
        assertTrue(csv.contains("2026-11-04,NO_OBSERVATION,0,0,0"))
        assertEquals(4, csv.trimEnd().lines().size)
    }

    @Test
    fun escapesSessionIdAndPublishedResultListAsCsvFields() {
        val report = HistoricalCoverageAudit.analyze(
            Market.SRIDEVI, "session, \"A\"",
            LocalDate.parse("2026-11-16"), LocalDate.parse("2026-11-16"),
            listOf(
                SourceObservation(
                    market = Market.SRIDEVI,
                    sessionId = "session, \"A\"",
                    date = LocalDate.parse("2026-11-16"),
                    sourceId = "publisher",
                    retrievedAt = Instant.parse("2026-11-17T12:00:00Z"),
                    rawText = "577-99-667",
                    normalizedResult = "577-99-667",
                    status = ObservationStatus.PUBLISHED
                )
            )
        )
        val csv = CoverageCsvExporter.export(listOf(report))
        assertTrue(csv.contains("\"session, \"\"A\"\"\","))
        assertTrue(csv.contains("577-99-667"))
        assertFalse(csv.contains("\"NO_OBSERVATION\""))
    }
}
