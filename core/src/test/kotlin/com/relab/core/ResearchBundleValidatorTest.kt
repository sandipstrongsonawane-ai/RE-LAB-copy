package com.relab.core

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchBundleValidatorTest {
    @Test
    fun acceptsBundleProducedByExporter() {
        val bytes = ResearchBundleExporter.export(
            emptyList(), emptyList(), emptyList(), emptyList(),
            Instant.parse("2026-11-17T12:00:00Z")
        )
        val result = ResearchBundleValidator.validate(bytes)
        assertTrue(result.errors.joinToString(), result.valid)
        assertEquals(8, result.verifiedFiles)
    }

    @Test
    fun rejectsTamperedArtifact() {
        val original = ResearchBundleExporter.export(
            emptyList(), emptyList(), emptyList(), emptyList(),
            Instant.parse("2026-11-17T12:00:00Z")
        )
        val entries = unzip(original).toMutableMap()
        entries["README.txt"] = "tampered".toByteArray()
        val result = ResearchBundleValidator.validate(zip(entries))
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("Byte size mismatch") || it.contains("SHA-256 mismatch") })
    }

    @Test
    fun enforcesUncompressedSizeLimitWhileReading() {
        val bytes = ResearchBundleExporter.export(
            emptyList(), emptyList(), emptyList(), emptyList(),
            Instant.parse("2026-11-17T12:00:00Z")
        )
        val result = ResearchBundleValidator.validate(bytes, maxTotalBytes = 16)
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("uncompressed size limit") })
    }

    @Test
    fun rejectsMissingAndUnsupportedEntries() {
        val original = ResearchBundleExporter.export(
            emptyList(), emptyList(), emptyList(), emptyList(),
            Instant.parse("2026-11-17T12:00:00Z")
        )
        val entries = unzip(original).toMutableMap()
        entries.remove("historical-coverage-audit.csv")
        entries["../outside.txt"] = byteArrayOf(1)
        val result = ResearchBundleValidator.validate(zip(entries))
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("Missing required files") })
        assertTrue(result.errors.any { it.contains("unsafe ZIP entry") })
    }

    @Test
    fun rejectsSemanticallyInvalidSourceCsvEvenWhenItsChecksumMatches() {
        val original = ResearchBundleExporter.export(
            emptyList(), emptyList(), emptyList(), emptyList(),
            Instant.parse("2026-11-17T12:00:00Z")
        )
        val entries = unzip(original).toMutableMap()
        entries["source-observations.csv"] = "not,a,valid,source,csv".toByteArray()
        val result = ResearchBundleValidator.validate(reindexedZip(entries))
        assertFalse(result.valid)
        assertTrue(result.errors.joinToString().contains("Source observations contain invalid rows"))
    }

    @Test
    fun rejectsSemanticallyInvalidPredictionLedgerEvenWhenItsChecksumMatches() {
        val original = ResearchBundleExporter.export(
            emptyList(), emptyList(), emptyList(), emptyList(),
            Instant.parse("2026-11-17T12:00:00Z")
        )
        val entries = unzip(original).toMutableMap()
        entries["prediction-learning-ledger.csv"] = "not,a,valid,ledger".toByteArray()
        val result = ResearchBundleValidator.validate(reindexedZip(entries))
        assertFalse(result.valid)
        assertTrue(result.errors.joinToString().contains("Invalid prediction-learning-ledger.csv"))
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun reindexedZip(entries: Map<String, ByteArray>): ByteArray {
        val index = buildString {
            appendLine("{")
            appendLine("  \"bundle_format_version\": 2,")
            appendLine("  \"artifacts\": [")
            appendLine(entries.entries.map { (name, bytes) ->
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                "    {\"file\":\"$name\",\"utf8_bytes\":${bytes.size},\"sha256\":\"$hash\"}"
            }.joinToString(",\\n"))
            appendLine("  ]")
            appendLine("}")
        }
        return zip(entries + ("bundle-index.json" to index.toByteArray()))
    }
}
