package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchManifestExporterTest {
    @Test
    fun manifestIncludesCountsRangesAndStableLedgerChecksum() {
        val observation = SourceObservation(
            market = Market.SRIDEVI,
            sessionId = "morning",
            date = LocalDate.parse("2026-11-16"),
            sourceId = "archive-A",
            retrievedAt = Instant.parse("2026-11-16T10:00:00Z"),
            publishedAt = Instant.parse("2026-11-16T09:00:00Z"),
            rawText = "577-99-667",
            normalizedResult = "577-99-667",
            status = ObservationStatus.PUBLISHED
        )
        val generated = Instant.parse("2026-11-17T12:00:00Z")
        val first = ResearchManifestExporter.export(listOf(observation), emptyList(), emptyList(), generated)
        val second = ResearchManifestExporter.export(listOf(observation), emptyList(), emptyList(), generated)

        assertEquals(first, second)
        assertTrue(first.contains("\"source_observation_count\": 1"))
        assertTrue(first.contains("\"single_source_unverified_groups\": 1"))
        assertTrue(first.contains("\"consensus_chart_records_exported\": 0"))
        assertTrue(first.contains("\"start_date\":\"2026-11-16\""))
        assertTrue(first.contains("\"source_ledger_sha256\": \""))
        assertTrue(first.contains("\"published_observations_with_timestamp\": 1"))
        assertTrue(first.contains("\"published_observations_missing_timestamp\": 0"))
        assertTrue(first.contains("\"negative_publication_lag_observations\": 0"))
        assertTrue(first.contains("\"source_timing_summary\": ["))
        assertTrue(first.contains("\"median_nonnegative_lag_minutes\":60"))
        assertTrue(first.contains(ResearchManifestExporter.RULESET_VERSION))
    }

    @Test
    fun timingSummaryArrayUsesRealJsonWhitespaceBetweenEntries() {
        val first = SourceObservation(
            market = Market.SRIDEVI,
            sessionId = "morning",
            date = LocalDate.parse("2026-11-16"),
            sourceId = "archive-A",
            retrievedAt = Instant.parse("2026-11-16T10:00:00Z"),
            publishedAt = Instant.parse("2026-11-16T09:00:00Z"),
            rawText = "577-99-667",
            normalizedResult = "577-99-667",
            status = ObservationStatus.PUBLISHED
        )
        val second = first.copy(sourceId = "archive-B")
        val manifest = ResearchManifestExporter.export(
            listOf(first, second), emptyList(), emptyList(), Instant.parse("2026-11-17T12:00:00Z")
        )

        assertTrue(manifest.contains("},\n    {"))
        assertTrue(!manifest.contains("},\\n    {"))
        assertTrue(manifest.contains("\"source_timing_summary\": ["))
    }

    @Test
    fun manifestEscapesSessionNamesAsValidJsonStrings() {
        val observation = SourceObservation(
            market = Market.KALYAN,
            sessionId = "evening \"special\"",
            date = LocalDate.parse("2026-11-16"),
            sourceId = "source",
            retrievedAt = Instant.parse("2026-11-16T12:00:00Z"),
            rawText = "pending",
            status = ObservationStatus.PENDING
        )
        val manifest = ResearchManifestExporter.export(
            listOf(observation), emptyList(), emptyList(), Instant.parse("2026-11-17T12:00:00Z")
        )
        assertTrue(manifest.contains("evening \\\"special\\\""))
        assertTrue(manifest.contains("\"PENDING\": 1"))
    }
}
