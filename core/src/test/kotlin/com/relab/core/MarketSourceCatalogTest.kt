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

    @Test fun sourceLookupUsesExactRegisteredUrl() {
        val source = MarketSourceCatalog.sources.first()
        assertEquals(source, MarketSourceCatalog.findByUrl(source.url))
        assertNull(MarketSourceCatalog.findByUrl(source.url + "?unreviewed=1"))
    }
}
