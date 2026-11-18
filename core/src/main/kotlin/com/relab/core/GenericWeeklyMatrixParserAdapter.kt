package com.relab.core

import org.jsoup.Jsoup
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Publisher-neutral weekly matrix parser. It recognizes a date-range column plus
 * weekday columns, then validates every extracted date/result independently.
 * Publisher-specific adapters remain preferred when a site's layout needs special rules.
 */
object GenericWeeklyMatrixParserAdapter : ChartParserAdapter {
    override val id: String = "generic-weekly-matrix-v1"

    private val weekdayAliases = mapOf(
        "mon" to 1, "monday" to 1,
        "tue" to 2, "tues" to 2, "tuesday" to 2,
        "wed" to 3, "wednesday" to 3,
        "thu" to 4, "thur" to 4, "thurs" to 4, "thursday" to 4,
        "fri" to 5, "friday" to 5,
        "sat" to 6, "saturday" to 6,
        "sun" to 7, "sunday" to 7
    )
    private val dateFormats = listOf(
        DateTimeFormatter.ofPattern("d-M-uuuu", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d/M/uuuu", Locale.ENGLISH),
        DateTimeFormatter.ISO_LOCAL_DATE
    )

    private data class Matrix(val table: org.jsoup.nodes.Element, val dateIndex: Int, val dayColumns: List<Pair<Int, Int>>)

    override fun supports(html: String, pageUrl: String): Boolean =
        runCatching { findMatrix(Jsoup.parse(html, pageUrl)) != null }.getOrDefault(false)

    override fun parse(
        html: String,
        pageUrl: String,
        market: Market,
        sessionId: String,
        retrievedAt: Instant
    ): ChartParserResult {
        val document = Jsoup.parse(html, pageUrl)
        val matrix = findMatrix(document)
            ?: throw IllegalArgumentException("UNSUPPORTED: no date-range plus weekday matrix recognized.")
        val sourceId = URI(pageUrl).host?.lowercase(Locale.ROOT)?.removePrefix("www.")
            ?: throw IllegalArgumentException("UNSUPPORTED: source URL has no publisher host.")
        val observations = mutableListOf<SourceObservation>()
        val issues = mutableListOf<String>()
        var skipped = 0

        matrix.table.select("tr").drop(1).forEachIndexed { rowIndex, row ->
            val cells = row.select("th,td").map { it.text().trim() }
            if (cells.size <= matrix.dateIndex || matrix.dayColumns.any { it.first >= cells.size }) {
                skipped++
                issues += "Weekly row ${rowIndex + 2}: missing date-range or weekday cells"
                return@forEachIndexed
            }
            val range = parseRange(cells[matrix.dateIndex])
            if (range == null) {
                skipped++
                issues += "Weekly row ${rowIndex + 2}: unsupported date range '${cells[matrix.dateIndex]}'"
                return@forEachIndexed
            }
            val (start, end) = range
            val expectedDays = java.time.temporal.ChronoUnit.DAYS.between(start, end).toInt() + 1
            if (expectedDays !in 5..7 || expectedDays != matrix.dayColumns.size ||
                matrix.dayColumns.map { it.second } != (start.dayOfWeek.value..end.dayOfWeek.value).toList() ||
                start.dayOfWeek.value > end.dayOfWeek.value
            ) {
                skipped++
                issues += "Weekly row ${rowIndex + 2}: date range does not match detected weekday columns"
                return@forEachIndexed
            }

            matrix.dayColumns.forEach { (columnIndex, weekday) ->
                val date = start.plusDays((weekday - start.dayOfWeek.value).toLong())
                if (date.isAfter(end) || date.dayOfWeek.value !in market.activeDays.map { it.value }) {
                    return@forEachIndexed
                }
                val cell = cells[columnIndex]
                val compact = cell.replace(Regex("\\s+"), "")
                val raw = "${cells[matrix.dateIndex]} | ${weekdayAliases.entries.first { it.value == weekday }.key} | $cell"
                val cleaned = compact.trim('*')
                val statusAndResult = when {
                    compact.isBlank() || cleaned.matches(Regex("[-—–]+")) ->
                        ObservationStatus.MISSING_FROM_SOURCE to null
                    cleaned.equals("holiday", true) || cleaned.equals("closed", true) ->
                        ObservationStatus.HOLIDAY_CLOSED to null
                    cleaned.equals("pending", true) || cleaned.equals("waiting", true) ->
                        ObservationStatus.PENDING to null
                    else -> {
                        val normalized = normalizeResult(cleaned)
                        if (normalized == null) ObservationStatus.MALFORMED to null
                        else {
                            val parts = normalized.split("-")
                            val record = ChartRecord(
                                market = market, date = date, openPanna = parts[0], jodi = parts[1],
                                closePanna = parts[2], sourceId = sourceId,
                                sourceRecordId = date.toString(), observedAt = retrievedAt,
                                sessionId = sessionId
                            )
                            if (ChartMath.validate(record) is ValidationResult.Valid)
                                ObservationStatus.PUBLISHED to normalized
                            else ObservationStatus.MALFORMED to null
                        }
                    }
                }
                observations += SourceObservation(
                    market = market, sessionId = sessionId, date = date,
                    sourceId = sourceId, sourceUrl = pageUrl, sourceRecordId = date.toString(),
                    retrievedAt = retrievedAt, rawText = raw,
                    normalizedResult = statusAndResult.second, status = statusAndResult.first
                )
                if (statusAndResult.first == ObservationStatus.MALFORMED) {
                    issues += "Weekly row ${rowIndex + 2}, weekday column $weekday: unrecognized or invalid result '$cell'"
                }
            }
        }
        return ChartParserResult(id, observations.distinctBy { it.date }, skipped, issues)
    }

    private fun findMatrix(document: org.jsoup.nodes.Document): Matrix? {
        document.select("table").forEach { table ->
            val header = table.select("tr").firstOrNull()?.select("th,td") ?: return@forEach
            val labels = header.map { it.text().trim().lowercase(Locale.ROOT).replace(Regex("[^a-z ]"), "").trim() }
            val dateIndex = labels.indexOfFirst { it in setOf("date range", "week", "week range", "dates", "date") }
            if (dateIndex < 0) return@forEach
            val days = labels.mapIndexedNotNull { index, label ->
                weekdayAliases[label]?.let { index to it }
            }
            if (days.size in 5..7 && days.map { it.second }.distinct().size == days.size &&
                days.map { it.second } == (days.first().second..days.last().second).toList()
            ) return Matrix(table, dateIndex, days)
        }
        return null
    }

    private fun parseRange(value: String): Pair<LocalDate, LocalDate>? {
        val normalized = value.trim().replace(Regex("\\s+(to|through|-)\\s+", RegexOption.IGNORE_CASE), " to ")
        val pieces = normalized.split(Regex("\\s+to\\s+", RegexOption.IGNORE_CASE))
        if (pieces.size != 2) return null
        fun parseDate(raw: String): LocalDate? = dateFormats.firstNotNullOfOrNull { formatter ->
            try { LocalDate.parse(raw.trim(), formatter) } catch (_: DateTimeParseException) { null }
        }
        val start = parseDate(pieces[0]) ?: return null
        val end = parseDate(pieces[1]) ?: return null
        return if (!end.isBefore(start)) start to end else null
    }

    private fun normalizeResult(value: String): String? {
        val separated = Regex("^(\\d{3})[-:/ ](\\d{2})[-:/ ](\\d{3})$").matchEntire(value)
        if (separated != null) return "${separated.groupValues[1]}-${separated.groupValues[2]}-${separated.groupValues[3]}"
        if (value.matches(Regex("\\d{8}"))) return "${value.substring(0, 3)}-${value.substring(3, 5)}-${value.substring(5, 8)}"
        return null
    }
}
