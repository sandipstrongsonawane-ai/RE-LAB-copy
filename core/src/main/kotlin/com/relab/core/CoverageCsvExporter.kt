package com.relab.core

/** Exports the full expected-day coverage ledger for independent audit. */
object CoverageCsvExporter {
    private val header = listOf(
        "market", "session_id", "start_date", "end_date", "expected_trading_days",
        "date", "coverage_state", "observation_count", "published_source_count",
        "pending_source_count", "holiday_source_count", "missing_source_count",
        "malformed_source_count", "distinct_published_results"
    )

    fun export(reports: List<CoverageReport>): String = buildString {
        appendLine(header.joinToString(",") { csv(it) })
        reports.sortedWith(compareBy<CoverageReport> { it.market.name }.thenBy { it.sessionId })
            .forEach { report ->
                report.days.forEach { day ->
                    val values = listOf(
                        report.market.name,
                        report.sessionId,
                        report.startDate.toString(),
                        report.endDate.toString(),
                        report.expectedTradingDays.toString(),
                        day.date.toString(),
                        day.state.name,
                        day.observationCount.toString(),
                        day.publishedSourceCount.toString(),
                        day.pendingSourceCount.toString(),
                        day.holidaySourceCount.toString(),
                        day.missingSourceCount.toString(),
                        day.malformedSourceCount.toString(),
                        day.distinctPublishedResults.joinToString(";")
                    )
                    appendLine(values.joinToString(",") { csv(it) })
                }
            }
    }

    private fun csv(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$escaped\"" else escaped
    }
}
