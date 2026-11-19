package com.relab.core

import java.time.Instant
import java.time.LocalDate

enum class PredictionGrade { GREEN, RED, GREY }

data class LockedSidePrediction(
    val market: Market,
    val sessionId: String,
    val targetDate: LocalDate,
    val side: PannaSide,
    val candidates: List<String>,
    val trainingRecords: Int,
    val lockedAt: Instant,
    val evidenceNote: String
) {
    init {
        require(sessionId.isNotBlank())
        require(candidates.distinct().size == candidates.size)
        require(candidates.all { it.length == 3 && it.all(Char::isDigit) })
        require(trainingRecords >= 0)
    }

    val id: String get() = "${market.name}:$sessionId:$targetDate:${side.name}"
}

data class PredictionVerification(
    val predictionId: String,
    val grade: PredictionGrade,
    val actualPanna: String?,
    val actualJodi: String?,
    val matchedRank: Int?,
    val verifiedAt: Instant,
    val reconciliationState: ReconciliationState,
    val explanation: String,
    val attempt: Int = 1
)

data class LearningPerformance(
    val market: Market,
    val sessionId: String,
    val side: PannaSide,
    val green: Int,
    val red: Int,
    val grey: Int,
    val verified: Int,
    val hitRate: Double?,
    val top3HitRate: Double?,
    val note: String
)

data class PredictionLearningLedger(
    val lockedPredictions: List<LockedSidePrediction> = emptyList(),
    val verifications: List<PredictionVerification> = emptyList()
) {
    init {
        require(lockedPredictions.map { it.id }.distinct().size == lockedPredictions.size) {
            "A prediction for a market/session/date/side can only be locked once."
        }
        require(verifications.all { it.attempt > 0 }) { "Verification attempt must be positive." }
        require(verifications.map { it.predictionId to it.attempt }.distinct().size == verifications.size) {
            "Verification attempt numbers must be unique per prediction."
        }
        require(verifications.all { result -> lockedPredictions.any { it.id == result.predictionId } }) {
            "Verification must refer to a previously locked prediction."
        }
        require(verifications.groupBy { it.predictionId }.values.all { attempts ->
            val ordered = attempts.sortedBy { it.attempt }
            ordered.map { it.attempt } == (1..ordered.size).toList() &&
                ordered.dropLast(1).all { it.grade == PredictionGrade.GREY }
        }) {
            "Verification attempts must be sequential; GREEN/RED grades are terminal."
        }
    }

    fun performance(market: Market, sessionId: String, side: PannaSide): LearningPerformance {
        val predictions = lockedPredictions.filter {
            it.market == market && it.sessionId == sessionId && it.side == side
        }
        val ids = predictions.map { it.id }.toSet()
        val results = verifications.filter { it.predictionId in ids }
            .groupBy { it.predictionId }.values.mapNotNull { attempts -> attempts.maxByOrNull { it.attempt } }
        val green = results.count { it.grade == PredictionGrade.GREEN }
        val red = results.count { it.grade == PredictionGrade.RED }
        val grey = results.count { it.grade == PredictionGrade.GREY }
        val verified = green + red
        val top3 = results.count { it.grade == PredictionGrade.GREEN && (it.matchedRank ?: Int.MAX_VALUE) <= 3 }
        return LearningPerformance(
            market, sessionId, side, green, red, grey, verified,
            if (verified == 0) null else green.toDouble() / verified,
            if (verified == 0) null else top3.toDouble() / verified,
            "Only independently reconciled results count as verified. Grey results are excluded from hit-rate denominators. Historical performance is not a future guarantee."
        )
    }
}

/**
 * A prediction is frozen before its target date. Verification only grades independent publisher consensus; missing, conflicting, or single-source
 * outcomes are provisional GREY attempts that can be rechecked without rewriting history.
 * The ledger is immutable so later learning cannot rewrite a historical prediction.
 */
object PredictionLearningCycle {
    /**
     * Returns true only when a locked prediction has new grading-relevant evidence.
     * GREY attempts are rechecked when reconciliation changes state (for example,
     * single-source to independent consensus), regardless of the imported rows'
     * historical retrievedAt timestamps. GREEN/RED remain terminal.
     */
    fun needsReverification(
        ledger: PredictionLearningLedger,
        predictionId: String,
        observations: List<SourceObservation>
    ): Boolean {
        val prediction = ledger.lockedPredictions.singleOrNull { it.id == predictionId }
            ?: throw IllegalArgumentException("Unknown locked prediction.")
        val latest = ledger.verifications
            .filter { it.predictionId == predictionId }
            .maxByOrNull { it.attempt }
        val currentState = SourceReconciliation.reconcile(observations).singleOrNull {
            it.market == prediction.market && it.sessionId == prediction.sessionId &&
                it.date == prediction.targetDate
        }?.state ?: ReconciliationState.NO_PUBLISHED_RESULT

        return when {
            latest == null -> currentState != ReconciliationState.NO_PUBLISHED_RESULT
            latest.grade != PredictionGrade.GREY -> false
            else -> currentState != latest.reconciliationState
        }
    }

    fun lock(
        observations: List<SourceObservation>,
        market: Market,
        sessionId: String,
        targetDate: LocalDate,
        side: PannaSide,
        lockedAt: Instant,
        ledger: PredictionLearningLedger = PredictionLearningLedger(),
        limit: Int = 10,
        minimumHistory: Int = 5
    ): Pair<PredictionLearningLedger, LockedSidePrediction> {
        require(sessionId.isNotBlank())
        val id = "${market.name}:$sessionId:$targetDate:${side.name}"
        require(ledger.lockedPredictions.none { it.id == id }) {
            "Prediction already locked. Historical predictions cannot be replaced."
        }
        require(observations.none {
            it.market == market && it.sessionId == sessionId && !it.date.isBefore(targetDate) &&
                it.status == ObservationStatus.PUBLISHED
        }) { "Target or future result is already present; refusing a post-result prediction." }

        val ranking = CandidateRankingEngine.rank(
            observations, market, sessionId, targetDate, side,
            limit = limit, minimumHistory = minimumHistory, learningLedger = ledger
        )
        require(!ranking.abstained) { ranking.note }
        val prediction = LockedSidePrediction(
            market, sessionId, targetDate, side,
            ranking.candidates.map { it.panna },
            ranking.trainingRecords, lockedAt, ranking.note
        )
        return ledger.copy(lockedPredictions = ledger.lockedPredictions + prediction) to prediction
    }

    fun verify(
        ledger: PredictionLearningLedger,
        predictionId: String,
        observations: List<SourceObservation>,
        verifiedAt: Instant
    ): Pair<PredictionLearningLedger, PredictionVerification> {
        val prediction = ledger.lockedPredictions.singleOrNull { it.id == predictionId }
            ?: throw IllegalArgumentException("Unknown locked prediction.")
        // GREY is provisional and may be rechecked; GREEN and RED are terminal.
        val latest = ledger.verifications
            .filter { it.predictionId == predictionId }
            .maxByOrNull { it.attempt }
        require(latest == null || latest.grade == PredictionGrade.GREY) {
            "Prediction already has a terminal GREEN/RED grade; it cannot be reverified."
        }

        // Each permitted check is appended as a new immutable attempt.
        val group = SourceReconciliation.reconcile(observations).singleOrNull {
            it.market == prediction.market && it.sessionId == prediction.sessionId &&
                it.date == prediction.targetDate
        }
        val state = group?.state ?: ReconciliationState.NO_PUBLISHED_RESULT
        val agreed = group?.agreedResult
        val parts = agreed?.split("-")
        val actual = if (parts?.size == 3 && parts.all { it.isNotBlank() }) {
            if (prediction.side == PannaSide.OPEN) parts[0] else parts[2]
        } else null

        val rank = actual?.let { prediction.candidates.indexOf(it).takeIf { index -> index >= 0 }?.plus(1) }
        val grade = when {
            state != ReconciliationState.CONSENSUS || actual == null -> PredictionGrade.GREY
            rank != null -> PredictionGrade.GREEN
            else -> PredictionGrade.RED
        }
        val explanation = when (grade) {
            PredictionGrade.GREEN -> "Consensus result matched locked candidate rank ${rank}."
            PredictionGrade.RED -> "Consensus result was outside the locked candidate list."
            PredictionGrade.GREY -> when (state) {
                ReconciliationState.CONFLICT -> "Publishers disagree; result is not graded."
                ReconciliationState.SINGLE_SOURCE_UNVERIFIED -> "Only one independent publisher; result is not graded."
                else -> "No verified consensus result is available; result is not graded."
            }
        }
        val verification = PredictionVerification(
            prediction.id, grade, actual,
            agreed?.split("-")?.getOrNull(1), rank, verifiedAt, state, explanation,
            attempt = (ledger.verifications.filter { it.predictionId == predictionId }.maxOfOrNull { it.attempt } ?: 0) + 1
        )
        return ledger.copy(verifications = ledger.verifications + verification) to verification
    }
}
