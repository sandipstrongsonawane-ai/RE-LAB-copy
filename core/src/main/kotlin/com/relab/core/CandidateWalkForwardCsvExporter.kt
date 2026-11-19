package com.relab.core

/** Exports chronological candidate-ranking backtest summaries, separating strict consensus from exploratory rows. */
object CandidateWalkForwardCsvExporter {
    const val HEADER = "market,session_id,side,exploratory,minimum_history,top_n,total_target_dates,evaluated_dates,insufficient_history_dates,baseline_top1_hits,baseline_top3_hits,baseline_top5_hits,baseline_top10_hits,baseline_mrr,learning_top1_hits,learning_top3_hits,learning_top5_hits,learning_top10_hits,learning_mrr,warning"

    fun export(reports: List<CandidateWalkForwardReport>): String {
        val rows = reports.sortedWith(
            compareBy<CandidateWalkForwardReport> { it.market.name }
                .thenBy { it.sessionId }
                .thenBy { it.side.name }
                .thenBy { it.exploratory }
        ).map { report ->
            listOf(
                report.market.name,
                report.sessionId,
                report.side.name,
                report.exploratory.toString(),
                report.minimumHistory.toString(),
                report.topN.toString(),
                report.totalTargetDates.toString(),
                report.evaluatedDates.toString(),
                report.insufficientHistoryDates.toString(),
                report.baselineMetrics.top1Hits.toString(),
                report.baselineMetrics.top3Hits.toString(),
                report.baselineMetrics.top5Hits.toString(),
                report.baselineMetrics.top10Hits.toString(),
                report.baselineMetrics.meanReciprocalRank?.toString().orEmpty(),
                report.learningEnabledMetrics.top1Hits.toString(),
                report.learningEnabledMetrics.top3Hits.toString(),
                report.learningEnabledMetrics.top5Hits.toString(),
                report.learningEnabledMetrics.top10Hits.toString(),
                report.learningEnabledMetrics.meanReciprocalRank?.toString().orEmpty(),
                report.warning
            ).joinToString(",") { escape(it) }
        }
        return (listOf(HEADER) + rows).joinToString("\n")
    }

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value
}
