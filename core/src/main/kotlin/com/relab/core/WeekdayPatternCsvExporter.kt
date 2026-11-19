package com.relab.core

/** CSV export for every weekday reverse-engineering finding, including explicit abstentions. */
object WeekdayPatternCsvExporter {
    fun export(findings: List<WeekdayPatternFinding>): String = buildString {
        appendLine("market,session_id,feature,records,active_weekdays,normalized_mutual_information,permutation_p_value,adjusted_q_value,status,explanation")
        findings.forEach { finding ->
            appendLine(listOf(
                finding.market.name,
                finding.sessionId,
                finding.feature,
                finding.records.toString(),
                finding.activeWeekdays.toString(),
                finding.normalizedMutualInformation?.toString().orEmpty(),
                finding.permutationPValue?.toString().orEmpty(),
                finding.adjustedQValue?.toString().orEmpty(),
                finding.status.name,
                finding.explanation
            ).joinToString(",") { csv(it) })
        }
    }

    private fun csv(value: String): String = "${'"'}${value.replace("${'"'}", "${'"'}${'"'}")}${'"'}"
}
