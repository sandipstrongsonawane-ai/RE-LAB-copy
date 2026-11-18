package com.relab.core

import java.time.Instant
import java.time.LocalDate

data class CsvImportIssue(val rowNumber: Int, val message: String)
data class CsvImportReport(
    val observations: List<SourceObservation>,
    val issues: List<CsvImportIssue>
)

/**
 * Imports source observations, not resolved chart rows.
 *
 * Required headers: market,session_id,date,source_id,retrieved_at,raw_text,status
 * Optional headers: source_record_id,source_url,published_at,result
 * Dates use ISO-8601 (yyyy-MM-dd); timestamps use Instant ISO-8601.
 */
object SourceCsvImporter {
    private val required = setOf(
        "market", "session_id", "date", "source_id", "retrieved_at", "raw_text", "status"
    )
    private val allowed = required + setOf("source_record_id", "source_url", "published_at", "result")

    fun import(csv: String): CsvImportReport {
        val rows = parseCsv(csv)
        if (rows.isEmpty()) return CsvImportReport(emptyList(), listOf(CsvImportIssue(1, "CSV is empty")))
        val headers = rows.first().map { it.trim().lowercase() }
        if (headers.size != headers.distinct().size) {
            return CsvImportReport(emptyList(), listOf(CsvImportIssue(1, "Duplicate header names")))
        }
        val missing = required - headers.toSet()
        val unknown = headers.toSet() - allowed
        if (missing.isNotEmpty() || unknown.isNotEmpty()) {
            val messages = buildList {
                if (missing.isNotEmpty()) add("Missing headers: ${missing.sorted().joinToString()}")
                if (unknown.isNotEmpty()) add("Unknown headers: ${unknown.sorted().joinToString()}")
            }
            return CsvImportReport(emptyList(), messages.map { CsvImportIssue(1, it) })
        }

        val index = headers.withIndex().associate { it.value to it.index }
        fun value(row: List<String>, name: String): String =
            index[name]?.let { row.getOrNull(it).orEmpty() }.orEmpty()

        val observations = mutableListOf<SourceObservation>()
        val issues = mutableListOf<CsvImportIssue>()
        rows.drop(1).forEachIndexed { offset, row ->
            val line = offset + 2
            try {
                require(row.size == headers.size) { "Expected ${headers.size} columns, found ${row.size}" }
                val market = Market.valueOf(value(row, "market").trim().uppercase())
                val date = LocalDate.parse(value(row, "date").trim())
                val retrievedAt = Instant.parse(value(row, "retrieved_at").trim())
                val publishedAt = value(row, "published_at").trim().takeIf { it.isNotEmpty() }?.let(Instant::parse)
                val rawText = value(row, "raw_text")
                val sourceId = value(row, "source_id").trim()
                val sessionId = value(row, "session_id").trim()
                var status = ObservationStatus.valueOf(value(row, "status").trim().uppercase())
                var result = value(row, "result").trim().takeIf { it.isNotEmpty() }

                if (status == ObservationStatus.PUBLISHED) {
                    val parts = result?.split('-') ?: emptyList()
                    val structurallyValid = parts.size == 3 &&
                        parts[0].length == 3 && parts[1].length == 2 && parts[2].length == 3 &&
                        parts.all { part -> part.all(Char::isDigit) }
                    val chartRecord = if (structurallyValid) ChartRecord(
                        market = market,
                        date = date,
                        openPanna = parts[0],
                        jodi = parts[1],
                        closePanna = parts[2],
                        sourceId = sourceId,
                        sourceRecordId = value(row, "source_record_id").trim().ifEmpty { null },
                        publishedAt = publishedAt,
                        observedAt = retrievedAt,
                        sessionId = sessionId
                    ) else null
                    if (chartRecord == null || ChartMath.validate(chartRecord) !is ValidationResult.Valid) {
                        status = ObservationStatus.MALFORMED
                        result = null
                    }
                } else {
                    result = null
                }

                observations += SourceObservation(
                    market = market,
                    sessionId = sessionId,
                    date = date,
                    sourceId = sourceId,
                    sourceRecordId = value(row, "source_record_id").trim().ifEmpty { null },
                    sourceUrl = value(row, "source_url").trim().ifEmpty { null },
                    retrievedAt = retrievedAt,
                    publishedAt = publishedAt,
                    rawText = rawText,
                    normalizedResult = result,
                    status = status
                )
            } catch (error: Exception) {
                issues += CsvImportIssue(line, error.message ?: "Invalid row")
            }
        }
        return CsvImportReport(observations, issues)
    }

    /** RFC-4180-style quoted fields, escaped quotes, and embedded newlines. */
    private fun parseCsv(input: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closedQuote = false
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (quoted) {
                when {
                    c == '"' && i + 1 < input.length && input[i + 1] == '"' -> {
                        field.append('"')
                        i++
                    }
                    c == '"' -> {
                        quoted = false
                        closedQuote = true
                    }
                    else -> field.append(c)
                }
            } else {
                when (c) {
                    '"' -> {
                        require(field.isEmpty() && !closedQuote) { "Quote inside unquoted field" }
                        quoted = true
                    }
                    ',' -> {
                        row += field.toString()
                        field.setLength(0)
                        closedQuote = false
                    }
                    '\n' -> {
                        row += field.toString().removeSuffix("\r")
                        field.setLength(0)
                        closedQuote = false
                        if (row.any { it.isNotEmpty() }) rows += row.toList()
                        row.clear()
                    }
                    else -> {
                        require(!closedQuote) {
                            "Unexpected character after closing quote at position $i"
                        }
                        field.append(c)
                    }
                }
            }
            i++
        }
        require(!quoted) { "Unclosed quoted field" }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString().removeSuffix("\r")
            rows += row.toList()
        }
        return rows
    }
}
