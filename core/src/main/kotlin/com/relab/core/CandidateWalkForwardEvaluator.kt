package com.relab.core

import java.time.LocalDate

data class CandidateWalkForwardCandidate(
    val panna: String,
    val selectedCount: Int,
    val exactHits: Int,
    val hitRate: Double,
    val lowSample: Boolean
)

data class CandidateWalkForwardReport(
    val market: Market,
    val sessionId: String,
    val side: PannaSide,
    val minimumHistory: Int,
    val topN: Int,
    val totalTargetDates: Int,
    val evaluatedDates: Int,
    val insufficientHistoryDates: Int,
    val top1Hits: Int,
    val top3Hits: Int,
    val top5Hits: Int,
    val top10Hits: Int,
    val meanReciprocalRank: Double?,
    val candidates: List<CandidateWalkForwardCandidate>,
    val exploratory: Boolean,
    val warning: String
) {
    fun hitRate(hits: Int): Double? =
        if (evaluatedDates == 0) null else hits.toDouble() / evaluatedDates
}

/**
 * Evaluates the existing candidate ranker chronologically. For each target date,
 * the ranker receives the full ledger but is constrained to dates strictly earlier
 * than that target. The target's actual result is used only after ranking is complete.
 *
 * This is a historical evaluation, not a future prediction or probability estimate.
 */
object CandidateWalkForwardEvaluator {
    fun evaluate(
        observations: List<SourceObservation>,
        market: Market,
        sessionId: String,
        side: PannaSide,
        minimumHistory: Int = 5,
        topN: Int = 10,
        allowSingleSourceExploration: Boolean = false,
        startDateInclusive: LocalDate? = null,
        endDateExclusive: LocalDate? = null,
        learningLedger: PredictionLearningLedger = PredictionLearningLedger()
    ): CandidateWalkForwardReport {
        require(sessionId.isNotBlank()) { "Session ID is required" }
        require(minimumHistory >= 1) { "Minimum history must be at least 1" }
        require(topN in 1..100) { "Top N must be between 1 and 100" }
        require(startDateInclusive == null || endDateExclusive == null ||
            startDateInclusive.isBefore(endDateExclusive)) {
            "End date must be after start date"
        }

        val eligibleRecords = (if (allowSingleSourceExploration) {
            ReconciledChartBuilder.buildExploratoryRecords(observations)
        } else {
            ReconciledChartBuilder.build(observations).records
        }).asSequence()
            .filter { it.market == market && it.sessionId == sessionId }
            .filter { endDateExclusive == null || it.date.isBefore(endDateExclusive) }
            .sortedBy { it.date }
            .toList()
        // The start date limits which rows are scored as targets, not the historical
        // training window. Earlier eligible records before the start date remain valid.
        val targetRecords = eligibleRecords.filter {
            startDateInclusive == null || !it.date.isBefore(startDateInclusive)
        }

        val selectedCounts = mutableMapOf<String, Int>()
        val exactHitsByCandidate = mutableMapOf<String, Int>()
        var evaluated = 0
        var top1 = 0
        var top3 = 0
        var top5 = 0
        var top10 = 0
        var reciprocalRankTotal = 0.0

        val priorRecords = mutableListOf<ChartRecord>()
        var eligibleIndex = 0
        targetRecords.forEach { actual ->
            while (eligibleIndex < eligibleRecords.size &&
                eligibleRecords[eligibleIndex].date.isBefore(actual.date)) {
                priorRecords += eligibleRecords[eligibleIndex]
                eligibleIndex++
            }
            val ranking = CandidateRankingEngine.rankRecords(
                records = priorRecords,
                market = market,
                sessionId = sessionId,
                targetDate = actual.date,
                side = side,
                limit = topN,
                minimumHistory = minimumHistory,
                learningLedger = learningLedger
            )
            if (!ranking.abstained) {
                evaluated++
                val actualPanna = if (side == PannaSide.OPEN) actual.openPanna else actual.closePanna
                val actualRank = ranking.candidates.indexOfFirst { it.panna == actualPanna }
                    .takeIf { it >= 0 }
                    ?.plus(1)

                if (actualRank == 1) top1++
                if (actualRank != null && actualRank <= 3) top3++
                if (actualRank != null && actualRank <= 5) top5++
                if (actualRank != null && actualRank <= 10) top10++
                if (actualRank != null) reciprocalRankTotal += 1.0 / actualRank

                ranking.candidates.forEach { candidate ->
                    selectedCounts[candidate.panna] = (selectedCounts[candidate.panna] ?: 0) + 1
                    if (candidate.panna == actualPanna) {
                        exactHitsByCandidate[candidate.panna] =
                            (exactHitsByCandidate[candidate.panna] ?: 0) + 1
                    }
                }
            }
            // The pointer adds this target only before the next later target is ranked.
        }

        val candidateMetrics = selectedCounts.map { (panna, selected) ->
            val hits = exactHitsByCandidate[panna] ?: 0
            CandidateWalkForwardCandidate(
                panna = panna,
                selectedCount = selected,
                exactHits = hits,
                hitRate = hits.toDouble() / selected,
                lowSample = selected < 5
            )
        }.sortedWith(
            compareByDescending<CandidateWalkForwardCandidate> { it.hitRate }
                .thenByDescending { it.selectedCount }
                .thenBy { it.panna }
        )

        val insufficient = targetRecords.size - evaluated
        val warning = when {
            evaluated == 0 -> "No target dates had at least $minimumHistory strictly earlier eligible records. No hit rate is available."
            evaluated < 30 -> "Low sample: only $evaluated historical target dates were evaluated. Rates are descriptive and unstable."
            else -> "Historical backtest only. Results do not imply future probability or guarantee."
        }

        return CandidateWalkForwardReport(
            market = market,
            sessionId = sessionId,
            side = side,
            minimumHistory = minimumHistory,
            topN = topN,
            totalTargetDates = targetRecords.size,
            evaluatedDates = evaluated,
            insufficientHistoryDates = insufficient,
            top1Hits = top1,
            top3Hits = top3,
            top5Hits = top5,
            top10Hits = top10,
            meanReciprocalRank = if (evaluated == 0) null else reciprocalRankTotal / evaluated,
            candidates = candidateMetrics,
            exploratory = allowSingleSourceExploration,
            warning = warning
        )
    }
}
