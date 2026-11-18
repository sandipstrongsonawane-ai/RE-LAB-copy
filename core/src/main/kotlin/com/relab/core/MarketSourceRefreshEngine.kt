package com.relab.core

import java.time.Instant

data class FetchedChartPage(
    val html: String,
    val finalUrl: String
)

data class SourceRefreshOutcome(
    val source: MarketSource,
    val observations: List<SourceObservation>,
    val skippedRows: Int,
    val issues: List<String>,
    val errorMessage: String? = null
) {
    val succeeded: Boolean get() = errorMessage == null
}

data class MarketSourceRefreshReport(
    val outcomes: List<SourceRefreshOutcome>
) {
    val observations: List<SourceObservation>
        get() = outcomes.flatMap { it.observations }

    val successfulSources: Int
        get() = outcomes.count { it.succeeded }

    val failedSources: Int
        get() = outcomes.count { !it.succeeded }
}

/**
 * Sequential, source-isolated refresh coordinator.
 *
 * Fetching is injected so the core stays platform/network independent and can
 * be tested with deterministic fixtures. One failed publisher never prevents
 * the remaining registered publishers from being attempted.
 */
object MarketSourceRefreshEngine {
    fun refresh(
        sources: List<MarketSource>,
        fetch: (MarketSource) -> FetchedChartPage,
        retrievedAt: () -> Instant = Instant::now
    ): MarketSourceRefreshReport {
        val outcomes = sources.map { source ->
            try {
                require(source.enabled) { "Source is disabled pending identity verification." }
                val page = fetch(source)
                val uri = java.net.URI(page.finalUrl)
                require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
                    "Fetched source must resolve to an absolute HTTPS URL."
                }
                val imported = HtmlChartImporter.import(
                    html = page.html,
                    pageUrl = page.finalUrl,
                    market = source.market,
                    sessionId = source.sessionId,
                    retrievedAt = retrievedAt()
                )
                SourceRefreshOutcome(
                    source = source,
                    observations = imported.observations,
                    skippedRows = imported.skippedRows,
                    issues = imported.issues
                )
            } catch (error: Exception) {
                SourceRefreshOutcome(
                    source = source,
                    observations = emptyList(),
                    skippedRows = 0,
                    issues = emptyList(),
                    errorMessage = error.message ?: error.javaClass.simpleName
                )
            }
        }
        return MarketSourceRefreshReport(outcomes)
    }
}
