package com.relab.core

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class HtmlChartImporterTest {
    private val url = "https://charts.example/archive"
    private val retrieved = Instant.parse("2026-11-17T10:00:00Z")

    @Test fun extractsValidDatedRowsAndKeepsSourceProvenance() {
        val html = """
            <html><table>
              <tr><th>Date</th><th>Result</th></tr>
              <tr><td>2026-11-09</td><td>277-65-249</td></tr>
              <tr><td>10/11/2026</td><td>578-07-368</td></tr>
              <tr><td>11 Nov 2026</td><td>800-81-137</td></tr>
            </table></html>
        """.trimIndent()
        val report = HtmlChartImporter.import(html, url, Market.SRIDEVI, "main", retrieved)
        assertEquals("Expected 3 dated rows; got ${report.observations.size}. skipped=${report.skippedRows}, issues=${report.issues}, observations=${report.observations.map { Triple(it.date, it.status, it.rawText) }}", 3, report.observations.size)
        assertEquals("charts.example", report.observations.first().sourceId)
        assertEquals(url, report.observations.first().sourceUrl)
        assertEquals(ObservationStatus.PUBLISHED, report.observations.first().status)
        assertEquals("277-65-249", report.observations.first().normalizedResult)
    }

    @Test fun dailyRowsOutsideMarketCalendarAreNotPublished() {
        val html = """
            <table>
              <tr><th>Date</th><th>Result</th></tr>
              <tr><td>2026-11-22</td><td>277-65-249</td></tr>
            </table>
        """.trimIndent()
        val report = HtmlChartImporter.import(html, url, Market.KALYAN, "main", retrieved)

        assertEquals(1, report.observations.size)
        assertEquals(ObservationStatus.OUTSIDE_MARKET_CALENDAR, report.observations.single().status)
        assertNull(report.observations.single().normalizedResult)
        assertEquals("2026-11-22 | 277-65-249", report.observations.single().rawText)
    }

    @Test fun invalidMathIsRetainedAsMalformedNotPublished() {
        val html = """<table><tr><td>2026-11-09</td><td>277-99-249</td></tr></table>"""
        val report = HtmlChartImporter.import(html, url, Market.SRIDEVI, "main", retrieved)
        assertEquals(1, report.observations.size)
        assertEquals(ObservationStatus.MALFORMED, report.observations.single().status)
        assertNull(report.observations.single().normalizedResult)
        assertEquals(1, report.issues.size)
    }

    @Test fun ignoresRowsWithoutExplicitDateAndResult() {
        val html = """<table><tr><td>Date</td><td>Open</td><td>Close</td></tr><tr><td>Today</td><td>Pending</td></tr></table>"""
        val report = HtmlChartImporter.import(html, url, Market.SRIDEVI, "main", retrieved)
        assertTrue(report.observations.isEmpty())
        assertEquals(2, report.skippedRows)
    }

    @Test fun extractsWeeklyPanelChartCellsAndTwoDigitYears() {
        val html = """
            <table>
              <tr><th>Date</th><th>MON</th><th>TUE</th><th>WED</th><th>THU</th><th>FRI</th><th>SAT</th><th>SUN</th></tr>
              <tr>
                <td>16/11/26 to 22/11/26</td>
                <td>577</td><td>99</td><td>667</td>
                <td>115</td><td>73</td><td>229</td>
                <td>367</td><td>69</td><td>289</td>
                <td></td><td></td><td></td><td></td><td></td><td></td>
                <td></td><td></td><td></td><td></td><td></td><td></td>
              </tr>
            </table>
        """.trimIndent()
        val report = HtmlChartImporter.import(html, url, Market.SRIDEVI, "main", retrieved)
        assertEquals(7, report.observations.size)
        assertEquals("2026-11-16", report.observations[0].date.toString())
        assertEquals("577-99-667", report.observations[0].normalizedResult)
        assertEquals("2026-11-17", report.observations[1].date.toString())
        assertEquals("115-73-229", report.observations[1].normalizedResult)
        assertEquals("2026-11-18", report.observations[2].date.toString())
        assertEquals("367-69-289", report.observations[2].normalizedResult)
    }

    @Test fun importsSingleCellPerDayWeeklyMatrixAndKeepsMarkersUnpublished() {
        val html = """
            <table>
              <tr><th>Date</th><th>MON</th><th>TUE</th><th>WED</th><th>THU</th><th>FRI</th><th>SAT</th><th>SUN</th></tr>
              <tr><td>16/11/26 to 22/11/26</td>
                <td>4 6 0 09 2 8 9</td><td>4 5 0 92 2 4 6</td>
                <td>1 8 9 83 6 8 9</td><td>1 5 5 14 6 8 0</td>
                <td>2 9 9 00 4 7 9</td><td>Pending</td><td>********</td>
              </tr>
            </table>
        """.trimIndent()
        val report = HtmlChartImporter.import(html, url, Market.SRIDEVI, "main", retrieved)
        assertEquals(7, report.observations.size)
        assertEquals("460-09-289", report.observations[0].normalizedResult)
        assertEquals("450-92-246", report.observations[1].normalizedResult)
        assertEquals(ObservationStatus.PENDING, report.observations[5].status)
        assertEquals(ObservationStatus.MISSING_FROM_SOURCE, report.observations[6].status)
        assertEquals("********", report.observations[6].rawText)
        assertEquals(2, report.observations.count { it.status != ObservationStatus.PUBLISHED })
    }

    @Test fun extractsKalyanSixDayWeeklyPanelWithoutTreatingSundayAsTradingDay() {
        val html = """
            <table>
              <tr><th>Date</th><th>MON</th><th>TUE</th><th>WED</th><th>THU</th><th>FRI</th><th>SAT</th></tr>
              <tr>
                <td>16/11/26 to 21/11/26</td>
                <td>577</td><td>99</td><td>667</td>
                <td>115</td><td>73</td><td>229</td>
                <td>367</td><td>69</td><td>289</td>
                <td>277</td><td>62</td><td>589</td>
                <td>123</td><td>66</td><td>457</td>
                <td>444</td><td>23</td><td>111</td>
              </tr>
            </table>
        """.trimIndent()
        val report = HtmlChartImporter.import(html, url, Market.KALYAN, "main", retrieved)
        assertEquals(6, report.observations.size)
        assertEquals("2026-11-16", report.observations.first().date.toString())
        assertEquals("2026-11-21", report.observations.last().date.toString())
        assertTrue(report.observations.all { it.date.dayOfWeek != java.time.DayOfWeek.SUNDAY })
    }

    @Test fun extractsMainBazarFiveDayRowsWithoutWeekendDates() {
        val html = """
            <table>
              <tr><th>Date</th><th>MON</th><th>TUE</th><th>WED</th><th>THU</th><th>FRI</th></tr>
              <tr><td>16/11/26 to 20/11/26</td>
                <td>668</td><td>07</td><td>449</td><td>668</td><td>07</td><td>449</td><td>668</td><td>07</td><td>449</td>
                <td>668</td><td>07</td><td>449</td><td>668</td><td>07</td><td>449</td>
              </tr>
            </table>
        """.trimIndent()
        val report = HtmlChartImporter.import(html, url, Market.MAIN_BAZAR, "main", retrieved)
        assertEquals(5, report.observations.size)
        assertEquals("2026-11-16", report.observations.first().date.toString())
        assertEquals("2026-11-20", report.observations.last().date.toString())
        assertTrue(report.observations.all { it.date.dayOfWeek in Market.MAIN_BAZAR.activeDays })
    }

    @Test fun timeBazarSupportsHistoricalSevenDayAndLaterSixDayRows() {
        val sevenDayHtml = """
            <table><tr><td>28/12/20 to 3/1/21</td>
              <td>668</td><td>07</td><td>449</td><td>668</td><td>07</td><td>449</td><td>668</td><td>07</td><td>449</td>
              <td>668</td><td>07</td><td>449</td><td>668</td><td>07</td><td>449</td><td>668</td><td>07</td><td>449</td><td>668</td><td>07</td><td>449</td>
            </tr></table>
        """.trimIndent()
        val sevenDay = HtmlChartImporter.import(sevenDayHtml, url, Market.TIME_BAZAR, "main", retrieved)
        assertEquals(7, sevenDay.observations.size)
        assertEquals("2021-01-03", sevenDay.observations.last().date.toString())

        val modernSevenDayHtml = """
            <table><tr><td>16/11/26 to 22/11/26</td>
              <td>668</td><td>07</td><td>449</td><td>668</td><td>07</td><td>449</td><td>668</td><td>07</td><td>449</td>
              <td>668</td><td>07</td><td>449</td><td>668</td><td>07</td><td>449</td><td>668</td><td>07</td><td>449</td>
              <td>* * *</td><td>**</td><td>* * *</td>
            </tr></table>
        """.trimIndent()
        val modernSevenDay = HtmlChartImporter.import(modernSevenDayHtml, url, Market.TIME_BAZAR, "main", retrieved)
        assertEquals(7, modernSevenDay.observations.size)
        assertEquals("2026-11-22", modernSevenDay.observations.last().date.toString())
        assertEquals(ObservationStatus.MISSING_FROM_SOURCE, modernSevenDay.observations.last().status)
    }

    @Test fun starredWeeklyCellIsPreservedAsMissingNotInventedHolidayOrResult() {
        val html = """
            <table><tr><td>16/11/26 to 22/11/26</td>
              <td>668</td><td>07</td><td>449</td><td>* * *</td><td>**</td><td>* * *</td><td>668</td><td>07</td><td>449</td>
              <td>668</td><td>07</td><td>449</td><td>668</td><td>07</td><td>449</td><td>668</td><td>07</td><td>449</td><td>668</td><td>07</td><td>449</td>
            </tr></table>
        """.trimIndent()
        val report = HtmlChartImporter.import(html, url, Market.SRIDEVI, "main", retrieved)
        assertEquals(7, report.observations.size)
        assertEquals(ObservationStatus.MISSING_FROM_SOURCE, report.observations[1].status)
        assertEquals("* * * | ** | * * *", report.observations[1].rawText)
        assertNull(report.observations[1].normalizedResult)
    }

    @Test fun importsVerifiedFirstRowsFromAllFiveLiveChartLayouts() {
        data class Fixture(
            val market: Market,
            val range: String,
            val cells: List<String>,
            val expectedFirst: String,
            val expectedCount: Int
        )
        val fixtures = listOf(
            Fixture(
                Market.SRIDEVI, "26/2/18 to 4/3/18",
                listOf("200","22","660", "139","39","568", "380","19","450",
                    "779","33","256", "* * *","**","* * *", "180","95","122", "345","20","226"),
                "200-22-660", 7
            ),
            Fixture(
                Market.SRIDEVI_NIGHT, "28/1/19 to 3/2/19",
                listOf("130","45","230", "126","97","223", "380","18","170",
                    "379","90","118", "114","65","799", "138","29","199", "224","80","280"),
                "130-45-230", 7
            ),
            Fixture(
                Market.KALYAN, "31/12/12 to 5/1/13",
                listOf("799","51","155", "345","29","360", "339","50","569",
                    "137","18","468", "369","84","680", "335","17","359"),
                "799-51-155", 6
            ),
            Fixture(
                Market.TIME_BAZAR, "30/10/17 to 5/11/17",
                listOf("579","12","147", "467","78","170", "269","79","360",
                    "469","90","145", "235","02","147", "478","96","169", "778","27","133"),
                "579-12-147", 7
            ),
            Fixture(
                Market.MAIN_BAZAR, "15/7/19 to 19/7/19",
                listOf("469","97","377", "570","23","670", "237","22","589",
                    "136","05","177", "127","02","138"),
                "469-97-377", 5
            )
        )
        fixtures.forEach { fixture ->
            val cells = fixture.cells.joinToString("") { "<td>$it</td>" }
            val html = "<table><tr><td>${fixture.range}</td>$cells</tr></table>"
            val report = HtmlChartImporter.import(html, url, fixture.market, "main", retrieved)
            assertEquals("${fixture.market} row count", fixture.expectedCount, report.observations.size)
            assertEquals("${fixture.market} first result", fixture.expectedFirst, report.observations.first().normalizedResult)
            assertTrue("${fixture.market} should retain source URL", report.observations.all { it.sourceUrl == url })
            if (fixture.market == Market.SRIDEVI) {
                assertEquals(ObservationStatus.MISSING_FROM_SOURCE, report.observations[4].status)
                assertEquals("* * * | ** | * * *", report.observations[4].rawText)
            }
        }
    }

    @Test fun preservesExactRepeatedDailyRowsInsteadOfSilentlyDeduplicatingThem() {
        val html = """
            <table>
              <tr><th>Date</th><th>Result</th></tr>
              <tr><td>2026-11-09</td><td>277-65-249</td></tr>
              <tr><td>2026-11-09</td><td>277-65-249</td></tr>
            </table>
        """.trimIndent()
        val report = HtmlChartImporter.import(html, url, Market.SRIDEVI, "main", retrieved)

        assertEquals(2, report.observations.size)
        assertEquals(listOf("277-65-249", "277-65-249"), report.observations.map { it.normalizedResult })
        assertEquals(1, report.observations.map { it.date }.distinct().size)
    }

    @Test fun rejectsNonHttpSchemes() {
        try {
            HtmlChartImporter.import("<table/>", "file:///tmp/chart.html", Market.SRIDEVI, "main", retrieved)
            fail("Expected URL scheme rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("HTTP"))
        }
    }
    @Test fun malformedWeeklyEndDateIsSkippedWithoutShiftingSessionRows() {
        val html = """
            <table>
              <tr><th>Date</th><th>MON</th><th>TUE</th><th>WED</th><th>THU</th><th>FRI</th><th>SAT</th><th>SUN</th></tr>
              <tr><td>18/2/19 to 24/2/16</td>
                <td>277</td><td>65</td><td>249</td><td>277</td><td>65</td><td>249</td>
                <td>277</td><td>65</td><td>249</td><td>277</td><td>65</td><td>249</td>
                <td>277</td><td>65</td><td>249</td><td>277</td><td>65</td><td>249</td>
                <td>277</td><td>65</td><td>249</td>
              </tr>
            </table>
        """.trimIndent()
        val report = HtmlChartImporter.import(html, url, Market.SRIDEVI_NIGHT, "night", retrieved)
        assertTrue(report.observations.isEmpty())
        assertEquals(1, report.skippedRows)
        assertTrue(report.issues.any { it.contains("date range and weekly cell groups do not match") })
    }

}
