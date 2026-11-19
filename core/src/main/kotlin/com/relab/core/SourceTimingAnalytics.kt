package com.relab.core

import java.time.Duration

data class SourceTimingSummary(
    val market: Market,
    val sessionId: String,
    val sourceId: String,
    val observations: Int,
    val publishedObservations: Int,
    val publishedTimestampCount: Int,
    val missingPublishedTimestampCount: Int,
    val negativeLagCount: Int,
    val medianLagMinutes: Long?,
    val minimumLagMinutes: Long?,
    val maximumLagMinutes: Long?
)

/** Publication-to-capture timing only; never substitutes retrieval time for publication time. */
object SourceTimingAnalytics {
    fun summarize(observations: List<SourceObservation>): List<SourceTimingSummary> =
        observations.groupBy { Triple(it.market, it.sessionId, it.sourceId) }
            .map { (key, rows) ->
                val published = rows.filter { it.status == ObservationStatus.PUBLISHED }
                val lags = published.mapNotNull { row ->
                    row.publishedAt?.let { Duration.between(it, row.retrievedAt).toMinutes() }
                }
                val nonNegative = lags.filter { it >= 0 }.sorted()
                SourceTimingSummary(
                    market = key.first,
                    sessionId = key.second,
                    sourceId = key.third,
                    observations = rows.size,
                    publishedObservations = published.size,
                    publishedTimestampCount = published.count { it.publishedAt != null },
                    missingPublishedTimestampCount = published.count { it.publishedAt == null },
                    negativeLagCount = lags.count { it < 0 },
                    medianLagMinutes = median(nonNegative),
                    minimumLagMinutes = nonNegative.minOrNull(),
                    maximumLagMinutes = nonNegative.maxOrNull()
                )
            }
            .sortedWith(compareBy<SourceTimingSummary> { it.market.name }
                .thenBy { it.sessionId }.thenBy { it.sourceId })

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val middle = values.size / 2
        return if (values.size % 2 == 1) values[middle]
        else (values[middle - 1] + values[middle]) / 2
    }

    fun exportCsv(observations: List<SourceObservation>): String {
        val header = listOf(
            "market", "session_id", "source_id", "observations", "published_observations",
            "published_timestamp_count", "missing_published_timestamp_count", "negative_lag_count",
            "median_nonnegative_lag_minutes", "minimum_nonnegative_lag_minutes", "maximum_nonnegative_lag_minutes"
        )
        val lines = mutableListOf(header.joinToString(","))
        summarize(observations).forEach { row ->
            lines += listOf(
                row.market.name, row.sessionId, row.sourceId, row.observations.toString(),
                row.publishedObservations.toString(), row.publishedTimestampCount.toString(),
                row.missingPublishedTimestampCount.toString(), row.negativeLagCount.toString(),
                row.medianLagMinutes?.toString().orEmpty(), row.minimumLagMinutes?.toString().orEmpty(),
                row.maximumLagMinutes?.toString().orEmpty()
            ).joinToString(",") { csv(it) }
        }
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun csv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"" + value.replace("\"", "\"\"") + "\""
        else value
}
