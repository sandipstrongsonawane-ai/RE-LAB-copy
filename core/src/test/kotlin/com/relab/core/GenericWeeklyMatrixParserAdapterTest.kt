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

    @Test fun treatsAllStarPlaceholdersAsMissingRatherThanMalformed() {
        val html = """
            <table>
              <tr><th>Date Range</th><th>Mon</th><th>Tue</th><th>Wed</th><th>Thu</th><th>Fri</th><th>Sat</th></tr>
              <tr><td>16/11/2026 to 21/11/2026</td><td>********</td><td>**</td><td>—</td><td>123-65-456</td><td>—</td><td>—</td></tr>
            </table>
        """.trimIndent()
        val parsed = GenericWeeklyMatrixParserAdapter.parse(
            html, "https://publisher.test/kalyan", Market.KALYAN, "main", retrievedAt
        )
        assertEquals(6, parsed.observations.size)
        assertEquals(ObservationStatus.MISSING_FROM_SOURCE, parsed.observations[0].status)
        assertEquals(ObservationStatus.MISSING_FROM_SOURCE, parsed.observations[1].status)
        assertEquals(ObservationStatus.MISSING_FROM_SOURCE, parsed.observations[2].status)
        assertEquals(ObservationStatus.PUBLISHED, parsed.observations[3].status)
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



    @Test fun parsesMondayAnchoredMatkaFlashPanelArchiveRows() {
        val html = """
            <table>
              <tr><th>Week</th><th>Mon</th><th>Tue</th><th>Wed</th><th>Thu</th><th>Fri</th><th>Sat</th><th>Sun</th></tr>
              <tr><td>02 Nov 2026</td><td>133-75-690</td><td>230-51-489</td><td>580-37-566</td><td>—</td><td>447-51-146</td><td>Holiday</td><td>—</td></tr>
            </table>
        """.trimIndent()
        val url = "https://matkaflash.com/milan-day/panel-chart"

        assertTrue(GenericWeeklyMatrixParserAdapter.supports(html, url))
        val parsed = ChartParserRegistry.parse(html, url, Market.MILAN_DAY, "main", retrievedAt)

        assertEquals("generic-weekly-matrix-v1", parsed.parserId)
        assertEquals(7, parsed.observations.size)
        assertEquals("133-75-690", parsed.observations.single {
            it.date == LocalDate.parse("2026-11-02")
        }.normalizedResult)
        assertEquals("230-51-489", parsed.observations.single {
            it.date == LocalDate.parse("2026-11-03")
        }.normalizedResult)
        assertEquals(ObservationStatus.MISSING_FROM_SOURCE, parsed.observations.single {
            it.date == LocalDate.parse("2026-11-05")
        }.status)
        assertEquals(ObservationStatus.HOLIDAY_CLOSED, parsed.observations.single {
            it.date == LocalDate.parse("2026-11-07")
        }.status)
        assertEquals("matkaflash.com", parsed.observations.first().sourceId)
    }


    @Test fun parsesKalyanSundayRowsAndKeepsPartialOpenResultsPending() {
        val html = """
            <table>
              <tr><th>Week</th><th>Mon</th><th>Tue</th><th>Wed</th><th>Thu</th><th>Fri</th><th>Sat</th><th>Sun</th></tr>
              <tr><td>09 Nov 2026</td><td>390-24-257</td><td>390-24-257</td><td>256-3*-***</td><td>390-24-257</td><td>390-24-257</td><td>Holiday</td><td>390-24-257</td></tr>
            </table>
        """.trimIndent()
        val url = "https://matkaflash.com/kalyan/panel-chart"
        val parsed = ChartParserRegistry.parse(html, url, Market.KALYAN, "main", retrievedAt)

        assertEquals(7, parsed.observations.size)
        val partial = parsed.observations.single { it.date == LocalDate.parse("2026-11-11") }
        assertEquals(ObservationStatus.PENDING, partial.status)
        assertNull(partial.normalizedResult)
        val saturday = parsed.observations.single { it.date == LocalDate.parse("2026-11-14") }
        assertEquals(ObservationStatus.HOLIDAY_CLOSED, saturday.status)
        val sunday = parsed.observations.single { it.date == LocalDate.parse("2026-11-15") }
        assertEquals(ObservationStatus.OUTSIDE_MARKET_CALENDAR, sunday.status)
        assertNull(sunday.normalizedResult)
        assertTrue(sunday.rawText.contains("390-24-257"))
    }

    @Test fun parsesVerifiedLivesattamatkaNetPanelRecordMatrix() {
        val html = """
            <table>
              <tr><th>Date</th><th>Mon</th><th>Tue</th><th>Wed</th><th>Thu</th><th>Fri</th><th>Sat</th><th>Sun</th></tr>
              <tr><td>16/11 to 22/11 2026</td><td>5 7 7 99 6 6 7</td><td>1 1 5 73 2 2 9</td><td>4 7 9 07 5 6 6</td><td></td><td></td><td></td><td></td></tr>
            </table>
        """.trimIndent()
        val url = "https://livesattamatka.net/panna-chart/sridevi"
        val parsed = ChartParserRegistry.parse(html, url, Market.SRIDEVI, "main", retrievedAt)
        assertEquals("generic-weekly-matrix-v1", parsed.parserId)
        assertEquals(7, parsed.observations.size)
        assertEquals("577-99-667", parsed.observations.single {
            it.date == LocalDate.parse("2026-11-16")
        }.normalizedResult)
        assertEquals("115-73-229", parsed.observations.single {
            it.date == LocalDate.parse("2026-11-17")
        }.normalizedResult)
        assertEquals("livesattamatka.net", parsed.observations.first().sourceId)
        assertEquals(ObservationStatus.MISSING_FROM_SOURCE, parsed.observations.single {
            it.date == LocalDate.parse("2026-11-19")
        }.status)
    }

    @Test fun preservesRepeatedDateRowsSoConflictingPublisherValuesAreNotSilentlyDiscarded() {
        val html = """
            <table>
              <tr><th>Date</th><th>Mon</th><th>Tue</th><th>Wed</th><th>Thu</th><th>Fri</th><th>Sat</th><th>Sun</th></tr>
              <tr><td>16/11/2026 to 22/11/2026</td><td>67924455</td><td>23498567</td><td>34524789</td><td>45657890</td><td>56783012</td><td>67813012</td><td>78943012</td></tr>
              <tr><td>16/11/2026 to 22/11/2026</td><td>23505140</td><td>23498567</td><td>34524789</td><td>45657890</td><td>56783012</td><td>67813012</td><td>78943012</td></tr>
            </table>
        """.trimIndent()
        val parsed = GenericWeeklyMatrixParserAdapter.parse(
            html, "https://publisher.example/chart", Market.SRIDEVI, "main", retrievedAt
        )

        val mondayRows = parsed.observations.filter { it.date == LocalDate.parse("2026-11-16") }
        assertEquals(2, mondayRows.size)
        assertEquals(setOf("679-24-455", "235-05-140"), mondayRows.mapNotNull { it.normalizedResult }.toSet())
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


    @Test fun parsesVerifiedLivesattamatkaAdditionalMarketsWithoutCombiningTheirIdentities() {
        fun parse(url: String, market: Market, range: String, cells: List<String>) =
            GenericWeeklyMatrixParserAdapter.parse(
                """<table><tr><th>Date</th><th>Mon</th><th>Tue</th><th>Wed</th><th>Thu</th><th>Fri</th><th>Sat</th><th>Sun</th></tr>
                   <tr><td>$range</td>${cells.joinToString("") { "<td>$it</td>" }}</tr></table>""",
                url, market, "main", retrievedAt
            )

        val timeBazar = parse(
            "https://livesattamatka.net/panna-chart/time-bazar", Market.TIME_BAZAR,
            "02/11 to 08/11 2026",
            listOf("2 5 8 51 2 3 6", "2 4 9 50 2 8 0", "4 6 9 99 3 7 9",
                "1 3 9 31 5 7 9", "", "", "")
        )
        assertEquals(7, timeBazar.observations.size)
        assertEquals("258-51-236", timeBazar.observations.first().normalizedResult)
        assertEquals(ObservationStatus.MISSING_FROM_SOURCE, timeBazar.observations.last().status)
        assertTrue(timeBazar.observations.all { it.sourceId == "livesattamatka.net" })

        val mainBazar = parse(
            "https://livesattamatka.net/panna-chart/main-bazar", Market.MAIN_BAZAR,
            "02/11 to 08/11 2026",
            listOf("1 1 0 29 2 7 0", "2 5 0 70 1 2 7", "1 3 5 96 2 6 8",
                "5 5 7 73 7 8 8", "", "", "")
        )
        assertEquals(7, mainBazar.observations.size)
        assertEquals("110-29-270", mainBazar.observations.first().normalizedResult)
        assertEquals(ObservationStatus.MISSING_FROM_SOURCE,
            mainBazar.observations.single { it.date == LocalDate.parse("2026-11-07") }.status)

        val mainNight = parse(
            "https://livesattamatka.net/panna-chart/main-bazar-night", Market.MAIN_BAZAR_NIGHT,
            "09/11 to 15/11 2026",
            listOf("5 7 8 07 3 4 0", "6 8 8 26 1 7 8", "3 5 5 37 7 0 0",
                "2 5 0 77 1 2 4", "1 4 0 59 1 8 0", "1 2 8 10 5 6 9", "")
        )
        assertEquals(7, mainNight.observations.size)
        assertEquals("578-07-340", mainNight.observations.first().normalizedResult)
        assertEquals(ObservationStatus.MISSING_FROM_SOURCE, mainNight.observations.last().status)

        val timeBazarDay = parse(
            "https://livesattamatka.net/panna-chart/time-bazar-day", Market.TIME_BAZAR_DAY,
            "09/11 to 15/11 2026",
            listOf("4 6 0 00 5 5 0", "2 8 0 06 1 7 8", "3 5 0 86 4 6 6",
                "2 4 4 06 5 5 6", "8 9 0 73 1 3 9", "6 6 0 20 5 6 9", "")
        )
        assertEquals("460-00-550", timeBazarDay.observations.first().normalizedResult)
        assertEquals(ObservationStatus.MISSING_FROM_SOURCE, timeBazarDay.observations.last().status)

        val newTimeBazar = parse(
            "https://livesattamatka.net/panna-chart/new-time-bazar", Market.NEW_TIME_BAZAR,
            "02/11 to 08/11 2026",
            listOf("2 3 0 59 1 9 9", "1 5 8 45 3 5 7", "3 7 8 84 1 1 2",
                "1 6 7 42 2 5 5", "3 4 5 22 1 2 9", "1 5 6 24 4 0 0", "")
        )
        assertEquals("230-59-199", newTimeBazar.observations.first().normalizedResult)
        assertEquals(ObservationStatus.OUTSIDE_MARKET_CALENDAR, newTimeBazar.observations.last().status)
        assertNotEquals(timeBazar.observations.first().market, newTimeBazar.observations.first().market)
    }


    @Test fun parsesFiveMoreVerifiedLivesattamatkaMarketArchives() {
        fun parse(slug: String, market: Market, range: String, cells: List<String>) =
            GenericWeeklyMatrixParserAdapter.parse(
                """<table><tr><th>Date</th><th>Mon</th><th>Tue</th><th>Wed</th><th>Thu</th><th>Fri</th><th>Sat</th><th>Sun</th></tr>
                   <tr><td>$range</td>${cells.joinToString("") { "<td>$it</td>" }}</tr></table>""",
                "https://livesattamatka.net/panna-chart/$slug", market, "main", retrievedAt
            )

        val cb = parse("cb", Market.CB, "09/11 to 15/11 2026",
            listOf("3 7 0 02 5 7 0", "3 4 7 48 2 6 0", "7 7 0 46 2 5 9",
                "1 4 5 07 3 4 0", "1 7 8 60 2 3 5", "2 4 8 40 6 7 7", ""))
        assertEquals("370-02-570", cb.observations.first().normalizedResult)
        assertEquals(7, cb.observations.size)
        assertEquals(ObservationStatus.MISSING_FROM_SOURCE, cb.observations.last().status)

        val mainRatan = parse("main-ratan", Market.MAIN_RATAN, "14/09 to 20/09 2026",
            listOf("", "", "1 3 9 36 3 4 9", "6 0 0 65 3 5 7", "", "", ""))
        assertEquals("139-36-349", mainRatan.observations.single {
            it.date == LocalDate.parse("2026-09-16")
        }.normalizedResult)
        assertEquals(ObservationStatus.MISSING_FROM_SOURCE,
            mainRatan.observations.single { it.date == LocalDate.parse("2026-09-19") }.status)

        val sundayBazar = parse("sunday-bazar", Market.SUNDAY_BAZAR, "09/11 to 15/11 2026",
            listOf("1 3 7 16 4 6 6", "5 8 9 25 4 5 6", "1 8 0 94 2 3 9",
                "3 6 8 71 6 6 9", "8 8 0 69 5 6 8", "3 8 0 14 1 5 8", ""))
        assertEquals("137-16-466", sundayBazar.observations.first().normalizedResult)
        assertEquals(ObservationStatus.MISSING_FROM_SOURCE, sundayBazar.observations.last().status)

        val geeta = parse("geeta-morning", Market.GEETA_MORNING, "09/11 to 15/11 2026",
            listOf("2 2 6 08 4 5 9", "1 3 7 16 7 9 0", "4 7 8 93 2 4 7",
                "2 6 7 59 5 7 7", "3 5 9 73 1 2 0", "3 4 5 26 7 9 0", ""))
        assertEquals("226-08-459", geeta.observations.first().normalizedResult)
        assertEquals(ObservationStatus.MISSING_FROM_SOURCE, geeta.observations.last().status)

        val mainMorning = parse("main-bazar-morning", Market.MAIN_BAZAR_MORNING, "12/10 to 18/10 2026",
            listOf("5 9 0 44 3 5 6", "1 6 9 64 1 4 9", "2 2 0 48 1 7 0",
                "5 8 9 22 1 5 6", "2 8 9 90 3 8 9", "2 4 0 60 3 8 9", "5 7 0 26 3 5 8"))
        assertEquals("590-44-356", mainMorning.observations.first().normalizedResult)
        assertEquals(7, mainMorning.observations.size)
        assertNotEquals(Market.MAIN_BAZAR, mainMorning.observations.first().market)
        assertTrue((cb.observations + mainRatan.observations + sundayBazar.observations +
            geeta.observations + mainMorning.observations).all {
            it.sourceId == "livesattamatka.net"
        })
    }

}
