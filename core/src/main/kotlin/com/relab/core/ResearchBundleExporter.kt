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
        candidateWalkForwardReports: List<CandidateWalkForwardReport> = emptyList()
    ): ByteArray {
        val artifacts = linkedMapOf(
            "source-observations.csv" to SourceCsvExporter.export(observations),
            "derived-consensus-research.csv" to DerivedChartCsvExporter.export(consensusRecords),
            "walk-forward-audit.csv" to WalkForwardCsvExporter.export(analyses),
            "cross-market-associations.csv" to CrossMarketAssociationCsvExporter.export(crossMarketReport),
            "candidate-walk-forward-audit.csv" to CandidateWalkForwardCsvExporter.export(candidateWalkForwardReports),
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
        appendLine("- derived-consensus-research.csv: validated multi-source consensus records only")
        appendLine("- walk-forward-audit.csv: chronological out-of-sample baseline audit")
        appendLine("- cross-market-associations.csv: dated market/session associations with lag, overlap, mutual information and adjusted p-value")
        appendLine("- candidate-walk-forward-audit.csv: chronological strict-consensus and explicitly exploratory baseline/learning metrics")
        appendLine("- historical-coverage-audit.csv: expected-day coverage states")
        appendLine("- source-publication-timing.csv: explicit publication-to-capture timing")
        appendLine("- prediction-learning-ledger.csv: frozen candidates and append-only GREEN/RED/GREY verification attempts")
        appendLine("- research-manifest.json: dataset checksum, reconciliation and timing metadata")
        appendLine("- bundle-index.json: SHA-256 and byte size for every other artifact")
        appendLine()
        appendLine("Important: single-source and conflicting records are retained in the source ledger but excluded from consensus research.")
        appendLine("Missing source rows are not evidence of a holiday. Publication times are never inferred.")
        appendLine("Cross-market associations are exploratory correlations, not proof of common control, causation, or future outcomes.")
        appendLine("Candidate walk-forward scoring uses only strictly earlier chart records and verified feedback available before each target-day UTC midnight.")
        appendLine("Walk-forward outputs are historical baseline evaluations, not guaranteed predictions.")
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
