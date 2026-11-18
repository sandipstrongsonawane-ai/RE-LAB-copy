package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class PublisherCoverageAuditTest {
    private val sourceA = MarketSource(
        Market.KALYAN, "main", "publisher-a", "https://a.example/chart", "panel"
    )
    private val sourceAHistory = MarketSource(
        Market.KALYAN, "main", "publisher-a", "https://a.example/history", "history"
    )
    private val sourceB = MarketSource(
        Market.KALYAN, "main", "publisher-b", "https://b.example/chart", "panel"
    )

    private fun row(source: MarketSource, date: String, result: String? = "789-46-150") =
        SourceObservation(
            market = source.market,
            sessionId = source.sessionId,
            date = LocalDate.parse(date),
            sourceId = source.sourceId,
            sourceUrl = source.url,
            retrievedAt = Instant.parse("2026-11-18T10:00:00Z"),
            rawText = result ?: "PENDING",
            normalizedResult = result,
            status = if (result == null) ObservationStatus.PENDING else ObservationStatus.PUBLISHED
        )

    @Test fun tracksMissingDatesSeparatelyForEveryRegisteredUrl() {
        val report = PublisherCoverageAudit.analyze(
            Market.KALYAN, "main",
            LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-04"),
            listOf(sourceA, sourceB),
            listOf(row(sourceA, "2026-11-02"), row(sourceB, "2026-11-03"))
        )
        assertEquals(3, report.expectedTradingDays)
        assertEquals(2, report.registeredUrlCount)
        assertEquals(6, report.expectedUrlDateEntries)
        assertEquals(4, report.noRowEntries)
        assertEquals(2, report.publishedEntries)
        assertEquals(6, report.days.size)
    }

    @Test fun separateUrlsFromSamePublisherHaveSeparateCoverageButSameIdentity() {
        val report = PublisherCoverageAudit.analyze(
            Market.KALYAN, "main",
            LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-02"),
            listOf(sourceA, sourceAHistory),
            listOf(row(sourceA, "2026-11-02"))
        )
        assertEquals(2, report.registeredUrlCount)
        assertEquals(1, report.publishedEntries)
        assertEquals(1, report.noRowEntries)
        assertEquals(setOf("publisher-a"), report.days.map { it.sourceId }.toSet())
    }

    @Test fun distinguishesPendingMalformedAndMultipleConflictingRows() {
        val malformed = row(sourceA, "2026-11-02").copy(
            normalizedResult = null, rawText = "bad", status = ObservationStatus.MALFORMED
        )
        val conflict = row(sourceB, "2026-11-02", "678-17-124")
        val secondConflict = conflict.copy(
            normalizedResult = "789-46-150", rawText = "789-46-150"
        )
        val report = PublisherCoverageAudit.analyze(
            Market.KALYAN, "main",
            LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-02"),
            listOf(sourceA, sourceB),
            listOf(malformed, conflict, secondConflict)
        )
        assertEquals(PublisherDateState.MALFORMED, report.days.first { it.sourceId == "publisher-a" }.state)
        assertEquals(
            PublisherDateState.MULTIPLE_PUBLISHED_VALUES,
            report.days.first { it.sourceId == "publisher-b" }.state
        )
    }

    @Test fun ignoresDisabledSourcesAndRejectsInvalidRange() {
        val disabled = sourceA.copy(enabled = false)
        val report = PublisherCoverageAudit.analyze(
            Market.KALYAN, "main",
            LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-02"),
            listOf(disabled), emptyList()
        )
        assertEquals(0, report.registeredUrlCount)
        assertTrue(report.days.isEmpty())
        try {
            PublisherCoverageAudit.analyze(
                Market.KALYAN, "main",
                LocalDate.parse("2026-11-03"), LocalDate.parse("2026-11-02"),
                listOf(sourceA), emptyList()
            )
            fail("Expected invalid date range")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
