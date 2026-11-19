package com.relab.core

/** Exports one chronological out-of-sample row per evaluated date and session. */
object WalkForwardCsvExporter {
    const val HEADER = "market,session_id,date,training_records,actual_open_family,global_open_baseline,global_open_correct,weekday_open_baseline,weekday_open_correct,previous_state_open_baseline,previous_state_open_correct,actual_close_family,global_close_baseline,global_close_correct,weekday_close_baseline,weekday_close_correct,previous_state_close_baseline,previous_state_close_correct"

    fun export(analyses: List<MarketAnalysis>): String {
        val rows = analyses
            .sortedWith(compareBy<MarketAnalysis> { it.market.name }.thenBy { it.sessionId })
            .flatMap { analysis ->
                analysis.walkForwardRows.mapIndexed { index, row ->
                    listOf(
                        analysis.market.name,
                        analysis.sessionId,
                        row.date.toString(),
                        (index + 1).toString(),
                        row.actualOpenFamilySequence,
                        row.globalModePrediction,
                        (row.globalModePrediction == row.actualOpenFamilySequence).toString(),
                        row.weekdayModePrediction,
                        (row.weekdayModePrediction == row.actualOpenFamilySequence).toString(),
                        row.previousStatePrediction,
                        (row.previousStatePrediction == row.actualOpenFamilySequence).toString(),
                        row.actualCloseFamilySequence,
                        row.closeGlobalModePrediction,
                        (row.closeGlobalModePrediction == row.actualCloseFamilySequence).toString(),
                        row.closeWeekdayModePrediction,
                        (row.closeWeekdayModePrediction == row.actualCloseFamilySequence).toString(),
                        row.closePreviousStatePrediction,
                        (row.closePreviousStatePrediction == row.actualCloseFamilySequence).toString()
                    ).joinToString(",") { escape(it) }
                }
            }
        return (listOf(HEADER) + rows).joinToString("\n")
    }

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value
}
