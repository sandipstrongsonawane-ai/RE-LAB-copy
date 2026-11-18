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
import androidx.compose.runtime.LaunchedEffect
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
import org.json.JSONArray
import org.json.JSONObject
import com.relab.core.MarketSource
import com.relab.core.UserMarketSourceRegistry

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

private fun loadMarketSources(preferences: android.content.SharedPreferences): List<MarketSource> {
    val saved = preferences.getString("market_sources_v1", null) ?: return com.relab.core.MarketSourceCatalog.sources
    return try {
        val array = JSONArray(saved)
        (0 until array.length()).mapNotNull { index ->
            val item = array.getJSONObject(index)
            runCatching {
                MarketSource(
                    market = com.relab.core.Market.valueOf(item.getString("market")),
                    sessionId = item.getString("session"),
                    sourceId = item.getString("publisher"),
                    url = item.getString("url"),
                    view = item.optString("view", "user-added"),
                    enabled = item.optBoolean("enabled", true),
                    note = item.optString("note", "")
                )
            }.getOrNull()
        }
    } catch (_: Exception) {
        com.relab.core.MarketSourceCatalog.sources
    }
}

private fun encodeMarketSources(sources: List<MarketSource>): String {
    val array = JSONArray()
    sources.forEach { source ->
        array.put(JSONObject().apply {
            put("market", source.market.name)
            put("session", source.sessionId)
            put("publisher", source.sourceId)
            put("url", source.url)
            put("view", source.view)
            put("enabled", source.enabled)
            put("note", source.note)
        })
    }
    return array.toString()
}

private fun loadSourceStatuses(preferences: android.content.SharedPreferences): Map<String, String> {
    val raw = preferences.getString("market_source_status_v1", null) ?: return emptyMap()
    return runCatching {
        val json = JSONObject(raw)
        json.keys().asSequence().associateWith { json.getString(it) }
    }.getOrDefault(emptyMap())
}

private fun encodeSourceStatuses(statuses: Map<String, String>): String =
    JSONObject().apply { statuses.forEach { (url, status) -> put(url, status) } }.toString()
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = LocalObservationDatabase(this)
        val preferences = getSharedPreferences("relab_local", MODE_PRIVATE)
        val initialMarketSources = loadMarketSources(preferences)
        val initialSourceStatuses = loadSourceStatuses(preferences)

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
                initialMarketSources = initialMarketSources,
                onSaveMarketSources = { sources -> preferences.edit().putString(KEY_MARKET_SOURCES, encodeMarketSources(sources)).apply() },
                initialSourceStatuses = initialSourceStatuses,
                onSaveSourceStatuses = { statuses -> preferences.edit().putString(KEY_SOURCE_STATUSES, encodeSourceStatuses(statuses)).apply() },
                initialCoverageRanges = initialCoverageRanges,
                onSaveCoverageRange = { key, start, end ->
                    preferences.edit().putString(COVERAGE_RANGE_PREFIX + key, "$start|$end").apply()
                },
                initialCsv = database.loadOriginalCsv(),
                initialObservations = database.loadObservations(),
                initialPredictionLedger = database.loadPredictionLedger(),
                onSaveLockedPrediction = { database.appendLockedPrediction(it) },
                onSavePredictionVerification = { database.appendPredictionVerification(it) },
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
        private const val KEY_MARKET_SOURCES = "market_sources_v1"
        private const val KEY_SOURCE_STATUSES = "market_source_status_v1"
    }
}

@Composable
private fun RelabHome(
    initialMarketSources: List<MarketSource>,
    onSaveMarketSources: (List<MarketSource>) -> Unit,
    initialSourceStatuses: Map<String, String>,
    onSaveSourceStatuses: (Map<String, String>) -> Unit,
    initialCsv: String?,
    initialObservations: List<com.relab.core.SourceObservation>,
    initialPredictionLedger: com.relab.core.PredictionLearningLedger,
    onSaveLockedPrediction: (com.relab.core.LockedSidePrediction) -> Unit,
    onSavePredictionVerification: (com.relab.core.PredictionVerification) -> Unit,
    initialCoverageRanges: Map<String, Pair<LocalDate, LocalDate>>,
    onSaveCoverageRange: (String, LocalDate, LocalDate) -> Unit,
    onSaveCsv: (String, List<com.relab.core.SourceObservation>) -> List<com.relab.core.SourceObservation>,
    onSaveLinkObservations: (List<com.relab.core.SourceObservation>) -> List<com.relab.core.SourceObservation>,
    onClearCsv: () -> Unit
) {
    val context = LocalContext.current
    var rawCsv by remember { mutableStateOf(initialCsv) }
    var observations by remember { mutableStateOf(initialObservations) }
    var predictionLedger by remember { mutableStateOf(initialPredictionLedger) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var chartUrl by remember { mutableStateOf("") }
    var marketSources by remember { mutableStateOf(initialMarketSources) }
    var sourceStatuses by remember { mutableStateOf(initialSourceStatuses) }
    var sourceUrlInput by remember { mutableStateOf("") }
    var sourcePublisherInput by remember { mutableStateOf("") }
    var sourceMarketInput by remember { mutableStateOf("") }
    var sourceSessionInput by remember { mutableStateOf("") }
    var editingSourceUrl by remember { mutableStateOf<String?>(null) }
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

    LaunchedEffect(observations, predictionLedger) {
        var currentLedger = predictionLedger
        currentLedger.lockedPredictions.forEach { prediction ->
            val needsRecheck = runCatching {
                com.relab.core.PredictionLearningCycle.needsReverification(
                    currentLedger, prediction.id, observations
                )
            }.getOrElse { error ->
                importMessage = "Prediction recheck pending: ${error.message ?: "evidence check failed"}"
                false
            }
            if (needsRecheck) {
                runCatching {
                    val (updated, verification) = com.relab.core.PredictionLearningCycle.verify(
                        currentLedger, prediction.id, observations, java.time.Instant.now()
                    )
                    onSavePredictionVerification(verification)
                    currentLedger = updated
                }.onFailure { error ->
                    importMessage = "Prediction verification pending: ${error.message ?: "storage/reconciliation error"}"
                }
            }
        }
        if (currentLedger != predictionLedger) predictionLedger = currentLedger
    }

    var coverageRangeOverrides by remember { mutableStateOf(initialCoverageRanges) }
    var dashboardAnalytics by remember { mutableStateOf<DashboardAnalytics?>(null) }
    var analyticsBusy by remember { mutableStateOf(false) }
    LaunchedEffect(observations, predictionLedger, coverageRangeOverrides, marketSources) {
        val snapshot = observations
        val ranges = coverageRangeOverrides
        dashboardAnalytics = null
        analyticsBusy = true
        try {
            dashboardAnalytics = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                DashboardAnalytics.compute(snapshot, ranges, marketSources, predictionLedger)
            }
        } catch (error: Exception) {
            importMessage = "Research analysis failed: ${error.message ?: "Unknown error"}"
        } finally {
            analyticsBusy = false
        }
    }

    val report = remember(rawCsv) { rawCsv?.let { SourceCsvImporter.import(it) } }
    val groups = dashboardAnalytics?.groups.orEmpty()
    val conflicts = groups.count { it.state == ReconciliationState.CONFLICT }
    val repeatedSourceRecords = dashboardAnalytics?.repeatedSourceRecords.orEmpty()
    val repeatedPublishedResults = dashboardAnalytics?.repeatedPublishedResults.orEmpty()
    val published = observations.count { it.status == ObservationStatus.PUBLISHED }
    val pending = observations.count { it.status == ObservationStatus.PENDING }
    val malformed = observations.count { it.status == ObservationStatus.MALFORMED }
    val chartBuild = dashboardAnalytics?.chartBuild ?: com.relab.core.ChartBuildReport(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    // Exploratory records include valid single-publisher rows, but remain visibly
    // unverified; the consensus ledger and reconciliation counts stay unchanged.
    val researchRecords = dashboardAnalytics?.researchRecords.orEmpty()
    val patternAnalyses = dashboardAnalytics?.patternAnalyses.orEmpty()
    val intelligenceReports = dashboardAnalytics?.intelligenceReports.orEmpty()
    val candidateSets = dashboardAnalytics?.candidateSets.orEmpty()
    val selfLearningReports = dashboardAnalytics?.selfLearningReports.orEmpty()
    val candidateWalkForwardReports = dashboardAnalytics?.candidateWalkForwardReports.orEmpty()
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

    val coverageReports = dashboardAnalytics?.coverageReports.orEmpty()
    val publisherCoverageReports = dashboardAnalytics?.publisherCoverageReports.orEmpty()

    val sourceTiming = dashboardAnalytics?.sourceTiming.orEmpty()
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
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Self-learning prediction ledger", style = MaterialTheme.typography.titleMedium)
                        Text("Locked: ${predictionLedger.lockedPredictions.size} · Verification attempts: ${predictionLedger.verifications.size}")
                        Text("GREEN/RED only count after independent publisher consensus. GREY checks remain provisional and can be rechecked when newer source evidence arrives.", style = MaterialTheme.typography.bodySmall)
                        predictionLedger.lockedPredictions.groupBy { Triple(it.market, it.sessionId, it.side) }.forEach { (key, _) ->
                            val performance = predictionLedger.performance(key.first, key.second, key.third)
                            Text("${key.first} · ${key.second} · ${key.third}: GREEN ${performance.green} / RED ${performance.red} / GREY ${performance.grey} · verified ${performance.verified}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Recent locked predictions · latest verification", style = MaterialTheme.typography.titleSmall)
                        predictionLedger.lockedPredictions
                            .sortedWith(compareByDescending<com.relab.core.LockedSidePrediction> { it.targetDate }.thenByDescending { it.lockedAt })
                            .take(20)
                            .forEach { prediction ->
                                val latest = predictionLedger.verifications
                                    .filter { it.predictionId == prediction.id }
                                    .maxByOrNull { it.attempt }
                                val gradeLabel = when (latest?.grade) {
                                    com.relab.core.PredictionGrade.GREEN -> "GREEN · HIT"
                                    com.relab.core.PredictionGrade.RED -> "RED · MISS"
                                    com.relab.core.PredictionGrade.GREY -> "GREY · UNVERIFIED"
                                    null -> "PENDING · AWAITING RESULT"
                                }
                                val actualLabel = latest?.actualPanna?.let { " · actual $it" } ?: ""
                                Text(
                                    "${prediction.targetDate} · ${prediction.market}/${prediction.sessionId} · ${prediction.side} · $gradeLabel$actualLabel",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Locked candidates: ${prediction.candidates.joinToString(", ")}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        Button(
                            enabled = observations.isNotEmpty(),
                            onClick = {
                                val target = LocalDate.now().plusDays(1)
                                var updated = predictionLedger
                                var lockedCount = 0
                                val groups = observations.map { it.market to it.sessionId }.distinct()
                                groups.forEach { (market, session) ->
                                    listOf(com.relab.core.PannaSide.OPEN, com.relab.core.PannaSide.CLOSE).forEach { side ->
                                        runCatching {
                                            val (next, prediction) = com.relab.core.PredictionLearningCycle.lock(
                                                observations, market, session, target, side, java.time.Instant.now(), updated
                                            )
                                            onSaveLockedPrediction(prediction)
                                            updated = next
                                            lockedCount++
                                        }
                                    }
                                }
                                predictionLedger = updated
                                importMessage = if (lockedCount > 0) {
                                    "Locked $lockedCount next-day research candidates before outcome publication."
                                } else {
                                    "No new prediction locked: history may be insufficient, target results may already exist, or predictions are already locked."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Lock next-day research candidates") }
                    }
                }
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
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Unlimited chart source manager", style = MaterialTheme.typography.titleMedium)
                        Text("Har saved URL ka apna source history rahega. Same publisher ke multiple pages ek hi evidence identity use karenge.")
                        OutlinedTextField(sourceUrlInput, { sourceUrlInput = it }, label = { Text("Chart URL (HTTPS)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(sourcePublisherInput, { sourcePublisherInput = it }, label = { Text("Publisher identity (same publisher ke pages par same naam)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(sourceMarketInput, { sourceMarketInput = it.uppercase() }, label = { Text("Market (auto-detect if known; otherwise enter market)") }, placeholder = { Text("SRIDEVI / SRIDEVI_NIGHT / KALYAN / TIME_BAZAR / MAIN_BAZAR") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(sourceSessionInput, { sourceSessionInput = it }, label = { Text("Session ID (required if URL is not recognized)") }, placeholder = { Text("main / night / evening") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Button(
                            enabled = sourceUrlInput.isNotBlank() && !linkBusy,
                            onClick = {
                                try {
                                    val uri = java.net.URI(sourceUrlInput.trim())
                                    val publisher = sourcePublisherInput.trim().ifBlank { uri.host.orEmpty().removePrefix("www.") }
                                        .lowercase().replace(Regex("[^a-z0-9.-]+"), "-").trim('-')
                                    val detectedMarket = com.relab.core.Market.fromSourceUrl(sourceUrlInput.trim())
                                    val marketName = sourceMarketInput.trim().ifBlank {
                                        detectedMarket?.name ?: error("Market could not be auto-detected. Select the market explicitly.")
                                    }
                                    val market = com.relab.core.Market.valueOf(marketName.uppercase())
                                    val detectedSession = if (detectedMarket == com.relab.core.Market.SRIDEVI_NIGHT) "night" else "main"
                                    val session = sourceSessionInput.trim().ifBlank {
                                        if (detectedMarket != null) detectedSession else error("Session is ambiguous. Enter a session ID.")
                                    }
                                    val source = MarketSource(market, session, publisher, sourceUrlInput.trim(), "user-added")
                                    marketSources = if (editingSourceUrl == null) {
                                        UserMarketSourceRegistry.add(marketSources, source)
                                    } else {
                                        UserMarketSourceRegistry.update(marketSources, editingSourceUrl!!, source)
                                    }
                                    onSaveMarketSources(marketSources)
                                    editingSourceUrl = null
                                    sourceUrlInput = ""
                                    sourcePublisherInput = ""
                                    sourceMarketInput = ""
                                    sourceSessionInput = ""
                                    importMessage = "Source saved. Parser support will be checked on refresh; unsupported pages remain marked, no chart will be fabricated."
                                } catch (error: Exception) {
                                    importMessage = "Source not added: ${error.message ?: "Check URL, publisher, market and session."}"
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (editingSourceUrl == null) "Add chart source" else "Save source changes") }
                        if (editingSourceUrl != null) {
                            OutlinedButton(onClick = {
                                editingSourceUrl = null
                                sourceUrlInput = ""
                                sourcePublisherInput = ""
                                sourceMarketInput = ""
                                sourceSessionInput = ""
                            }) { Text("Cancel edit") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !linkBusy && marketSources.any { it.enabled },
                                onClick = {
                                    refreshRegisteredSources(marketSources, onSaveLinkObservations, { observations = it }, { importMessage = it }, { linkBusy = it }, { statuses -> sourceStatuses = sourceStatuses + statuses; onSaveSourceStatuses(sourceStatuses) })
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text(if (linkBusy) "Refreshing…" else "Refresh all sources") }
                        }
                        Text("Registered sources: ${marketSources.size} · Enabled: ${marketSources.count { it.enabled }}")
                        marketSources.forEach { source ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("${source.sourceId} · ${source.market.name}/${source.sessionId}", style = MaterialTheme.typography.titleSmall)
                                Text(source.url, style = MaterialTheme.typography.bodySmall)
                                sourceStatuses[source.url]?.let { Text("Last refresh: $it", style = MaterialTheme.typography.bodySmall) }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick = {
                                        editingSourceUrl = source.url
                                        sourceUrlInput = source.url
                                        sourcePublisherInput = source.sourceId
                                        sourceMarketInput = source.market.name
                                        sourceSessionInput = source.sessionId
                                    }) { Text("Edit") }
                                    OutlinedButton(onClick = {
                                        marketSources = UserMarketSourceRegistry.setEnabled(marketSources, source.url, !source.enabled)
                                        onSaveMarketSources(marketSources)
                                    }) { Text(if (source.enabled) "Disable" else "Enable") }
                                    OutlinedButton(onClick = {
                                        marketSources = UserMarketSourceRegistry.remove(marketSources, source.url)
                                        sourceStatuses = sourceStatuses - source.url
                                        onSaveSourceStatuses(sourceStatuses)
                                        onSaveMarketSources(marketSources)
                                    }) { Text("Remove") }
                                    OutlinedButton(enabled = source.enabled && !linkBusy, onClick = {
                                        refreshRegisteredSources(listOf(source), onSaveLinkObservations, { observations = it }, { importMessage = it }, { linkBusy = it }, { statuses -> sourceStatuses = sourceStatuses + statuses; onSaveSourceStatuses(sourceStatuses) })
                                    }) { Text("Refresh") }
                                }
                            }
                        }
                    }
                }
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
                if (analyticsBusy) Text("Updating historical analytics in background…", style = MaterialTheme.typography.bodySmall)
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
                Text("Self-learning reverse engineering · discovered rules", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Rules are mined separately per market/session from multi-source consensus records only. Support and baseline lift are historical evidence, not guaranteed prediction accuracy.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (selfLearningReports.isEmpty()) {
                    Text("Self-learning hypotheses will appear when enough valid historical rows are available.")
                } else {
                    selfLearningReports.forEach { report ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "${report.market} · ${report.sessionId} · ${report.side} · ${report.evaluatedDates} evaluated dates",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                if (report.hypotheses.isEmpty()) {
                                    Text("No rule met minimum historical support (${report.minimumSupport}).")
                                } else {
                                    report.hypotheses.take(5).forEach { hypothesis ->
                                        Text(
                                            "${hypothesis.condition} → ${hypothesis.hitRate.times(100).let { "%.1f".format(it) }}% " +
                                                "(${hypothesis.correct}/${hypothesis.evaluated}) · baseline " +
                                                "${hypothesis.baselineHitRate.times(100).let { "%.1f".format(it) }}% · " +
                                                "lift ${hypothesis.liftOverBaseline.times(100).let { "%+.1f".format(it) }} pp",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
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
                    "Consensus-only and single-source-inclusive exploratory backtests are shown separately. Every target is ranked using only eligible earlier dates.",
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
                Text("Per-URL publisher coverage", style = MaterialTheme.typography.titleLarge)
                if (publisherCoverageReports.isEmpty()) {
                    Text("Add a chart URL to start tracking date-wise coverage for each publisher URL.")
                } else {
                    publisherCoverageReports.forEach { coverage ->
                        PublisherCoverageReportCard(coverage, sourceStatuses)
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
                Text("Publisher-specific chart history", style = MaterialTheme.typography.titleLarge)
                // A separate chart/history is rendered for every URL. Publisher identity
                // remains shared across that publisher's URLs for evidence counting.
                val sourceChartGroups = observations.groupBy {
                    Triple(it.sourceId, it.sourceUrl.orEmpty(), it.market to it.sessionId)
                }.toList().sortedWith(compareBy({ it.first.third.first.name }, { it.first.third.second }, { it.first.first }, { it.first.second }))
                if (sourceChartGroups.isEmpty()) {
                    Text("Each registered URL's own chart history will appear here after supported rows are fetched.")
                } else {
                    sourceChartGroups.forEach { (identity, rows) ->
                        Card {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("${identity.first} · ${identity.third.first.name}/${identity.third.second}", style = MaterialTheme.typography.titleSmall)
                                Text("URL: ${identity.second}", style = MaterialTheme.typography.bodySmall)
                                Text("${rows.size} rows · ${rows.map { it.date }.distinct().size} distinct dates · latest ${rows.maxOf { it.date }}", style = MaterialTheme.typography.bodySmall)
                                rows.sortedByDescending { it.date }.take(12).forEach { row ->
                                    Text("${row.date}: ${row.normalizedResult ?: row.rawText} · ${row.status}", style = MaterialTheme.typography.bodySmall)
                                }
                                if (rows.size > 12) Text("Showing latest 12 of ${rows.size} rows.", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
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
    fun rate(metrics: com.relab.core.CandidateWalkForwardMetrics, hits: Int): String =
        if (metrics.evaluatedDates == 0) "—"
        else "${"%.1f".format(hits * 100.0 / metrics.evaluatedDates)}%"

    fun metricLine(label: String, metrics: com.relab.core.CandidateWalkForwardMetrics): String =
        "$label · evaluated ${metrics.evaluatedDates} · Top-1 ${rate(metrics, metrics.top1Hits)} · " +
            "Top-3 ${rate(metrics, metrics.top3Hits)} · Top-5 ${rate(metrics, metrics.top5Hits)} · " +
            "Top-10 ${rate(metrics, metrics.top10Hits)} · MRR ${metrics.meanReciprocalRank?.let { "%.3f".format(it) } ?: "—"}"

    val feedbackValues = report.feedbackAvailability.map { it.verifiedPredictionsAvailable }
    val feedbackSummary = if (feedbackValues.isEmpty()) {
        "Prior verified feedback: no target dates"
    } else {
        "Prior verified feedback per target: min ${feedbackValues.minOrNull()} · max ${feedbackValues.maxOrNull()} · " +
            "targets with feedback ${feedbackValues.count { it > 0 }}/${feedbackValues.size}"
    }

    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("${report.market} · ${report.sessionId} · ${report.side}", style = MaterialTheme.typography.titleMedium)
            Text(
                if (report.exploratory) "EXPLORATORY · SINGLE-SOURCE INCLUDED" else "CONSENSUS ONLY",
                style = MaterialTheme.typography.labelSmall
            )
            Text("Targets: ${report.totalTargetDates} · Insufficient earlier history: ${report.insufficientHistoryDates}")
            Text(metricLine("BASELINE", report.baselineMetrics), style = MaterialTheme.typography.bodySmall)
            Text(metricLine("LEARNING ENABLED", report.learningEnabledMetrics), style = MaterialTheme.typography.bodySmall)
            Text(feedbackSummary, style = MaterialTheme.typography.bodySmall)
            Text(
                "Feedback is eligible only when GREEN/RED verification was recorded before the target date. Same-day, future and GREY outcomes are excluded.",
                style = MaterialTheme.typography.labelSmall
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
private fun PublisherCoverageReportCard(
    report: com.relab.core.PublisherCoverageReport,
    sourceStatuses: Map<String, String>
) {
    val byUrl = report.days.groupBy { it.sourceId to it.sourceUrl }
    Card {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text("${report.market} · ${report.sessionId}", style = MaterialTheme.typography.titleMedium)
            Text("Range: ${report.startDate} → ${report.endDate} · Trading days: ${report.expectedTradingDays}")
            Text("Registered URLs: ${report.registeredUrlCount} · Expected URL/date entries: ${report.expectedUrlDateEntries}")
            Text("Published URL/date entries: ${report.publishedEntries} · No row: ${report.noRowEntries} · Malformed: ${report.malformedEntries}")
            byUrl.forEach { (identity, days) ->
                val published = days.count {
                    it.state == com.relab.core.PublisherDateState.PUBLISHED ||
                        it.state == com.relab.core.PublisherDateState.MULTIPLE_PUBLISHED_VALUES
                }
                val gaps = days.filter { it.state == com.relab.core.PublisherDateState.NO_ROW }
                val label = when {
                    gaps.isEmpty() -> "No unfilled dates"
                    else -> "No row on ${gaps.size} trading dates"
                }
                Text("${identity.first} · ${published}/${days.size} dates published", style = MaterialTheme.typography.titleSmall)
                Text(identity.second, style = MaterialTheme.typography.bodySmall)
                Text(
                    sourceStatuses[identity.second]?.let { "Last refresh: $it" } ?: "Last refresh: not attempted yet",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(label, style = MaterialTheme.typography.bodySmall)
                days.filter { it.state != com.relab.core.PublisherDateState.PUBLISHED }
                    .takeLast(6).asReversed().forEach { day ->
                        val state = when (day.state) {
                            com.relab.core.PublisherDateState.PUBLISHED -> "PUBLISHED"
                            com.relab.core.PublisherDateState.MULTIPLE_PUBLISHED_VALUES -> "CONFLICTING ROWS"
                            com.relab.core.PublisherDateState.PENDING -> "PENDING"
                            com.relab.core.PublisherDateState.HOLIDAY_REPORTED -> "HOLIDAY"
                            com.relab.core.PublisherDateState.SOURCE_MISSING_REPORTED -> "SOURCE MARKED MISSING"
                            com.relab.core.PublisherDateState.MALFORMED -> "MALFORMED"
                            com.relab.core.PublisherDateState.NO_ROW -> "NO ROW"
                        }
                        Text("${day.date} · $state", style = MaterialTheme.typography.labelSmall)
                    }
            }
            Text(
                "Coverage is tracked per exact registered URL. NO ROW means no matching record was imported; it is not automatically a holiday or proof the publisher omitted the result.",
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
                // Persist on the worker thread: SQLite writes and dataset merging must
                // never block the Compose/UI thread after a successful fetch.
                val merged = if (report.observations.isNotEmpty()) save(report.observations) else emptyList()
                mainHandler.post {
                    try {
                        if (report.observations.isEmpty()) {
                            val detail = report.issues.firstOrNull()
                                ?: "No dated chart-result rows found. This page may be JavaScript-rendered or use an unsupported layout."
                            updateMessage("No records imported: $detail Skipped rows: ${report.skippedRows}.")
                        } else {
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

private fun refreshRegisteredSources(
    sources: List<MarketSource>,
    save: (List<com.relab.core.SourceObservation>) -> List<com.relab.core.SourceObservation>,
    updateObservations: (List<com.relab.core.SourceObservation>) -> Unit,
    updateMessage: (String) -> Unit,
    updateBusy: (Boolean) -> Unit,
    updateStatuses: (Map<String, String>) -> Unit
) {
    val enabledSources = sources.filter { it.enabled }
    val mainHandler = Handler(Looper.getMainLooper())
    if (enabledSources.isEmpty()) {
        updateMessage("No enabled chart sources to refresh.")
        return
    }
    updateBusy(true)
    updateMessage("Refreshing ${enabledSources.size} chart sources independently…")
    Thread {
        try {
            val report = com.relab.core.MarketSourceRefreshEngine.refresh(
                sources = enabledSources,
                fetch = { source ->
                    val connection = URL(source.url).openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = true
                    connection.connectTimeout = 15000
                    connection.readTimeout = 20000
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", "RE-LAB-Android/0.1")
                    connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                    try {
                        val code = connection.responseCode
                        require(code in 200..299) { "HTTP $code" }
                        val contentType = connection.contentType.orEmpty().lowercase()
                        require(contentType.contains("text/html") || contentType.contains("application/xhtml+xml") || contentType.isBlank()) {
                            "Unsupported content type: $contentType"
                        }
                        val bytes = connection.inputStream.use { readBoundedBytes(it, 8_000_000L) }
                        com.relab.core.FetchedChartPage(bytes.toString(Charsets.UTF_8), connection.url.toString())
                    } finally {
                        connection.disconnect()
                    }
                },
                retrievedAt = { java.time.Instant.now() }
            )
            // Merge and persist fetched observations off the UI thread.
            val merged = if (report.observations.isNotEmpty()) save(report.observations) else null
            mainHandler.post {
                try {
                    val extracted = report.observations
                    if (merged != null) updateObservations(merged)
                    val checkedAt = java.time.Instant.now().toString()
                    updateStatuses(report.outcomes.associate { outcome ->
                        val state = if (outcome.errorMessage == null) {
                            "OK · ${outcome.observations.size} rows · $checkedAt"
                        } else {
                            "FAILED · ${outcome.errorMessage} · $checkedAt"
                        }
                        outcome.source.url to state
                    })
                    val detail = report.outcomes.joinToString(" | ") { outcome ->
                        when {
                            outcome.errorMessage != null -> "${outcome.source.sourceId}: UNSUPPORTED/FAILED (${outcome.errorMessage})"
                            outcome.observations.isEmpty() -> "${outcome.source.sourceId}: no rows parsed; skipped ${outcome.skippedRows}; parser support required"
                            else -> "${outcome.source.sourceId}: ${outcome.observations.size} rows; issues ${outcome.issues.size}; skipped ${outcome.skippedRows}"
                        }
                    }
                    updateMessage("${report.successfulSources}/${report.outcomes.size} source requests completed; ${extracted.size} observations extracted. $detail")
                } catch (error: Exception) {
                    updateMessage("Refresh save failed: ${error.message ?: "Unknown error"}")
                } finally {
                    updateBusy(false)
                }
            }
        } catch (error: Exception) {
            mainHandler.post {
                updateMessage("Refresh failed: ${error.message ?: "Unknown error"}")
                updateBusy(false)
            }
        }
    }.start()
}
