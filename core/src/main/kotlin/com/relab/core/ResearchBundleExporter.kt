package com.relab.core

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Packages the complete auditable research export into one portable ZIP file. */
object ResearchBundleExporter {
    fun export(
        observations: List<SourceObservation>,
        consensusRecords: List<ChartRecord>,
        analyses: List<MarketAnalysis>,
        coverageReports: List<CoverageReport>,
        generatedAt: Instant,
        predictionLedger: PredictionLearningLedger = PredictionLearningLedger(),
        crossMarketReport: CrossMarketReverseEngineeringReport? = null,
        candidateWalkForwardReports: List<CandidateWalkForwardReport> = emptyList(),
        regimeChangeFindings: List<RegimeChangeFinding> = emptyList(),
        weekdayPatternFindings: List<WeekdayPatternFinding> = emptyList(),
        intelligenceReports: List<MarketIntelligenceReport> = emptyList(),
        candidateSets: List<Triple<CandidateRanking, CandidateRanking, List<JodiCandidate>>> = emptyList(),
        sangamResearchSets: List<SangamResearchSet> = emptyList(),
        sangamWalkForwardReports: List<SangamWalkForwardReport> = emptyList(),
        selfLearningReports: List<HypothesisDiscoveryReport> = emptyList(),
        publisherCoverageReports: List<PublisherCoverageReport> = emptyList(),
        repeatedSourceRecords: List<DuplicateObservationGroup> = emptyList(),
        repeatedPublishedResults: List<RepeatedPublishedResultGroup> = emptyList()
    ): ByteArray {
        val artifacts = linkedMapOf(
            "source-observations.csv" to SourceCsvExporter.export(observations),
            "source-observations.json" to SourceObservationJsonExporter.export(observations, generatedAt),
            "derived-consensus-research.csv" to DerivedChartCsvExporter.export(consensusRecords),
            "walk-forward-audit.csv" to WalkForwardCsvExporter.export(analyses),
            "cross-market-associations.csv" to CrossMarketAssociationCsvExporter.export(crossMarketReport),
            "candidate-walk-forward-audit.csv" to CandidateWalkForwardCsvExporter.export(candidateWalkForwardReports),
            "market-regime-change-audit.csv" to MarketRegimeChangeCsvExporter.export(regimeChangeFindings),
            "weekday-pattern-audit.csv" to WeekdayPatternCsvExporter.export(weekdayPatternFindings),
            "research-findings.txt" to findingsSummary(intelligenceReports, candidateSets, sangamResearchSets, sangamWalkForwardReports, selfLearningReports, publisherCoverageReports, repeatedSourceRecords, repeatedPublishedResults),
            "historical-coverage-audit.csv" to CoverageCsvExporter.export(coverageReports),
            "source-publication-timing.csv" to SourceTimingAnalytics.exportCsv(observations),
            "prediction-learning-ledger.csv" to PredictionLedgerCsvCodec.export(predictionLedger),
            "research-manifest.json" to ResearchManifestExporter.export(observations, consensusRecords, analyses, generatedAt),
            "README.txt" to readme(observations, consensusRecords, analyses, coverageReports, generatedAt, predictionLedger)
        )
        val index = buildString {
            appendLine("{")
            appendLine("  \"bundle_format_version\": 2,")
            appendLine("  \"generated_at\": ${json(generatedAt.toString())},")
            appendLine("  \"artifacts\": [")
            appendLine(artifacts.entries.map { (name, content) ->
                val bytes = content.toByteArray(StandardCharsets.UTF_8)
                "    {\"file\":${json(name)},\"utf8_bytes\":${bytes.size},\"sha256\":${json(sha256(bytes))}}"
            }.joinToString(",\n"))
            appendLine("  ]")
            appendLine("}")
        }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            (artifacts + ("bundle-index.json" to index)).forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun readme(
        observations: List<SourceObservation>,
        records: List<ChartRecord>,
        analyses: List<MarketAnalysis>,
        coverage: List<CoverageReport>,
        generatedAt: Instant,
        predictionLedger: PredictionLearningLedger
    ): String = buildString {
        appendLine("RE-LAB research bundle")
        appendLine("Generated at: $generatedAt")
        appendLine("Source observations: ${observations.size}")
        appendLine("Multi-source consensus chart records: ${records.size}")
        appendLine("Market/session analyses: ${analyses.size}")
        appendLine("Coverage reports: ${coverage.size}")
        appendLine("Locked predictions: ${predictionLedger.lockedPredictions.size}")
        appendLine("Verification attempts: ${predictionLedger.verifications.size}")
        appendLine()
        appendLine("Files:")
        appendLine("- source-observations.csv: full raw source ledger and provenance")
        appendLine("- source-observations.json: lossless JSON copy of every raw source observation and provenance field")
        appendLine("- derived-consensus-research.csv: validated multi-source consensus records only")
        appendLine("- walk-forward-audit.csv: chronological out-of-sample baseline audit")
        appendLine("- cross-market-associations.csv: dated market/session associations with lag, overlap, mutual information and adjusted p-value")
        appendLine("- candidate-walk-forward-audit.csv: chronological strict-consensus and explicitly exploratory baseline/learning metrics")
        appendLine("- market-regime-change-audit.csv: recent-vs-previous window distribution shifts with permutation p-values and within-session q-values")
        appendLine("- weekday-pattern-audit.csv: weekday-feature association findings, minimum-history abstentions, permutation p-values and within-session q-values")
        appendLine("- research-findings.txt: complete serialized findings for market intelligence, ranked candidates, Sangam research/backtests, self-learning hypotheses, publisher coverage and duplicate audits")
        appendLine("- historical-coverage-audit.csv: expected-day coverage states")
        appendLine("- source-publication-timing.csv: explicit publication-to-capture timing")
        appendLine("- prediction-learning-ledger.csv: frozen candidates and append-only GREEN/RED/GREY verification attempts")
        appendLine("- research-manifest.json: dataset checksum, reconciliation and timing metadata")
        appendLine("- bundle-index.json: SHA-256 and byte size for every other artifact")
        appendLine()
        appendLine("Important: single-source and conflicting records are retained in the source ledger but excluded from consensus research.")
        appendLine("Missing source rows are not evidence of a holiday. Publication times are never inferred.")
        appendLine("Cross-market associations are exploratory correlations, not proof of common control, causation, or future outcomes.")
        appendLine("Regime-change flags compare the latest two equal windows in strict consensus records; they describe historical distribution change, not future outcomes.")
        appendLine("Candidate walk-forward scoring uses only strictly earlier chart records and verified feedback available before each target-day UTC midnight.")
        appendLine("Walk-forward outputs are historical baseline evaluations, not guaranteed predictions.")
    }

    private fun findingsSummary(
        intelligence: List<MarketIntelligenceReport>,
        candidates: List<Triple<CandidateRanking, CandidateRanking, List<JodiCandidate>>>,
        sangam: List<SangamResearchSet>,
        sangamBacktests: List<SangamWalkForwardReport>,
        hypotheses: List<HypothesisDiscoveryReport>,
        publisherCoverage: List<PublisherCoverageReport>,
        repeatedSources: List<DuplicateObservationGroup>,
        repeatedPublished: List<RepeatedPublishedResultGroup>
    ): String = buildString {
        appendLine("RE-LAB complete research findings")
        appendLine("This file preserves the full data-class representation for every computed finding.")
        fun group(name: String, values: List<Any>) {
            appendLine()
            appendLine("[$name] count=${values.size}")
            values.forEachIndexed { index, value -> appendLine("${index + 1}. $value") }
        }
        group("market-intelligence", intelligence)
        group("ranked-open-close-jodi-candidates", candidates)
        group("sangam-research", sangam)
        group("sangam-walk-forward", sangamBacktests)
        group("self-learning-hypotheses", hypotheses)
        group("publisher-coverage", publisherCoverage)
        group("repeated-source-record-audit", repeatedSources)
        group("repeated-published-result-audit", repeatedPublished)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
