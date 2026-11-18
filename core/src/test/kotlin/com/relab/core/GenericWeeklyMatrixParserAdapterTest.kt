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
    @Test fun parsesHpOfficialWeeklyMatrixWithCompactDatesAndSpacedDigits() {
        val html = """
            <table>
              <tr><th>DATE</th><th>MON</th><th>TUE</th><th>WED</th><th>THU</th><th>FRI</th><th>SAT</th><th>SUN</th></tr>
              <tr><td>29/12/25TO04/01/26</td><td>4 6 0 09 2 8 9</td><td>4 5 0 92 2 4 6</td><td>1 8 9 83 6 8 9</td><td>1 5 5 14 6 8 0</td><td>2 9 9 00 4 7 9</td><td>2 5 0 78 1 7 0</td><td>1 3 4 89 4 5 0</td></tr>
            </table>
        """.trimIndent()
        val url = "https://hpofficial.in/sridevi-panel-chart/"
        assertTrue(GenericWeeklyMatrixParserAdapter.supports(html, url))
        val parsed = ChartParserRegistry.parse(html, url, Market.SRIDEVI, "main", retrievedAt)
        assertEquals(7, parsed.observations.size)
        val monday = parsed.observations.single { it.date == LocalDate.parse("2025-12-29") }
        assertEquals("460-09-289", monday.normalizedResult)
        assertEquals("hpofficial.in", monday.sourceId)
        assertTrue(parsed.observations.all { it.status == ObservationStatus.PUBLISHED })
    }

    @Test fun parsesLiveSattaWeeklyMatrixWhereYearAppearsOnlyAtRangeEnd() {
        val html = """
            <table>
              <tr><th>Date</th><th>Mon</th><th>Tue</th><th>Wed</th><th>Thu</th><th>Fri</th><th>Sat</th><th>Sun</th></tr>
              <tr><td>16/11 to 22/11 2026</td><td>5 7 7 99 6 6 7</td><td>1 1 5 73 2 2 9</td><td></td><td></td><td></td><td></td><td></td></tr>
              <tr><td>09/11 to 15/11 2026</td><td>2 7 7 65 2 4 9</td><td>5 7 8 07 3 6 8</td><td>8 0 0 81 1 3 7</td><td>5 6 6 73 1 4 8</td><td>2 8 8 82 6 7 9</td><td>2 7 9 81 2 3 6</td><td>6 9 9 41 5 6 0</td></tr>
            </table>
        """.trimIndent()
        val url = "https://livesattamatka.org/panel-record/sridevi"
        assertTrue(GenericWeeklyMatrixParserAdapter.supports(html, url))
        val parsed = ChartParserRegistry.parse(html, url, Market.SRIDEVI, "main", retrievedAt)
        assertEquals(14, parsed.observations.size)
        val monday = parsed.observations.single { it.date == LocalDate.parse("2026-11-16") }
        assertEquals("577-99-667", monday.normalizedResult)
        assertEquals(ObservationStatus.MISSING_FROM_SOURCE,
            parsed.observations.single { it.date == LocalDate.parse("2026-11-18") }.status)
        assertEquals("livesattamatka.org", monday.sourceId)
    }


    @Test fun preservesRepeatedDateRowsSoConflictingPublisherValuesAreNotSilentlyDiscarded() {
        val html = """
            <table>
              <tr><th>Date</th><th>Mon</th><th>Tue</th><th>Wed</th><th>Thu</th><th>Fri</th><th>Sat</th><th>Sun</th></tr>
              <tr><td>16/11/2026 to 22/11/2026</td><td>12365456</td><td>23498567</td><td>34524789</td><td>45657890</td><td>56783012</td><td>67813012</td><td>78943012</td></tr>
              <tr><td>16/11/2026 to 22/11/2026</td><td>99965456</td><td>23498567</td><td>34524789</td><td>45657890</td><td>56783012</td><td>67813012</td><td>78943012</td></tr>
            </table>
        """.trimIndent()
        val parsed = GenericWeeklyMatrixParserAdapter.parse(
            html, "https://publisher.example/chart", Market.SRIDEVI, "main", retrievedAt
        )

        val mondayRows = parsed.observations.filter { it.date == LocalDate.parse("2026-11-16") }
        assertEquals(2, mondayRows.size)
        assertEquals(setOf("123-65-456", "999-65-456"), mondayRows.mapNotNull { it.normalizedResult }.toSet())
        assertEquals(14, parsed.observations.size)
    }

    @Test fun supportsWeekdayColumnsThatWrapAcrossSundayToMonday() {
        val html = """
            <table>
              <tr><th>Week</th><th>Sunday</th><th>Monday</th><th>Tuesday</th><th>Wednesday</th><th>Thursday</th><th>Friday</th><th>Saturday</th></tr>
              <tr><td>15/11/2026 to 21/11/2026</td><td>12365456</td><td>23498567</td><td>34524789</td><td>45657890</td><td>56783012</td><td>67813012</td><td>78943012</td></tr>
            </table>
        """.trimIndent()
        val url = "https://publisher.example/history/sridevi"
        assertTrue(GenericWeeklyMatrixParserAdapter.supports(html, url))
        val parsed = GenericWeeklyMatrixParserAdapter.parse(
            html, url, Market.SRIDEVI, "main", retrievedAt
        )
        assertEquals(7, parsed.observations.size)
        assertEquals("123-65-456", parsed.observations.single {
            it.date == LocalDate.parse("2026-11-15")
        }.normalizedResult)
        assertEquals("234-98-567", parsed.observations.single {
            it.date == LocalDate.parse("2026-11-16")
        }.normalizedResult)
    }

    @Test fun rejectsWrappedWeekdayHeaderWhenDateRangeStartsOnDifferentWeekday() {
        val html = """
            <table>
              <tr><th>Week</th><th>Sunday</th><th>Monday</th><th>Tuesday</th><th>Wednesday</th><th>Thursday</th><th>Friday</th><th>Saturday</th></tr>
              <tr><td>16/11/2026 to 22/11/2026</td><td>12365456</td><td>23498567</td><td>34524789</td><td>45657890</td><td>56783012</td><td>67813012</td><td>78943012</td></tr>
            </table>
        """.trimIndent()
        assertTrue(GenericWeeklyMatrixParserAdapter.supports(html, "https://publisher.example/chart"))
        val parsed = GenericWeeklyMatrixParserAdapter.parse(
            html, "https://publisher.example/chart", Market.SRIDEVI, "main", retrievedAt
        )
        assertTrue(parsed.observations.isEmpty())
        assertEquals(1, parsed.skippedRows)
    }

}
