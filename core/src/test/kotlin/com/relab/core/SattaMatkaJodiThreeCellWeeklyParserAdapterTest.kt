package com.relab.core

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SattaMatkaJodiThreeCellWeeklyParserAdapterTest {
    private val at = Instant.parse("2026-11-19T03:00:00Z")

    @Test fun recognizesAndParsesSevenDaySrideviNightThreeCellMatrix() {
        val url = "https://sattamatkajodi.com/record/sridevi-night-satta-penal-chart.php"
        val html = """
            <table>
              <tr><th>Date</th><th>MON</th><th>TUE</th><th>WED</th><th>THU</th><th>FRI</th><th>SAT</th><th>SUN</th></tr>
              <tr><td>28/1/19 to 3/2/19</td>
                <td>277</td><td>65</td><td>249</td><td>277</td><td>65</td><td>249</td>
                <td>277</td><td>65</td><td>249</td><td>277</td><td>65</td><td>249</td>
                <td>277</td><td>65</td><td>249</td><td>277</td><td>65</td><td>249</td>
                <td>* * *</td><td>**</td><td>* * *</td>
              </tr>
            </table>
        """.trimIndent()
        assertTrue(SattaMatkaJodiThreeCellWeeklyParserAdapter.supports(html, url))
        val parsed = ChartParserRegistry.parse(html, url, Market.SRIDEVI_NIGHT, "night", at)
        assertEquals("sattamatkajodi-three-cell-weekly-v1", parsed.parserId)
        assertEquals(7, parsed.observations.size)
        assertEquals("277-65-249", parsed.observations.first().normalizedResult)
        assertEquals(ObservationStatus.MISSING_FROM_SOURCE, parsed.observations.last().status)
        assertTrue(parsed.observations.all { it.market == Market.SRIDEVI_NIGHT && it.sessionId == "night" })
    }

    @Test fun recognizesSixDayKalyanThreeCellMatrix() {
        val url = "https://sattamatkajodi.com/record/kalyan-penal-chart.php"
        val html = """
            <table>
              <tr><th>Date</th><th>MON</th><th>TUE</th><th>WED</th><th>THU</th><th>FRI</th><th>SAT</th></tr>
              <tr><td>31/12/12 to 5/1/13</td>
                <td>277</td><td>65</td><td>249</td><td>277</td><td>65</td><td>249</td>
                <td>277</td><td>65</td><td>249</td><td>277</td><td>65</td><td>249</td>
                <td>277</td><td>65</td><td>249</td><td>277</td><td>65</td><td>249</td>
              </tr>
            </table>
        """.trimIndent()
        assertTrue(SattaMatkaJodiThreeCellWeeklyParserAdapter.supports(html, url))
        val parsed = ChartParserRegistry.parse(html, url, Market.KALYAN, "main", at)
        assertEquals(6, parsed.observations.size)
        assertTrue(parsed.observations.all { it.market == Market.KALYAN && it.sessionId == "main" && it.status == ObservationStatus.PUBLISHED })
    }

    @Test fun doesNotClaimUnrelatedHostsOrSingleCellWeeklyMatrices() {
        val html = """
            <table><tr><th>Date</th><th>MON</th><th>TUE</th><th>WED</th><th>THU</th><th>FRI</th><th>SAT</th></tr>
              <tr><td>31/12/12 to 5/1/13</td><td>779-51-137</td><td>155-29-368</td><td>234-96-349</td><td>455-68-233</td><td>267-99-678</td><td>880-67-777</td></tr>
            </table>
        """.trimIndent()
        assertFalse(SattaMatkaJodiThreeCellWeeklyParserAdapter.supports(html, "https://other.example/chart"))
        assertFalse(SattaMatkaJodiThreeCellWeeklyParserAdapter.supports(html, "https://sattamatkajodi.com/record/kalyan-penal-chart.php"))
    }
}
