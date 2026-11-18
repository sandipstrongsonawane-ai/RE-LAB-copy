package com.relab.core

import org.jsoup.Jsoup
import java.time.Instant

data class ChartParserResult(
    val parserId: String,
    val observations: List<SourceObservation>,
    val skippedRows: Int,
    val issues: List<String>
)

/**
 * Adapter contract for publisher-specific chart layouts.
 *
 * Add a site-specific implementation only after fixture-backed verification.
 * The generic table adapter is deliberately conservative and never invents rows.
 */
interface ChartParserAdapter {
    val id: String
    fun supports(html: String, pageUrl: String): Boolean
    fun parse(
        html: String,
        pageUrl: String,
        market: Market,
        sessionId: String,
        retrievedAt: Instant
    ): ChartParserResult
}

object GenericHtmlTableParserAdapter : ChartParserAdapter {
    override val id: String = "generic-html-table"

    override fun supports(html: String, pageUrl: String): Boolean =
        runCatching { Jsoup.parse(html, pageUrl).select("table").isNotEmpty() }.getOrDefault(false)

    override fun parse(
        html: String,
        pageUrl: String,
        market: Market,
        sessionId: String,
        retrievedAt: Instant
    ): ChartParserResult {
        val report = HtmlChartImporter.import(html, pageUrl, market, sessionId, retrievedAt)
        return ChartParserResult(id, report.observations, report.skippedRows, report.issues)
    }
}

object ChartParserRegistry {
    // Publisher-specific adapters should be added ahead of the conservative fallback
    // only after real saved-page fixtures and tests prove their extraction.
    private val adapters: List<ChartParserAdapter> = listOf(GenericHtmlTableParserAdapter)

    fun parse(
        html: String,
        pageUrl: String,
        market: Market,
        sessionId: String,
        retrievedAt: Instant = Instant.now()
    ): ChartParserResult {
        require(html.isNotBlank()) { "UNSUPPORTED: downloaded page is empty." }
        val adapter = adapters.firstOrNull { it.supports(html, pageUrl) }
            ?: throw IllegalArgumentException("UNSUPPORTED: no registered parser adapter recognizes this page layout.")
        return adapter.parse(html, pageUrl, market, sessionId, retrievedAt)
    }
}
