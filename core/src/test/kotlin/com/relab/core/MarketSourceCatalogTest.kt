package com.relab.core

import org.junit.Assert.*
import org.junit.Test

class MarketSourceCatalogTest {
    @Test fun hasIndependentSrideviAndKalyanSourceSets() {
        val sridevi = MarketSourceCatalog.enabledFor(Market.SRIDEVI)
        val kalyan = MarketSourceCatalog.enabledFor(Market.KALYAN)
        assertEquals(3, sridevi.size)
        assertEquals(2, kalyan.size)
        assertTrue(sridevi.all { it.market == Market.SRIDEVI && it.sessionId == "main" })
        assertTrue(kalyan.all { it.market == Market.KALYAN && it.sessionId == "main" })
        assertFalse(sridevi.any { it in kalyan })
    }

    @Test fun multipleViewsFromOnePublisherKeepOneCanonicalIdentity() {
        val hiraViews = MarketSourceCatalog.sources.filter { it.sourceId == "hpofficial" }
        assertEquals(1, hiraViews.size)
        assertEquals("hpofficial", hiraViews.single().sourceId)
    }

    @Test fun ambiguousKalyanArchiveIsNotEnabledForAutomaticCollection() {
        val source = MarketSourceCatalog.sources.single { it.sourceId == "kalyanchart" }
        assertFalse(source.enabled)
        assertTrue(source.note.contains("session"))
    }


    @Test fun usesFullHistoryPanelChartPagesForMatkaFlash() {
        assertEquals(
            "https://matkaflash.com/sridevi/panel-chart",
            MarketSourceCatalog.enabledFor(Market.SRIDEVI).single { it.sourceId == "matkaflash" }.url
        )
        assertEquals(
            "https://matkaflash.com/kalyan/panel-chart",
            MarketSourceCatalog.enabledFor(Market.KALYAN).single { it.sourceId == "matkaflash" }.url
        )
        assertEquals(
            "https://matkaflash.com/milan-day/panel-chart",
            MarketSourceCatalog.enabledFor(Market.MILAN_DAY).single().url
        )
    }

    @Test fun includesKalyanMorningAndNightAsSeparateSessions() {
        val morning = MarketSourceCatalog.enabledFor(Market.KALYAN_MORNING, "main").single()
        val night = MarketSourceCatalog.enabledFor(Market.KALYAN_NIGHT, "night").single()
        assertEquals("https://matkaflash.com/kalyan-morning/panel-chart", morning.url)
        assertEquals("https://matkaflash.com/kalyan-night/panel-chart", night.url)
        assertNotEquals(morning.market, night.market)
        assertEquals("matkaflash", morning.sourceId)
        assertEquals("matkaflash", night.sourceId)
    }

    @Test fun livesattamatkaNetArchivesStayDisabledAndSharePublisherIdentity() {
        val netSources = MarketSourceCatalog.sources.filter { it.url.contains("livesattamatka.net") }
        assertEquals(4, netSources.size)
        assertTrue(netSources.all { !it.enabled && it.sourceId == "livesattamatka" })
        assertEquals(
            setOf(Market.SRIDEVI, Market.KALYAN, Market.KALYAN_MORNING, Market.valueOf("PUNA_BAZAR")),
            netSources.map { it.market }.toSet()
        )
    }

    @Test fun sourceLookupUsesExactRegisteredUrl() {
        val source = MarketSourceCatalog.sources.first()
        assertEquals(source, MarketSourceCatalog.findByUrl(source.url))
        assertNull(MarketSourceCatalog.findByUrl(source.url + "?unreviewed=1"))
    }
}
