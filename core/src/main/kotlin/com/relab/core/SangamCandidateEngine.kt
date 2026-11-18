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
