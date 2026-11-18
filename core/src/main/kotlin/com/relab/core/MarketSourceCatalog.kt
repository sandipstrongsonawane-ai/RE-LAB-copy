package com.relab.core

/**
 * Explicit, auditable registry of public archive pages.
 *
 * A publisher may expose multiple views; those views share one sourceId and
 * therefore can never count as independent corroboration. Only full panel
 * pages are enabled for automatic ingestion. Ambiguous multi-market archives
 * remain disabled until their exact session-specific page is verified.
 */
data class MarketSource(
    val market: Market,
    val sessionId: String,
    val sourceId: String,
    val url: String,
    val view: String,
    val enabled: Boolean = true,
    val note: String = ""
) {
    init {
        require(sessionId.isNotBlank())
        require(sourceId.isNotBlank())
        val uri = java.net.URI(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
            "Market source must use an absolute HTTPS URL"
        }
    }
}

object MarketSourceCatalog {
    val sources: List<MarketSource> = listOf(
        MarketSource(
            Market.SRIDEVI, "main", "hpofficial",
            "https://hpofficial.in/sridevi-panel-chart/", "panel"
        ),
        MarketSource(
            Market.SRIDEVI, "main", "livesattamatka",
            "https://livesattamatka.org/panel-record/sridevi", "panel"
        ),
        MarketSource(
            Market.SRIDEVI, "main", "matkaflash",
            "https://matkaflash.com/sridevi?tab=history", "panel-history"
        ),
        MarketSource(
            Market.KALYAN, "main", "fixresult",
            "https://www.fixresult.in/kalyan-matka-chart.html", "panel"
        ),
        MarketSource(
            Market.KALYAN, "main", "matkaflash",
            "https://matkaflash.com/kalyan", "panel-history"
        ),
        MarketSource(
            Market.KALYAN, "main", "kalyanchart",
            "https://kalyanchart.cyou/history/", "archive",
            enabled = false,
            note = "Archive contains multiple sessions; exact Kalyan evening page must be verified before automated collection."
        )
    )

    fun enabledFor(market: Market, sessionId: String = "main"): List<MarketSource> =
        sources.filter { it.enabled && it.market == market && it.sessionId == sessionId }

    fun findByUrl(url: String): MarketSource? =
        sources.firstOrNull { it.url == url }
}
