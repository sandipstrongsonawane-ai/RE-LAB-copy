package com.relab.core

/** Exports validated, multi-publisher consensus records with reproducible derived features. */
object DerivedChartCsvExporter {
    const val HEADER = "market,session_id,date,open_panna,jodi,close_panna,open_ank,close_ank,open_family_sequence,close_family_sequence,open_jodi_family,close_jodi_family,open_panna_type,close_panna_type,reverse_open_panna,reverse_close_panna,source_ids,published_at,observed_at"

    fun export(records: List<ChartRecord>): String {
        val rows = records.sortedWith(compareBy<ChartRecord> { it.market.name }.thenBy { it.sessionId }.thenBy { it.date })
        val lines = rows.map { record ->
            val features = (ChartMath.validate(record) as? ValidationResult.Valid)?.features
                ?: throw IllegalArgumentException("Cannot export invalid chart record: ${record.market}/${record.sessionId}/${record.date}")
            listOf(
                record.market.name,
                record.sessionId,
                record.date.toString(),
                record.openPanna,
                record.jodi,
                record.closePanna,
                features.openAnk.toString(),
                features.closeAnk.toString(),
                features.openFamilySequence.joinToString(""),
                features.closeFamilySequence.joinToString(""),
                features.openJodiFamily.toString(),
                features.closeJodiFamily.toString(),
                features.openType.name,
                features.closeType.name,
                features.reverseOpenPanna,
                features.reverseClosePanna,
                record.sourceId,
                record.publishedAt?.toString().orEmpty(),
                record.observedAt?.toString().orEmpty()
            ).joinToString(",") { escape(it) }
        }
        return (listOf(HEADER) + lines).joinToString("\n")
    }

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value
}
