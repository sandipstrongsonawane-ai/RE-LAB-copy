package com.relab.core

import java.time.LocalDate
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** Categorical signals available for cross-market historical relationship research. */
enum class CrossMarketMetric { OPEN_FAMILY, CLOSE_FAMILY, OPEN_ANK, CLOSE_ANK }

data class CrossMarketAssociation(
    val firstMarket: Market,
    val firstSessionId: String,
    val secondMarket: Market,
    val secondSessionId: String,
    /** Positive lag means the second series is observed this many calendar days after the first. */
    val lagDays: Int,
    val metric: CrossMarketMetric,
    val pairedRecords: Int,
    val mutualInformationBits: Double,
    val normalizedMutualInformation: Double,
    val empiricalPValue: Double,
    val adjustedPValue: Double,
    val statisticallyUnusual: Boolean
)

data class CrossMarketReverseEngineeringReport(
    val seriesAnalyzed: Int,
    val invalidRecordsSkipped: Int,
    val pairComparisons: Int,
    val associations: List<CrossMarketAssociation>,
    val note: String
)

/**
 * Searches dated chart histories for cross-market categorical associations and
 * calendar lead/lag relationships. It uses a permutation null, corrects for the
 * number of tested lags/metrics per pair, and never treats association as causation.
 *
 * Inputs must already be source-reconciled; duplicate dates within a market/session
 * are rejected rather than silently selecting one publisher's result.
 */
object CrossMarketReverseEngineering {
    private data class SeriesKey(val market: Market, val sessionId: String)
    private data class Signal(
        val openFamily: String,
        val closeFamily: String,
        val openAnk: String,
        val closeAnk: String
    ) {
        fun value(metric: CrossMarketMetric): String = when (metric) {
            CrossMarketMetric.OPEN_FAMILY -> openFamily
            CrossMarketMetric.CLOSE_FAMILY -> closeFamily
            CrossMarketMetric.OPEN_ANK -> openAnk
            CrossMarketMetric.CLOSE_ANK -> closeAnk
        }
    }
    private data class DatedSignal(val date: LocalDate, val signal: Signal)
    private data class PairValue(val first: String, val second: String)

    fun analyze(
        records: List<ChartRecord>,
        minimumOverlap: Int = 12,
        maxLagDays: Int = 3,
        permutations: Int = 199
    ): CrossMarketReverseEngineeringReport {
        require(minimumOverlap >= 5) { "Minimum overlap must be at least 5 records." }
        require(maxLagDays in 0..14) { "Maximum lag must be between 0 and 14 days." }
        require(permutations in 99..999) { "Permutation count must be between 99 and 999." }

        var invalidSkipped = 0
        val series = records.groupBy { SeriesKey(it.market, it.sessionId) }
            .entries
            .sortedWith(compareBy({ it.key.market.name }, { it.key.sessionId }))
            .mapNotNull { (key, rows) ->
                require(key.sessionId.isNotBlank()) { "Session ID is required." }
                val duplicateDates = rows.groupingBy { it.date }.eachCount().filterValues { it > 1 }
                require(duplicateDates.isEmpty()) {
                    "Duplicate dates for ${key.market}/${key.sessionId} must be reconciled before reverse engineering."
                }
                val dated = rows.mapNotNull { record ->
                    val valid = ChartMath.validate(record) as? ValidationResult.Valid
                    if (valid == null) {
                        invalidSkipped++
                        null
                    } else {
                        val features = valid.features
                        DatedSignal(
                            record.date,
                            Signal(
                                features.openFamilySequence.joinToString(""),
                                features.closeFamilySequence.joinToString(""),
                                features.openAnk.toString(),
                                features.closeAnk.toString()
                            )
                        )
                    }
                }.sortedBy { it.date }
                if (dated.isEmpty()) null else key to dated
            }

        val output = mutableListOf<CrossMarketAssociation>()
        var comparisons = 0
        val metrics = CrossMarketMetric.entries
        val testsPerPair = ((maxLagDays * 2) + 1) * metrics.size

        for (firstIndex in series.indices) {
            for (secondIndex in firstIndex + 1 until series.size) {
                val (firstKey, firstRows) = series[firstIndex]
                val (secondKey, secondRows) = series[secondIndex]
                val secondByDate = secondRows.associateBy { it.date }

                for (lag in -maxLagDays..maxLagDays) {
                    val joined = firstRows.mapNotNull { first ->
                        secondByDate[first.date.plusDays(lag.toLong())]?.let { second ->
                            first.signal to second.signal
                        }
                    }
                    if (joined.size < minimumOverlap) continue

                    for (metric in metrics) {
                        val pairs = joined.map { (a, b) ->
                            PairValue(a.value(metric), b.value(metric))
                        }
                        val observedMi = mutualInformation(pairs)
                        val hx = entropy(pairs.map { it.first })
                        val hy = entropy(pairs.map { it.second })
                        val nmi = if (hx <= 0.0 || hy <= 0.0) 0.0
                            else (observedMi / sqrt(hx * hy)).coerceIn(0.0, 1.0)
                        val seed = stableSeed(firstKey, secondKey, lag, metric)
                        val random = Random(seed)
                        val shuffled = pairs.map { it.second }.toMutableList()
                        var atLeastAsStrong = 0
                        repeat(permutations) {
                            for (i in shuffled.lastIndex downTo 1) {
                                val j = random.nextInt(i + 1)
                                val temp = shuffled[i]
                                shuffled[i] = shuffled[j]
                                shuffled[j] = temp
                            }
                            val permuted = pairs.indices.map { i -> PairValue(pairs[i].first, shuffled[i]) }
                            if (mutualInformation(permuted) >= observedMi - 1e-12) atLeastAsStrong++
                        }
                        val pValue = (atLeastAsStrong + 1.0) / (permutations + 1.0)
                        val adjusted = (pValue * testsPerPair).coerceAtMost(1.0)
                        comparisons++
                        output += CrossMarketAssociation(
                            firstKey.market, firstKey.sessionId,
                            secondKey.market, secondKey.sessionId,
                            lag, metric, pairs.size, observedMi, nmi,
                            pValue, adjusted,
                            adjusted <= 0.05 && nmi >= 0.15
                        )
                    }
                }
            }
        }

        return CrossMarketReverseEngineeringReport(
            seriesAnalyzed = series.size,
            invalidRecordsSkipped = invalidSkipped,
            pairComparisons = comparisons,
            associations = output.sortedWith(
                compareBy<CrossMarketAssociation> { it.adjustedPValue }
                    .thenByDescending { it.normalizedMutualInformation }
                    .thenByDescending { it.pairedRecords }
                    .thenBy { it.firstMarket.name }
                    .thenBy { it.secondMarket.name }
                    .thenBy { it.lagDays }
                    .thenBy { it.metric.name }
            ),
            note = "Exploratory historical association only. A relationship is not proof of shared control, causation, or a future result. Source reconciliation, sufficient overlap, and out-of-sample validation remain mandatory."
        )
    }

    private fun entropy(values: List<String>): Double {
        if (values.isEmpty()) return 0.0
        val counts = values.groupingBy { it }.eachCount()
        return counts.values.sumOf { count ->
            val p = count.toDouble() / values.size
            -p * (ln(p) / ln(2.0))
        }
    }

    private fun mutualInformation(pairs: List<PairValue>): Double {
        if (pairs.isEmpty()) return 0.0
        val xCounts = pairs.groupingBy { it.first }.eachCount()
        val yCounts = pairs.groupingBy { it.second }.eachCount()
        val jointCounts = pairs.groupingBy { it.first to it.second }.eachCount()
        val n = pairs.size.toDouble()
        return jointCounts.entries.sumOf { (joint, count) ->
            val pxy = count / n
            val px = (xCounts[joint.first] ?: 0) / n
            val py = (yCounts[joint.second] ?: 0) / n
            pxy * (ln(pxy / (px * py)) / ln(2.0))
        }.coerceAtLeast(0.0)
    }

    private fun stableSeed(
        first: SeriesKey,
        second: SeriesKey,
        lag: Int,
        metric: CrossMarketMetric
    ): Int {
        var result = 17
        result = 31 * result + first.market.name.hashCode()
        result = 31 * result + first.sessionId.hashCode()
        result = 31 * result + second.market.name.hashCode()
        result = 31 * result + second.sessionId.hashCode()
        result = 31 * result + lag
        result = 31 * result + metric.ordinal
        return result
    }
}
