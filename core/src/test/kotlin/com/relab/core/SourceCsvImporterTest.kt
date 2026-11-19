package com.relab.core

import org.junit.Assert.*
import org.junit.Test

class SourceCsvImporterTest {
    private val header = "market,session_id,date,source_id,retrieved_at,raw_text,status,source_record_id,source_url,published_at,result"

    @Test fun importsPublishedPendingAndMalformedObservationsWithoutDroppingRawText() {
        val csv = listOf(
            header,
            "SRIDEVI,main,2026-11-09,archive-a,2026-11-09T08:00:00Z,\"277-65-249, morning chart\",PUBLISHED,row-1,https://example.test/a,2026-11-09T08:01:00Z,277-65-249",
            "SRIDEVI,main,2026-11-10,archive-a,2026-11-10T08:00:00Z,pending,PENDING,row-2,,,",
            "SRIDEVI,main,2026-11-11,archive-a,2026-11-11T08:00:00Z,wrong jodi,PUBLISHED,row-3,,,277-99-249"
        ).joinToString("\n")

        val report = SourceCsvImporter.import(csv)
        assertTrue(report.issues.toString(), report.issues.isEmpty())
        assertEquals(3, report.observations.size)
        assertEquals("277-65-249, morning chart", report.observations[0].rawText)
        assertEquals(ObservationStatus.PUBLISHED, report.observations[0].status)
        assertEquals("277-65-249", report.observations[0].normalizedResult)
        assertEquals(ObservationStatus.PENDING, report.observations[1].status)
        assertNull(report.observations[1].normalizedResult)
        assertEquals(ObservationStatus.MALFORMED, report.observations[2].status)
        assertNull(report.observations[2].normalizedResult)
    }

    @Test fun reportsBadRowsWithoutDiscardingValidRows() {
        val csv = listOf(
            header,
            "SRIDEVI,main,2026-11-09,archive-a,2026-11-09T08:00:00Z,ok,PUBLISHED,row-1,,,277-65-249",
            "SRIDEVI,main,not-a-date,archive-a,2026-11-10T08:00:00Z,bad,PENDING,row-2,,,,"
        ).joinToString("\n")

        val report = SourceCsvImporter.import(csv)
        assertEquals(1, report.observations.size)
        assertEquals(1, report.issues.size)
        assertEquals(3, report.issues.single().rowNumber)
    }

    @Test fun acceptsEscapedQuotesAndCrLfAfterQuotedFields() {
        val csv = (header + "\r\n" +
            "SRIDEVI,main,2026-11-09,archive-a,2026-11-09T08:00:00Z,\"say \"\"hello\"\", chart\",PENDING,row-1,,,")
        val report = SourceCsvImporter.import(csv)
        assertTrue(report.issues.toString(), report.issues.isEmpty())
        assertEquals("say \"hello\", chart", report.observations.single().rawText)
    }

    @Test fun rejectsCharactersImmediatelyAfterAClosingQuote() {
        val csv = header + "\n" +
            "SRIDEVI,main,2026-11-09,archive-a,2026-11-09T08:00:00Z,\"raw\"oops,PENDING,row-1,,,"
        try {
            SourceCsvImporter.import(csv)
            fail("Malformed CSV field should be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("after closing quote"))
        }
    }

    @Test fun acceptsUtf8BomBeforeFirstHeader() {
        val csv = "\uFEFF" + header + "\n" +
            "SRIDEVI,main,2026-11-09,archive-a,2026-11-09T08:00:00Z,raw,PENDING,row-1,,,"
        val report = SourceCsvImporter.import(csv)
        assertTrue(report.issues.toString(), report.issues.isEmpty())
        assertEquals(1, report.observations.size)
        assertEquals("raw", report.observations.single().rawText)
    }

    @Test fun rejectsUnknownOrMissingHeaders() {
        val report = SourceCsvImporter.import("market,date")
        assertTrue(report.observations.isEmpty())
        assertTrue(report.issues.any { it.message.contains("Missing headers") })
    }
}
