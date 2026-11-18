package com.relab.core

import java.time.Instant
import java.net.URI
import java.util.Locale

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
                val uri = URI(page.finalUrl)
                require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null) {
                    "Fetched source must resolve to an absolute HTTPS URL without embedded credentials."
                }
                val registeredHost = canonicalHost(URI(source.url).host)
                val finalHost = canonicalHost(uri.host)
                require(finalHost == registeredHost) {
                    "Redirect changed publisher host from '$registeredHost' to '$finalHost'; verify and register the destination explicitly."
                }
                val imported = ChartParserRegistry.parse(
                    html = page.html,
                    pageUrl = page.finalUrl,
                    market = source.market,
                    sessionId = source.sessionId,
                    retrievedAt = retrievedAt()
                )
                require(imported.observations.isNotEmpty()) {
                    "UNSUPPORTED: no recognized dated chart rows were found by parser '${imported.parserId}'. Page may need a site-specific adapter."
                }
                SourceRefreshOutcome(
                    source = source,
                    // Keep configured publisher identity and original registered URL.
                    observations = imported.observations.map { it.copy(sourceId = source.sourceId, sourceUrl = source.url) },
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

    private fun canonicalHost(host: String?): String =
        host?.lowercase(Locale.ROOT)?.removePrefix("www.")
            ?: throw IllegalArgumentException("Source URL host is missing.")
}
