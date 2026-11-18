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
    val errorMessage: String? = null,
    val snapshots: List<SourcePageSnapshot> = emptyList()
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
                    if (fetched.isSuccess) {
                        val page = fetched.getOrThrow()
                        indexedSources.groupBy { it.value.market to it.value.sessionId }.values.forEach { registrations ->
                            val first = registrations.first()
                            val capturedAt = retrievedAt()
                            registrations.forEach { indexed ->
                                val source = indexed.value
                                val snapshot = SourcePageSnapshot(
                                    sourceId = source.sourceId,
                                    market = source.market,
                                    sessionId = source.sessionId,
                                    requestedUrl = source.url,
                                    finalUrl = page.finalUrl,
                                    retrievedAt = capturedAt,
                                    html = page.html
                                )
                                val parsed = if (indexed.index == first.index) {
                                    refreshParsed(first.value, page, capturedAt, snapshot)
                                } else {
                                    val base = outcomesByIndex[first.index]
                                        ?: refreshParsed(first.value, page, capturedAt, SourcePageSnapshot(
                                            first.value.sourceId, first.value.market, first.value.sessionId,
                                            first.value.url, page.finalUrl, capturedAt, page.html
                                        ))
                                    base.copy(
                                        source = source,
                                        observations = base.observations.map {
                                            it.copy(sourceId = source.sourceId, sourceUrl = source.url)
                                        },
                                        snapshots = listOf(snapshot)
                                    )
                                }
                                outcomesByIndex[indexed.index] = parsed
                            }
                        }
                    } else {
                        val error = fetched.exceptionOrNull()!!
                        indexedSources.forEach { indexed ->
                            val source = indexed.value
                            outcomesByIndex[indexed.index] = SourceRefreshOutcome(
                                source, emptyList(), 0, emptyList(),
                                error.message ?: error.javaClass.simpleName
                            )
                        }
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
        retrievedAt: Instant,
        snapshot: SourcePageSnapshot
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
            sessionId = source.sessionId, retrievedAt = retrievedAt
        )
        require(imported.observations.isNotEmpty()) {
            "UNSUPPORTED: no recognized dated chart rows were found by parser '${imported.parserId}'. Page may need a site-specific adapter."
        }
        SourceRefreshOutcome(
            source = source,
            observations = imported.observations.map { it.copy(sourceId = source.sourceId, sourceUrl = source.url) },
            skippedRows = imported.skippedRows, issues = imported.issues,
            snapshots = listOf(snapshot)
        )
    } catch (error: Exception) {
        SourceRefreshOutcome(
            source, emptyList(), 0, emptyList(),
            error.message ?: error.javaClass.simpleName, listOf(snapshot)
        )
    }

    private const val MAX_PARALLEL_SOURCES = 4
    private fun canonicalHost(host: String?): String =
        host?.lowercase(Locale.ROOT)?.removePrefix("www.")
            ?: throw IllegalArgumentException("Source URL host is missing.")
}
