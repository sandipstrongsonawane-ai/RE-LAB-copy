package com.relab.core

import java.time.temporal.ChronoUnit

/** Deterministic descriptive analysis. No predictive claim is implied by frequency. */
data class PatternCount(
    val pattern: String,
    val count: Int,
    val denominator: Int,
    val percentage: Double,
    val meetsMinimumSupport: Boolean
)

data class RepeatGap(val pattern: String, val gapsInDays: List<Long>)

data class WalkForwardRow(
    val date: java.time.LocalDate,
    val actualOpenFamilySequence: String,
    val globalModePrediction: String,
    val weekdayModePrediction: String,
    val previousStatePrediction: String,
    val actualCloseFamilySequence: String,
    val closeGlobalModePrediction: String,
    val closeWeekdayModePrediction: String,
    val closePreviousStatePrediction: String
)

data class WalkForwardMetric(val correct: Int, val evaluated: Int) {
    val hitRate: Double get() = if (evaluated == 0) 0.0 else correct.toDouble() / evaluated
}

data class MarketAnalysis(
    val market: Market,
    val sessionId: String,
    val acceptedRecords: Int,
    val rejectedRecords: Int,
    val openExactPanna: List<PatternCount>,
    val closeExactPanna: List<PatternCount>,
    val openFamilySequences: List<PatternCount>,
    val closeFamilySequences: List<PatternCount>,
    val openWeekdayCounts: Map<java.time.DayOfWeek, Int>,
    val closeWeekdayCounts: Map<java.time.DayOfWeek, Int>,
    /** Matrix rows are previous family, columns are next family. */
    val openFamilyDigitTransitions: List<List<Int>>,
    val closeFamilyDigitTransitions: List<List<Int>>,
    /** Sparse 125-state transition map: previous sequence > next sequence. */
    val openPannaFamilyTransitions: Map<String, Int>,
    val closePannaFamilyTransitions: Map<String, Int>,
    val openToCloseFamilyRelations: List<PatternCount>,
    val jodiFamilyCombinations: List<PatternCount>,
    val openAnkCounts: List<PatternCount>,
    val closeAnkCounts: List<PatternCount>,
    val openPannaTypeCounts: Map<PannaType, Int>,
    val closePannaTypeCounts: Map<PannaType, Int>,
    val openDigitPositionCounts: List<Map<Int, Int>>,
    val closeDigitPositionCounts: List<Map<Int, Int>>,
    /** Pair frequencies for positions 0-1, 0-2 and 1-2, in that order. */
    val openDigitPairPositionCounts: List<List<PatternCount>>,
    val closeDigitPairPositionCounts: List<List<PatternCount>>,
    val openReversePannaCounts: List<PatternCount>,
    val closeReversePannaCounts: List<PatternCount>,
    val openRepeatGaps: List<RepeatGap>,
    val closeRepeatGaps: List<RepeatGap>,
    val walkForwardRows: List<WalkForwardRow>,
    val globalModeBaseline: WalkForwardMetric,
    val weekdayModeBaseline: WalkForwardMetric,
    val previousStateBaseline: WalkForwardMetric,
    val closeGlobalModeBaseline: WalkForwardMetric,
    val closeWeekdayModeBaseline: WalkForwardMetric,
    val closePreviousStateBaseline: WalkForwardMetric
)

object PatternAnalysis {
    fun analyze(records: List<ChartRecord>, minimumSupport: Int = 1): List<MarketAnalysis> {
        require(minimumSupport >= 1) { "minimumSupport must be at least 1" }
        return records.groupBy { it.market to it.sessionId }
            .entries
            .sortedWith(compareBy({ it.key.first.name }, { it.key.second }))
            .mapNotNull { (identity, rows) ->
                analyzeSession(identity.first, identity.second, rows, minimumSupport)
            }
    }

    private fun analyzeSession(
        market: Market,
        sessionId: String,
        records: List<ChartRecord>,
        minimumSupport: Int
    ): MarketAnalysis {
        require(sessionId.isNotBlank()) { "Session ID cannot be blank" }
        val validated = records.mapNotNull { record ->
            (ChartMath.validate(record) as? ValidationResult.Valid)?.let { record to it.features }
        }.sortedBy { it.first.date }

        require(validated.map { it.first.date }.distinct().size == validated.size) {
            "Duplicate dates for $market/$sessionId: reconcile source observations before analysis"
        }

        val accepted = validated.map { it.first }
        val features = validated.map { it.second }
        if (accepted.isEmpty()) return emptyAnalysis(market, sessionId, records.size)

        val openSeq = features.map { it.openFamilySequence.joinToString("") }
        val closeSeq = features.map { it.closeFamilySequence.joinToString("") }

        fun counts(values: List<String>): List<PatternCount> {
            if (values.isEmpty()) return emptyList()
            val grouped = values.groupingBy { it }.eachCount()
            return grouped.map { (pattern, count) ->
                PatternCount(pattern, count, values.size, count * 100.0 / values.size, count >= minimumSupport)
            }.sortedWith(compareByDescending<PatternCount> { it.count }.thenBy { it.pattern })
        }

        fun digitTransitions(sequences: List<List<Int>>): List<List<Int>> {
            val matrix = Array(5) { IntArray(5) }
            for (i in 1 until sequences.size) {
                for (position in 0..2) matrix[sequences[i - 1][position]][sequences[i][position]]++
            }
            return matrix.map { it.toList() }
        }

        fun sequenceTransitions(sequences: List<String>): Map<String, Int> {
            val result = mutableMapOf<String, Int>()
            for (i in 1 until sequences.size) {
                val key = "${sequences[i - 1]}>${sequences[i]}"
                result[key] = (result[key] ?: 0) + 1
            }
            return result.toSortedMap()
        }

        fun typeCounts(values: List<PannaType>): Map<PannaType, Int> =
            PannaType.entries.associateWith { type -> values.count { it == type } }

        fun positionCounts(pannas: List<String>): List<Map<Int, Int>> =
            (0..2).map { position ->
                (0..9).associateWith { digit -> pannas.count { it[position].digitToInt() == digit } }
            }

        fun digitPairPositionCounts(pannas: List<String>): List<List<PatternCount>> {
            val positions = listOf(0 to 1, 0 to 2, 1 to 2)
            return positions.map { (first, second) ->
                counts(pannas.map { "${it[first]}${it[second]}" })
            }
        }

        fun repeatGaps(patterns: List<String>): List<RepeatGap> {
            val lastSeen = mutableMapOf<String, java.time.LocalDate>()
            val gaps = mutableMapOf<String, MutableList<Long>>()
            accepted.indices.forEach { index ->
                val pattern = patterns[index]
                lastSeen[pattern]?.let { previous ->
                    gaps.getOrPut(pattern) { mutableListOf() }
                        .add(ChronoUnit.DAYS.between(previous, accepted[index].date))
                }
                lastSeen[pattern] = accepted[index].date
            }
            return gaps.toSortedMap().map { (pattern, values) -> RepeatGap(pattern, values.toList()) }
        }

        val weekdayOpen = accepted.groupingBy { it.date.dayOfWeek }.eachCount()
        val weekdayClose = accepted.groupingBy { it.date.dayOfWeek }.eachCount()
        val rows = mutableListOf<WalkForwardRow>()
        // Maintain only the strictly-prior history needed for each walk-forward row.
        // This avoids repeatedly copying/scanning every prefix of the chart.
        val openCounts = mutableMapOf<String, Int>()
        val closeCounts = mutableMapOf<String, Int>()
        val openWeekdayCounts = mutableMapOf<java.time.DayOfWeek, MutableMap<String, Int>>()
        val closeWeekdayCounts = mutableMapOf<java.time.DayOfWeek, MutableMap<String, Int>>()
        fun add(counts: MutableMap<String, Int>, value: String) {
            counts[value] = (counts[value] ?: 0) + 1
        }
        fun modeOf(counts: Map<String, Int>): String =
            counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .first().key
        for (i in accepted.indices) {
            val day = accepted[i].date.dayOfWeek
            if (i > 0) {
                val openGlobalMode = modeOf(openCounts)
                val closeGlobalMode = modeOf(closeCounts)
                val openDayCounts = openWeekdayCounts[day].orEmpty()
                val closeDayCounts = closeWeekdayCounts[day].orEmpty()
                rows += WalkForwardRow(
                    date = accepted[i].date,
                    actualOpenFamilySequence = openSeq[i],
                    globalModePrediction = openGlobalMode,
                    weekdayModePrediction = if (openDayCounts.isEmpty()) openGlobalMode else modeOf(openDayCounts),
                    previousStatePrediction = openSeq[i - 1],
                    actualCloseFamilySequence = closeSeq[i],
                    closeGlobalModePrediction = closeGlobalMode,
                    closeWeekdayModePrediction = if (closeDayCounts.isEmpty()) closeGlobalMode else modeOf(closeDayCounts),
                    closePreviousStatePrediction = closeSeq[i - 1]
                )
            }
            // Add today's values only after evaluating today's prediction.
            add(openCounts, openSeq[i])
            add(closeCounts, closeSeq[i])
            add(openWeekdayCounts.getOrPut(day) { mutableMapOf() }, openSeq[i])
            add(closeWeekdayCounts.getOrPut(day) { mutableMapOf() }, closeSeq[i])
        }
        fun openMetric(selector: (WalkForwardRow) -> String) =
            WalkForwardMetric(rows.count { selector(it) == it.actualOpenFamilySequence }, rows.size)
        fun closeMetric(selector: (WalkForwardRow) -> String) =
            WalkForwardMetric(rows.count { selector(it) == it.actualCloseFamilySequence }, rows.size)

        val openPannas = accepted.map { it.openPanna }
        val closePannas = accepted.map { it.closePanna }
        return MarketAnalysis(
            market = market,
            sessionId = sessionId,
            acceptedRecords = accepted.size,
            rejectedRecords = records.size - accepted.size,
            openExactPanna = counts(openPannas),
            closeExactPanna = counts(closePannas),
            openFamilySequences = counts(openSeq),
            closeFamilySequences = counts(closeSeq),
            openWeekdayCounts = weekdayOpen,
            closeWeekdayCounts = weekdayClose,
            openFamilyDigitTransitions = digitTransitions(features.map { it.openFamilySequence }),
            closeFamilyDigitTransitions = digitTransitions(features.map { it.closeFamilySequence }),
            openPannaFamilyTransitions = sequenceTransitions(openSeq),
            closePannaFamilyTransitions = sequenceTransitions(closeSeq),
            openToCloseFamilyRelations = counts(openSeq.indices.map { "${openSeq[it]}>${closeSeq[it]}" }),
            jodiFamilyCombinations = counts(features.map { "${it.openJodiFamily}${it.closeJodiFamily}" }),
            openAnkCounts = counts(features.map { it.openAnk.toString() }),
            closeAnkCounts = counts(features.map { it.closeAnk.toString() }),
            openPannaTypeCounts = typeCounts(features.map { it.openType }),
            closePannaTypeCounts = typeCounts(features.map { it.closeType }),
            openDigitPositionCounts = positionCounts(openPannas),
            closeDigitPositionCounts = positionCounts(closePannas),
            openDigitPairPositionCounts = digitPairPositionCounts(openPannas),
            closeDigitPairPositionCounts = digitPairPositionCounts(closePannas),
            openReversePannaCounts = counts(features.map { it.reverseOpenPanna }),
            closeReversePannaCounts = counts(features.map { it.reverseClosePanna }),
            openRepeatGaps = repeatGaps(openSeq),
            closeRepeatGaps = repeatGaps(closeSeq),
            walkForwardRows = rows,
            globalModeBaseline = openMetric { it.globalModePrediction },
            weekdayModeBaseline = openMetric { it.weekdayModePrediction },
            previousStateBaseline = openMetric { it.previousStatePrediction },
            closeGlobalModeBaseline = closeMetric { it.closeGlobalModePrediction },
            closeWeekdayModeBaseline = closeMetric { it.closeWeekdayModePrediction },
            closePreviousStateBaseline = closeMetric { it.closePreviousStatePrediction }
        )
    }

    private fun emptyAnalysis(market: Market, sessionId: String, rejected: Int) = MarketAnalysis(
        market, sessionId, 0, rejected,
        emptyList(), emptyList(), emptyList(), emptyList(),
        emptyMap(), emptyMap(),
        List(5) { List(5) { 0 } }, List(5) { List(5) { 0 } },
        emptyMap(), emptyMap(), emptyList(), emptyList(), emptyList(), emptyList(),
        PannaType.entries.associateWith { 0 }, PannaType.entries.associateWith { 0 },
        List(3) { (0..9).associateWith { 0 } }, List(3) { (0..9).associateWith { 0 } },
        List(3) { emptyList() }, List(3) { emptyList() },
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        WalkForwardMetric(0, 0), WalkForwardMetric(0, 0), WalkForwardMetric(0, 0),
        WalkForwardMetric(0, 0), WalkForwardMetric(0, 0), WalkForwardMetric(0, 0)
    )

    private fun mode(values: List<String>): String =
        values.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .first().key
}
