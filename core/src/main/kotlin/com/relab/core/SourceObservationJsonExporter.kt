package com.relab.core

import java.time.Instant

/**
 * Lossless JSON representation of the raw observation ledger.
 * This is a portable interchange format, not a derived/reconciled chart.
 */
object SourceObservationJsonExporter {
    fun export(observations: List<SourceObservation>, generatedAt: Instant): String = buildString {
        appendLine("{")
        appendLine("  \"schema_version\": 1,")
        appendLine("  \"generated_at\": ${quote(generatedAt.toString())},")
        appendLine("  \"record_count\": ${observations.size},")
        appendLine("  \"observations\": [")
        observations.forEachIndexed { index, item ->
            appendLine("    {")
            appendLine("      \"market\": ${quote(item.market.name)},")
            appendLine("      \"session_id\": ${quote(item.sessionId)},")
            appendLine("      \"date\": ${quote(item.date.toString())},")
            appendLine("      \"source_id\": ${quote(item.sourceId)},")
            appendLine("      \"source_record_id\": ${nullable(item.sourceRecordId)},")
            appendLine("      \"source_url\": ${nullable(item.sourceUrl)},")
            appendLine("      \"retrieved_at\": ${quote(item.retrievedAt.toString())},")
            appendLine("      \"published_at\": ${nullable(item.publishedAt?.toString())},")
            appendLine("      \"raw_text\": ${quote(item.rawText)},")
            appendLine("      \"normalized_result\": ${nullable(item.normalizedResult)},")
            appendLine("      \"status\": ${quote(item.status.name)}")
            append("    }")
            if (index != observations.lastIndex) append(',')
            appendLine()
        }
        appendLine("  ]")
        appendLine("}")
    }

    private fun nullable(value: String?): String = value?.let(::quote) ?: "null"

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
