package com.relab.core

import java.time.LocalDate
import java.time.ZoneOffset

data class VerifiedFamilyFeedback(
    val familySequence: String,
    val exposure: Int,
    val hits: Int,
    val smoothedHitRate: Double
)

data class VerifiedPredictionFeedbackReport(
    val verifiedPredictions: Int,
    val families: Map<String, VerifiedFamilyFeedback>,
    val baselineSmoothedHitRate: Double,
    val note: String
)

/**
 * Turns only terminal GREEN/RED prediction outcomes into a small, bounded
 * family-level ranking adjustment. GREY attempts are excluded, and only the
 * latest attempt for each prediction is counted. The target-date cutoff prevents
 * later prediction outcomes from leaking into earlier candidate rankings.
 */
object VerifiedPredictionFeedbackEngine {
    fun analyze(
        ledger: PredictionLearningLedger,
        market: Market,
        sessionId: String,
        targetDate: LocalDate,
        side: PannaSide
    ): VerifiedPredictionFeedbackReport {
        require(sessionId.isNotBlank())
        val latest = ledger.verifications
            .groupBy { it.predictionId }
            .mapNotNull { (_, attempts) -> attempts.maxByOrNull { it.attempt } }
            .filter { it.grade != PredictionGrade.GREY && it.actualPanna?.matches(Regex("\\d{3}")) == true }
            // Only outcomes actually verified before this target may affect its ranking.
            .filter { it.verifiedAt.isBefore(targetDate.atStartOfDay(ZoneOffset.UTC).toInstant()) }
            .mapNotNull { verification ->
                val prediction = ledger.lockedPredictions.singleOrNull { it.id == verification.predictionId }
                    ?: return@mapNotNull null
                if (prediction.market != market || prediction.sessionId != sessionId ||
                    prediction.side != side || !prediction.targetDate.isBefore(targetDate)) return@mapNotNull null
                val actual = verification.actualPanna ?: return@mapNotNull null
                prediction to familySequence(actual)
            }

        data class Counts(var exposure: Int = 0, var hits: Int = 0)
        val counts = linkedMapOf<String, Counts>()
        latest.forEach { (prediction, actualFamily) ->
            prediction.candidates.map(::familySequence).distinct().forEach { family ->
                val value = counts.getOrPut(family) { Counts() }
                value.exposure++
                if (family == actualFamily) value.hits++
            }
        }
        val totalExposure = counts.values.sumOf { it.exposure }
        val totalHits = counts.values.sumOf { it.hits }
        val baseline = (totalHits + 1.0) / (totalExposure + 2.0)
        val families = counts.mapValues { (family, value) ->
            VerifiedFamilyFeedback(
                family, value.exposure, value.hits,
                (value.hits + 1.0) / (value.exposure + 2.0)
            )
        }
        return VerifiedPredictionFeedbackReport(
            latest.size, families, baseline,
            "Only latest terminal GREEN/RED attempts from earlier target dates are used. GREY is excluded. Ranking adjustment is bounded to +/-5 points and is not a probability."
        )
    }

    private fun familySequence(panna: String): String =
        panna.map { ChartMath.family(it.digitToInt()).toString() }.joinToString("")
}
