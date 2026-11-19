package com.relab.core

/**
 * Exports the complete source-observation ledger in the importer's CSV format.
 * Raw source text and source metadata are retained; this is not a result-only export.
 */
object SourceCsvExporter {
    const val HEADER =
        "market,session_id,date,source_id,retrieved_at,raw_text,status,source_record_id,source_url,published_at,result"

    fun export(observations: List<SourceObservation>): String {
        return buildString {
            append(HEADER)
            observations.forEach { row ->
                append('\n')
                append(
                    listOf(
                        row.market.name,
                        row.sessionId,
                        row.date.toString(),
                        row.sourceId,
                        row.retrievedAt.toString(),
                        row.rawText,
                        row.status.name,
                        row.sourceRecordId.orEmpty(),
                        row.sourceUrl.orEmpty(),
                        row.publishedAt?.toString().orEmpty(),
                        row.normalizedResult.orEmpty()
                    ).joinToString(",") { encode(it) }
                )
            }
        }
    }

    private fun encode(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }
}
