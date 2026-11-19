package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SourceCsvExporterTest {
    @Test fun roundTripsRawTextAndSourceMetadataIncludingQuotesAndNewlines() {
        val original = SourceObservation(
            market = Market.SRIDEVI,
            sessionId = "main",
            date = LocalDate.parse("2026-11-17"),
            sourceId = "archive, primary",
            sourceRecordId = "row-\"17\"",
            sourceUrl = "https://example.test/chart?id=17",
            retrievedAt = Instant.parse("2026-11-17T10:00:00Z"),
            publishedAt = Instant.parse("2026-11-17T10:01:00Z"),
            rawText = "Result: 115-73-229, note \"verified\"\nsecond line",
            normalizedResult = "115-73-229",
            status = ObservationStatus.PUBLISHED
        )

        val csv = SourceCsvExporter.export(listOf(original))
        val imported = SourceCsvImporter.import(csv)

        assertTrue(imported.issues.toString(), imported.issues.isEmpty())
        assertEquals(listOf(original), imported.observations)
    }

    @Test fun exportsEmptyDatasetAsHeaderOnlyCsv() {
        assertEquals(SourceCsvExporter.HEADER, SourceCsvExporter.export(emptyList()))
    }

    @Test fun roundTripsPendingAndHolidayStatesWithoutInventingResults() {
        val rows = listOf(
            SourceObservation(
                Market.KALYAN, "main", LocalDate.parse("2026-11-17"), "source-a",
                retrievedAt = Instant.parse("2026-11-17T10:00:00Z"),
                rawText = "pending", status = ObservationStatus.PENDING
            ),
            SourceObservation(
                Market.KALYAN, "main", LocalDate.parse("2026-11-18"), "source-a",
                retrievedAt = Instant.parse("2026-11-18T10:00:00Z"),
                rawText = "holiday", status = ObservationStatus.HOLIDAY_CLOSED
            )
        )
        val imported = SourceCsvImporter.import(SourceCsvExporter.export(rows))
        assertTrue(imported.issues.toString(), imported.issues.isEmpty())
        assertEquals(rows, imported.observations)
    }
}
