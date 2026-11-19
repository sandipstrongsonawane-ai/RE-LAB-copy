package com.relab.core

/** Portable audit of the most recent two-window regime comparison per market/session. */
object MarketRegimeChangeCsvExporter {
    const val HEADER = "market,session,feature,previous_window_records,recent_window_records,total_variation,permutation_p_value,adjusted_q_value,status,explanation"

    fun export(findings: List<RegimeChangeFinding>): String = buildString {
        appendLine(HEADER)
        findings.sortedWith(compareBy<RegimeChangeFinding> { it.market.name }.thenBy { it.sessionId }.thenBy { it.feature })
            .forEach { row ->
                appendLine(listOf(
                    row.market.name, row.sessionId, row.feature,
                    row.previousWindowRecords.toString(), row.recentWindowRecords.toString(),
                    row.totalVariation?.toString().orEmpty(), row.permutationPValue?.toString().orEmpty(),
                    row.adjustedQValue?.toString().orEmpty(), row.status.name, row.explanation
                ).joinToString(",") { quote(it) })
            }
    }

    private fun quote(value: String): String = "${'"'}${value.replace("${'"'}", "${'"'}${'"'}")}${'"'}"
}
