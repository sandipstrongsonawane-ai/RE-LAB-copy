package com.relab.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class BundleValidationResult(
    val valid: Boolean,
    val verifiedFiles: Int,
    val errors: List<String>
)

/** Verifies bundle structure and every artifact checksum before a restore/import is attempted. */
object ResearchBundleValidator {
    private val requiredFiles = setOf(
        "source-observations.csv",
        "derived-consensus-research.csv",
        "walk-forward-audit.csv",
        "historical-coverage-audit.csv",
        "source-publication-timing.csv",
        "prediction-learning-ledger.csv",
        "research-manifest.json",
        "README.txt",
        "bundle-index.json"
    )

    private val indexEntry = Regex(
        """\{"file":"([^"]+)","utf8_bytes":([0-9]+),"sha256":"([a-f0-9]{64})"\}"""
    )

    fun validate(zipBytes: ByteArray, maxTotalBytes: Long = 100_000_000L): BundleValidationResult {
        val errors = mutableListOf<String>()
        val entries = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory || name.startsWith("/") || name.split('/').any { it == ".." } ||
                        name.contains('\\') || name !in requiredFiles) {
                        errors += "Unsupported or unsafe ZIP entry: $name"
                        zip.closeEntry()
                        continue
                    }
                    if (entries.containsKey(name)) {
                        errors += "Duplicate ZIP entry: $name"
                        zip.closeEntry()
                        continue
                    }
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var entryTooLarge = false
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        if (totalBytes > maxTotalBytes) {
                            errors += "Bundle exceeds the uncompressed size limit"
                            entryTooLarge = true
                            break
                        }
                        output.write(buffer, 0, count)
                    }
                    if (entryTooLarge) break
                    entries[name] = output.toByteArray()
                    zip.closeEntry()
                }
            }
        } catch (error: Exception) {
            errors += "Invalid ZIP: ${error.message ?: "read error"}"
        }

        val missing = requiredFiles - entries.keys
        if (missing.isNotEmpty()) errors += "Missing required files: ${missing.sorted().joinToString()}"
        val indexBytes = entries["bundle-index.json"]
        var verified = 0
        if (indexBytes != null) {
            val index = String(indexBytes, Charsets.UTF_8)
            if (!Regex(""""bundle_format_version"\s*:\s*2""").containsMatchIn(index)) {
                errors += "Unsupported bundle format version"
            }
            val matches = indexEntry.findAll(index).toList()
            val indexedNames = matches.map { it.groupValues[1] }
            if (indexedNames.size != matches.map { it.groupValues[1] }.distinct().size) {
                errors += "Duplicate artifact in bundle index"
            }
            val expectedNames = requiredFiles - "bundle-index.json"
            if (indexedNames.toSet() != expectedNames) {
                errors += "Bundle index does not list exactly the required artifacts"
            }
            matches.forEach { match ->
                val name = match.groupValues[1]
                val expectedSize = match.groupValues[2].toLongOrNull()
                val expectedHash = match.groupValues[3]
                val bytes = entries[name]
                if (bytes == null) {
                    errors += "Indexed artifact missing: $name"
                } else if (expectedSize != bytes.size.toLong()) {
                    errors += "Byte size mismatch: $name"
                } else if (sha256(bytes) != expectedHash) {
                    errors += "SHA-256 mismatch: $name"
                } else {
                    verified++
                }
            }
        } else {
            errors += "Missing bundle index"
        }
        return BundleValidationResult(errors.isEmpty(), verified, errors)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
