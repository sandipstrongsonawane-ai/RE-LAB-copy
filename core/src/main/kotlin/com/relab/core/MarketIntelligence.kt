package com.relab.core

import java.time.DayOfWeek

/**
 * Market-specific descriptive intelligence. It detects changes in historical
 * distributions; it does not claim that past patterns predict future results.
 */
enum class MarketRegime { INSUFFICIENT_HISTORY, STABLE, WATCH, SHIFT_DETECTED }

data class TransitionEvidence(
    val transition: String,
    val count: Int,
    val percentage: Double
)

data class MarketIntelligenceReport(
    val market: Market,
    val sessionId: String,
    val validRecords: Int,
    val openWeekdayCounts: Map<DayOfWeek, Int>,
    val closeWeekdayCounts: Map<DayOfWeek, Int>,
    val topOpenTransitions: List<TransitionEvidence>,
    val topCloseTransitions: List<TransitionEvidence>,
    val openFamilyEntropyBits: Double,
    val closeFamilyEntropyBits: Double,
    val comparisonWindowSize: Int,
    /** Mean total-variation distance across open and close family sequences. */
    val distributionShift: Double?,
    val regime: MarketRegime,
    val explanation: String
)

object MarketIntelligence {
    fun analyze(records: List<ChartRecord>, minimumRecords: Int = 10): List<MarketIntelligenceReport> {
        require(minimumRecords >= 2) { "minimumRecords must be at least 2" }
        return records.groupBy { it.market to it.sessionId }
            .entries
            .sortedWith(compareBy({ it.key.first.name }, { it.key.second }))
            .mapNotNull { (identity, rows) -> analyzeMarket(identity.first, identity.second, rows, minimumRecords) }
    }

    private fun analyzeMarket(
        market: Market,
        sessionId: String,
        rows: List<ChartRecord>,
        minimumRecords: Int
    ): MarketIntelligenceReport? {
        val valid = rows.mapNotNull { record ->
            (ChartMath.validate(record) as? ValidationResult.Valid)?.let { record to it.features }
        }.sortedBy { it.first.date }
        require(valid.map { it.first.date }.distinct().size == valid.size) {
            "Duplicate dates for $market/$sessionId must be reconciled before intelligence analysis"
        }
        if (valid.isEmpty()) return null

        val open = valid.map { it.second.openFamilySequence.joinToString("") }
        val close = valid.map { it.second.closeFamilySequence.joinToString("") }

        fun transitions(values: List<String>): List<TransitionEvidence> {
            if (values.size < 2) return emptyList()
            val pairs = values.zipWithNext().map { (before, after) -> "$before>$after" }
            val counts = pairs.groupingBy { it }.eachCount()
            return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(5)
                .map { TransitionEvidence(it.key, it.value, it.value * 100.0 / pairs.size) }
        }

        fun entropy(values: List<String>): Double {
            if (values.isEmpty()) return 0.0
            val counts = values.groupingBy { it }.eachCount()
            return counts.values.sumOf { count ->
                val p = count.toDouble() / values.size
                -p * (kotlin.math.ln(p) / kotlin.math.ln(2.0))
            }
        }

        fun totalVariation(old: List<String>, recent: List<String>): Double {
            if (old.isEmpty() || recent.isEmpty()) return 0.0
            val oldCounts = old.groupingBy { it }.eachCount()
            val recentCounts = recent.groupingBy { it }.eachCount()
            val keys = oldCounts.keys + recentCounts.keys
            return 0.5 * keys.sumOf { key ->
                kotlin.math.abs(
                    (oldCounts[key] ?: 0).toDouble() / old.size -
                        (recentCounts[key] ?: 0).toDouble() / recent.size
                )
            }
        }

        val n = valid.size
        val window = (n / 3).coerceAtLeast(5).coerceAtMost(20)
        val shift = if (n >= minimumRecords && n >= window * 2) {
            val oldStart = n - window * 2
            val oldEnd = n - window
            val oldOpen = open.subList(oldStart, oldEnd)
            val recentOpen = open.takeLast(window)
            val oldClose = close.subList(oldStart, oldEnd)
            val recentClose = close.takeLast(window)
            (totalVariation(oldOpen, recentOpen) + totalVariation(oldClose, recentClose)) / 2.0
        } else null

        val regime = when {
            shift == null -> MarketRegime.INSUFFICIENT_HISTORY
            shift >= 0.45 -> MarketRegime.SHIFT_DETECTED
            shift >= 0.25 -> MarketRegime.WATCH
            else -> MarketRegime.STABLE
        }
        val explanation = when (regime) {
            MarketRegime.INSUFFICIENT_HISTORY -> "Need at least $minimumRecords valid dated records and two comparison windows."
            MarketRegime.STABLE -> "Recent family-sequence distribution is broadly similar to the preceding comparison window."
            MarketRegime.WATCH -> "Recent family-sequence distribution differs from the preceding window; monitor before changing weights."
            MarketRegime.SHIFT_DETECTED -> "Large recent distribution change detected. Older pattern weights should be treated cautiously."
        }

        return MarketIntelligenceReport(
            market = market,
            sessionId = sessionId,
            validRecords = n,
            openWeekdayCounts = valid.groupingBy { it.first.date.dayOfWeek }.eachCount().toSortedMap(compareBy { it.value }),
            closeWeekdayCounts = valid.groupingBy { it.first.date.dayOfWeek }.eachCount().toSortedMap(compareBy { it.value }),
            topOpenTransitions = transitions(open),
            topCloseTransitions = transitions(close),
            openFamilyEntropyBits = entropy(open),
            closeFamilyEntropyBits = entropy(close),
            comparisonWindowSize = if (shift == null) 0 else window,
            distributionShift = shift,
            regime = regime,
            explanation = explanation
        )
    }
}
