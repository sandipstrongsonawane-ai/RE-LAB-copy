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

    @Test fun onePublisherFailureDoesNotStopOtherSources() {
        var calls = 0
        val report = MarketSourceRefreshEngine.refresh(
            sridevi,
            fetch = {
                calls++
                if (calls == 1) error("temporary network failure")
                FetchedChartPage(html, it.url)
            }
        )
        assertEquals(2, calls)
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

    @Test fun rejectsInsecureRedirectTargets() {
        val report = MarketSourceRefreshEngine.refresh(
            sridevi.take(1),
            fetch = { FetchedChartPage(html, "http://insecure.example/chart") }
        )
        assertEquals(1, report.failedSources)
        assertTrue(report.observations.isEmpty())
        assertTrue(report.outcomes.single().errorMessage.orEmpty().contains("HTTPS"))
    }
}
