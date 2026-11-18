package com.relab.core

import java.time.LocalDate

data class CandidateWalkForwardCandidate(
    val panna: String,
    val selectedCount: Int,
    val exactHits: Int,
    val hitRate: Double,
    val lowSample: Boolean
)

data class CandidateWalkForwardMetrics(
    val evaluatedDates: Int,
    val insufficientHistoryDates: Int,
    val top1Hits: Int,
    val top3Hits: Int,
    val top5Hits: Int,
    val top10Hits: Int,
    val meanReciprocalRank: Double?
) {
    fun hitRate(hits: Int): Double? =
        if (evaluatedDates == 0) null else hits.toDouble() / evaluatedDates
}

data class CandidateFeedbackAvailability(
    val targetDate: LocalDate,
    val verifiedPredictionsAvailable: Int
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
    val warning: String,
    val baselineMetrics: CandidateWalkForwardMetrics = CandidateWalkForwardMetrics(0, 0, 0, 0, 0, 0, null),
    val learningEnabledMetrics: CandidateWalkForwardMetrics = CandidateWalkForwardMetrics(0, 0, 0, 0, 0, 0, null),
    val feedbackAvailability: List<CandidateFeedbackAvailability> = emptyList()
) {
    fun hitRate(hits: Int): Double? =
        if (evaluatedDates == 0) null else hits.toDouble() / evaluatedDates
}

/**
 * Evaluates baseline and learning-enabled ranking chronologically. Each target sees
 * only chart records strictly earlier than itself, and only GREEN/RED outcomes whose
 * verifiedAt is before target-day UTC midnight. Target actuals are scored after ranking.
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
        // The start date limits scored targets, not the historical training window.
        val targetRecords = eligibleRecords.filter {
            startDateInclusive == null || !it.date.isBefore(startDateInclusive)
        }

        val selectedCounts = mutableMapOf<String, Int>()
        val exactHitsByCandidate = mutableMapOf<String, Int>()
        val baselineCounts = MutableMetrics()
        val learnedCounts = MutableMetrics()
        val feedbackAvailability = mutableListOf<CandidateFeedbackAvailability>()
        val priorRecords = mutableListOf<ChartRecord>()
        var eligibleIndex = 0

        targetRecords.forEach { actual ->
            while (eligibleIndex < eligibleRecords.size &&
                eligibleRecords[eligibleIndex].date.isBefore(actual.date)) {
                priorRecords += eligibleRecords[eligibleIndex]
                eligibleIndex++
            }
            val baseline = CandidateRankingEngine.rankRecords(
                records = priorRecords, market = market, sessionId = sessionId,
                targetDate = actual.date, side = side, limit = topN,
                minimumHistory = minimumHistory
            )
            val learned = CandidateRankingEngine.rankRecords(
                records = priorRecords, market = market, sessionId = sessionId,
                targetDate = actual.date, side = side, limit = topN,
                minimumHistory = minimumHistory, learningLedger = learningLedger
            )
            val availableFeedback = VerifiedPredictionFeedbackEngine.analyze(
                learningLedger, market, sessionId, actual.date, side
            ).verifiedPredictions
            feedbackAvailability += CandidateFeedbackAvailability(actual.date, availableFeedback)

            if (!baseline.abstained) {
                baselineCounts.record(
                    baseline.candidates.map { it.panna },
                    if (side == PannaSide.OPEN) actual.openPanna else actual.closePanna
                )
            }
            if (!learned.abstained) {
                val actualPanna = if (side == PannaSide.OPEN) actual.openPanna else actual.closePanna
                learnedCounts.record(learned.candidates.map { it.panna }, actualPanna)
                learned.candidates.forEach { candidate ->
                    selectedCounts[candidate.panna] = (selectedCounts[candidate.panna] ?: 0) + 1
                    if (candidate.panna == actualPanna) {
                        exactHitsByCandidate[candidate.panna] =
                            (exactHitsByCandidate[candidate.panna] ?: 0) + 1
                    }
                }
            }
        }

        val candidateMetrics = selectedCounts.map { (panna, selected) ->
            val hits = exactHitsByCandidate[panna] ?: 0
            CandidateWalkForwardCandidate(
                panna = panna, selectedCount = selected, exactHits = hits,
                hitRate = hits.toDouble() / selected, lowSample = selected < 5
            )
        }.sortedWith(
            compareByDescending<CandidateWalkForwardCandidate> { it.hitRate }
                .thenByDescending { it.selectedCount }.thenBy { it.panna }
        )

        val baselineMetrics = baselineCounts.toReport(targetRecords.size)
        val learningMetrics = learnedCounts.toReport(targetRecords.size)
        val evaluated = learningMetrics.evaluatedDates
        val warning = when {
            evaluated == 0 -> "No target dates had at least $minimumHistory strictly earlier eligible records. No hit rate is available."
            evaluated < 30 -> "Low sample: only $evaluated historical target dates were evaluated. Rates are descriptive and unstable."
            else -> "Historical backtest only. Results do not imply future probability or guarantee."
        }

        return CandidateWalkForwardReport(
            market = market, sessionId = sessionId, side = side,
            minimumHistory = minimumHistory, topN = topN,
            totalTargetDates = targetRecords.size,
            evaluatedDates = learningMetrics.evaluatedDates,
            insufficientHistoryDates = learningMetrics.insufficientHistoryDates,
            top1Hits = learningMetrics.top1Hits, top3Hits = learningMetrics.top3Hits,
            top5Hits = learningMetrics.top5Hits, top10Hits = learningMetrics.top10Hits,
            meanReciprocalRank = learningMetrics.meanReciprocalRank,
            candidates = candidateMetrics, exploratory = allowSingleSourceExploration,
            warning = warning, baselineMetrics = baselineMetrics,
            learningEnabledMetrics = learningMetrics,
            feedbackAvailability = feedbackAvailability
        )
    }

    private class MutableMetrics {
        var evaluatedDates = 0
        var top1Hits = 0
        var top3Hits = 0
        var top5Hits = 0
        var top10Hits = 0
        var reciprocalRankTotal = 0.0

        fun record(candidates: List<String>, actualPanna: String) {
            evaluatedDates++
            val rank = candidates.indexOf(actualPanna).takeIf { it >= 0 }?.plus(1)
            if (rank == 1) top1Hits++
            if (rank != null && rank <= 3) top3Hits++
            if (rank != null && rank <= 5) top5Hits++
            if (rank != null && rank <= 10) top10Hits++
            if (rank != null) reciprocalRankTotal += 1.0 / rank
        }

        fun toReport(totalTargets: Int) = CandidateWalkForwardMetrics(
            evaluatedDates = evaluatedDates,
            insufficientHistoryDates = totalTargets - evaluatedDates,
            top1Hits = top1Hits, top3Hits = top3Hits,
            top5Hits = top5Hits, top10Hits = top10Hits,
            meanReciprocalRank = if (evaluatedDates == 0) null
                else reciprocalRankTotal / evaluatedDates
        )
    }
}
