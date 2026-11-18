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

    @Test fun repeatedWeeklyRowsAreRetainedAndReconcileAsConflict() {
        val html = """
            <table>
              <tr><th>Date Range</th><th>Mon</th><th>Tue</th><th>Wed</th><th>Thu</th><th>Fri</th><th>Sat</th></tr>
              <tr><td>16-11-2026 to 21-11-2026</td><td>12365456</td><td>23498567</td><td>34524789</td><td>45657890</td><td>56783012</td><td>67813012</td></tr>
              <tr><td>16-11-2026 to 21-11-2026</td><td>99965456</td><td>23498567</td><td>34524789</td><td>45657890</td><td>56783012</td><td>67813012</td></tr>
            </table>
        """.trimIndent()
        val parsed = FixResultKalyanWeeklyParserAdapter.parse(html, pageUrl, Market.KALYAN, "main", retrievedAt)
        val monday = parsed.observations.filter { it.date == LocalDate.parse("2026-11-16") }
        assertEquals(2, monday.size)
        assertEquals(setOf("123-65-456", "999-65-456"), monday.mapNotNull { it.normalizedResult }.toSet())
        assertEquals(ReconciliationState.CONFLICT, SourceReconciliation.reconcile(monday).single().state)
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
