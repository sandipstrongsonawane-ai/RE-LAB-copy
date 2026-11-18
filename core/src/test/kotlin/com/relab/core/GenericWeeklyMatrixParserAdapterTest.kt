package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class GenericWeeklyMatrixParserAdapterTest {
    private val retrievedAt = Instant.parse("2026-11-18T04:30:00Z")

    @Test fun detectsPublisherNeutralSevenDayMatrixAndPreservesSourceIdentity() {
        val html = """
            <table>
              <tr><th>Week Range</th><th>Monday</th><th>Tuesday</th><th>Wednesday</th><th>Thursday</th><th>Friday</th><th>Saturday</th><th>Sunday</th></tr>
              <tr><td>16-11-2026 to 22-11-2026</td><td>12365456</td><td>23498567</td><td>34524789</td><td>45657890</td><td>56783012</td><td>67813012</td><td>78943012</td></tr>
            </table>
        """.trimIndent()
        val url = "https://charts.example.org/market/sridevi"

        assertTrue(GenericWeeklyMatrixParserAdapter.supports(html, url))
        val parsed = ChartParserRegistry.parse(html, url, Market.SRIDEVI, "day", retrievedAt)

        assertEquals("generic-weekly-matrix-v1", parsed.parserId)
        assertEquals(7, parsed.observations.size)
        val monday = parsed.observations.single { it.date == LocalDate.parse("2026-11-16") }
        assertEquals("123-65-456", monday.normalizedResult)
        assertEquals("charts.example.org", monday.sourceId)
        assertEquals(url, monday.sourceUrl)
        assertTrue(monday.rawText.contains("12365456"))
        assertTrue(parsed.observations.all { it.status == ObservationStatus.PUBLISHED })
    }

    @Test fun supportsFiveOrSixDayMatricesAndKeepsMissingAndMalformedCellsUnpublished() {
        val html = """
            <table>
              <tr><th>Date Range</th><th>Mon</th><th>Tue</th><th>Wed</th><th>Thu</th><th>Fri</th><th>Sat</th></tr>
              <tr><td>16/11/2026 to 21/11/2026</td><td>123-65-456</td><td>-</td><td>bad</td><td>23498567</td><td>34587678</td><td>45698789</td></tr>
            </table>
        """.trimIndent()
        val parsed = GenericWeeklyMatrixParserAdapter.parse(
            html, "https://publisher.test/kalyan", Market.KALYAN, "main", retrievedAt
        )

        assertEquals(6, parsed.observations.size)
        assertEquals(ObservationStatus.MISSING_FROM_SOURCE,
            parsed.observations.single { it.date == LocalDate.parse("2026-11-17") }.status)
        assertEquals(ObservationStatus.MALFORMED,
            parsed.observations.single { it.date == LocalDate.parse("2026-11-18") }.status)
        assertEquals("234-98-567",
            parsed.observations.single { it.date == LocalDate.parse("2026-11-19") }.normalizedResult)
        assertTrue(parsed.issues.isNotEmpty())
    }

    @Test fun refusesUnrecognizedHeadersAndDoesNotInventDateRows() {
        val html = "<table><tr><th>Date</th><th>Result</th></tr><tr><td>16-11-2026</td><td>12365456</td></tr></table>"
        assertFalse(GenericWeeklyMatrixParserAdapter.supports(html, "https://publisher.test/chart"))
        try {
            GenericWeeklyMatrixParserAdapter.parse(html, "https://publisher.test/chart", Market.SRIDEVI, "main", retrievedAt)
            fail("Expected unsupported layout")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("UNSUPPORTED"))
        }
    }
}
