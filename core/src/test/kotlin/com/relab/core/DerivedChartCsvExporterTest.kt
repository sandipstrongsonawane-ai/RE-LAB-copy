package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DerivedChartCsvExporterTest {
    @Test fun exportsDerivedFeaturesAndPublisherProvenance() {
        val record = ChartRecord(
            market = Market.SRIDEVI,
            date = LocalDate.parse("2026-11-17"),
            openPanna = "115",
            jodi = "73",
            closePanna = "229",
            sourceId = "publisher-a+publisher-b",
            publishedAt = Instant.parse("2026-11-17T09:00:00Z"),
            observedAt = Instant.parse("2026-11-17T09:05:00Z"),
            sessionId = "main, morning"
        )
        val csv = DerivedChartCsvExporter.export(listOf(record))
        assertTrue(csv.startsWith(DerivedChartCsvExporter.HEADER))
        assertTrue(csv.contains("\"main, morning\""))
        assertTrue(csv.contains("115,73,229,7,3,110,224,2,3,DP,DP,511,922,publisher-a+publisher-b"))
        assertTrue(csv.contains("2026-11-17T09:00:00Z"))
    }

    @Test fun rejectsInvalidRowsInsteadOfSilentlyExportingThem() {
        val invalid = ChartRecord(
            Market.SRIDEVI, LocalDate.parse("2026-11-17"),
            "115", "99", "229", "publisher-a"
        )
        try {
            DerivedChartCsvExporter.export(listOf(invalid))
            fail("Expected invalid record rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Cannot export invalid"))
        }
    }

    @Test fun emptyExportContainsHeaderOnly() {
        assertEquals(DerivedChartCsvExporter.HEADER, DerivedChartCsvExporter.export(emptyList()))
    }
}
