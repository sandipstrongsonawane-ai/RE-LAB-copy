package com.relab.core

/** Converts only source-reconciled consensus rows into analyzable chart records. */
data class ChartBuildReport(
    val records: List<ChartRecord>,
    val conflictGroups: List<ReconciliationGroup>,
    val singleSourceUnverifiedGroups: List<ReconciliationGroup>,
    val noPublishedResultGroups: List<ReconciliationGroup>,
    val invalidGroups: List<ReconciliationGroup>
)

object ReconciledChartBuilder {
    /**
     * Builds clearly unverified research rows from one-publisher observations.
     * These rows are suitable for exploratory frequency analysis only; they are
     * never promoted to multi-source consensus or used to resolve conflicts.
     */
    fun buildExploratoryRecords(observations: List<SourceObservation>): List<ChartRecord> =
        SourceReconciliation.reconcile(observations).mapNotNull { group ->
            if (group.state != ReconciliationState.CONSENSUS &&
                group.state != ReconciliationState.SINGLE_SOURCE_UNVERIFIED) return@mapNotNull null
            val published = group.observations.filter { it.status == ObservationStatus.PUBLISHED }
            val values = published.mapNotNull { it.normalizedResult }.distinct()
            if (values.size != 1) return@mapNotNull null
            val parts = values.single().split('-')
            if (parts.size != 3) return@mapNotNull null
            val record = ChartRecord(
                market = group.market, date = group.date,
                openPanna = parts[0], jodi = parts[1], closePanna = parts[2],
                sourceId = published.map { it.sourceId }.distinct().sorted().joinToString("+"),
                publishedAt = published.mapNotNull { it.publishedAt }.maxOrNull(),
                observedAt = published.maxOfOrNull { it.retrievedAt },
                sessionId = group.sessionId
            )
            record.takeIf { ChartMath.validate(it) is ValidationResult.Valid }
        }

    fun build(observations: List<SourceObservation>): ChartBuildReport {
        val records = mutableListOf<ChartRecord>()
        val conflicts = mutableListOf<ReconciliationGroup>()
        val unverified = mutableListOf<ReconciliationGroup>()
        val unpublished = mutableListOf<ReconciliationGroup>()
        val invalid = mutableListOf<ReconciliationGroup>()

        SourceReconciliation.reconcile(observations).forEach { group ->
            when (group.state) {
                ReconciliationState.CONFLICT -> conflicts += group
                ReconciliationState.SINGLE_SOURCE_UNVERIFIED -> unverified += group
                ReconciliationState.NO_PUBLISHED_RESULT -> unpublished += group
                ReconciliationState.CONSENSUS -> {
                    val parts = group.agreedResult.orEmpty().split('-')
                    if (parts.size != 3) {
                        invalid += group
                    } else {
                        val published = group.observations.filter { it.status == ObservationStatus.PUBLISHED }
                        val record = ChartRecord(
                            market = group.market,
                            date = group.date,
                            openPanna = parts[0],
                            jodi = parts[1],
                            closePanna = parts[2],
                            sourceId = published.map { it.sourceId }.distinct().sorted().joinToString("+"),
                            publishedAt = published.mapNotNull { it.publishedAt }.maxOrNull(),
                            observedAt = published.maxOfOrNull { it.retrievedAt },
                            sessionId = group.sessionId
                        )
                        if (ChartMath.validate(record) is ValidationResult.Valid) records += record
                        else invalid += group
                    }
                }
            }
        }
        return ChartBuildReport(records, conflicts, unverified, unpublished, invalid)
    }
}
