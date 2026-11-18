package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class FixResultKalyanWeeklyParserAdapterTest {
    private val pageUrl = "https://www.fixresult.in/kalyan-matka-chart.html"
    private val retrievedAt = Instant.parse("2026-11-18T04:00:00Z")

    private fun fixture(): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/fixresult-kalyan-weekly.html")) {
            "FixResult weekly fixture resource is missing"
        }.bufferedReader().use { it.readText() }

    @Test fun extractsSixWeekdaysAndPreservesLeadingZeroJodiAndRawSourceDetails() {
        val html = fixture()
        assertTrue(FixResultKalyanWeeklyParserAdapter.supports(html, pageUrl))

        val parsed = ChartParserRegistry.parse(html, pageUrl, Market.KALYAN, "main", retrievedAt)

        assertEquals("fixresult-kalyan-weekly-v1", parsed.parserId)
        assertEquals(6, parsed.observations.size)
        assertEquals("679-24-455", parsed.observations.single { it.date == LocalDate.parse("2026-09-21") }.normalizedResult)
        val thursday = parsed.observations.single { it.date == LocalDate.parse("2026-09-24") }
        assertEquals("235-05-140", thursday.normalizedResult)
        assertEquals("fixresult.in", thursday.sourceId)
        assertEquals(pageUrl, thursday.sourceUrl)
        assertTrue(thursday.rawText.contains("2 3 5 05 1 4 0"))
    }

    @Test fun missingAndPartialCellsAreNotPromotedToPublishedResults() {
        val parsed = FixResultKalyanWeeklyParserAdapter.parse(
            fixture(), pageUrl, Market.KALYAN, "main", retrievedAt
        )
        val missing = parsed.observations.single { it.date == LocalDate.parse("2026-09-25") }
        assertEquals(ObservationStatus.MISSING_FROM_SOURCE, missing.status)
        assertNull(missing.normalizedResult)

        val partial = parsed.observations.single { it.date == LocalDate.parse("2026-09-26") }
        assertEquals(ObservationStatus.MALFORMED, partial.status)
        assertNull(partial.normalizedResult)
        assertTrue(parsed.issues.any { it.contains("expected 8 result digits") })
    }

    @Test fun adapterRejectsOtherHostsAndUnrelatedTableLayouts() {
        val html = fixture()
        assertFalse(FixResultKalyanWeeklyParserAdapter.supports(html, "https://example.com/kalyan-matka-chart.html"))
        assertFalse(
            FixResultKalyanWeeklyParserAdapter.supports(
                "<table><tr><th>Date</th><th>Result</th></tr></table>",
                pageUrl
            )
        )
    }

    @Test fun parserRejectsWrongMarketInsteadOfMislabelingRecords() {
        try {
            FixResultKalyanWeeklyParserAdapter.parse(
                fixture(), pageUrl, Market.SRIDEVI, "main", retrievedAt
            )
            fail("Expected a market mismatch to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("only configured for Kalyan"))
        }
    }
}
