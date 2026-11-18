package com.relab.core

/**
 * Derived Sangam research candidates. These are combinations of independently
 * ranked panna/ank candidates, not outcome probabilities or fixed predictions.
 */
data class HalfSangamCandidate(
    val kind: HalfSangamKind,
    val panna: String,
    val ank: Int,
    val score: Double,
    val supportingPairs: Int
) {
    val label: String get() = when (kind) {
        HalfSangamKind.OPEN_PANNA_CLOSE_ANK -> "$panna-$ank"
        HalfSangamKind.OPEN_ANK_CLOSE_PANNA -> "$ank-$panna"
    }
}

enum class HalfSangamKind { OPEN_PANNA_CLOSE_ANK, OPEN_ANK_CLOSE_PANNA }

data class FullSangamCandidate(
    val openPanna: String,
    val closePanna: String,
    val score: Double,
    val supportingPairs: Int
) {
    val label: String get() = "$openPanna-$closePanna"
}

data class SangamResearchSet(
    val halfSangam: List<HalfSangamCandidate>,
    val fullSangam: List<FullSangamCandidate>
)

object SangamCandidateEngine {
    fun derive(
        openCandidates: List<PannaCandidate>,
        closeCandidates: List<PannaCandidate>,
        limit: Int = 10
    ): SangamResearchSet {
        require(limit in 1..100) { "Candidate limit must be between 1 and 100" }
        if (openCandidates.isEmpty() || closeCandidates.isEmpty()) {
            return SangamResearchSet(emptyList(), emptyList())
        }

        data class Aggregate(var score: Double = 0.0, var count: Int = 0) {
            fun add(openScore: Double, closeScore: Double) {
                score += openScore * closeScore / 100.0
                count++
            }
            fun mean(): Double = if (count == 0) 0.0 else score / count
        }

        val halfOpenPannaCloseAnk = linkedMapOf<Pair<String, Int>, Aggregate>()
        val halfOpenAnkClosePanna = linkedMapOf<Pair<Int, String>, Aggregate>()
        val full = linkedMapOf<Pair<String, String>, Aggregate>()

        openCandidates.forEach { open ->
            closeCandidates.forEach { close ->
                val openAnk = open.panna.sumOf { it.digitToInt() } % 10
                val closeAnk = close.panna.sumOf { it.digitToInt() } % 10
                val pairScore = open.score * close.score / 100.0

                halfOpenPannaCloseAnk.getOrPut(open.panna to closeAnk) { Aggregate() }
                    .add(open.score, close.score)
                halfOpenAnkClosePanna.getOrPut(openAnk to close.panna) { Aggregate() }
                    .add(open.score, close.score)
                full.getOrPut(open.panna to close.panna) { Aggregate() }
                    .add(open.score, close.score)
            }
        }

        val half = buildList {
            halfOpenPannaCloseAnk.forEach { (key, value) ->
                add(HalfSangamCandidate(HalfSangamKind.OPEN_PANNA_CLOSE_ANK, key.first, key.second, value.mean(), value.count))
            }
            halfOpenAnkClosePanna.forEach { (key, value) ->
                add(HalfSangamCandidate(HalfSangamKind.OPEN_ANK_CLOSE_PANNA, key.second, key.first, value.mean(), value.count))
            }
        }.sortedWith(compareByDescending<HalfSangamCandidate> { it.score }
            .thenBy { it.kind.name }.thenBy { it.label })
            .take(limit)

        val fullSangam = full.map { (key, value) ->
            FullSangamCandidate(key.first, key.second, value.mean(), value.count)
        }.sortedWith(compareByDescending<FullSangamCandidate> { it.score }
            .thenBy { it.openPanna }.thenBy { it.closePanna })
            .take(limit)

        return SangamResearchSet(half, fullSangam)
    }
}


data class SangamWalkForwardReport(
    val market: Market,
    val sessionId: String,
    val totalTargetDates: Int,
    val evaluatedDates: Int,
    val insufficientHistoryDates: Int,
    val halfTop1Hits: Int,
    val halfTop3Hits: Int,
    val halfTop5Hits: Int,
    val fullTop1Hits: Int,
    val fullTop3Hits: Int,
    val fullTop5Hits: Int,
    val warning: String
) {
    fun halfHitRate(hits: Int): Double? = if (evaluatedDates == 0) null else hits.toDouble() / evaluatedDates
    fun fullHitRate(hits: Int): Double? = if (evaluatedDates == 0) null else hits.toDouble() / evaluatedDates
}

/**
 * Chronological Sangam backtest. Each target date is scored only after candidates
 * are generated from strictly earlier eligible rows. Consensus is the default;
 * single-publisher exploration must be explicitly requested and remains labelled.
 */
object SangamWalkForwardEvaluator {
    fun evaluate(
        observations: List<SourceObservation>,
        market: Market,
        sessionId: String,
        minimumHistory: Int = 5,
        topN: Int = 10,
        allowSingleSourceExploration: Boolean = false,
        startDateInclusive: java.time.LocalDate? = null,
        endDateExclusive: java.time.LocalDate? = null
    ): SangamWalkForwardReport {
        require(sessionId.isNotBlank()) { "Session ID is required" }
        require(minimumHistory >= 1) { "Minimum history must be at least 1" }
        require(topN in 1..100) { "Top N must be between 1 and 100" }
        require(startDateInclusive == null || endDateExclusive == null ||
            startDateInclusive.isBefore(endDateExclusive)) {
            "End date must be after start date"
        }

        val eligible = (if (allowSingleSourceExploration) {
            ReconciledChartBuilder.buildExploratoryRecords(observations)
        } else {
            ReconciledChartBuilder.build(observations).records
        }).filter { it.market == market && it.sessionId == sessionId }
            .filter { endDateExclusive == null || it.date.isBefore(endDateExclusive) }
            .sortedBy { it.date }
        require(eligible.map { it.date }.distinct().size == eligible.size) {
            "Duplicate dates must be reconciled before Sangam backtesting"
        }

        val targets = eligible.filter { startDateInclusive == null || !it.date.isBefore(startDateInclusive) }
        val prior = mutableListOf<ChartRecord>()
        var index = 0
        var evaluated = 0
        var half1 = 0
        var half3 = 0
        var half5 = 0
        var full1 = 0
        var full3 = 0
        var full5 = 0

        targets.forEach { actual ->
            while (index < eligible.size && eligible[index].date.isBefore(actual.date)) {
                prior += eligible[index]
                index++
            }
            val open = CandidateRankingEngine.rankRecords(
                prior, market, sessionId, actual.date, PannaSide.OPEN,
                limit = topN, minimumHistory = minimumHistory
            )
            val close = CandidateRankingEngine.rankRecords(
                prior, market, sessionId, actual.date, PannaSide.CLOSE,
                limit = topN, minimumHistory = minimumHistory
            )
            if (open.abstained || close.abstained) return@forEach

            val sangam = SangamCandidateEngine.derive(open.candidates, close.candidates, topN)
            val actualOpenAnk = actual.openPanna.sumOf { it.digitToInt() } % 10
            val actualCloseAnk = actual.closePanna.sumOf { it.digitToInt() } % 10
            val actualHalf = setOf("\${actual.openPanna}-\${actualCloseAnk}", "\${actualOpenAnk}-\${actual.closePanna}")
            val actualFull = "\${actual.openPanna}-\${actual.closePanna}"
            val halfRanks = sangam.halfSangam.map { it.label }
            val fullRanks = sangam.fullSangam.map { it.label }
            evaluated++
            if (halfRanks.take(1).any { it in actualHalf }) half1++
            if (halfRanks.take(3).any { it in actualHalf }) half3++
            if (halfRanks.take(5).any { it in actualHalf }) half5++
            if (fullRanks.take(1).contains(actualFull)) full1++
            if (fullRanks.take(3).contains(actualFull)) full3++
            if (fullRanks.take(5).contains(actualFull)) full5++
        }

        val insufficient = targets.size - evaluated
        val warning = when {
            evaluated == 0 -> "No target dates had \${minimumHistory} strictly earlier eligible records. No hit rate is available."
            evaluated < 30 -> "Low sample: only \${evaluated} historical target dates were evaluated. Rates are descriptive and unstable."
            allowSingleSourceExploration -> "Exploratory single-publisher backtest. Not independently verified; no future probability is implied."
            else -> "Consensus-only historical backtest. Past hit rates do not imply future probability or guarantee."
        }
        return SangamWalkForwardReport(
            market, sessionId, targets.size, evaluated, insufficient,
            half1, half3, half5, full1, full3, full5, warning
        )
    }
}
