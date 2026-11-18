package com.relab.core

import java.time.Instant
import java.net.URI
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

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
 * Bounded-parallel refresh coordinator.
 *
 * Exact duplicate URLs are fetched once per refresh batch, then parsed separately
 * for each registered market/session so source provenance and calendar semantics
 * remain attached to their own registration. Distinct URLs remain isolated and
 * are fetched with a bounded worker pool.
 */
object MarketSourceRefreshEngine {
    fun refresh(
        sources: List<MarketSource>,
        fetch: (MarketSource) -> FetchedChartPage,
        retrievedAt: () -> Instant = Instant::now
    ): MarketSourceRefreshReport {
        if (sources.isEmpty()) return MarketSourceRefreshReport(emptyList())

        val groups = sources.withIndex().groupBy { it.value.url to it.value.enabled }
        val executor = Executors.newFixedThreadPool(minOf(MAX_PARALLEL_SOURCES, groups.size))
        val outcomesByIndex = arrayOfNulls<SourceRefreshOutcome>(sources.size)
        try {
            val tasks = groups.values.map { indexedSources ->
                Callable {
                    val representative = indexedSources.first().value
                    val fetched = try {
                        require(representative.enabled) { "Source is disabled pending identity verification." }
                        Result.success(fetch(representative))
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                    indexedSources.forEach { indexed ->
                        val source = indexed.value
                        outcomesByIndex[indexed.index] = fetched.fold(
                            onSuccess = { page -> refreshParsed(source, page, retrievedAt) },
                            onFailure = { error ->
                                SourceRefreshOutcome(source, emptyList(), 0, emptyList(),
                                    error.message ?: error.javaClass.simpleName)
                            }
                        )
                    }
                }
            }
            executor.invokeAll(tasks).forEach { it.get() }
        } finally {
            executor.shutdown()
        }
        return MarketSourceRefreshReport(outcomesByIndex.mapIndexed { index, outcome ->
            outcome ?: SourceRefreshOutcome(sources[index], emptyList(), 0, emptyList(), "Refresh did not produce an outcome.")
        })
    }

    private fun refreshParsed(
        source: MarketSource,
        page: FetchedChartPage,
        retrievedAt: () -> Instant
    ): SourceRefreshOutcome = try {
        require(source.enabled) { "Source is disabled pending identity verification." }
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
            html = page.html, pageUrl = page.finalUrl, market = source.market,
            sessionId = source.sessionId, retrievedAt = retrievedAt()
        )
        require(imported.observations.isNotEmpty()) {
            "UNSUPPORTED: no recognized dated chart rows were found by parser '${imported.parserId}'. Page may need a site-specific adapter."
        }
        SourceRefreshOutcome(
            source = source,
            observations = imported.observations.map { it.copy(sourceId = source.sourceId, sourceUrl = source.url) },
            skippedRows = imported.skippedRows, issues = imported.issues
        )
    } catch (error: Exception) {
        SourceRefreshOutcome(source, emptyList(), 0, emptyList(), error.message ?: error.javaClass.simpleName)
    }

    private const val MAX_PARALLEL_SOURCES = 4
    private fun canonicalHost(host: String?): String =
        host?.lowercase(Locale.ROOT)?.removePrefix("www.")
            ?: throw IllegalArgumentException("Source URL host is missing.")
}
