package com.relab.core

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class MarketSourceRefreshEngineTest {
    private val sridevi = MarketSourceCatalog.enabledFor(Market.SRIDEVI).take(2)
    private val html = """<table><tr><td>2026-11-16</td><td>277-65-249</td></tr></table>"""

    @Test fun refreshesEachRegisteredPublisherAndKeepsSourceProvenance() {
        val report = MarketSourceRefreshEngine.refresh(
            sridevi,
            fetch = { FetchedChartPage(html, it.url) },
            retrievedAt = { Instant.parse("2026-11-18T03:00:00Z") }
        )
        assertEquals(2, report.successfulSources)
        assertEquals(0, report.failedSources)
        assertEquals(2, report.observations.size)
        assertEquals(setOf("hpofficial", "livesattamatka"), report.observations.map { it.sourceId }.toSet())
        assertTrue(report.observations.all { it.market == Market.SRIDEVI && it.sessionId == "main" })
    }

    @Test fun multipleUrlsFromOnePublisherKeepOneConfiguredEvidenceIdentity() {
        val pages = listOf(
            MarketSource(Market.SRIDEVI, "main", "publisher-a", "https://one.example/chart", "panel"),
            MarketSource(Market.SRIDEVI, "main", "publisher-a", "https://two.example/history", "history")
        )
        val report = MarketSourceRefreshEngine.refresh(pages, fetch = { FetchedChartPage(html, it.url) })
        assertEquals(2, report.observations.size)
        assertEquals(setOf("publisher-a"), report.observations.map { it.sourceId }.toSet())
        assertEquals(setOf("https://one.example/chart", "https://two.example/history"), report.observations.map { it.sourceUrl }.toSet())
    }

    @Test fun exactDuplicateUrlsAreFetchedAndParsedOncePerMarketSession() {
        val duplicateSources = listOf(
            MarketSource(Market.SRIDEVI, "main", "publisher-a", "https://same.example/chart", "panel"),
            MarketSource(Market.SRIDEVI, "main", "publisher-b", "https://same.example/chart", "panel")
        )
        var fetchCount = 0
        var parseTimestampCount = 0
        val report = MarketSourceRefreshEngine.refresh(
            duplicateSources,
            fetch = {
                fetchCount++
                FetchedChartPage(html, it.url)
            },
            retrievedAt = {
                parseTimestampCount++
                Instant.parse("2026-11-18T03:00:00Z")
            }
        )
        assertEquals(1, fetchCount)
        assertEquals(1, parseTimestampCount)
        assertEquals(2, report.successfulSources)
        assertEquals(2, report.observations.size)
        assertEquals(listOf("publisher-a", "publisher-b"), report.outcomes.map { it.source.sourceId })
        assertEquals(setOf("publisher-a", "publisher-b"), report.observations.map { it.sourceId }.toSet())
        assertTrue(report.observations.all { it.sourceUrl == "https://same.example/chart" })
    }

    @Test fun duplicateUrlWithDifferentMarketSessionsIsParsedIndependently() {
        val registrations = listOf(
            MarketSource(Market.SRIDEVI, "main", "publisher-a", "https://same.example/chart", "panel"),
            MarketSource(Market.KALYAN, "main", "publisher-b", "https://same.example/chart", "panel")
        )
        var fetchCount = 0
        var parseCount = 0
        val report = MarketSourceRefreshEngine.refresh(
            registrations,
            fetch = {
                fetchCount++
                FetchedChartPage(html, it.url)
            },
            retrievedAt = {
                parseCount++
                Instant.parse("2026-11-18T03:00:00Z")
            }
        )
        assertEquals(1, fetchCount)
        assertEquals(2, parseCount)
        assertEquals(2, report.successfulSources)
        assertEquals(setOf(Market.SRIDEVI, Market.KALYAN), report.observations.map { it.market }.toSet())
    }

    @Test fun disabledRegistrationDoesNotShareFetchWithEnabledDuplicateUrl() {
        val registrations = listOf(
            MarketSource(Market.SRIDEVI, "main", "publisher-a", "https://same.example/chart", "panel", enabled = true),
            MarketSource(Market.SRIDEVI, "main", "publisher-b", "https://same.example/chart", "panel", enabled = false)
        )
        var fetchCount = 0
        val report = MarketSourceRefreshEngine.refresh(
            registrations,
            fetch = {
                fetchCount++
                FetchedChartPage(html, it.url)
            }
        )
        assertEquals(1, fetchCount)
        assertEquals(1, report.successfulSources)
        assertEquals(1, report.failedSources)
        assertEquals("publisher-a", report.outcomes[0].source.sourceId)
        assertTrue(report.outcomes[1].errorMessage.orEmpty().contains("disabled"))
    }

    @Test fun onePublisherFailureDoesNotStopOtherSources() {
        val failedSource = sridevi.first()
        val report = MarketSourceRefreshEngine.refresh(
            sridevi,
            fetch = {
                if (it == failedSource) error("temporary network failure")
                FetchedChartPage(html, it.url)
            }
        )
        assertEquals(2, report.outcomes.size)
        assertEquals(1, report.failedSources)
        assertEquals(1, report.successfulSources)
        assertEquals("temporary network failure", report.outcomes.first().errorMessage)
        assertEquals(1, report.observations.size)
    }

    @Test fun disabledSourcesAreReportedWithoutFetching() {
        val disabled = MarketSourceCatalog.sources.single { it.sourceId == "kalyanchart" }
        var fetched = false
        val report = MarketSourceRefreshEngine.refresh(
            listOf(disabled),
            fetch = {
                fetched = true
                FetchedChartPage(html, it.url)
            }
        )
        assertFalse(fetched)
        assertEquals(1, report.failedSources)
        assertTrue(report.outcomes.single().errorMessage.orEmpty().contains("disabled"))
    }

    @Test fun unsupportedLayoutIsReportedAsFailureAndDoesNotCreateChartRows() {
        val report = MarketSourceRefreshEngine.refresh(
            sridevi.take(1),
            fetch = { FetchedChartPage("<html><body><p>Welcome, no chart table here</p></body></html>", it.url) }
        )
        assertEquals(1, report.failedSources)
        assertEquals(0, report.successfulSources)
        assertTrue(report.observations.isEmpty())
        assertTrue(report.outcomes.single().errorMessage.orEmpty().contains("UNSUPPORTED"))
    }

    @Test fun unrelatedTableWithNoRecognizedDatedResultsIsNotReportedAsSuccessful() {
        val report = MarketSourceRefreshEngine.refresh(
            sridevi.take(1),
            fetch = { FetchedChartPage("<table><tr><th>News</th></tr><tr><td>Welcome</td></tr></table>", it.url) }
        )
        assertEquals(1, report.failedSources)
        assertTrue(report.observations.isEmpty())
        assertTrue(report.outcomes.single().errorMessage.orEmpty().contains("no recognized dated chart rows"))
    }

    @Test fun rejectsInsecureRedirectTargets() {
        val report = MarketSourceRefreshEngine.refresh(
            sridevi.take(1),
            fetch = { FetchedChartPage(html, "http://insecure.example/chart") }
        )
        assertEquals(1, report.failedSources)
        assertTrue(report.observations.isEmpty())
        assertTrue(report.outcomes.single().errorMessage.orEmpty().contains("HTTPS"))
    }

    @Test fun rejectsCrossPublisherRedirectsInsteadOfMisattributingTheirRows() {
        val report = MarketSourceRefreshEngine.refresh(
            sridevi.take(1),
            fetch = { FetchedChartPage(html, "https://unrelated.example/copied-chart") }
        )
        assertEquals(1, report.failedSources)
        assertTrue(report.observations.isEmpty())
        assertTrue(report.outcomes.single().errorMessage.orEmpty().contains("Redirect changed publisher host"))
    }

    @Test fun allowsCanonicalWwwRedirectWithinTheSamePublisher() {
        val source = MarketSource(Market.SRIDEVI, "main", "publisher-a", "https://www.example.com/chart", "panel")
        val report = MarketSourceRefreshEngine.refresh(
            listOf(source),
            fetch = { FetchedChartPage(html, "https://example.com/history") }
        )
        assertEquals(1, report.successfulSources)
        assertEquals(1, report.observations.size)
        assertEquals("publisher-a", report.observations.single().sourceId)
    }
    @Test fun rawHtmlSnapshotIsRetainedWithRetrievalTimeAndHashEvenWhenParserRejectsPage() {
        val unsupportedHtml = "<html><body>unrecognized chart markup</body></html>"
        val capturedAt = Instant.parse("2026-11-18T03:00:00Z")
        val report = MarketSourceRefreshEngine.refresh(
            sridevi.take(1),
            fetch = { FetchedChartPage(unsupportedHtml, it.url) },
            retrievedAt = { capturedAt }
        )
        val outcome = report.outcomes.single()
        val snapshot = outcome.snapshots.single()
        assertTrue(outcome.errorMessage.orEmpty().contains("UNSUPPORTED"))
        assertEquals(unsupportedHtml, snapshot.html)
        assertEquals(capturedAt, snapshot.retrievedAt)
        assertEquals(outcome.source.sourceId, snapshot.sourceId)
        assertEquals(outcome.source.market, snapshot.market)
        assertEquals(outcome.source.sessionId, snapshot.sessionId)
        assertEquals(outcome.source.url, snapshot.requestedUrl)
        assertEquals(unsupportedHtml.toByteArray(Charsets.UTF_8).size, snapshot.utf8Bytes)
        assertEquals(64, snapshot.sha256.length)
    }

    @Test fun parsesSrideviNightThreeCellWeeklyMatrixWithNightSessionProvenance() {
        val source = MarketSourceCatalog.sources.single {
            it.sourceId == "sattamatkajodi" && it.market == Market.SRIDEVI_NIGHT
        }.copy(enabled = true)
        val html = """
            <table>
              <tr><th>Date</th><th>MON</th><th>TUE</th><th>WED</th><th>THU</th><th>FRI</th><th>SAT</th><th>SUN</th></tr>
              <tr><td>28/1/19 to 3/2/19</td>
                <td>277</td><td>65</td><td>249</td>
                <td>277</td><td>65</td><td>249</td>
                <td>277</td><td>65</td><td>249</td>
                <td>277</td><td>65</td><td>249</td>
                <td>277</td><td>65</td><td>249</td>
                <td>277</td><td>65</td><td>249</td>
                <td>* * *</td><td>**</td><td>* * *</td>
              </tr>
            </table>
        """.trimIndent()
        val report = MarketSourceRefreshEngine.refresh(
            listOf(source),
            fetch = { FetchedChartPage(html, it.url) },
            retrievedAt = { Instant.parse("2026-11-19T03:00:00Z") }
        )
        assertEquals(1, report.successfulSources)
        assertEquals(7, report.observations.size)
        assertTrue(report.observations.all {
            it.market == Market.SRIDEVI_NIGHT && it.sessionId == "night" && it.sourceId == "sattamatkajodi"
        })
        assertEquals("277-65-249", report.observations.first().normalizedResult)
        assertEquals(ObservationStatus.MISSING_FROM_SOURCE, report.observations.last().status)
    }

}
