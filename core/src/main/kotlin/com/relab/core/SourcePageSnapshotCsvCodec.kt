package com.relab.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/** Restores byte-verified raw HTML snapshots from the optional bundle manifest. */
object SourcePageSnapshotCsvCodec {
    val HEADER = "source_id,market,session_id,requested_url,final_url,retrieved_at,html_file,sha256,utf8_bytes"

    fun import(manifestCsv: String, htmlFiles: Map<String, ByteArray>): List<SourcePageSnapshot> {
        val rows = parseCsv(manifestCsv)
        require(rows.isNotEmpty() && rows.first() == HEADER.split(',')) {
            "Invalid source-page-snapshots.csv header."
        }
        val snapshots = rows.drop(1).mapIndexed { index, row ->
            require(row.size == 9) { "Snapshot manifest row ${index + 2} must contain 9 fields." }
            val file = row[6]
            require(Regex("""source-snapshots/[0-9]{6}_[a-f0-9]{64}\.html""").matches(file)) {
                "Unsafe snapshot file path in manifest row ${index + 2}."
            }
            val bytes = htmlFiles[file] ?: error("Snapshot HTML file is missing: $file")
            val expectedSize = row[8].toIntOrNull() ?: error("Invalid snapshot byte size in row ${index + 2}.")
            require(bytes.size == expectedSize) { "Snapshot byte size mismatch: $file" }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            require(hash == row[7]) { "Snapshot SHA-256 mismatch: $file" }
            val html = String(bytes, StandardCharsets.UTF_8)
            require(html.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) {
                "Snapshot is not valid round-trippable UTF-8: $file"
            }
            SourcePageSnapshot(
                sourceId = row[0],
                market = Market.valueOf(row[1]),
                sessionId = row[2],
                requestedUrl = row[3],
                finalUrl = row[4],
                retrievedAt = Instant.parse(row[5]),
                html = html
            ).also {
                require(it.sha256 == row[7]) { "Decoded snapshot SHA-256 mismatch: $file" }
            }
        }
        require(snapshots.size == snapshots.distinctBy {
            listOf(it.sourceId, it.market.name, it.sessionId, it.requestedUrl, it.retrievedAt.toString(), it.sha256)
        }.size) { "Duplicate source snapshot manifest rows." }
        require(htmlFiles.keys == rows.drop(1).map { it[6] }.toSet()) {
            "Snapshot manifest and HTML file entries do not match exactly."
        }
        return snapshots
    }

    private fun parseCsv(input: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var cursor = 0
        while (cursor < input.length) {
            val ch = input[cursor]
            when {
                quoted && ch == '"' && cursor + 1 < input.length && input[cursor + 1] == '"' -> {
                    field.append('"')
                    cursor++
                }
                ch == '"' -> quoted = !quoted
                !quoted && ch == ',' -> {
                    row += field.toString()
                    field.setLength(0)
                }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    row += field.toString()
                    field.setLength(0)
                    if (row.any { it.isNotEmpty() }) rows += row
                    row = mutableListOf()
                    if (ch == '\r' && cursor + 1 < input.length && input[cursor + 1] == '\n') cursor++
                }
                else -> field.append(ch)
            }
            cursor++
        }
        require(!quoted) { "Unclosed quoted field in snapshot manifest." }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += row
        }
        return rows
    }
}
