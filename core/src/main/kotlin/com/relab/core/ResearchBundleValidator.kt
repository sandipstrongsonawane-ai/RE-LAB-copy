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
    private data class IndexedArtifact(val name: String, val size: Long, val sha256: String)

    private val requiredFiles = setOf(
        "source-observations.csv",
        "source-observations.json",
        "derived-consensus-research.csv",
        "walk-forward-audit.csv",
        "cross-market-associations.csv",
        "candidate-walk-forward-audit.csv",
        "market-regime-change-audit.csv",
        "weekday-pattern-audit.csv",
        "research-findings.txt",
        "historical-coverage-audit.csv",
        "source-publication-timing.csv",
        "prediction-learning-ledger.csv",
        "research-manifest.json",
        "README.txt",
        "bundle-index.json"
    )

    // This intentionally accepts only the index shape emitted by ResearchBundleExporter.
    // Anchoring the entire document prevents regex matches inside malformed JSON or trailing junk.
    private val indexRoot = Regex(
        """\s*\{\s*"bundle_format_version"\s*:\s*2\s*,\s*(?:"generated_at"\s*:\s*"(?:\\.|[^"\\])*"\s*,\s*)?"artifacts"\s*:\s*\[(.*)]\s*}\s*""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val artifactPattern = Regex(
        """\s*\{\s*"file"\s*:\s*"([^"\\]*)"\s*,\s*"utf8_bytes"\s*:\s*([0-9]+)\s*,\s*"sha256"\s*:\s*"([a-f0-9]{64})"\s*}\s*"""
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
                        name.contains('\\') || !(name in requiredFiles || name == "source-page-snapshots.csv" ||
                            Regex("""source-snapshots/[0-9]{6}_[a-f0-9]{64}\.html""").matches(name))) {
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
            val parsedArtifacts = parseIndex(String(indexBytes, Charsets.UTF_8))
            if (parsedArtifacts == null) {
                errors += "Malformed bundle index JSON or unsupported index structure"
            } else {
                val indexedNames = parsedArtifacts.map { it.name }
                if (indexedNames.size != indexedNames.distinct().size) {
                    errors += "Duplicate artifact in bundle index"
                }
                val expectedNames = entries.keys - "bundle-index.json"
                if (!indexedNames.toSet().containsAll(requiredFiles - "bundle-index.json") ||
                    indexedNames.toSet() != expectedNames) {
                    errors += "Bundle index does not list exactly the required and optional artifacts"
                }
                val snapshotFiles = entries.keys.filter { it.startsWith("source-snapshots/") }
                val snapshotManifest = entries["source-page-snapshots.csv"]
                if (snapshotFiles.isEmpty() != (snapshotManifest == null)) {
                    errors += "Raw snapshot files and source-page-snapshots.csv must be present together"
                } else if (snapshotManifest != null) {
                    val manifest = String(snapshotManifest, Charsets.UTF_8)
                    if (!manifest.startsWith("source_id,market,session_id,requested_url,final_url,retrieved_at,html_file,sha256,utf8_bytes\n") ||
                        snapshotFiles.any { file -> !manifest.contains(file) || !manifest.contains(sha256(entries.getValue(file))) }) {
                        errors += "Raw snapshot manifest is malformed or omits an HTML snapshot"
                    }
                }
                parsedArtifacts.forEach { artifact ->
                    val bytes = entries[artifact.name]
                    if (bytes == null) {
                        errors += "Indexed artifact missing: ${artifact.name}"
                    } else if (artifact.size != bytes.size.toLong()) {
                        errors += "Byte size mismatch: ${artifact.name}"
                    } else if (sha256(bytes) != artifact.sha256) {
                        errors += "SHA-256 mismatch: ${artifact.name}"
                    } else {
                        verified++
                    }
                }
            }
        } else {
            errors += "Missing bundle index"
        }

        // A matching checksum proves byte integrity, not that the CSV records are importable.
        // Validate semantic content here so the app never starts a partial restore.
        if (errors.isEmpty()) {
            val sourceCsv = String(entries.getValue("source-observations.csv"), Charsets.UTF_8)
            val sourceReport = runCatching { SourceCsvImporter.import(sourceCsv) }
                .getOrElse { error ->
                    errors += "Invalid source-observations.csv: ${error.message ?: "parse error"}"
                    null
                }
            if (sourceReport != null && sourceReport.issues.isNotEmpty()) {
                val details = sourceReport.issues.take(5).joinToString("; ") {
                    "row ${it.rowNumber}: ${it.message}"
                }
                errors += "Source observations contain invalid rows: $details"
            }

            val ledgerCsv = String(entries.getValue("prediction-learning-ledger.csv"), Charsets.UTF_8)
            runCatching { PredictionLedgerCsvCodec.import(ledgerCsv) }.onFailure { error ->
                errors += "Invalid prediction-learning-ledger.csv: ${error.message ?: "parse error"}"
            }
        }
        return BundleValidationResult(errors.isEmpty(), verified, errors)
    }

    /** Parses the complete exporter index; any unconsumed character or unknown field is rejected. */
    private fun parseIndex(input: String): List<IndexedArtifact>? {
        val root = indexRoot.matchEntire(input) ?: return null
        val body = root.groupValues[1]
        val artifacts = mutableListOf<IndexedArtifact>()
        var cursor = 0
        fun skipWhitespace() {
            while (cursor < body.length && body[cursor].isWhitespace()) cursor++
        }
        skipWhitespace()
        if (cursor == body.length) return artifacts
        while (cursor < body.length) {
            val match = artifactPattern.matchAt(body, cursor) ?: return null
            val size = match.groupValues[2].toLongOrNull() ?: return null
            artifacts += IndexedArtifact(match.groupValues[1], size, match.groupValues[3])
            cursor = match.range.last + 1
            skipWhitespace()
            if (cursor == body.length) break
            if (body[cursor] != ',') return null
            cursor++
            skipWhitespace()
            if (cursor == body.length) return null
        }
        return artifacts
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
