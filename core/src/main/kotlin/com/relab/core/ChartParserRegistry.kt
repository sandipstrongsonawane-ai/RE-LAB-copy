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
            if (cells.size <= dateIndex) {
                skipped++
                issues += "History row ${index + 2}: date column is missing"
                return@forEachIndexed
            }
            val date = runCatching { java.time.LocalDate.parse(cells[dateIndex], dateFormatter) }.getOrNull()
            if (date == null) {
                skipped++
                issues += "History row ${index + 2}: unrecognized date '${cells[dateIndex]}'"
                return@forEachIndexed
            }
            val raw = cells.joinToString(" | ")
            if (cells.any { it.equals("holiday", ignoreCase = true) }) {
                observations += SourceObservation(
                    market = market, sessionId = sessionId, date = date,
                    sourceId = "matkaflash.com", sourceUrl = pageUrl,
                    retrievedAt = retrievedAt, rawText = raw,
                    normalizedResult = null, status = ObservationStatus.HOLIDAY_CLOSED
                )
                return@forEachIndexed
            }
            if (cells.size <= maxOf(dateIndex, openIndex, jodiIndex, closeIndex, statusIndex)) {
                skipped++
                issues += "History row ${index + 2}: expected six result columns"
                return@forEachIndexed
            }
            val open = cells[openIndex]
            val jodi = cells[jodiIndex]
            val close = cells[closeIndex]
            val statusText = cells[statusIndex]
            val published = statusText.equals("declared", ignoreCase = true)
            if (!published) {
                observations += SourceObservation(
                    market = market, sessionId = sessionId, date = date,
                    sourceId = "matkaflash.com", sourceUrl = pageUrl,
                    retrievedAt = retrievedAt, rawText = raw,
                    normalizedResult = null, status = ObservationStatus.PENDING
                )
                return@forEachIndexed
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


/**
 * Adapter for FixResult's weekly Kalyan Mon-Sat matrix.
 *
 * The first column is a Monday-to-Saturday date range; each following
 * weekday cell contains the 3-digit open panna, 2-digit jodi and
 * 3-digit close panna. Empty/holiday-style cells remain non-published.
 */
object FixResultKalyanWeeklyParserAdapter : ChartParserAdapter {
    override val id: String = "fixresult-kalyan-weekly-v1"

    private val rangePattern = Regex("(\\d{2})-(\\d{2})-(\\d{4})\\s*to\\s*(\\d{2})-(\\d{2})-(\\d{4})", RegexOption.IGNORE_CASE)

    override fun supports(html: String, pageUrl: String): Boolean {
        val host = runCatching { java.net.URI(pageUrl).host?.lowercase(java.util.Locale.ROOT)?.removePrefix("www.") }.getOrNull()
        if (host != "fixresult.in") return false
        val path = runCatching { java.net.URI(pageUrl).path.lowercase(java.util.Locale.ROOT) }.getOrNull()
        if (path != "/kalyan-matka-chart.html") return false
        return runCatching {
            Jsoup.parse(html, pageUrl).select("table").any { table ->
                val headers = table.select("tr").firstOrNull()?.select("th,td")
                    ?.map { it.text().trim().lowercase(java.util.Locale.ROOT) }.orEmpty()
                listOf("date range", "mon", "tue", "wed", "thu", "fri", "sat")
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
        require(market == Market.KALYAN) { "FixResult weekly matrix is only configured for Kalyan." }
        val observations = mutableListOf<SourceObservation>()
        val issues = mutableListOf<String>()
        var skipped = 0
        val table = Jsoup.parse(html, pageUrl).select("table").firstOrNull { candidate ->
            val headers = candidate.select("tr").firstOrNull()?.select("th,td")
                ?.map { it.text().trim().lowercase(java.util.Locale.ROOT) }.orEmpty()
            listOf("date range", "mon", "tue", "wed", "thu", "fri", "sat")
                .all { required -> headers.any { it == required } }
        } ?: throw IllegalArgumentException("UNSUPPORTED: FixResult Kalyan weekly matrix headers changed.")

        table.select("tr").drop(1).forEachIndexed { rowIndex, row ->
            val cells = row.select("th,td").map { it.text().trim() }
            if (cells.size < 7) {
                skipped++
                issues += "Weekly row ${rowIndex + 2}: expected date range and six weekday cells"
                return@forEachIndexed
            }
            val match = rangePattern.find(cells[0])
            val monday = match?.let {
                runCatching {
                    java.time.LocalDate.of(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt())
                }.getOrNull()
            }
            val saturday = match?.let {
                runCatching {
                    java.time.LocalDate.of(it.groupValues[6].toInt(), it.groupValues[5].toInt(), it.groupValues[4].toInt())
                }.getOrNull()
            }
            if (monday == null || saturday == null || monday.dayOfWeek != java.time.DayOfWeek.MONDAY ||
                saturday != monday.plusDays(5)
            ) {
                skipped++
                issues += "Weekly row ${rowIndex + 2}: invalid Monday-Saturday date range '${cells[0]}'"
                return@forEachIndexed
            }

            for (dayIndex in 0 until 6) {
                val date = monday.plusDays(dayIndex.toLong())
                val cellRaw = cells[dayIndex + 1]
                val raw = "${cells[0]} | ${listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat")[dayIndex]} | $cellRaw"
                val compact = cellRaw.replace(Regex("\\s+"), "")
                if (compact.isBlank() || compact.contains("—") || compact == "-" || compact.equals("holiday", true)) {
                    observations += SourceObservation(
                        market = market, sessionId = sessionId, date = date,
                        sourceId = "fixresult.in", sourceUrl = pageUrl,
                        sourceRecordId = date.toString(), retrievedAt = retrievedAt,
                        rawText = raw, normalizedResult = null,
                        status = if (compact.isBlank() || compact.contains("—") || compact == "-")
                            ObservationStatus.MISSING_FROM_SOURCE else ObservationStatus.HOLIDAY_CLOSED
                    )
                    continue
                }

                val digits = compact.filter(Char::isDigit)
                if (digits.length != 8 || digits != compact) {
                    observations += SourceObservation(
                        market = market, sessionId = sessionId, date = date,
                        sourceId = "fixresult.in", sourceUrl = pageUrl,
                        sourceRecordId = date.toString(), retrievedAt = retrievedAt,
                        rawText = raw, normalizedResult = null, status = ObservationStatus.MALFORMED
                    )
                    issues += "Weekly row ${rowIndex + 2}, ${listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat")[dayIndex]}: expected 8 result digits"
                    continue
                }

                val open = digits.substring(0, 3)
                val jodi = digits.substring(3, 5)
                val close = digits.substring(5, 8)
                val record = ChartRecord(
                    market = market, date = date, openPanna = open, jodi = jodi,
                    closePanna = close, sourceId = "fixresult.in",
                    sourceRecordId = date.toString(), observedAt = retrievedAt,
                    sessionId = sessionId
                )
                val validation = ChartMath.validate(record)
                val valid = validation is ValidationResult.Valid
                observations += SourceObservation(
                    market = market, sessionId = sessionId, date = date,
                    sourceId = "fixresult.in", sourceUrl = pageUrl,
                    sourceRecordId = date.toString(), retrievedAt = retrievedAt,
                    rawText = raw, normalizedResult = "$open-$jodi-$close".takeIf { valid },
                    status = if (valid) ObservationStatus.PUBLISHED else ObservationStatus.MALFORMED
                )
                if (!valid) issues += "Weekly row ${rowIndex + 2}, ${listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat")[dayIndex]}: ${(validation as ValidationResult.Invalid).reasons.joinToString()}"
            }
        }
        return ChartParserResult(id, observations.distinctBy { it.date }, skipped, issues)
    }
}

object ChartParserRegistry {
    // Publisher-specific adapters should be added ahead of the conservative fallback
    // only after real saved-page fixtures and tests prove their extraction.
    private val adapters: List<ChartParserAdapter> = listOf(MatkaFlashHistoryParserAdapter, FixResultKalyanWeeklyParserAdapter, GenericHtmlTableParserAdapter)

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
