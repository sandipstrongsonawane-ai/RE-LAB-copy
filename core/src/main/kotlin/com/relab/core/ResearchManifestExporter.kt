package com.relab.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/** Machine-readable provenance manifest for a research export. */
object ResearchManifestExporter {
    const val FORMAT_VERSION = "1"
    const val RULESET_VERSION = "relab-chart-math-1"

    fun export(
        observations: List<SourceObservation>,
        consensusRecords: List<ChartRecord>,
        analyses: List<MarketAnalysis>,
        generatedAt: Instant
    ): String {
        val sourceCsv = SourceCsvExporter.export(observations)
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(sourceCsv.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val groups = SourceReconciliation.reconcile(observations)
        val sessions = observations.groupBy { it.market.name to it.sessionId }
            .toSortedMap(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .map { (key, rows) ->
                """{"market":${quote(key.first)},"session_id":${quote(key.second)},"start_date":${quote(rows.minOf { it.date }.toString())},"end_date":${quote(rows.maxOf { it.date }.toString())},"observations":${rows.size}}"""
            }
        val walkForwardRows = analyses.sumOf { it.walkForwardRows.size }
        val timingSummaries = SourceTimingAnalytics.summarize(observations).map { row ->
            """{"market":${quote(row.market.name)},"session_id":${quote(row.sessionId)},"source_id":${quote(row.sourceId)},"observations":${row.observations},"published_observations":${row.publishedObservations},"published_timestamp_count":${row.publishedTimestampCount},"missing_published_timestamp_count":${row.missingPublishedTimestampCount},"negative_lag_count":${row.negativeLagCount},"median_nonnegative_lag_minutes":${row.medianLagMinutes ?: "null"},"minimum_nonnegative_lag_minutes":${row.minimumLagMinutes ?: "null"},"maximum_nonnegative_lag_minutes":${row.maximumLagMinutes ?: "null"}}"""
        }
        val publishedCount = observations.count { it.status == ObservationStatus.PUBLISHED }
        val publishedWithTimestamp = observations.count {
            it.status == ObservationStatus.PUBLISHED && it.publishedAt != null
        }
        val negativeLagCount = observations.count {
            it.status == ObservationStatus.PUBLISHED && it.publishedAt != null &&
                it.retrievedAt.isBefore(it.publishedAt)
        }
        return buildString {
            appendLine("{")
            appendLine("  \"format_version\": ${quote(FORMAT_VERSION)},")
            appendLine("  \"ruleset_version\": ${quote(RULESET_VERSION)},")
            appendLine("  \"generated_at\": ${quote(generatedAt.toString())},")
            appendLine("  \"source_ledger_sha256\": ${quote(checksum)},")
            appendLine("  \"source_ledger_csv_utf8_bytes\": ${sourceCsv.toByteArray(StandardCharsets.UTF_8).size},")
            appendLine("  \"source_observation_count\": ${observations.size},")
            appendLine("  \"source_status_counts\": {")
            appendLine(ObservationStatus.entries.joinToString(",\n") { status ->
                "    ${quote(status.name)}: ${observations.count { it.status == status }}"
            })
            appendLine("  },")
            appendLine("  \"distinct_source_ids\": ${observations.map { it.sourceId }.distinct().size},")
            appendLine("  \"reconciliation_groups\": ${groups.size},")
            appendLine("  \"consensus_groups\": ${groups.count { it.state == ReconciliationState.CONSENSUS }},")
            appendLine("  \"single_source_unverified_groups\": ${groups.count { it.state == ReconciliationState.SINGLE_SOURCE_UNVERIFIED }},")
            appendLine("  \"conflict_groups\": ${groups.count { it.state == ReconciliationState.CONFLICT }},")
            appendLine("  \"consensus_chart_records_exported\": ${consensusRecords.size},")
            appendLine("  \"walk_forward_out_of_sample_rows\": $walkForwardRows,")
            appendLine("  \"published_observations_with_timestamp\": $publishedWithTimestamp,")
            appendLine("  \"published_observations_missing_timestamp\": ${publishedCount - publishedWithTimestamp},")
            appendLine("  \"negative_publication_lag_observations\": $negativeLagCount,")
            appendLine("  \"source_timing_summary\": [")
            appendLine(timingSummaries.joinToString(",\n") { "    $it" })
            appendLine("  ],")
            appendLine("  \"sessions\": [")
            appendLine(sessions.joinToString(",\n") { "    $it" })
            appendLine("  ]")
            appendLine("}")
        }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
