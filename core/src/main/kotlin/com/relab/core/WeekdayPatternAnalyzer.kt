package com.relab.core

import java.time.DayOfWeek
import kotlin.random.Random

enum class WeekdayPatternStatus { INSUFFICIENT_HISTORY, NO_UNUSUAL_ASSOCIATION, UNUSUAL_ASSOCIATION }

data class WeekdayPatternFinding(
    val market: Market,
    val sessionId: String,
    val feature: String,
    val records: Int,
    val activeWeekdays: Int,
    val normalizedMutualInformation: Double?,
    val permutationPValue: Double?,
    val adjustedQValue: Double?,
    val status: WeekdayPatternStatus,
    val explanation: String
)

/**
 * Tests whether historical chart features are associated with weekday.
 * Values are permuted across the observed weekday labels; tests are corrected
 * with Benjamini-Hochberg within each market/session. This is descriptive only.
 */
object WeekdayPatternAnalyzer {
    private data class Observation(val day: DayOfWeek, val value: String)

    fun analyze(
        records: List<ChartRecord>,
        permutations: Int = 499,
        minimumRecords: Int = 30,
        minimumActiveWeekdays: Int = 3,
        minimumNmi: Double = 0.15
    ): List<WeekdayPatternFinding> {
        require(permutations >= 99) { "At least 99 permutations are required" }
        require(minimumRecords >= 2) { "Minimum records must be at least 2" }
        require(minimumActiveWeekdays in 2..7) { "Minimum active weekdays must be 2..7" }
        require(minimumNmi in 0.0..1.0) { "Minimum NMI must be between 0 and 1" }

        return records.groupBy { it.market to it.sessionId }
            .entries.sortedWith(compareBy({ it.key.first.name }, { it.key.second }))
            .flatMap { (identity, rows) ->
                val valid = rows.mapNotNull { record ->
                    (ChartMath.validate(record) as? ValidationResult.Valid)?.let { record to it.features }
                }.sortedBy { it.first.date }
                require(valid.map { it.first.date }.distinct().size == valid.size) {
                    "Duplicate dates for ${identity.first}/${identity.second} must be reconciled"
                }

                val features = listOf(
                    "open_family_sequence" to valid.map { Observation(it.first.date.dayOfWeek, it.second.openFamilySequence.joinToString("")) },
                    "close_family_sequence" to valid.map { Observation(it.first.date.dayOfWeek, it.second.closeFamilySequence.joinToString("")) },
                    "open_panna_type" to valid.map { Observation(it.first.date.dayOfWeek, it.second.openType.name) },
                    "close_panna_type" to valid.map { Observation(it.first.date.dayOfWeek, it.second.closeType.name) },
                    "open_ank" to valid.map { Observation(it.first.date.dayOfWeek, it.second.openAnk.toString()) },
                    "close_ank" to valid.map { Observation(it.first.date.dayOfWeek, it.second.closeAnk.toString()) }
                )
                val findings = features.map { (name, values) ->
                    val active = values.map { it.day }.distinct().size
                    if (values.size < minimumRecords || active < minimumActiveWeekdays) {
                        WeekdayPatternFinding(
                            identity.first, identity.second, name, values.size, active,
                            null, null, null, WeekdayPatternStatus.INSUFFICIENT_HISTORY,
                            "Need at least $minimumRecords valid records across $minimumActiveWeekdays active weekdays."
                        )
                    } else {
                        val nmi = normalizedMutualInformation(values.map { it.day }, values.map { it.value })
                        val p = permutationP(values, permutations, identity.first.name, identity.second, name)
                        WeekdayPatternFinding(
                            identity.first, identity.second, name, values.size, active, nmi, p, null,
                            WeekdayPatternStatus.NO_UNUSUAL_ASSOCIATION,
                            "Permutation evidence is pending within-session multiple-test correction."
                        )
                    }
                }
                val tested = findings.filter { it.permutationPValue != null }
                val qByFeature = benjaminiHochberg(tested.map { it.permutationPValue!! })
                val qMap = tested.mapIndexed { index, finding -> finding.feature to qByFeature[index] }.toMap()
                findings.map { finding ->
                    val q = qMap[finding.feature] ?: return@map finding
                    val unusual = q <= 0.05 && finding.normalizedMutualInformation!! >= minimumNmi
                    finding.copy(
                        adjustedQValue = q,
                        status = if (unusual) WeekdayPatternStatus.UNUSUAL_ASSOCIATION else WeekdayPatternStatus.NO_UNUSUAL_ASSOCIATION,
                        explanation = if (unusual)
                            "Weekday association is unusual after within-session correction; historical evidence only, not a forecast."
                        else
                            "No sufficiently strong weekday association was detected after within-session correction."
                    )
                }
            }
    }

    private fun permutationP(
        values: List<Observation>, permutations: Int, market: String, session: String, feature: String
    ): Double {
        val days = values.map { it.day }
        val outcomes = values.map { it.value }
        val observed = mutualInformation(days, outcomes)
        val random = Random(stableSeed(market, session, feature, values))
        var exceedances = 0
        repeat(permutations) {
            if (mutualInformation(days, outcomes.shuffled(random)) + 1e-12 >= observed) exceedances++
        }
        return (exceedances + 1.0) / (permutations + 1.0)
    }

    private fun normalizedMutualInformation(days: List<DayOfWeek>, outcomes: List<String>): Double {
        val mi = mutualInformation(days, outcomes)
        val hDay = entropy(days.map { it.name })
        val hOutcome = entropy(outcomes)
        val denominator = minOf(hDay, hOutcome)
        return if (denominator <= 0.0) 0.0 else (mi / denominator).coerceIn(0.0, 1.0)
    }

    private fun mutualInformation(x: List<DayOfWeek>, y: List<String>): Double {
        val total = x.size.toDouble()
        val xCounts = x.groupingBy { it }.eachCount()
        val yCounts = y.groupingBy { it }.eachCount()
        val joint = x.indices.groupingBy { x[it] to y[it] }.eachCount()
        return joint.entries.sumOf { (pair, count) ->
            val pxy = count / total
            val px = xCounts.getValue(pair.first) / total
            val py = yCounts.getValue(pair.second) / total
            pxy * kotlin.math.ln(pxy / (px * py))
        }
    }

    private fun entropy(values: List<String>): Double {
        val total = values.size.toDouble()
        return values.groupingBy { it }.eachCount().values.sumOf { count ->
            val p = count / total
            -p * kotlin.math.ln(p)
        }
    }

    private fun benjaminiHochberg(pValues: List<Double>): List<Double> {
        val order = pValues.indices.sortedBy { pValues[it] }
        val adjusted = DoubleArray(pValues.size)
        var running = 1.0
        for (index in order.indices.reversed()) {
            running = minOf(running, pValues[order[index]] * pValues.size / (index + 1))
            adjusted[order[index]] = running.coerceIn(0.0, 1.0)
        }
        return adjusted.toList()
    }

    private fun stableSeed(market: String, session: String, feature: String, values: List<Observation>): Int {
        var hash = 17
        listOf(market, session, feature).forEach { hash = 31 * hash + it.hashCode() }
        values.forEach { hash = 31 * hash + (it.day to it.value).hashCode() }
        return hash
    }
}
