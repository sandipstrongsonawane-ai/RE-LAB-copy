package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class MatkaFlashHistoryParserAdapterTest {
    private val pageUrl = "https://matkaflash.com/sridevi?tab=history"
    private val retrievedAt = Instant.parse("2026-11-18T03:00:00Z")

    private fun fixture(): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/matkaflash-sridevi-history.html")) {
            "MatkaFlash fixture resource is missing"
        }.bufferedReader().use { it.readText() }

    @Test fun recognizesPublisherAndExtractsDatedHistoryWithoutLosingLeadingZeroJodi() {
        val html = fixture()
        assertTrue(MatkaFlashHistoryParserAdapter.supports(html, pageUrl))

        val parsed = ChartParserRegistry.parse(html, pageUrl, Market.SRIDEVI, "main", retrievedAt)

        assertEquals("matkaflash-history-v1", parsed.parserId)
        assertEquals(4, parsed.observations.size)
        val published = parsed.observations.filter { it.status == ObservationStatus.PUBLISHED }
        assertEquals(3, published.size)
        assertEquals("115-73-229", published.first().normalizedResult)
        assertEquals("2026-11-17", published.first().date.toString())
        assertEquals("578-07-368", published.single { it.date == LocalDate.parse("2026-11-10") }.normalizedResult)
        assertTrue(published.all { it.sourceUrl == pageUrl && it.sourceId == "matkaflash.com" })
    }

    @Test fun pendingRowsArePreservedButNeverTreatedAsPublishedResults() {
        val parsed = MatkaFlashHistoryParserAdapter.parse(
            fixture(), pageUrl, Market.SRIDEVI, "main", retrievedAt
        )
        val pending = parsed.observations.single { it.date == LocalDate.parse("2026-11-18") }
        assertEquals(ObservationStatus.PENDING, pending.status)
        assertNull(pending.normalizedResult)
    }


    @Test fun extractsKalyanHistoryAndRetainsExplicitHolidayRows() {
        val html = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/matkaflash-kalyan-history.html")) {
            "MatkaFlash Kalyan fixture resource is missing"
        }.bufferedReader().use { it.readText() }
        val url = "https://matkaflash.com/kalyan"
        val parsed = ChartParserRegistry.parse(html, url, Market.KALYAN, "main", retrievedAt)

        assertEquals("matkaflash-history-v1", parsed.parserId)
        assertEquals(4, parsed.observations.size)
        assertEquals(
            "390-24-257",
            parsed.observations.single { it.date == LocalDate.parse("2026-11-12") }.normalizedResult
        )
        val holiday = parsed.observations.single { it.date == LocalDate.parse("2026-11-13") }
        assertEquals(ObservationStatus.HOLIDAY_CLOSED, holiday.status)
        assertNull(holiday.normalizedResult)
        assertTrue(parsed.observations.all { it.sourceUrl == url })
    }

    @Test fun repeatedDateRowsAreRetainedAndReconcileAsConflict() {
        val html = """
            <table>
              <tr><th>Date</th><th>Weekday</th><th>Open Panna</th><th>Jodi</th><th>Close Panna</th><th>Status</th></tr>
              <tr><td>17 Nov 2026</td><td>Tuesday</td><td>115</td><td>73</td><td>229</td><td>Declared</td></tr>
              <tr><td>17 Nov 2026</td><td>Tuesday</td><td>556</td><td>63</td><td>148</td><td>Declared</td></tr>
            </table>
        """.trimIndent()
        val parsed = MatkaFlashHistoryParserAdapter.parse(html, pageUrl, Market.SRIDEVI, "main", retrievedAt)
        assertEquals(2, parsed.observations.size)
        assertEquals(setOf("115-73-229", "556-63-148"), parsed.observations.mapNotNull { it.normalizedResult }.toSet())
        assertEquals(ReconciliationState.CONFLICT, SourceReconciliation.reconcile(parsed.observations).single().state)
    }

    @Test fun adapterDoesNotClaimOtherPublishersOrHeaderLayouts() {
        val html = fixture()
        assertFalse(MatkaFlashHistoryParserAdapter.supports(html, "https://example.com/sridevi"))
        assertFalse(
            MatkaFlashHistoryParserAdapter.supports(
                "<table><tr><th>Date</th><th>Result</th></tr></table>",
                pageUrl
            )
        )
    }
}
