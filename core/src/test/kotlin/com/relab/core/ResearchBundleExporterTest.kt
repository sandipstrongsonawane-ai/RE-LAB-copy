package com.relab.core

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchBundleExporterTest {
    @Test
    fun bundleContainsCompleteLedgerResearchAndIntegrityIndex() {
        val observation = SourceObservation(
            market = Market.SRIDEVI,
            sessionId = "main",
            date = LocalDate.parse("2026-11-16"),
            sourceId = "publisher-a",
            retrievedAt = Instant.parse("2026-11-16T10:00:00Z"),
            publishedAt = Instant.parse("2026-11-16T09:00:00Z"),
            rawText = "577-99-667",
            normalizedResult = "577-99-667",
            status = ObservationStatus.PUBLISHED
        )
        val prediction = LockedSidePrediction(
            Market.SRIDEVI, "main", LocalDate.parse("2026-11-17"), PannaSide.OPEN,
            listOf("123", "456"), 8, Instant.parse("2026-11-17T11:00:00Z"), "frozen fixture"
        )
        val attempt = PredictionVerification(
            prediction.id, PredictionGrade.GREY, null, null, null,
            Instant.parse("2026-11-17T12:00:00Z"), ReconciliationState.SINGLE_SOURCE_UNVERIFIED,
            "Awaiting independent publisher.", 1
        )
        val ledger = PredictionLearningLedger(listOf(prediction), listOf(attempt))
        val bytes = ResearchBundleExporter.export(
            listOf(observation), emptyList(), emptyList(), emptyList(),
            Instant.parse("2026-11-17T12:00:00Z"), ledger,
            CrossMarketReverseEngineeringReport(2, 0, 1, emptyList(), "Exploratory only")
        )
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }

        assertTrue(entries.keys.containsAll(listOf(
            "source-observations.csv", "source-observations.json", "derived-consensus-research.csv",
            "walk-forward-audit.csv", "historical-coverage-audit.csv",
            "source-publication-timing.csv", "prediction-learning-ledger.csv", "cross-market-associations.csv", "research-manifest.json",
            "candidate-walk-forward-audit.csv", "market-regime-change-audit.csv", "README.txt", "bundle-index.json"
        )))
        val sourceCsv = String(entries.getValue("source-observations.csv"), Charsets.UTF_8)
        assertTrue(sourceCsv.contains("publisher-a"))
        assertTrue(sourceCsv.contains("577-99-667"))
        val crossMarketCsv = String(entries.getValue("cross-market-associations.csv"), Charsets.UTF_8)
        assertEquals(CrossMarketAssociationCsvExporter.HEADER, crossMarketCsv)
        assertEquals(CandidateWalkForwardCsvExporter.HEADER, String(entries.getValue("candidate-walk-forward-audit.csv"), Charsets.UTF_8))
        assertEquals(MarketRegimeChangeCsvExporter.HEADER + "\n", String(entries.getValue("market-regime-change-audit.csv"), Charsets.UTF_8))
        val manifest = String(entries.getValue("research-manifest.json"), Charsets.UTF_8)
        assertTrue(manifest.contains("source_ledger_sha256"))
        val restoredLedger = PredictionLedgerCsvCodec.import(
            String(entries.getValue("prediction-learning-ledger.csv"), Charsets.UTF_8)
        )
        assertEquals(PredictionLearningLedger(listOf(prediction), listOf(attempt)), restoredLedger)
        assertTrue(String(entries.getValue("README.txt"), Charsets.UTF_8).contains("Locked predictions: 1"))
        val index = String(entries.getValue("bundle-index.json"), Charsets.UTF_8)
        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(entries.getValue("source-observations.csv"))
            .joinToString("") { "%02x".format(it) }
        assertTrue(index.contains(expectedHash))
        assertTrue(String(entries.getValue("README.txt"), Charsets.UTF_8).contains("not guaranteed predictions"))
        assertNotNull(bytes)
    }

    @Test
    fun sourceObservationJsonExportPreservesRawValuesProvenanceAndLeadingZeroes() {
        val first = SourceObservation(
            market = Market.SRIDEVI, sessionId = "night",
            date = LocalDate.parse("2026-11-18"), sourceId = "publisher-a",
            sourceRecordId = "row-\"07", sourceUrl = "https://publisher.example/chart",
            retrievedAt = Instant.parse("2026-11-18T18:00:00Z"), publishedAt = null,
            rawText = "012-07-000\\n*", normalizedResult = "012-07-000",
            status = ObservationStatus.PUBLISHED
        )
        val conflict = first.copy(
            sourceId = "publisher-b", rawText = "123-45-678",
            normalizedResult = "123-45-678"
        )
        val json = SourceObservationJsonExporter.export(
            listOf(first, conflict), Instant.parse("2026-11-19T00:00:00Z")
        )
        assertTrue(json.contains("\"record_count\": 2"))
        assertTrue(json.contains("\"session_id\": \"night\""))
        assertTrue(json.contains("\"raw_text\": \"012-07-000\\\\n*\""))
        assertTrue(json.contains("\"normalized_result\": \"012-07-000\""))
        assertTrue(json.contains("\"published_at\": null"))
        assertTrue(json.contains("\"source_record_id\":"))
        assertTrue(json.contains("row-"))
        assertTrue(json.contains("123-45-678"))
    }
    @Test
    fun bundleExportsExactRawHtmlSnapshotsAndManifestProvenance() {
        val html = "<html><table><tr><td>2026-11-16</td><td>012-07-000</td></tr></table></html>"
        val snapshot = SourcePageSnapshot(
            sourceId = "publisher-a",
            market = Market.SRIDEVI,
            sessionId = "night",
            requestedUrl = "https://publisher.example/night",
            finalUrl = "https://publisher.example/night?view=full",
            retrievedAt = Instant.parse("2026-11-18T03:00:00Z"),
            html = html
        )
        val bytes = ResearchBundleExporter.export(
            emptyList(), emptyList(), emptyList(), emptyList(),
            Instant.parse("2026-11-18T04:00:00Z"),
            sourceSnapshots = listOf(snapshot)
        )
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        val htmlPath = entries.keys.single { it.startsWith("source-snapshots/") }
        assertEquals(html, String(entries.getValue(htmlPath), Charsets.UTF_8))
        val manifest = String(entries.getValue("source-page-snapshots.csv"), Charsets.UTF_8)
        assertTrue(manifest.contains("publisher-a"))
        assertTrue(manifest.contains("night"))
        assertTrue(manifest.contains(snapshot.requestedUrl))
        assertTrue(manifest.contains(snapshot.finalUrl))
        assertTrue(manifest.contains(snapshot.retrievedAt.toString()))
        assertTrue(manifest.contains(snapshot.sha256))
        assertTrue(manifest.contains(htmlPath))
    }

}
