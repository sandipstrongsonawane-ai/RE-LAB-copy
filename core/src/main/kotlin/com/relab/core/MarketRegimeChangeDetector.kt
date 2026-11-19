package com.relab.core

import kotlin.math.abs
import kotlin.random.Random

enum class RegimeChangeStatus { INSUFFICIENT_HISTORY, STABLE, WATCH, SHIFT_DETECTED }

data class RegimeChangeFinding(
    val market: Market,
    val sessionId: String,
    val feature: String,
    val previousWindowRecords: Int,
    val recentWindowRecords: Int,
    val totalVariation: Double?,
    val permutationPValue: Double?,
    val adjustedQValue: Double?,
    val status: RegimeChangeStatus,
    val explanation: String
)

/**
 * Chronological, market/session-isolated change-point check.
 * A two-window total-variation effect is tested against a label-permutation null;
 * Benjamini-Hochberg correction is applied across the open/close tests for each
 * market/session. This is descriptive evidence, not a forecast.
 */
object MarketRegimeChangeDetector {
    fun analyze(
        records: List<ChartRecord>,
        windowSize: Int = 20,
        permutations: Int = 499,
        minimumEffect: Double = 0.20,
        watchEffect: Double = 0.12
    ): List<RegimeChangeFinding> {
        require(windowSize >= 2) { "windowSize must be at least 2" }
        require(permutations >= 99) { "At least 99 permutations are required" }
        require(minimumEffect in 0.0..1.0 && watchEffect in 0.0..minimumEffect) {
            "Effect thresholds must satisfy 0 <= watchEffect <= minimumEffect <= 1"
        }
        return records.groupBy { it.market to it.sessionId }
            .entries.sortedWith(compareBy({ it.key.first.name }, { it.key.second }))
            .flatMap { (identity, rows) ->
                val valid = rows.mapNotNull { row ->
                    (ChartMath.validate(row) as? ValidationResult.Valid)?.let { row to it.features }
                }.sortedBy { it.first.date }
                require(valid.map { it.first.date }.distinct().size == valid.size) {
                    "Duplicate dates for ${identity.first}/${identity.second} must be reconciled"
                }
                if (valid.size < windowSize * 2) {
                    return@flatMap listOf("open" to null, "close" to null).map { (feature, _) ->
                        RegimeChangeFinding(
                            identity.first, identity.second, feature,
                            minOf(windowSize, valid.size), minOf(windowSize, valid.size),
                            null, null, null, RegimeChangeStatus.INSUFFICIENT_HISTORY,
                            "Need at least ${windowSize * 2} valid dated records for two complete windows."
                        )
                    }
                }
                val open = valid.map { it.second.openFamilySequence.joinToString("") }
                val close = valid.map { it.second.closeFamilySequence.joinToString("") }
                val openFinding = test(identity.first, identity.second, "open", open, windowSize, permutations)
                val closeFinding = test(identity.first, identity.second, "close", close, windowSize, permutations)
                val raw = listOf(openFinding, closeFinding)
                val qValues = benjaminiHochberg(raw.map { it.permutationPValue!! })
                raw.mapIndexed { index, finding ->
                    val q = qValues[index]
                    val effect = finding.totalVariation!!
                    val status = when {
                        q <= 0.05 && effect >= minimumEffect -> RegimeChangeStatus.SHIFT_DETECTED
                        effect >= watchEffect -> RegimeChangeStatus.WATCH
                        else -> RegimeChangeStatus.STABLE
                    }
                    finding.copy(
                        adjustedQValue = q,
                        status = status,
                        explanation = when (status) {
                            RegimeChangeStatus.SHIFT_DETECTED -> "Recent distribution change is statistically unusual after within-session correction; older pattern weights may be stale."
                            RegimeChangeStatus.WATCH -> "Distribution difference is worth monitoring, but statistical evidence is not strong enough to flag a confirmed shift."
                            RegimeChangeStatus.STABLE -> "No material distribution shift was detected in these two windows."
                            RegimeChangeStatus.INSUFFICIENT_HISTORY -> "Insufficient history."
                        }
                    )
                }
            }
    }

    private fun test(
        market: Market, sessionId: String, feature: String, values: List<String>,
        windowSize: Int, permutations: Int
    ): RegimeChangeFinding {
        val previous = values.subList(values.size - windowSize * 2, values.size - windowSize)
        val recent = values.takeLast(windowSize)
        val observed = totalVariation(previous, recent)
        val pooled = previous + recent
        val random = Random(stableSeed(market.name, sessionId, feature, pooled))
        var exceedances = 0
        repeat(permutations) {
            val shuffled = pooled.shuffled(random)
            val effect = totalVariation(shuffled.take(windowSize), shuffled.takeLast(windowSize))
            if (effect + 1e-12 >= observed) exceedances++
        }
        val p = (exceedances + 1.0) / (permutations + 1.0)
        return RegimeChangeFinding(
            market, sessionId, feature, windowSize, windowSize, observed, p, null,
            RegimeChangeStatus.WATCH, "Permutation test pending within-session adjustment."
        )
    }

    private fun totalVariation(first: List<String>, second: List<String>): Double {
        val a = first.groupingBy { it }.eachCount()
        val b = second.groupingBy { it }.eachCount()
        return 0.5 * (a.keys.union(b.keys).sumOf { key ->
            abs((a[key] ?: 0).toDouble() / first.size - (b[key] ?: 0).toDouble() / second.size)
        })
    }

    private fun benjaminiHochberg(pValues: List<Double>): List<Double> {
        val order = pValues.indices.sortedBy { pValues[it] }
        val adjusted = DoubleArray(pValues.size)
        var running = 1.0
        for (rankIndex in order.indices.reversed()) {
            val rank = rankIndex + 1
            running = minOf(running, pValues[order[rankIndex]] * pValues.size / rank)
            adjusted[order[rankIndex]] = running.coerceIn(0.0, 1.0)
        }
        return adjusted.toList()
    }

    private fun stableSeed(market: String, session: String, feature: String, values: List<String>): Int {
        var hash = 17
        listOf(market, session, feature).forEach { part -> hash = 31 * hash + part.hashCode() }
        values.forEach { value -> hash = 31 * hash + value.hashCode() }
        return hash
    }
}
