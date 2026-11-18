package com.relab.core

import java.time.LocalDate

enum class HypothesisSide { OPEN, CLOSE }

data class DiscoveredHypothesis(
    val id: String,
    val market: Market,
    val sessionId: String,
    val side: HypothesisSide,
    val condition: String,
    val support: Int,
    val correct: Int,
    val evaluated: Int,
    val hitRate: Double,
    val baselineCorrect: Int,
    val baselineHitRate: Double,
    val liftOverBaseline: Double
)

data class HypothesisDiscoveryReport(
    val market: Market,
    val sessionId: String,
    val side: HypothesisSide,
    val targetDates: Int,
    val evaluatedDates: Int,
    val minimumSupport: Int,
    val hypotheses: List<DiscoveredHypothesis>,
    val note: String
)

/**
 * Mines conditional rules from each market/session's own reconciled history.
 *
 * Rules are discovered from observed context -> next family-sequence transitions.
 * Only earlier transitions are used for each walk-forward target. The engine reports
 * evidence and baselines; it does not claim that a rule is causal or predictive.
 */
object SelfLearningHypothesisEngine {
    private val dimensions = listOf(
        "target_weekday",
        "previous_open_family",
        "previous_close_family",
        "previous_open_ank",
        "previous_close_ank",
        "previous_open_type",
        "previous_close_type",
        "previous_open_positions",
        "previous_close_positions"
    )

    fun discover(
        records: List<ChartRecord>,
        market: Market,
        sessionId: String,
        side: HypothesisSide,
        minimumHistory: Int = 8,
        minimumSupport: Int = 3,
        startDateInclusive: LocalDate? = null,
        endDateExclusive: LocalDate? = null
    ): HypothesisDiscoveryReport {
        require(sessionId.isNotBlank()) { "Session ID is required" }
        require(minimumHistory >= 2) { "Minimum history must be at least 2" }
        require(minimumSupport >= 2) { "Minimum support must be at least 2" }
        require(startDateInclusive == null || endDateExclusive == null ||
            startDateInclusive.isBefore(endDateExclusive)) { "Invalid date window" }

        val rows = records.asSequence()
            .filter { it.market == market && it.sessionId == sessionId }
            .filter { ChartMath.validate(it) is ValidationResult.Valid }
            .sortedBy { it.date }
            .toList()
        require(rows.map { it.date }.distinct().size == rows.size) {
            "Duplicate dates must be reconciled before hypothesis discovery"
        }

        val targetIndices = rows.indices.filter { index ->
            index >= minimumHistory &&
                (startDateInclusive == null || !rows[index].date.isBefore(startDateInclusive)) &&
                (endDateExclusive == null || rows[index].date.isBefore(endDateExclusive))
        }
        data class Score(var support: Int = 0, var correct: Int = 0, var evaluated: Int = 0, var baselineCorrect: Int = 0)
        val scores = linkedMapOf<String, Score>()
        val conditions = linkedMapOf<String, String>()
        var evaluatedDates = 0

        fun family(panna: String) = panna.map { ChartMath.family(it.digitToInt()) }.joinToString("")
        fun ank(panna: String) = (panna.sumOf { it.digitToInt() } % 10).toString()
        fun type(panna: String) = ChartMath.pannaType(panna).name

        fun context(previous: ChartRecord, targetDate: LocalDate): Map<String, String> = mapOf(
            "target_weekday" to targetDate.dayOfWeek.name,
            "previous_open_family" to family(previous.openPanna),
            "previous_close_family" to family(previous.closePanna),
            "previous_open_ank" to ank(previous.openPanna),
            "previous_close_ank" to ank(previous.closePanna),
            "previous_open_type" to type(previous.openPanna),
            "previous_close_type" to type(previous.closePanna),
            "previous_open_positions" to previous.openPanna.map { ChartMath.family(it.digitToInt()) }.joinToString(""),
            "previous_close_positions" to previous.closePanna.map { ChartMath.family(it.digitToInt()) }.joinToString("")
        )

        fun outcome(record: ChartRecord): String =
            family(if (side == HypothesisSide.OPEN) record.openPanna else record.closePanna)

        // Single-feature and pair-feature rules are generated from data, not hand-ranked.
        val ruleDimensions = dimensions.flatMapIndexed { index, first ->
            listOf(listOf(first)) + dimensions.drop(index + 1).map { second -> listOf(first, second) }
        }

        targetIndices.forEach { targetIndex ->
            val target = rows[targetIndex]
            val currentContext = context(rows[targetIndex - 1], target.date)
            val trainingTransitions = (1 until targetIndex).map { transitionIndex ->
                val previous = rows[transitionIndex - 1]
                val actual = rows[transitionIndex]
                context(previous, actual.date) to outcome(actual)
            }
            if (trainingTransitions.isEmpty()) return@forEach
            val baselineCounts = trainingTransitions.groupingBy { it.second }.eachCount()
            val baselinePrediction = baselineCounts.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .first().key
            val actualOutcome = outcome(target)
            evaluatedDates++

            ruleDimensions.forEach { selectedDimensions ->
                val value = selectedDimensions.joinToString("&") { dimension ->
                    "$dimension=${currentContext.getValue(dimension)}"
                }
                val matching = trainingTransitions.filter { (features, _) ->
                    selectedDimensions.all { dimension ->
                        features[dimension] == currentContext[dimension]
                    }
                }
                if (matching.size < minimumSupport) return@forEach
                val counts = matching.groupingBy { it.second }.eachCount()
                val prediction = counts.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .first().key
                val id = selectedDimensions.joinToString("+") + ":" + value
                val score = scores.getOrPut(id) { Score() }
                conditions[id] = value
                score.support += matching.size
                score.evaluated++
                if (prediction == actualOutcome) score.correct++
                if (baselinePrediction == actualOutcome) score.baselineCorrect++
            }
        }

        val hypotheses = scores.map { (id, score) ->
            val hitRate = score.correct.toDouble() / score.evaluated
            val baselineRate = score.baselineCorrect.toDouble() / score.evaluated
            DiscoveredHypothesis(
                id = id,
                market = market,
                sessionId = sessionId,
                side = side,
                condition = conditions.getValue(id),
                support = score.support,
                correct = score.correct,
                evaluated = score.evaluated,
                hitRate = hitRate,
                baselineCorrect = score.baselineCorrect,
                baselineHitRate = baselineRate,
                liftOverBaseline = hitRate - baselineRate
            )
        }.sortedWith(
            compareByDescending<DiscoveredHypothesis> { it.liftOverBaseline }
                .thenByDescending { it.hitRate }
                .thenByDescending { it.support }
                .thenBy { it.id }
        )

        return HypothesisDiscoveryReport(
            market, sessionId, side, targetIndices.size, evaluatedDates, minimumSupport,
            hypotheses,
            "Rules are mined from earlier transitions only. Support is historical exposure; lift is versus the same-window global-mode baseline. Results are research evidence, not probabilities or guarantees."
        )
    }
}
