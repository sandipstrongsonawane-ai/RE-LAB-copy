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


/**
 * Adapter for MatkaFlash's date-wise result-history table.
 *
 * The adapter is intentionally gated by both the publisher host and the
 * complete six-column header. It does not scrape live-result widgets or
 * infer missing rows from adjacent dates.
 */
object MatkaFlashHistoryParserAdapter : ChartParserAdapter {
    override val id: String = "matkaflash-history-v1"

    private val dateFormatter = java.time.format.DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("d MMM uuuu")
        .toFormatter(java.util.Locale.ENGLISH)

    override fun supports(html: String, pageUrl: String): Boolean {
        val host = runCatching { java.net.URI(pageUrl).host?.lowercase(java.util.Locale.ROOT)?.removePrefix("www.") }.getOrNull()
        if (host != "matkaflash.com") return false
        return runCatching {
            Jsoup.parse(html, pageUrl).select("table").any { table ->
                val headers = table.select("tr").firstOrNull()?.select("th,td")
                    ?.map { it.text().trim().lowercase(java.util.Locale.ROOT) }.orEmpty()
                listOf("date", "weekday", "open panna", "jodi", "close panna", "status")
                    .all { required -> headers.any { it == required } }
            }
        }.getOrDefault(false)
    }

    override fun parse(
        html: String,
        pageUrl: String,
        market: Market,
        sessionId: String,
        retrievedAt: Instant
    ): ChartParserResult {
        val observations = mutableListOf<SourceObservation>()
        val issues = mutableListOf<String>()
        var skipped = 0
        val document = Jsoup.parse(html, pageUrl)
        val table = document.select("table").firstOrNull { candidate ->
            val headers = candidate.select("tr").firstOrNull()?.select("th,td")
                ?.map { it.text().trim().lowercase(java.util.Locale.ROOT) }.orEmpty()
            listOf("date", "weekday", "open panna", "jodi", "close panna", "status")
                .all { required -> headers.any { it == required } }
        } ?: throw IllegalArgumentException("UNSUPPORTED: MatkaFlash history table headers changed.")

        val headers = table.select("tr").first().select("th,td")
            .map { it.text().trim().lowercase(java.util.Locale.ROOT) }
        val dateIndex = headers.indexOf("date")
        val openIndex = headers.indexOf("open panna")
        val jodiIndex = headers.indexOf("jodi")
        val closeIndex = headers.indexOf("close panna")
        val statusIndex = headers.indexOf("status")

        table.select("tr").drop(1).forEachIndexed { index, row ->
            val cells = row.select("th,td").map { it.text().trim() }
            if (cells.size <= maxOf(dateIndex, openIndex, jodiIndex, closeIndex, statusIndex)) {
                skipped++
                issues += "History row ${index + 2}: expected six result columns"
                return@forEachIndexed
            }
            val date = runCatching { java.time.LocalDate.parse(cells[dateIndex], dateFormatter) }.getOrNull()
            if (date == null) {
                skipped++
                issues += "History row ${index + 2}: unrecognized date '${cells[dateIndex]}'"
                return@forEachIndexed
            }
            val open = cells[openIndex]
            val jodi = cells[jodiIndex]
            val close = cells[closeIndex]
            val statusText = cells[statusIndex]
            val raw = cells.joinToString(" | ")
            val published = statusText.equals("declared", ignoreCase = true)
            if (!published) {
                observations += SourceObservation(
                    market = market, sessionId = sessionId, date = date,
                    sourceId = "matkaflash.com", sourceUrl = pageUrl,
                    retrievedAt = retrievedAt, rawText = raw,
                    normalizedResult = null, status = ObservationStatus.PENDING
                )
                continue
            }
            if (!open.matches(Regex("\\d{3}")) || !jodi.matches(Regex("\\d{2}")) || !close.matches(Regex("\\d{3}"))) {
                observations += SourceObservation(
                    market = market, sessionId = sessionId, date = date,
                    sourceId = "matkaflash.com", sourceUrl = pageUrl,
                    retrievedAt = retrievedAt, rawText = raw,
                    normalizedResult = null, status = ObservationStatus.MALFORMED
                )
                issues += "History row ${index + 2}: declared result has invalid panna/jodi width"
                return@forEachIndexed
            }
            val result = "$open-$jodi-$close"
            val record = ChartRecord(
                market = market, date = date, openPanna = open, jodi = jodi,
                closePanna = close, sourceId = "matkaflash.com",
                observedAt = retrievedAt, sessionId = sessionId
            )
            val validation = ChartMath.validate(record)
            val valid = validation is ValidationResult.Valid
            observations += SourceObservation(
                market = market, sessionId = sessionId, date = date,
                sourceId = "matkaflash.com", sourceUrl = pageUrl,
                retrievedAt = retrievedAt, rawText = raw,
                normalizedResult = result.takeIf { valid },
                status = if (valid) ObservationStatus.PUBLISHED else ObservationStatus.MALFORMED
            )
            if (!valid) issues += "History row ${index + 2}: ${(validation as ValidationResult.Invalid).reasons.joinToString()}"
        }
        return ChartParserResult(id, observations.distinctBy { it.date }, skipped, issues)
    }
}

object ChartParserRegistry {
    // Publisher-specific adapters should be added ahead of the conservative fallback
    // only after real saved-page fixtures and tests prove their extraction.
    private val adapters: List<ChartParserAdapter> = listOf(MatkaFlashHistoryParserAdapter, GenericHtmlTableParserAdapter)

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
