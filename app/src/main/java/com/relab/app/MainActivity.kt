package com.relab.app

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.relab.core.ObservationStatus
import com.relab.core.CoverageReport
import com.relab.core.CoverageState
import com.relab.core.CoverageCsvExporter
import com.relab.core.ResearchBundleExporter
import com.relab.core.DuplicateObservationGroup
import com.relab.core.DuplicateObservationState
import com.relab.core.HistoricalCoverageAudit
import com.relab.core.ReconciliationState
import com.relab.core.MarketAnalysis
import com.relab.core.PatternAnalysis
import com.relab.core.ReconciledChartBuilder
import com.relab.core.SourceCsvImporter
import com.relab.core.SourceCsvExporter
import com.relab.core.SourceObservationMerger
import com.relab.core.SourceTimingAnalytics
import com.relab.core.SourceReconciliation
import java.time.LocalDate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_BUNDLE_ARCHIVE_BYTES = 100_000_000L

private fun readBoundedBytes(input: InputStream, maxBytes: Long): ByteArray {
    require(maxBytes >= 0) { "Maximum byte count must not be negative" }
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes) { "Selected file exceeds size limit: " + maxBytes + " bytes" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = LocalObservationDatabase(this)
        val preferences = getSharedPreferences("relab_local", MODE_PRIVATE)

        // One-time migration from the first CSV-only build. Keep the old preference
        // until the database transaction succeeds so a failed migration is recoverable.
        val legacyCsv = preferences.getString("source_csv", null)
        if (database.loadOriginalCsv() == null && !legacyCsv.isNullOrBlank()) {
            val legacyReport = SourceCsvImporter.import(legacyCsv)
            if (legacyReport.observations.isNotEmpty()) {
                database.replaceDataset(legacyCsv, legacyReport.observations)
                preferences.edit().remove("source_csv").apply()
            }
        }

        val initialCoverageRanges = preferences.all.mapNotNull { (key, value) ->
            if (!key.startsWith(COVERAGE_RANGE_PREFIX) || value !is String) return@mapNotNull null
            val parts = value.split('|')
            if (parts.size != 2) return@mapNotNull null
            try {
                key.removePrefix(COVERAGE_RANGE_PREFIX) to (LocalDate.parse(parts[0]) to LocalDate.parse(parts[1]))
            } catch (_: Exception) {
                null
            }
        }.toMap()

        setContent {
            RelabHome(
                initialCoverageRanges = initialCoverageRanges,
                onSaveCoverageRange = { key, start, end ->
                    preferences.edit().putString(COVERAGE_RANGE_PREFIX + key, "$start|$end").apply()
                },
                initialCsv = database.loadOriginalCsv(),
                initialObservations = database.loadObservations(),
                onSaveCsv = { csv, observations ->
                    require(observations.isNotEmpty()) { "No valid source observations to save" }
                    database.appendDataset(csv, observations)
                },
                onSaveLinkObservations = { imported ->
                    require(imported.isNotEmpty()) { "No chart rows were extracted from this link" }
                    database.appendDataset(SourceCsvExporter.export(imported), imported)
                },
                onClearCsv = {
                    database.clearDataset()
                    val rangeKeys = preferences.all.keys.filter { it.startsWith(COVERAGE_RANGE_PREFIX) }
                    preferences.edit().apply {
                        remove("source_csv")
                        rangeKeys.forEach(::remove)
                    }.apply()
                }
            )
        }
    }

    companion object {
        private const val COVERAGE_RANGE_PREFIX = "coverage_range:"
    }
}

@Composable
private fun RelabHome(
    initialCsv: String?,
    initialObservations: List<com.relab.core.SourceObservation>,
    initialCoverageRanges: Map<String, Pair<LocalDate, LocalDate>>,
    onSaveCoverageRange: (String, LocalDate, LocalDate) -> Unit,
    onSaveCsv: (String, List<com.relab.core.SourceObservation>) -> List<com.relab.core.SourceObservation>,
    onSaveLinkObservations: (List<com.relab.core.SourceObservation>) -> List<com.relab.core.SourceObservation>,
    onClearCsv: () -> Unit
) {
    val context = LocalContext.current
    var rawCsv by remember { mutableStateOf(initialCsv) }
    var observations by remember { mutableStateOf(initialObservations) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var chartUrl by remember { mutableStateOf("") }
    var linkBusy by remember { mutableStateOf(false) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) {
            try {
                val csv = SourceCsvExporter.export(observations)
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csv) }
                    ?: error("Backup file could not be written")
                importMessage = "Exported ${observations.size} source observations with provenance and raw text."
            } catch (error: Exception) {
                importMessage = "Export error: ${error.message ?: "Unknown error"}"
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val csv = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("Selected file could not be opened")
                val parsed = SourceCsvImporter.import(csv)
                if (parsed.observations.isEmpty()) {
                    importMessage = "Import failed: no valid source observations. " + (parsed.issues.firstOrNull()?.message ?: "")
                } else {
                    val previousCount = observations.size
                    val merged = onSaveCsv(csv, parsed.observations)
                    val addedCount = (merged.size - previousCount).coerceAtLeast(0)
                    observations = merged
                    rawCsv = csv
                    importMessage = "Added ${addedCount} new observations. Total: ${merged.size}. Exact re-imports are skipped; source conflicts are retained. Row issues: ${parsed.issues.size}."
                }
            } catch (error: Exception) {
                importMessage = "CSV read/import error: ${error.message ?: "Unknown error"}"
            }
        }
    }

    val report = remember(rawCsv) { rawCsv?.let { SourceCsvImporter.import(it) } }
    val groups = remember(observations) { SourceReconciliation.reconcile(observations) }
    val conflicts = groups.count { it.state == ReconciliationState.CONFLICT }
    val repeatedSourceRecords = remember(observations) {
        SourceObservationMerger.auditRepeatedSourceRecords(observations)
    }
    val repeatedPublishedResults = remember(observations) {
        SourceObservationMerger.auditRepeatedPublishedResultsAcrossDates(observations)
    }
    val published = observations.count { it.status == ObservationStatus.PUBLISHED }
    val pending = observations.count { it.status == ObservationStatus.PENDING }
    val malformed = observations.count { it.status == ObservationStatus.MALFORMED }
    val chartBuild = remember(observations) { ReconciledChartBuilder.build(observations) }
    // Exploratory records include valid single-publisher rows, but remain visibly
    // unverified; the consensus ledger and reconciliation counts stay unchanged.
    val researchRecords = remember(observations) {
        ReconciledChartBuilder.buildExploratoryRecords(observations)
    }
    val patternAnalyses = remember(researchRecords) { PatternAnalysis.analyze(researchRecords) }
    val intelligenceReports = remember(researchRecords) { com.relab.core.MarketIntelligence.analyze(researchRecords) }
    val candidateSets = remember(observations) {
        researchRecords.map { it.market to it.sessionId }.distinct().map { (market, sessionId) ->
            val target = LocalDate.now()
            val open = com.relab.core.CandidateRankingEngine.rank(
                observations, market, sessionId, target, com.relab.core.PannaSide.OPEN,
                limit = 10, minimumHistory = 5, allowSingleSourceExploration = true
            )
            val close = com.relab.core.CandidateRankingEngine.rank(
                observations, market, sessionId, target, com.relab.core.PannaSide.CLOSE,
                limit = 10, minimumHistory = 5, allowSingleSourceExploration = true
            )
            Triple(
                open,
                close,
                com.relab.core.CandidateRankingEngine.deriveJodiCandidates(
                    open.candidates, close.candidates, limit = 10
                )
            )
        }
    }
    val candidateWalkForwardReports = remember(observations) {
        researchRecords.map { it.market to it.sessionId }.distinct().flatMap { (market, sessionId) ->
            listOf(
                com.relab.core.CandidateWalkForwardEvaluator.evaluate(
                    observations, market, sessionId, com.relab.core.PannaSide.OPEN,
                    minimumHistory = 5, topN = 10, allowSingleSourceExploration = true
                ),
                com.relab.core.CandidateWalkForwardEvaluator.evaluate(
                    observations, market, sessionId, com.relab.core.PannaSide.CLOSE,
                    minimumHistory = 5, topN = 10, allowSingleSourceExploration = true
                )
            )
        }
    }
    val researchExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) {
            try {
                val csv = com.relab.core.DerivedChartCsvExporter.export(chartBuild.records)
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csv) }
                    ?: error("Research export file could not be written")
                importMessage = "Exported ${chartBuild.records.size} validated consensus chart records with derived features."
            } catch (error: Exception) {
                importMessage = "Research export error: ${error.message ?: "Unknown error"}"
            }
        }
    }
    val walkForwardExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) {
            try {
                val csv = com.relab.core.WalkForwardCsvExporter.export(patternAnalyses)
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csv) }
                    ?: error("Walk-forward file could not be written")
                val exportedRows = patternAnalyses.sumOf { it.walkForwardRows.size }
                importMessage = "Exported $exportedRows chronological walk-forward rows across ${patternAnalyses.size} market/session analyses."
            } catch (error: Exception) {
                importMessage = "Walk-forward export error: ${error.message ?: "Unknown error"}"
            }
        }
    }

    val manifestExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) {
            try {
                val manifest = com.relab.core.ResearchManifestExporter.export(
                    observations, chartBuild.records, patternAnalyses, java.time.Instant.now()
                )
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(manifest) }
                    ?: error("Research manifest file could not be written")
                importMessage = "Exported reproducibility manifest with SHA-256 source-ledger checksum and dataset coverage metadata."
            } catch (error: Exception) {
                importMessage = "Manifest export error: ${error.message ?: "Unknown error"}"
            }
        }
    }

    var coverageRangeOverrides by remember { mutableStateOf(initialCoverageRanges) }
    val coverageReports = remember(observations, coverageRangeOverrides) {
        observations.groupBy { it.market to it.sessionId }
            .mapNotNull { (key, rows) ->
                val dates = rows.map { it.date }
                if (dates.isEmpty()) null else HistoricalCoverageAudit.analyze(
                    market = key.first,
                    sessionId = key.second,
                    startDate = coverageRangeOverrides["${key.first.name}:${key.second}"]?.first ?: dates.minOrNull()!!,
                    endDate = coverageRangeOverrides["${key.first.name}:${key.second}"]?.second ?: dates.maxOrNull()!!,
                    observations = rows
                )
            }
            .sortedWith(compareBy<CoverageReport> { it.market.name }.thenBy { it.sessionId })
    }

    val sourceTiming = remember(observations) { SourceTimingAnalytics.summarize(observations) }
    val timingExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) {
            try {
                val csv = SourceTimingAnalytics.exportCsv(observations)
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csv) }
                    ?: error("Source timing file could not be written")
                importMessage = "Exported publication-to-capture timing for ${sourceTiming.size} publisher/session groups. Missing publication times remain unestimated."
            } catch (error: Exception) {
                importMessage = "Source timing export error: ${error.message ?: "Unknown error"}"
            }
        }    }

    val coverageExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) {
            try {
                val csv = CoverageCsvExporter.export(coverageReports)
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csv) }
                    ?: error("Coverage audit file could not be written")
                val rows = coverageReports.sumOf { it.days.size }
                importMessage = "Exported $rows expected trading-day coverage rows across ${coverageReports.size} market/session reports."
            } catch (error: Exception) {
                importMessage = "Coverage export error: ${error.message ?: "Unknown error"}"
            }
        }
    }

    val bundleExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) {
            try {
                val bundle = ResearchBundleExporter.export(
                    observations, chartBuild.records, patternAnalyses, coverageReports, java.time.Instant.now()
                )
                context.contentResolver.openOutputStream(uri)?.use { it.write(bundle) }
                    ?: error("Research bundle file could not be written")
                importMessage = "Exported complete research bundle: ${observations.size} source observations, ${chartBuild.records.size} consensus records and SHA-256 integrity index."
            } catch (error: Exception) {
                importMessage = "Research bundle export error: ${error.message ?: "Unknown error"}"
            }
        }
    }

    val bundleValidationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use {
                    readBoundedBytes(it, MAX_BUNDLE_ARCHIVE_BYTES)
                } ?: error("Selected bundle could not be opened")
                val validation = com.relab.core.ResearchBundleValidator.validate(bytes)
                if (!validation.valid) {
                    importMessage = "Bundle rejected: ${validation.errors.joinToString("; ")}"
                } else {
                    val sourceCsv = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                        var content: String? = null
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (entry.name == "source-observations.csv") {
                                content = readBoundedBytes(zip, MAX_BUNDLE_ARCHIVE_BYTES).toString(Charsets.UTF_8)
                                break
                            }
                            zip.closeEntry()
                        }
                        content
                    } ?: error("Verified bundle has no source-observations.csv")
                    val parsed = SourceCsvImporter.import(sourceCsv)
                    if (parsed.observations.isEmpty()) {
                        importMessage = "Bundle verified, but no valid source observations were available to import."
                    } else {
                        val previousCount = observations.size
                        val merged = onSaveCsv(sourceCsv, parsed.observations)
                        val addedCount = (merged.size - previousCount).coerceAtLeast(0)
                        observations = merged
                        rawCsv = sourceCsv
                        importMessage = "Verified bundle imported: ${validation.verifiedFiles} artifact checksums passed; added ${addedCount} observations. Total: ${merged.size}. Existing records and conflicts were retained. Row issues: ${parsed.issues.size}."
                    }
                }
            } catch (error: Exception) {
                importMessage = "Bundle validation error: ${error.message ?: "Unknown error"}"
            }
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("RE-LAB", style = MaterialTheme.typography.headlineLarge)
                Text("5 markets · Offline chart research", style = MaterialTheme.typography.titleMedium)
                Text("Paste any supported chart URL. RE-LAB detects the market from its exact source path.")
                OutlinedTextField(
                    value = chartUrl,
                    onValueChange = { chartUrl = it },
                    label = { Text("Paste chart website link") },
                    placeholder = { Text("https://sattamatkajodi.com/record/kalyan-penal-chart.php") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = chartUrl.isNotBlank() && !linkBusy,
                    onClick = {
                        val detected = com.relab.core.Market.fromSourceUrl(chartUrl.trim())
                        if (detected == null) {
                            importMessage = "Unsupported chart URL. Supported markets: Sridevi, Sridevi Night, Kalyan, Time Bazar, Main Bazar."
                        } else {
                            importChartLink(chartUrl, detected, onSaveLinkObservations, { observations = it }, { importMessage = it }, { linkBusy = it })
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (linkBusy) "Fetching..." else "Auto-detect market & fetch") }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { picker.launch(arrayOf("text/*", "text/csv", "application/csv")) }) {
                            Text("Import CSV")
                        }
                        OutlinedButton(
                            onClick = { bundleValidationPicker.launch(arrayOf("application/zip", "application/octet-stream")) }
                        ) { Text("Import verified bundle") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { bundleExportPicker.launch("relab-complete-research-bundle.zip") },
                            enabled = observations.isNotEmpty()
                        ) { Text("Export complete bundle") }
                        OutlinedButton(
                            onClick = { exportPicker.launch("relab-source-backup.csv") },
                            enabled = observations.isNotEmpty()
                        ) { Text("Export backup") }
                    }
                    OutlinedButton(onClick = {
                        rawCsv = null
                        observations = emptyList()
                        onClearCsv()
                        importMessage = "Local database cleared."
                    }, enabled = observations.isNotEmpty()) { Text("Clear local dataset") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { researchExportPicker.launch("relab-derived-consensus-research.csv") },
                        enabled = chartBuild.records.isNotEmpty()
                    ) { Text("Export research CSV") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { walkForwardExportPicker.launch("relab-walk-forward-audit.csv") },
                        enabled = patternAnalyses.any { it.walkForwardRows.isNotEmpty() }
                    ) { Text("Export walk-forward CSV") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { manifestExportPicker.launch("relab-research-manifest.json") },
                        enabled = observations.isNotEmpty()
                    ) { Text("Export research manifest") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { coverageExportPicker.launch("relab-historical-coverage-audit.csv") },
                        enabled = coverageReports.isNotEmpty()
                    ) { Text("Export coverage audit CSV") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { timingExportPicker.launch("relab-source-publication-timing.csv") },
                        enabled = sourceTiming.isNotEmpty()
                    ) { Text("Export source timing CSV") }
                }
                importMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Card(colors = CardDefaults.cardColors()) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Local dataset", style = MaterialTheme.typography.titleLarge)
                        Text("Source observations: ${observations.size}")
                        Text("Published: $published   Pending: $pending")
                        Text("Malformed rows: $malformed   Conflicting dates: $conflicts")
                        Text("Reconciled date groups: ${groups.size}")
                        if (report == null) {
                            Text("No CSV imported yet. Import a source CSV to begin historical research.")
                            Text(
                                "Required columns: market, session_id, date, source_id, retrieved_at, raw_text, status. " +
                                    "For a published result, include the result column too.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (report != null && report.issues.isNotEmpty()) {
                            Text("Import row issues: ${report.issues.size}")
                            report.issues.take(5).forEach { issue ->
                                Text("Row ${issue.rowNumber}: ${issue.message}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Text("Pattern research · exploratory + verified", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Analysis input: ${researchRecords.size} valid historical rows (${chartBuild.records.size} consensus + ${chartBuild.singleSourceUnverifiedGroups.size} single-source groups) · Conflicts excluded: ${chartBuild.conflictGroups.size} · Pending/missing excluded: ${chartBuild.noPublishedResultGroups.size} · Invalid excluded: ${chartBuild.invalidGroups.size}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (patternAnalyses.isEmpty()) {
                    Text("No valid published rows available for pattern analysis yet. Check the imported chart rows and source format.")
                } else {
                    patternAnalyses.forEach { analysis -> PatternResearchCard(analysis) }
                }
                Text("Market Intelligence · market-specific behaviour", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Each market is analysed independently. Recent-vs-older distribution changes are descriptive alerts, not future-result predictions.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (intelligenceReports.isEmpty()) {
                    Text("Market intelligence will appear after valid published chart rows are available.")
                } else {
                    intelligenceReports.forEach { MarketIntelligenceCard(it) }
                }
                Text("Next draw candidate research", style = MaterialTheme.typography.titleLarge)
                if (candidateSets.isEmpty()) {
                    Text("No usable published history yet. Candidates require at least 5 earlier valid rows; single-source results are exploratory and remain unverified.")
                } else {
                    candidateSets.forEach { (open, close, jodi) ->
                        CandidateResearchCard(open, close, jodi)
                    }
                }
                Text("Candidate walk-forward backtest · chronological", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Each historical target is ranked using only eligible earlier dates. Single-source records are included only in this separately labelled exploratory report.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (candidateWalkForwardReports.isEmpty()) {
                    Text("Candidate-specific backtest will appear when valid historical chart rows are available.")
                } else {
                    candidateWalkForwardReports.forEach { CandidateWalkForwardCard(it) }
                }
                Text("Historical coverage audit", style = MaterialTheme.typography.titleLarge)
                if (coverageReports.isEmpty()) {
                    Text("Coverage audit will appear after source data is imported.")
                } else {
                    coverageReports.forEach { coverage ->
                        CoverageReportCard(coverage) { start, end ->
                            val key = "${coverage.market.name}:${coverage.sessionId}"
                            coverageRangeOverrides = coverageRangeOverrides + (key to (start to end))
                            onSaveCoverageRange(key, start, end)
                        }
                    }
                }
                Text("Repeated source-record audit", style = MaterialTheme.typography.titleLarge)
                if (repeatedSourceRecords.isEmpty()) {
                    Text("No repeated source-record IDs found. Records without a source record ID are not guessed or grouped.")
                } else {
                    DuplicateObservationAuditCard(repeatedSourceRecords)
                }
                Text("Repeated result across dates · stale archive review", style = MaterialTheme.typography.titleLarge)
                if (repeatedPublishedResults.isEmpty()) {
                    Text("No identical published result repeated across 3 or more distinct dates by the same source.")
                } else {
                    RepeatedPublishedResultAuditCard(repeatedPublishedResults)
                }
                Text("Recent source observations", style = MaterialTheme.typography.titleLarge)
                if (groups.isEmpty()) {
                    Text("No source records loaded.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        groups.asReversed().take(30).forEach { group -> SourceGroupCard(group) }
                    }
                }
                Text("Offline SQLite · Source provenance preserved · Conflicts never auto-resolved", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}


@Composable
private fun CandidateWalkForwardCard(report: com.relab.core.CandidateWalkForwardReport) {
    fun rate(hits: Int): String =
        if (report.evaluatedDates == 0) "—" else "${"%.1f".format(hits * 100.0 / report.evaluatedDates)}%"

    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("${report.market} · ${report.sessionId} · ${report.side}", style = MaterialTheme.typography.titleMedium)
            Text(
                "${if (report.exploratory) "EXPLORATORY · SINGLE-SOURCE INCLUDED" else "CONSENSUS ONLY"}",
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                "Targets: ${report.totalTargetDates} · Evaluated: ${report.evaluatedDates} · Insufficient earlier history: ${report.insufficientHistoryDates}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Actual panna in ranked list · Top 1: ${rate(report.top1Hits)} · Top 3: ${rate(report.top3Hits)} · Top 5: ${rate(report.top5Hits)} · Top 10: ${rate(report.top10Hits)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Mean reciprocal rank: ${report.meanReciprocalRank?.let { "%.3f".format(it) } ?: "—"}",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Candidate-level exact hits when selected", style = MaterialTheme.typography.titleSmall)
            report.candidates.take(8).forEach { candidate ->
                Text(
                    "${candidate.panna} · ${candidate.exactHits}/${candidate.selectedCount} · ${"%.1f".format(candidate.hitRate * 100)}%" +
                        if (candidate.lowSample) " · LOW SAMPLE" else "",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(report.warning, color = if (report.evaluatedDates < 30) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Text(
                "Historical sample performance only—not future probability, advice, or a guarantee. Candidate exposure and exact hits are counted separately.",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun CoverageReportCard(report: CoverageReport, onRangeApply: (LocalDate, LocalDate) -> Unit) {
    var startText by remember(report.market, report.sessionId, report.startDate) { mutableStateOf(report.startDate.toString()) }
    var endText by remember(report.market, report.sessionId, report.endDate) { mutableStateOf(report.endDate.toString()) }
    var rangeError by remember(report.market, report.sessionId) { mutableStateOf<String?>(null) }
    Card {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text("${report.market} · ${report.sessionId}", style = MaterialTheme.typography.titleMedium)
            Text("Coverage date range (YYYY-MM-DD)", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = startText, onValueChange = { startText = it }, label = { Text("Start date") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = endText, onValueChange = { endText = it }, label = { Text("End date") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Button(onClick = {
                try {
                    val start = LocalDate.parse(startText.trim())
                    val end = LocalDate.parse(endText.trim())
                    require(!end.isBefore(start)) { "End date must be on or after start date" }
                    onRangeApply(start, end)
                    rangeError = null
                } catch (_: Exception) {
                    rangeError = "Enter valid dates in YYYY-MM-DD format; end date must not be earlier."
                }
            }) { Text("Apply date range") }
            rangeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text("Range: ${report.startDate} → ${report.endDate}")
            Text("Expected trading days: ${report.expectedTradingDays}")
            Text("Published: ${report.publishedDays} · Multi-source consensus: ${report.consensusDays} · Single-source unverified: ${report.singleSourceDays} · Conflicts: ${report.conflictDays}")
            Text("Pending: ${report.pendingDays} · Holidays: ${report.holidayDays}")
            Text("Source missing: ${report.sourceMissingDays} · Malformed only: ${report.malformedOnlyDays} · No observation: ${report.noObservationDays}")
            Text("Latest coverage records", style = MaterialTheme.typography.titleSmall)
            report.days.takeLast(10).asReversed().forEach { day ->
                val stateLabel = when (day.state) {
                    CoverageState.NO_OBSERVATION -> "NO OBSERVATION"
                    CoverageState.HOLIDAY_REPORTED -> "HOLIDAY REPORTED"
                    CoverageState.PENDING -> "PENDING"
                    CoverageState.SOURCE_MISSING -> "SOURCE MISSING"
                    CoverageState.MALFORMED_ONLY -> "MALFORMED ONLY"
                    CoverageState.SINGLE_SOURCE_UNVERIFIED -> "SINGLE SOURCE · UNVERIFIED"
                    CoverageState.CONSENSUS -> "MULTI-SOURCE CONSENSUS"
                    CoverageState.CONFLICT -> "CONFLICT"
                }
                val resultLabel = when {
                    day.distinctPublishedResults.size > 1 -> day.distinctPublishedResults.joinToString(" / ")
                    day.distinctPublishedResults.size == 1 -> day.distinctPublishedResults.single()
                    else -> "${day.observationCount} source observations"
                }
                Text("${day.date} · $stateLabel · $resultLabel", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Malformed-only dates are separate from missing observations. No observation is not assumed to be a holiday; source conflicts remain unresolved.",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}


@Composable
private fun DuplicateObservationAuditCard(groups: List<DuplicateObservationGroup>) {
    val changed = groups.count { it.state == DuplicateObservationState.SOURCE_RECORD_CHANGED }
    val unchanged = groups.size - changed
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Groups: ${groups.size} · Same content: $unchanged · Content changed: $changed")
            groups.asReversed().take(20).forEach { group ->
                val stateLabel = when (group.state) {
                    DuplicateObservationState.REPEATED_SAME_CONTENT -> "REPEATED · SAME CONTENT"
                    DuplicateObservationState.SOURCE_RECORD_CHANGED -> "SOURCE RECORD CHANGED"
                }
                val snapshots = group.observations.joinToString(" → ") { observation ->
                    "${observation.retrievedAt} [${observation.status}: ${observation.normalizedResult ?: observation.rawText}]"
                }
                Text("${group.market} · ${group.sessionId} · ${group.date} · ${group.sourceId} · ${group.sourceRecordId}", style = MaterialTheme.typography.titleSmall)
                Text("$stateLabel · ${group.observations.size} snapshots", style = MaterialTheme.typography.bodySmall)
                Text(snapshots, style = MaterialTheme.typography.bodySmall)
            }
            Text("Audit only: no source rows are deleted, merged, or silently corrected.", style = MaterialTheme.typography.labelSmall)
        }
    }
}


@Composable
private fun RepeatedPublishedResultAuditCard(groups: List<com.relab.core.RepeatedPublishedResultGroup>) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Review flags: ${groups.size}")
            groups.take(20).forEach { group ->
                val dates = group.observations.map { it.date }.distinct().joinToString()
                Text("${group.market} · ${group.sessionId} · ${group.sourceId}", style = MaterialTheme.typography.titleSmall)
                Text("${group.result} repeated on ${group.distinctDates} dates", style = MaterialTheme.typography.bodySmall)
                Text("Dates: $dates", style = MaterialTheme.typography.bodySmall)
            }
            Text("Review signal only—not proof of stale data. Original observations remain preserved.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PatternResearchCard(analysis: MarketAnalysis) {
    val openTop = analysis.openFamilySequences.take(3).joinToString { "${it.pattern} (${it.count})" }.ifBlank { "Not enough records" }
    val closeTop = analysis.closeFamilySequences.take(3).joinToString { "${it.pattern} (${it.count})" }.ifBlank { "Not enough records" }
    val openPairs = analysis.openDigitPairPositionCounts.mapIndexed { index, counts ->
        val top = counts.take(2).joinToString { "${it.pattern} (${it.count})" }
        "P${index + 1}: ${top.ifBlank { "—" }}"
    }.joinToString(" · ")
    val closePairs = analysis.closeDigitPairPositionCounts.mapIndexed { index, counts ->
        val top = counts.take(2).joinToString { "${it.pattern} (${it.count})" }
        "P${index + 1}: ${top.ifBlank { "—" }}"
    }.joinToString(" · ")
    Card {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text("${analysis.market} · ${analysis.sessionId}", style = MaterialTheme.typography.titleMedium)
            Text("Accepted: ${analysis.acceptedRecords} · Rejected by chart maths: ${analysis.rejectedRecords}")
            Text("Top open family sequences: $openTop", style = MaterialTheme.typography.bodySmall)
            Text("Top close family sequences: $closeTop", style = MaterialTheme.typography.bodySmall)
            Text("Open digit pairs: $openPairs", style = MaterialTheme.typography.bodySmall)
            Text("Close digit pairs: $closePairs", style = MaterialTheme.typography.bodySmall)
            Text(
                "SP/DP/TP open: ${analysis.openPannaTypeCounts} · close: ${analysis.closePannaTypeCounts}",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Repeat-gap samples · open: ${analysis.openRepeatGaps.sumOf { it.gapsInDays.size }} · close: ${analysis.closeRepeatGaps.sumOf { it.gapsInDays.size }}")
            Text("Walk-forward hit rate · Open family", style = MaterialTheme.typography.titleSmall)
            Text(
                "Global ${"%.1f".format(analysis.globalModeBaseline.hitRate * 100)}% · Weekday ${"%.1f".format(analysis.weekdayModeBaseline.hitRate * 100)}% · Previous ${"%.1f".format(analysis.previousStateBaseline.hitRate * 100)}%  (n=${analysis.globalModeBaseline.evaluated})",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Walk-forward hit rate · Close family", style = MaterialTheme.typography.titleSmall)
            Text(
                "Global ${"%.1f".format(analysis.closeGlobalModeBaseline.hitRate * 100)}% · Weekday ${"%.1f".format(analysis.closeWeekdayModeBaseline.hitRate * 100)}% · Previous ${"%.1f".format(analysis.closePreviousStateBaseline.hitRate * 100)}%  (n=${analysis.closeGlobalModeBaseline.evaluated})",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Descriptive history only; walk-forward scores are baselines, not predictions.", style = MaterialTheme.typography.labelSmall)
        }
    }
}


@Composable
private fun MarketIntelligenceCard(report: com.relab.core.MarketIntelligenceReport) {
    val regimeLabel = when (report.regime) {
        com.relab.core.MarketRegime.INSUFFICIENT_HISTORY -> "GREY · INSUFFICIENT HISTORY"
        com.relab.core.MarketRegime.STABLE -> "GREEN · BROADLY STABLE"
        com.relab.core.MarketRegime.WATCH -> "AMBER · WATCH FOR CHANGE"
        com.relab.core.MarketRegime.SHIFT_DETECTED -> "RED · DISTRIBUTION SHIFT"
    }
    val regimeColor = when (report.regime) {
        com.relab.core.MarketRegime.INSUFFICIENT_HISTORY -> MaterialTheme.colorScheme.onSurfaceVariant
        com.relab.core.MarketRegime.STABLE -> MaterialTheme.colorScheme.primary
        com.relab.core.MarketRegime.WATCH -> MaterialTheme.colorScheme.tertiary
        com.relab.core.MarketRegime.SHIFT_DETECTED -> MaterialTheme.colorScheme.error
    }
    fun transitionText(items: List<com.relab.core.TransitionEvidence>): String =
        items.take(3).joinToString(" · ") { "${it.transition} (${it.count}, ${"%.1f".format(it.percentage)}%)" }
            .ifBlank { "Not enough transitions" }
    fun weekdayText(items: Map<java.time.DayOfWeek, Int>): String =
        items.entries.sortedByDescending { it.value }.take(3)
            .joinToString(" · ") { "${it.key.name.lowercase().replaceFirstChar(Char::uppercase)}: ${it.value}" }
            .ifBlank { "No weekday evidence" }

    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("${report.market} · ${report.sessionId}", style = MaterialTheme.typography.titleMedium)
            Text("Records: ${report.validRecords} · ${regimeLabel}", color = regimeColor, style = MaterialTheme.typography.titleSmall)
            Text(report.explanation, style = MaterialTheme.typography.bodySmall)
            Text(
                "Recent-vs-prior distribution difference: ${report.distributionShift?.let { "${"%.1f".format(it * 100)}%" } ?: "Not evaluated"}" +
                    if (report.comparisonWindowSize > 0) " · Window: ${report.comparisonWindowSize} rows" else "",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Open transitions: ${transitionText(report.topOpenTransitions)}", style = MaterialTheme.typography.bodySmall)
            Text("Close transitions: ${transitionText(report.topCloseTransitions)}", style = MaterialTheme.typography.bodySmall)
            Text("Open weekday counts: ${weekdayText(report.openWeekdayCounts)}", style = MaterialTheme.typography.bodySmall)
            Text("Close weekday counts: ${weekdayText(report.closeWeekdayCounts)}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Family-sequence entropy · Open ${"%.2f".format(report.openFamilyEntropyBits)} bits · Close ${"%.2f".format(report.closeFamilyEntropyBits)} bits",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Shift flag compares two equal recent/older windows; it is a descriptive distribution test, not a prediction or proof of a market mechanism.",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun SourceGroupCard(group: com.relab.core.ReconciliationGroup) {
    val resultText = when (group.state) {
        ReconciliationState.CONSENSUS -> "MULTI-SOURCE CONSENSUS · ${group.agreedResult}"
        ReconciliationState.SINGLE_SOURCE_UNVERIFIED -> "SINGLE SOURCE · UNVERIFIED · " +
            group.observations.filter { it.status == ObservationStatus.PUBLISHED }
                .mapNotNull { it.normalizedResult }.distinct().joinToString()
        ReconciliationState.CONFLICT -> "CONFLICT · " + group.observations
            .filter { it.status == ObservationStatus.PUBLISHED }
            .mapNotNull { it.normalizedResult }.distinct().sorted().joinToString(" / ")
        ReconciliationState.NO_PUBLISHED_RESULT -> "NO PUBLISHED RESULT · ${group.observations.size} source observations"
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${group.market} · ${group.sessionId} · ${group.date}", style = MaterialTheme.typography.titleSmall)
            Text(resultText, style = MaterialTheme.typography.bodyMedium)
            Text(group.observations.joinToString { "${it.sourceId}: ${it.status}" }, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CandidateResearchCard(
    open: com.relab.core.CandidateRanking,
    close: com.relab.core.CandidateRanking,
    jodi: List<com.relab.core.JodiCandidate>
) {
    fun pct(count: Int, total: Int): String =
        if (total <= 0) "—" else "${"%.1f".format(count * 100.0 / total)}%"

    @Composable
    fun candidateRows(title: String, ranking: com.relab.core.CandidateRanking) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        ranking.candidates.take(5).forEachIndexed { index, candidate ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${index + 1}. ${candidate.panna} · ${candidate.pannaType}", style = MaterialTheme.typography.bodyMedium)
                Text("Exact ${pct(candidate.exactHistoricalCount, ranking.trainingRecords)}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Family ${pct(candidate.familyHistoricalCount, ranking.trainingRecords)} · weekday ${candidate.weekdayFamilyCount} · transition ${candidate.previousFamilyTransitionCount}",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }

    Card {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text("${open.market} · ${open.sessionId}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Earlier records: ${open.trainingRecords} · Target: ${open.targetDate}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Exploratory ranking may include unverified single-source rows; not consensus-only. No candidate-level walk-forward hit rate is claimed.",
                style = MaterialTheme.typography.labelSmall
            )
            if (open.abstained || close.abstained) {
                Text(open.note)
                Text(close.note)
            } else {
                candidateRows("Open Panna · historical frequency", open)
                candidateRows("Close Panna · historical frequency", close)
                Text("Derived Jodi · relative evidence rank", style = MaterialTheme.typography.titleSmall)
                jodi.take(5).forEachIndexed { index, candidate ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${index + 1}. ${candidate.jodi}", style = MaterialTheme.typography.bodyMedium)
                        Text("Index ${"%.1f".format(candidate.score)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    "Exact % = exact panna count ÷ earlier records. Family % uses digit-family matches. Jodi index is not a probability.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

private fun importChartLink(
    rawUrl: String,
    market: com.relab.core.Market,
    save: (List<com.relab.core.SourceObservation>) -> List<com.relab.core.SourceObservation>,
    updateObservations: (List<com.relab.core.SourceObservation>) -> Unit,
    updateMessage: (String) -> Unit,
    updateBusy: (Boolean) -> Unit
) {
    val cleanUrl = rawUrl.trim()
    val mainHandler = Handler(Looper.getMainLooper())
    updateBusy(true)
    updateMessage("Fetching chart page…")
    Thread {
        try {
            val uri = java.net.URI(cleanUrl)
            require(uri.scheme == "https" || uri.scheme == "http") { "Enter a valid HTTP or HTTPS link." }
            require(!uri.host.isNullOrBlank()) { "The link must include a website host." }
            val connection = URL(cleanUrl).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "RE-LAB-Android/0.1")
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            try {
                val code = connection.responseCode
                require(code in 200..299) { "Website returned HTTP $code." }
                val type = connection.contentType.orEmpty().lowercase()
                require(type.contains("text/html") || type.contains("application/xhtml+xml") || type.isBlank()) {
                    "This link did not return an HTML chart page ($type)."
                }
                val bytes = connection.inputStream.use { readBoundedBytes(it, 8_000_000L) }
                val html = bytes.toString(Charsets.UTF_8)
                val report = com.relab.core.HtmlChartImporter.import(
                    html, connection.url.toString(), market, "main", java.time.Instant.now()
                )
                mainHandler.post {
                    try {
                        if (report.observations.isEmpty()) {
                            val detail = report.issues.firstOrNull()
                                ?: "No dated chart-result rows found. This page may be JavaScript-rendered or use an unsupported layout."
                            updateMessage("No records imported: $detail Skipped rows: ${report.skippedRows}.")
                        } else {
                            val merged = save(report.observations)
                            updateObservations(merged)
                            updateMessage(
                                "${market.name}: fetched ${report.observations.size} rows; total saved observations ${merged.size}. " +
                                    "Malformed rows ${report.issues.size}; skipped rows ${report.skippedRows}. Source: ${uri.host}."
                            )
                        }
                    } catch (error: Exception) {
                        updateMessage("Save failed: ${error.message ?: "Unknown error"}")
                    } finally {
                        updateBusy(false)
                    }
                }
            } finally {
                connection.disconnect()
            }
        } catch (error: Exception) {
            mainHandler.post {
                updateMessage("Link import failed: ${error.message ?: "Unknown error"}")
                updateBusy(false)
            }
        }
    }.start()
}
