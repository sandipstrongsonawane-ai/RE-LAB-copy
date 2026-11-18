package com.relab.app

import com.relab.core.*
import java.time.LocalDate

/**
 * Immutable result of the expensive, read-only dashboard calculations.
 * Built from one observation snapshot on Dispatchers.Default so Compose never
 * performs full-history reconciliation, pattern mining, or walk-forward scoring.
 */
internal data class DashboardAnalytics(
    val groups: List<ReconciliationGroup>,
    val repeatedSourceRecords: List<DuplicateObservationGroup>,
    val repeatedPublishedResults: List<RepeatedPublishedResultGroup>,
    val chartBuild: ChartBuildReport,
    val researchRecords: List<ChartRecord>,
    val patternAnalyses: List<MarketAnalysis>,
    val intelligenceReports: List<MarketIntelligenceReport>,
    val candidateSets: List<Triple<CandidateRanking, CandidateRanking, List<JodiCandidate>>>,
    val selfLearningReports: List<HypothesisDiscoveryReport>,
    val candidateWalkForwardReports: List<CandidateWalkForwardReport>,
    val coverageReports: List<CoverageReport>,
    val publisherCoverageReports: List<PublisherCoverageReport>,
    val sourceTiming: List<SourceTimingSummary>
) {
    companion object {
        fun compute(
            observations: List<SourceObservation>,
            coverageRangeOverrides: Map<String, Pair<LocalDate, LocalDate>>,
            marketSources: List<MarketSource>,
            learningLedger: PredictionLearningLedger
        ): DashboardAnalytics {
            val groups = SourceReconciliation.reconcile(observations)
            val chartBuild = ReconciledChartBuilder.build(observations)
            val researchRecords = ReconciledChartBuilder.buildExploratoryRecords(observations)
            val patternAnalyses = PatternAnalysis.analyze(researchRecords)
            val intelligenceReports = MarketIntelligence.analyze(researchRecords)

            val candidateSets = researchRecords
                .map { it.market to it.sessionId }
                .distinct()
                .map { (market, sessionId) ->
                    val target = LocalDate.now()
                    val open = CandidateRankingEngine.rank(
                        observations, market, sessionId, target, PannaSide.OPEN,
                        limit = 10, minimumHistory = 5, allowSingleSourceExploration = true,
                        learningLedger = learningLedger
                    )
                    val close = CandidateRankingEngine.rank(
                        observations, market, sessionId, target, PannaSide.CLOSE,
                        limit = 10, minimumHistory = 5, allowSingleSourceExploration = true,
                        learningLedger = learningLedger
                    )
                    Triple(open, close, CandidateRankingEngine.deriveJodiCandidates(
                        open.candidates, close.candidates, limit = 10
                    ))
                }

            val selfLearningReports = chartBuild.records
                .map { it.market to it.sessionId }
                .distinct()
                .flatMap { (market, sessionId) ->
                    listOf(
                        SelfLearningHypothesisEngine.discover(
                            chartBuild.records, market, sessionId, HypothesisSide.OPEN,
                            minimumHistory = 8, minimumSupport = 3
                        ),
                        SelfLearningHypothesisEngine.discover(
                            chartBuild.records, market, sessionId, HypothesisSide.CLOSE,
                            minimumHistory = 8, minimumSupport = 3
                        )
                    )
                }

            val candidateWalkForwardReports = researchRecords
                .map { it.market to it.sessionId }
                .distinct()
                .flatMap { (market, sessionId) ->
                    listOf(
                        CandidateWalkForwardEvaluator.evaluate(
                            observations, market, sessionId, PannaSide.OPEN,
                            minimumHistory = 5, topN = 10, allowSingleSourceExploration = false,
                            learningLedger = learningLedger
                        ),
                        CandidateWalkForwardEvaluator.evaluate(
                            observations, market, sessionId, PannaSide.CLOSE,
                            minimumHistory = 5, topN = 10, allowSingleSourceExploration = false,
                            learningLedger = learningLedger
                        ),
                        CandidateWalkForwardEvaluator.evaluate(
                            observations, market, sessionId, PannaSide.OPEN,
                            minimumHistory = 5, topN = 10, allowSingleSourceExploration = true,
                            learningLedger = learningLedger
                        ),
                        CandidateWalkForwardEvaluator.evaluate(
                            observations, market, sessionId, PannaSide.CLOSE,
                            minimumHistory = 5, topN = 10, allowSingleSourceExploration = true,
                            learningLedger = learningLedger
                        )
                    )
                }

            val coverageReports = observations
                .groupBy { it.market to it.sessionId }
                .mapNotNull { (key, rows) ->
                    val dates = rows.map { it.date }
                    if (dates.isEmpty()) null else HistoricalCoverageAudit.analyze(
                        market = key.first,
                        sessionId = key.second,
                        startDate = coverageRangeOverrides["${key.first.name}:${key.second}"]?.first
                            ?: dates.minOrNull()!!,
                        endDate = coverageRangeOverrides["${key.first.name}:${key.second}"]?.second
                            ?: dates.maxOrNull()!!,
                        observations = rows
                    )
                }
                .sortedWith(compareBy<CoverageReport> { it.market.name }.thenBy { it.sessionId })

            // Include registered URL groups even before their first successful fetch.
            // With no imported dates, show a rolling 30-day coverage window by default.
            val coverageKeys = (observations.map { it.market to it.sessionId } +
                marketSources.filter { it.enabled }.map { it.market to it.sessionId }).distinct()
            val publisherCoverageReports = coverageKeys.map { key ->
                val rows = observations.filter { it.market == key.first && it.sessionId == key.second }
                val dates = rows.map { it.date }
                val override = coverageRangeOverrides["${key.first.name}:${key.second}"]
                val start = override?.first ?: dates.minOrNull() ?: LocalDate.now().minusDays(30)
                val end = override?.second ?: dates.maxOrNull() ?: LocalDate.now()
                PublisherCoverageAudit.analyze(
                    market = key.first,
                    sessionId = key.second,
                    startDate = start,
                    endDate = end,
                    sources = marketSources,
                    observations = rows
                )
            }.sortedWith(compareBy<PublisherCoverageReport> { it.market.name }.thenBy { it.sessionId })

            return DashboardAnalytics(
                groups = groups,
                repeatedSourceRecords = SourceObservationMerger.auditRepeatedSourceRecords(observations),
                repeatedPublishedResults = SourceObservationMerger.auditRepeatedPublishedResultsAcrossDates(observations),
                chartBuild = chartBuild,
                researchRecords = researchRecords,
                patternAnalyses = patternAnalyses,
                intelligenceReports = intelligenceReports,
                candidateSets = candidateSets,
                selfLearningReports = selfLearningReports,
                candidateWalkForwardReports = candidateWalkForwardReports,
                coverageReports = coverageReports,
                publisherCoverageReports = publisherCoverageReports,
                sourceTiming = SourceTimingAnalytics.summarize(observations)
            )
        }
    }
}
