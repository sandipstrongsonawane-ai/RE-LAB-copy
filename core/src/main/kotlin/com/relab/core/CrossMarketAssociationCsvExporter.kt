package com.relab.core

/** Stable CSV representation of exploratory cross-market association findings. */
object CrossMarketAssociationCsvExporter {
    const val HEADER = "first_market,first_session,second_market,second_session,lag_days,metric,paired_records,mutual_information_bits,normalized_mutual_information,empirical_p_value,adjusted_p_value,statistically_unusual"

    fun export(report: CrossMarketReverseEngineeringReport?): String {
        if (report == null) return HEADER
        val rows = report.associations.map { association ->
            listOf(
                association.firstMarket.name,
                association.firstSessionId,
                association.secondMarket.name,
                association.secondSessionId,
                association.lagDays.toString(),
                association.metric.name,
                association.pairedRecords.toString(),
                association.mutualInformationBits.toString(),
                association.normalizedMutualInformation.toString(),
                association.empiricalPValue.toString(),
                association.adjustedPValue.toString(),
                association.statisticallyUnusual.toString()
            ).joinToString(",") { escape(it) }
        }
        return (listOf(HEADER) + rows).joinToString("\n")
    }

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value
}
