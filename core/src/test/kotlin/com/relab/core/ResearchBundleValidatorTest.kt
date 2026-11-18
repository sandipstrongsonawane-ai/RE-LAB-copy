package com.relab.core

import java.io.ByteArrayOutputStream
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
}
