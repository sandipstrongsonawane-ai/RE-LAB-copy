package com.relab.core

import org.junit.Assert.*
import org.junit.Test

class UserMarketSourceRegistryTest {
    private val base = MarketSourceCatalog.enabledFor(Market.SRIDEVI).take(1)
    private fun source(url: String, publisher: String = "newpublisher", market: Market = Market.SRIDEVI) =
        MarketSource(market, "main", publisher, url, "user-added")

    @Test fun acceptsAnUnboundedListAndKeepsDifferentPagesFromSamePublisherTogether() {
        val many = (1..75).fold(emptyList<MarketSource>()) { acc, n ->
            UserMarketSourceRegistry.add(acc, source("https://publisher.example/chart/$n"))
        }
        assertEquals(75, many.size)
        assertEquals(1, many.map { it.sourceId }.distinct().size)
    }

    @Test fun rejectsDuplicateUrlButAllowsDistinctPublisherViews() {
        val first = source("https://publisher.example/chart")
        val added = UserMarketSourceRegistry.add(emptyList(), first)
        try {
            UserMarketSourceRegistry.add(added, first.copy(url = "https://publisher.example/chart/"))
            fail("duplicate URL must be rejected after normalization")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("already registered"))
        }
        val second = UserMarketSourceRegistry.add(added, first.copy(url = "https://publisher.example/history"))
        assertEquals(2, second.size)
        assertEquals(1, second.map { it.sourceId }.distinct().size)
    }

    @Test fun disableAndRemoveAffectOnlyTheSelectedUrl() {
        val first = source("https://publisher.example/a")
        val second = first.copy(url = "https://publisher.example/b")
        val both = listOf(first, second)
        val disabled = UserMarketSourceRegistry.setEnabled(both, first.url, false)
        assertFalse(disabled[0].enabled)
        assertTrue(disabled[1].enabled)
        assertEquals(listOf(second), UserMarketSourceRegistry.remove(both, first.url))
    }


    @Test fun editCanChangeUrlPublisherMarketAndSession() {
        val original = source("https://publisher.example/old")
        val updated = source("https://newpublisher.example/chart", "new-publisher", Market.KALYAN)
            .copy(sessionId = "evening", enabled = false)
        val result = UserMarketSourceRegistry.update(listOf(original), original.url, updated)
        assertEquals(1, result.size)
        assertEquals("new-publisher", result.single().sourceId)
        assertEquals(Market.KALYAN, result.single().market)
        assertEquals("evening", result.single().sessionId)
        assertFalse(result.single().enabled)
    }

    @Test fun rejectsHttpAndCredentialBearingUrls() {
        listOf("http://publisher.example/chart", "https://user:pass@publisher.example/chart").forEach { url ->
            try {
                UserMarketSourceRegistry.add(emptyList(), source(url))
                fail("unsafe URL should be rejected")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
