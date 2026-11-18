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
            val expectedWeekdays = (0 until matrix.dayColumns.size)
                .map { ((start.dayOfWeek.value - 1 + it) % 7) + 1 }
            if (expectedDays !in 5..7 || expectedDays != matrix.dayColumns.size ||
                end != start.plusDays((expectedDays - 1).toLong()) ||
                matrix.dayColumns.map { it.second } != expectedWeekdays
            ) {
                skipped++
                issues += "Weekly row ${rowIndex + 2}: date range does not match detected weekday columns"
                return@forEachIndexed
            }

            matrix.dayColumns.forEachIndexed { dayOffset, (columnIndex, weekday) ->
                val date = start.plusDays(dayOffset.toLong())
                if (date.dayOfWeek.value != weekday || date.isAfter(end) ||
                    date.dayOfWeek.value !in market.activeDays.map { it.value }) {
                    return@forEachIndexed
                }
                val cell = cells[columnIndex]
                val compact = cell.replace(Regex("\\s+"), "")
                val raw = "${cells[matrix.dateIndex]} | ${weekdayAliases.entries.first { it.value == weekday }.key} | $cell"
                val cleaned = compact.trim('*')
                val statusAndResult = when {
                    compact.isBlank() || cleaned.isBlank() || cleaned.matches(Regex("[-—–]+")) ->
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
        // Keep every source row, including repeated dates: differing values must remain visible as conflicts.
        return ChartParserResult(id, observations, skipped, issues)
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
            val cyclicWeekdays = if (days.isEmpty()) emptyList() else
                (0 until days.size).map { ((days.first().second - 1 + it) % 7) + 1 }
            if (days.size in 5..7 && days.map { it.second }.distinct().size == days.size &&
                days.map { it.second } == cyclicWeekdays
            ) return Matrix(table, dateIndex, days)
        }
        return null
    }

    private fun parseRange(value: String): Pair<LocalDate, LocalDate>? {
        // Supports publisher variants such as:
        // 29/12/25TO04/01/26, 16-11-2026 to 22-11-2026,
        // and 16/11 to 22/11 2026 (year printed only once).
        val match = Regex(
            """^\s*(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2,4}))?\s*(?:to|through|[-–])\s*(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2,4}))?(?:\s+(\d{4}))?\s*$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(value) ?: return null
        val day1 = match.groupValues[1].toIntOrNull() ?: return null
        val month1 = match.groupValues[2].toIntOrNull() ?: return null
        val year1Raw = match.groupValues[3].takeIf { it.isNotBlank() }?.toIntOrNull()
        val day2 = match.groupValues[4].toIntOrNull() ?: return null
        val month2 = match.groupValues[5].toIntOrNull() ?: return null
        val year2Raw = (match.groupValues[6].ifBlank { match.groupValues[7] })
            .takeIf { it.isNotBlank() }?.toIntOrNull()
        fun expandYear(year: Int?): Int? = year?.let { if (it < 100) 2000 + it else it }
        val endYear = expandYear(year2Raw) ?: expandYear(year1Raw) ?: return null
        var startYear = expandYear(year1Raw) ?: endYear
        if (year1Raw == null && month1 > month2) startYear--
        return try {
            val start = LocalDate.of(startYear, month1, day1)
            val end = LocalDate.of(endYear, month2, day2)
            if (end.isBefore(start)) null else start to end
        } catch (_: java.time.DateTimeException) {
            null
        }
    }

    private fun normalizeResult(value: String): String? {
        val separated = Regex("^(\\d{3})[-:/ ](\\d{2})[-:/ ](\\d{3})$").matchEntire(value)
        if (separated != null) return "${separated.groupValues[1]}-${separated.groupValues[2]}-${separated.groupValues[3]}"
        if (value.matches(Regex("\\d{8}"))) return "${value.substring(0, 3)}-${value.substring(3, 5)}-${value.substring(5, 8)}"
        return null
    }
}
