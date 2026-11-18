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
        // Search-index-discovered long-range archives. Keep disabled until raw HTML
        // snapshots are captured and parser output is independently fixture-validated.
        MarketSource(
            Market.SRIDEVI, "main", "dlbosss",
            "https://www.dlbosss.com/panel-chart-record/sridevi.php", "weekly-panel",
            enabled = false,
            note = "Search-index excerpt shows dated 2024-2026 weekly panel rows; raw snapshot and parser fixture not yet audited."
        ),
        MarketSource(
            Market.SRIDEVI, "main", "realmatka",
            "https://www.realmatka.in/panel-chart-record/sridevi", "weekly-panel",
            enabled = false,
            note = "Search-index excerpt shows dated 2024 weekly panel rows; raw snapshot and parser fixture not yet audited."
        ),
        MarketSource(
            Market.KALYAN, "main", "ratan567official",
            "https://ratan567official.com/kalyan/pana-chart/", "weekly-panel",
            enabled = false,
            note = "Search-index excerpt reports 303 dated results from Sep 2025 to Sep 2026; raw snapshot and parser fixture not yet audited."
        ),
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
            "https://matkaflash.com/sridevi/panel-chart", "panel-history"
        ),
        MarketSource(
            Market.KALYAN, "main", "fixresult",
            "https://www.fixresult.in/kalyan-matka-chart.html", "panel"
        ),
        MarketSource(
            Market.KALYAN, "main", "matkaflash",
            "https://matkaflash.com/kalyan/panel-chart", "panel-history"
        ),
        MarketSource(
            Market.MILAN_DAY, "main", "matkaflash",
            "https://matkaflash.com/milan-day/panel-chart", "panel-history"
        ),
        MarketSource(
            Market.KALYAN_MORNING, "main", "matkaflash",
            "https://matkaflash.com/kalyan-morning/panel-chart", "panel-history"
        ),
        MarketSource(
            Market.KALYAN_NIGHT, "night", "matkaflash",
            "https://matkaflash.com/kalyan-night/panel-chart", "panel-history"
        ),
        // livesattamatka.net exposes the same weekly panel matrix format as the
        // already registered .org site. Keep the shared publisher identity so
        // these domains cannot falsely count as independent corroboration.
        MarketSource(
            Market.SRIDEVI, "main", "livesattamatka",
            "https://livesattamatka.net/panna-chart/sridevi", "weekly-panel",
            enabled = true,
            note = "Verified dated weekly panel archive; grouped with livesattamatka.org under one publisher identity to prevent duplicate corroboration."
        ),
        MarketSource(
            Market.KALYAN, "main", "livesattamatka",
            "https://livesattamatka.net/panna-chart/kalyan", "weekly-panel",
            enabled = true,
            note = "Verified dated weekly panel archive; grouped with livesattamatka.org under one publisher identity."
        ),
        MarketSource(
            Market.KALYAN_MORNING, "main", "livesattamatka",
            "https://livesattamatka.net/panna-chart/kalyan-morning", "weekly-panel",
            enabled = true,
            note = "Verified dated weekly panel archive; grouped with livesattamatka.org under one publisher identity."
        ),
        MarketSource(
            Market.valueOf("PUNA_BAZAR"), "main", "livesattamatka",
            "https://livesattamatka.net/panna-chart/puna-bazar", "weekly-panel",
            enabled = true,
            note = "Verified dated weekly panel archive; grouped with livesattamatka.org under one publisher identity."
        ),
        MarketSource(
            Market.TIME_BAZAR, "main", "livesattamatka",
            "https://livesattamatka.net/panna-chart/time-bazar", "weekly-panel",
            note = "Verified weekly panel archive; Monday-Saturday calendar."
        ),
        MarketSource(
            Market.MAIN_BAZAR, "main", "livesattamatka",
            "https://livesattamatka.net/panna-chart/main-bazar", "weekly-panel",
            note = "Verified weekly panel archive; Monday-Friday calendar."
        ),
        MarketSource(
            Market.MAIN_BAZAR_NIGHT, "main", "livesattamatka",
            "https://livesattamatka.net/panna-chart/main-bazar-night", "weekly-panel",
            note = "Verified weekly panel archive; kept separate from Main Bazar day/night market identities."
        ),
        MarketSource(
            Market.TIME_BAZAR_DAY, "main", "livesattamatka",
            "https://livesattamatka.net/panna-chart/time-bazar-day", "weekly-panel",
            note = "Verified weekly panel archive; distinct from Time Bazar."
        ),
        MarketSource(
            Market.NEW_TIME_BAZAR, "main", "livesattamatka",
            "https://livesattamatka.net/panna-chart/new-time-bazar", "weekly-panel",
            note = "Verified weekly panel archive; Monday-Saturday calendar and distinct market identity."
        ),
        MarketSource(
            Market.CB, "main", "livesattamatka",
            "https://livesattamatka.net/panna-chart/cb", "weekly-panel",
            note = "Verified weekly panel archive; seven-day market."
        ),
        MarketSource(
            Market.MAIN_RATAN, "main", "livesattamatka",
            "https://livesattamatka.net/panna-chart/main-ratan", "weekly-panel",
            note = "Verified weekly panel archive; Monday-Friday market."
        ),
        MarketSource(
            Market.SUNDAY_BAZAR, "main", "livesattamatka",
            "https://livesattamatka.net/panna-chart/sunday-bazar", "weekly-panel",
            note = "Verified weekly panel archive; seven-day market."
        ),
        MarketSource(
            Market.GEETA_MORNING, "main", "livesattamatka",
            "https://livesattamatka.net/panna-chart/geeta-morning", "weekly-panel",
            note = "Verified weekly panel archive; seven-day market."
        ),
        MarketSource(
            Market.MAIN_BAZAR_MORNING, "main", "livesattamatka",
            "https://livesattamatka.net/panna-chart/main-bazar-morning", "weekly-panel",
            note = "Verified weekly panel archive; distinct morning market identity."
        ),
        // SattaMatkai publishes date-ranged weekly panel matrices for these
        // exact market pages. The generic weekly-matrix adapter recognizes the
        // Date + weekday table and preserves star placeholders as pending/missing.
        MarketSource(
            Market.SRIDEVI, "main", "sattamatkai",
            "https://sattamatkai.in/panel-chart-record/sridevi", "weekly-panel",
            note = "Verified Sridevi panel archive with dated weekday matrix; parser coverage is fixture-tested."
        ),
        MarketSource(
            Market.KALYAN, "main", "sattamatkai",
            "https://sattamatkai.in/panel-chart-record/kalyan", "weekly-panel",
            note = "Verified Kalyan panel archive with dated weekday matrix; parser coverage is fixture-tested."
        ),
        MarketSource(
            Market.TIME_BAZAR, "main", "sattamatkai",
            "https://sattamatkai.in/panel-chart-record/time-bazar", "weekly-panel",
            note = "Verified Time Bazar panel archive with dated weekday matrix; parser coverage is fixture-tested."
        ),
        MarketSource(
            Market.MILAN_DAY, "main", "sattamatkai",
            "https://sattamatkai.in/panel-chart-record/milan-day", "weekly-panel",
            note = "Verified Milan Day panel archive with dated weekday matrix; parser coverage is fixture-tested."
        ),
        // User-supplied SattaMatkaJodi historical panel pages. All three URLs
        // share one canonical publisher identity; keep them disabled until a real
        // raw-HTML capture is fixture-tested against the three-cell weekly parser.
        MarketSource(
            Market.KALYAN, "main", "sattamatkajodi",
            "https://sattamatkajodi.com/record/kalyan-penal-chart.php", "weekly-panel-three-cell",
            enabled = false,
            note = "User-supplied long-range Kalyan panel archive; raw HTML fixture and full date/session audit required before enabling."
        ),
        MarketSource(
            Market.SRIDEVI, "main", "sattamatkajodi",
            "https://sattamatkajodi.com/record/sridevi-satta-penal-chart.php", "weekly-panel-three-cell",
            enabled = false,
            note = "User-supplied Sridevi day panel archive; same publisher identity as its other chart pages; raw HTML fixture audit required."
        ),
        MarketSource(
            Market.SRIDEVI_NIGHT, "night", "sattamatkajodi",
            "https://sattamatkajodi.com/record/sridevi-night-satta-penal-chart.php", "weekly-panel-three-cell",
            enabled = false,
            note = "User-supplied Sridevi Night panel archive; kept separate from Sridevi day; raw HTML fixture audit required."
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
