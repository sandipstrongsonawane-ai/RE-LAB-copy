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
    val crossMarketReport: CrossMarketReverseEngineeringReport,
    val regimeChangeFindings: List<RegimeChangeFinding>,
    val weekdayPatternFindings: List<WeekdayPatternFinding>,
    val candidateSets: List<Triple<CandidateRanking, CandidateRanking, List<JodiCandidate>>>,
    val verifiedCandidateSets: List<Triple<CandidateRanking, CandidateRanking, List<JodiCandidate>>>,
    val sangamResearchSets: List<SangamResearchSet>,
    val sangamWalkForwardReports: List<SangamWalkForwardReport>,
    val selfLearningReports: List<HypothesisDiscoveryReport>,
    val candidateWalkForwardReports: List<CandidateWalkForwardReport>,
    val coverageReports: List<CoverageReport>,
    val publisherCoverageReports: List<PublisherCoverageReport>,
    val sourceTiming: List<SourceTimingSummary>
) {
    companion object {
        // A single-entry exact-input cache avoids repeating the expensive full
        // reconciliation/research pipeline when Compose re-enters the same state.
        // Equality is structural (not a hash-only key), so changed data/settings
        // always invalidate the cached snapshot.
        private data class CacheKey(
            val observations: List<SourceObservation>,
            val coverageRanges: Map<String, Pair<LocalDate, LocalDate>>,
            val marketSources: List<MarketSource>,
            val learningLedger: PredictionLearningLedger
        )

        @Volatile private var cachedKey: CacheKey? = null
        @Volatile private var cachedValue: DashboardAnalytics? = null

        @Synchronized
        fun compute(
            observations: List<SourceObservation>,
            coverageRangeOverrides: Map<String, Pair<LocalDate, LocalDate>>,
            marketSources: List<MarketSource>,
            learningLedger: PredictionLearningLedger
        ): DashboardAnalytics {
            val key = CacheKey(
                observations = observations.toList(),
                coverageRanges = coverageRangeOverrides.toMap(),
                marketSources = marketSources.toList(),
                learningLedger = learningLedger
            )
            if (cachedKey == key) cachedValue?.let { return it }

            val groups = SourceReconciliation.reconcile(observations)
            val chartBuild = ReconciledChartBuilder.build(observations)
            val researchRecords = ReconciledChartBuilder.buildExploratoryRecords(observations)
            val patternAnalyses = PatternAnalysis.analyze(researchRecords)
            val intelligenceReports = MarketIntelligence.analyze(researchRecords)
            val crossMarketReport = CrossMarketReverseEngineering.analyze(
                researchRecords, minimumOverlap = 20, maxLagDays = 1, permutations = 99
            )
            // Use validated multi-source consensus rows for regime monitoring; never
            // let exploratory single-source rows trigger a regime-shift flag.
            val regimeChangeFindings = MarketRegimeChangeDetector.analyze(
                chartBuild.records, windowSize = 20, permutations = 499
            )
            // Weekday association is evaluated only on validated multi-source consensus rows.
            val weekdayPatternFindings = WeekdayPatternAnalyzer.analyze(
                chartBuild.records, permutations = 499, minimumRecords = 30, minimumActiveWeekdays = 3
            )

            // Build a home card for every observed or enabled market/session,
            // including markets with no history yet (they must visibly abstain).
            val marketSessionKeys = (observations.map { it.market to it.sessionId } +
                marketSources.filter { it.enabled }.map { it.market to it.sessionId })
                .distinct()
                .sortedWith(compareBy<Pair<Market, String>> { it.first.name }.thenBy { it.second })

            val candidateSets = marketSessionKeys
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

            // Verified mode is strictly consensus-only. Exploratory mode is kept in
            // candidateSets and may include single-publisher rows, with an explicit label.
            val verifiedCandidateSets = marketSessionKeys.map { (market, sessionId) ->
                val target = LocalDate.now()
                val open = CandidateRankingEngine.rank(
                    observations, market, sessionId, target, PannaSide.OPEN,
                    limit = 10, minimumHistory = 5, allowSingleSourceExploration = false,
                    learningLedger = learningLedger
                )
                val close = CandidateRankingEngine.rank(
                    observations, market, sessionId, target, PannaSide.CLOSE,
                    limit = 10, minimumHistory = 5, allowSingleSourceExploration = false,
                    learningLedger = learningLedger
                )
                Triple(open, close, CandidateRankingEngine.deriveJodiCandidates(open.candidates, close.candidates, limit = 10))
            }

            val sangamResearchSets = candidateSets.map { (open, close, _) ->
                SangamCandidateEngine.derive(open.candidates, close.candidates, limit = 10)
            }

            val sangamWalkForwardReports = researchRecords
                .map { it.market to it.sessionId }
                .distinct()
                .map { (market, sessionId) ->
                    SangamWalkForwardEvaluator.evaluate(
                        observations, market, sessionId,
                        minimumHistory = 5, topN = 10,
                        allowSingleSourceExploration = false
                    )
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

            // Include every observed market and every enabled registered market, even
            // when a source has never returned a row. This makes never-fetched history
            // visible as NO_OBSERVATION instead of silently omitting the market.
            val coverageKeys = (observations.map { it.market to it.sessionId } +
                marketSources.filter { it.enabled }.map { it.market to it.sessionId }).distinct()

            val coverageReports = coverageKeys.map { key ->
                val dates = observations.asSequence()
                    .filter { it.market == key.first && it.sessionId == key.second }
                    .map { it.date }
                    .toList()
                val override = coverageRangeOverrides["${key.first.name}:${key.second}"]
                val start = override?.first ?: dates.minOrNull() ?: LocalDate.now().minusDays(30)
                val end = override?.second ?: dates.maxOrNull() ?: LocalDate.now()
                HistoricalCoverageAudit.analyze(
                    market = key.first,
                    sessionId = key.second,
                    startDate = start,
                    endDate = end,
                    observations = observations
                )
            }.sortedWith(compareBy<CoverageReport> { it.market.name }.thenBy { it.sessionId })

            // Per-URL publisher coverage also uses the same complete market/session set.
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
                crossMarketReport = crossMarketReport,
                regimeChangeFindings = regimeChangeFindings,
                weekdayPatternFindings = weekdayPatternFindings,
                candidateSets = candidateSets,
                verifiedCandidateSets = verifiedCandidateSets,
                sangamResearchSets = sangamResearchSets,
                sangamWalkForwardReports = sangamWalkForwardReports,
                selfLearningReports = selfLearningReports,
                candidateWalkForwardReports = candidateWalkForwardReports,
                coverageReports = coverageReports,
                publisherCoverageReports = publisherCoverageReports,
                sourceTiming = SourceTimingAnalytics.summarize(observations)
            ).also { result ->
                cachedKey = key
                cachedValue = result
            }
        }
    }
}
