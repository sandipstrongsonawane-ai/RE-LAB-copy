package com.relab.core

import org.jsoup.Jsoup
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

data class HtmlChartImportReport(
    val observations: List<SourceObservation>,
    val skippedRows: Int,
    val issues: List<String>
)

/** Extracts dated daily rows and Monday-Sunday weekly chart cells without inventing results. */
object HtmlChartImporter {
    private val resultPattern = Regex("""(?<!\d)(\d{3})\s*[-–/]\s*(\d{2})\s*[-–/]\s*(\d{3})(?!\d)""")
    private val rangePattern = Regex("""(\d{1,2})[/-](\d{1,2})[/-](\d{2,4})""")
    private val datePatterns = listOf(
        Regex("""(?<!\d)(\d{4})-(\d{1,2})-(\d{1,2})(?!\d)"""),
        Regex("""(?<!\d)(\d{1,2})[/-](\d{1,2})[/-](\d{2,4})(?!\d)"""),
        Regex("""(?i)\b(\d{1,2})\s+([A-Z]{3,9})\s+(\d{4})\b""")
    )
    private val months = mapOf(
        "JAN" to 1, "FEB" to 2, "MAR" to 3, "APR" to 4, "MAY" to 5, "JUN" to 6,
        "JUL" to 7, "AUG" to 8, "SEP" to 9, "OCT" to 10, "NOV" to 11, "DEC" to 12
    )

    fun import(
        html: String,
        pageUrl: String,
        market: Market,
        sessionId: String,
        retrievedAt: Instant = Instant.now()
    ): HtmlChartImportReport {
        require(html.isNotBlank()) { "The downloaded page is empty" }
        require(sessionId.isNotBlank()) { "Session ID is required" }
        val uri = java.net.URI(pageUrl)
        require(uri.scheme == "https" || uri.scheme == "http") { "Only HTTP and HTTPS URLs are supported" }
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")
            ?: throw IllegalArgumentException("URL host is missing")
        val document = Jsoup.parse(html, pageUrl)
        val observations = mutableListOf<SourceObservation>()
        val issues = mutableListOf<String>()
        var skipped = 0

        document.select("table tr").forEachIndexed { index, row ->
            val cells = row.select("th,td").map { it.text().trim() }
            if (cells.isEmpty()) return@forEachIndexed
            val raw = cells.joinToString(" | ")
            val weekStart = parseDate(cells.first())
            val dayCellTotal = cells.size - 1
            val allowedDayCounts = when (market) {
                Market.KALYAN -> setOf(6)
                Market.MAIN_BAZAR -> setOf(5)
                Market.TIME_BAZAR -> setOf(7)
                Market.SRIDEVI, Market.SRIDEVI_NIGHT -> setOf(7)
            }
            val rangeDates = rangePattern.findAll(cells.first()).mapNotNull { parseDate(it.value) }.toList()
            val threeCellDayCount = if (dayCellTotal % 3 == 0) dayCellTotal / 3 else -1
            val oneCellDayCount = dayCellTotal
            val oneCellLayout = oneCellDayCount in allowedDayCounts
            val threeCellLayout = threeCellDayCount in allowedDayCounts
            val dayCount = when {
                oneCellLayout -> oneCellDayCount
                threeCellLayout -> threeCellDayCount
                else -> -1
            }
            if (weekStart != null && dayCount in allowedDayCounts) {
                if (weekStart.dayOfWeek != java.time.DayOfWeek.MONDAY ||
                    rangeDates.size != 2 ||
                    java.time.temporal.ChronoUnit.DAYS.between(rangeDates[0], rangeDates[1]).toInt() + 1 != dayCount) {
                    issues += "Week row ${index + 1}: date range and weekly cell groups do not match"
                    skipped++
                    return@forEachIndexed
                }
                val dailyCells = if (oneCellLayout) cells.drop(1).map { listOf(it) } else cells.drop(1).chunked(3)
                dailyCells.forEachIndexed { dayOffset, parts ->
                    val date = weekStart.plusDays(dayOffset.toLong())
                    val rawCell = parts.joinToString(" | ")
                    val rawLower = rawCell.lowercase(Locale.ROOT)
                    if (oneCellLayout) {
                        val digits = rawCell.filter(Char::isDigit)
                        val statusOnly = when {
                            rawCell.isBlank() || rawCell.contains('*') || rawCell.matches(Regex("[-—–\\s]+")) ->
                                ObservationStatus.MISSING_FROM_SOURCE
                            Regex("\\b(?:holiday|closed|market off|no market|weekly off)\\b").containsMatchIn(rawLower) ->
                                ObservationStatus.HOLIDAY_CLOSED
                            Regex("\\b(?:pending|awaiting|not declared)\\b").containsMatchIn(rawLower) ->
                                ObservationStatus.PENDING
                            else -> null
                        }
                        if (statusOnly != null) {
                            observations += observation(market, sessionId, date, host, pageUrl, retrievedAt, rawCell, null, statusOnly)
                            return@forEachIndexed
                        }
                        if (digits.length != 8) {
                            issues += "Week row ${index + 1}, day ${dayOffset + 1}: unrecognized single-cell result '$rawCell'"
                            observations += observation(market, sessionId, date, host, pageUrl, retrievedAt, rawCell, null, ObservationStatus.MALFORMED)
                            return@forEachIndexed
                        }
                        val result = "${digits.substring(0, 3)}-${digits.substring(3, 5)}-${digits.substring(5, 8)}"
                        val status = validateResult(result, market, date, host, retrievedAt, sessionId)
                        observations += observation(market, sessionId, date, host, pageUrl, retrievedAt, rawCell, result.takeIf { status == ObservationStatus.PUBLISHED }, status)
                        if (status == ObservationStatus.MALFORMED) {
                            issues += "Week row ${index + 1}, day ${dayOffset + 1}: Panna/Jodi validation failed ($result)"
                        }
                        return@forEachIndexed
                    }
                    val open = parts[0].filter { it.isDigit() || it == '*' }
                    val jodi = parts[1].filter { it.isDigit() || it == '*' }
                    val close = parts[2].filter { it.isDigit() || it == '*' }
                    val compact = open + jodi + close
                    if (compact.isEmpty() || compact.contains('*')) {
                        observations += observation(market, sessionId, date, host, pageUrl, retrievedAt, rawCell, null, ObservationStatus.MISSING_FROM_SOURCE)
                        return@forEachIndexed
                    }
                    if (open.length != 3 || jodi.length != 2 || close.length != 3 ||
                        !(open + jodi + close).all(Char::isDigit)) {
                        issues += "Week row ${index + 1}, day ${dayOffset + 1}: unrecognized three-cell result '$rawCell'"
                        observations += observation(market, sessionId, date, host, pageUrl, retrievedAt, rawCell, null, ObservationStatus.MALFORMED)
                        return@forEachIndexed
                    }
                    val result = "$open-$jodi-$close"
                    val status = validateResult(result, market, date, host, retrievedAt, sessionId)
                    observations += observation(market, sessionId, date, host, pageUrl, retrievedAt, rawCell, result.takeIf { status == ObservationStatus.PUBLISHED }, status)
                    if (status == ObservationStatus.MALFORMED) {
                        issues += "Week row ${index + 1}, day ${dayOffset + 1}: Panna/Jodi validation failed ($result)"
                    }
                }
                return@forEachIndexed
            }

            // A two-date range is a weekly row. If its columns do not match a
            // supported layout, never fall through and misread its first result as a daily record.
            if (rangePattern.findAll(cells.first()).count() == 2) {
                skipped++
                return@forEachIndexed
            }

            // Parse the dedicated date cell first. Parsing the entire row can let
            // unrelated result text interfere with locale-specific date detection.
            val date = parseDate(cells.first()) ?: parseDate(raw)
            val match = resultPattern.find(raw)
            if (date == null || match == null) {
                skipped++
                return@forEachIndexed
            }
            val result = "${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}"
            val status = validateResult(result, market, date, host, retrievedAt, sessionId)
            observations += observation(market, sessionId, date, host, pageUrl, retrievedAt, raw, result.takeIf { status == ObservationStatus.PUBLISHED }, status)
            if (status == ObservationStatus.MALFORMED) issues += "Table row ${index + 1}: Panna/Jodi validation failed ($result)"
        }
        // Preserve every extracted row. Repeated rows are evidence and must remain auditable.
        return HtmlChartImportReport(observations, skipped, issues)
    }

    private fun validateResult(result: String, market: Market, date: LocalDate, host: String, at: Instant, session: String): ObservationStatus {
        val parts = result.split('-')
        val record = ChartRecord(market = market, date = date, openPanna = parts[0], jodi = parts[1], closePanna = parts[2], sourceId = host, observedAt = at, sessionId = session)
        return if (ChartMath.validate(record) is ValidationResult.Valid) ObservationStatus.PUBLISHED else ObservationStatus.MALFORMED
    }

    private fun observation(market: Market, session: String, date: LocalDate, host: String, url: String, at: Instant, raw: String, result: String?, status: ObservationStatus) =
        SourceObservation(market = market, sessionId = session, date = date, sourceId = host, sourceUrl = url, retrievedAt = at, rawText = raw, normalizedResult = result, status = status)

    private fun year(value: String): Int {
        val y = value.toInt()
        return if (value.length == 2) 2000 + y else y
    }

    private fun parseDate(text: String): LocalDate? {
        // Prefer ISO dates before the generic day/month/year range matcher.
        datePatterns[0].find(text)?.let {
            return runCatching { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.getOrNull()
        }
        rangePattern.find(text)?.let { m ->
            return runCatching { LocalDate.of(year(m.groupValues[3]), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()
        }
        datePatterns[1].find(text)?.let {
            return runCatching { LocalDate.of(year(it.groupValues[3]), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }.getOrNull()
        }
        datePatterns[2].find(text)?.let {
            val month = months[it.groupValues[2].take(3).uppercase(Locale.ROOT)] ?: return null
            return runCatching { LocalDate.of(it.groupValues[3].toInt(), month, it.groupValues[1].toInt()) }.getOrNull()
        }
        return null
    }
}
