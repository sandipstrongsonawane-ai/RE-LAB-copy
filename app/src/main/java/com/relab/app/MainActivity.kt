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
private fun uiText(language: String, hinglish: String, hindi: String, marathi: String): String =
    when (language.lowercase()) {
        "hindi" -> hindi
        "marathi" -> marathi
        else -> hinglish
    }

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
                onRestoreResearchBundle = { csv, importedObservations, ledger ->
                    database.appendResearchBundle(csv, importedObservations, ledger)
                },
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
    onRestoreResearchBundle: (String, List<com.relab.core.SourceObservation>, com.relab.core.PredictionLearningLedger) -> Pair<List<com.relab.core.SourceObservation>, com.relab.core.PredictionLearningLedger>,
    initialCoverageRanges: Map<String, Pair<LocalDate, LocalDate>>,
    onSaveCoverageRange: (String, LocalDate, LocalDate) -> Unit,
    onSaveCsv: (String, List<com.relab.core.SourceObservation>) -> List<com.relab.core.SourceObservation>,
    onSaveLinkObservations: (List<com.relab.core.SourceObservation>) -> List<com.relab.core.SourceObservation>,
    onClearCsv: () -> Unit
) {
    val context = LocalContext.current
    val languagePreferences = remember { context.getSharedPreferences("relab_local", android.content.Context.MODE_PRIVATE) }
    var uiLanguage by remember { mutableStateOf(languagePreferences.getString("ui_language", "hinglish") ?: "hinglish") }
    var activeSection by remember { mutableStateOf("home") }
    var showLedgerDetails by remember { mutableStateOf(false) }
    var showDatasetDetails by remember { mutableStateOf(false) }
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
                    observations, chartBuild.records, patternAnalyses, coverageReports, java.time.Instant.now(), predictionLedger
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
                    val ledgerCsv = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                        var content: String? = null
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (entry.name == "prediction-learning-ledger.csv") {
                                content = readBoundedBytes(zip, MAX_BUNDLE_ARCHIVE_BYTES).toString(Charsets.UTF_8)
                                break
                            }
                            zip.closeEntry()
                        }
                        content
                    } ?: error("Verified bundle has no prediction-learning-ledger.csv")
                    val importedLedger = com.relab.core.PredictionLedgerCsvCodec.import(ledgerCsv)
                    if (parsed.observations.isEmpty() && importedLedger.lockedPredictions.isEmpty()) {
                        importMessage = "Bundle verified, but it contains no valid source observations or locked predictions."
                    } else {
                        val previousCount = observations.size
                        val (merged, restoredLedger) = onRestoreResearchBundle(
                            sourceCsv, parsed.observations, importedLedger
                        )
                        val addedCount = (merged.size - previousCount).coerceAtLeast(0)
                        observations = merged
                        predictionLedger = restoredLedger
                        rawCsv = sourceCsv
                        importMessage = "Verified bundle restored atomically: ${validation.verifiedFiles} artifact checksums passed; added ${addedCount} observations and restored ${importedLedger.lockedPredictions.size} locked predictions / ${importedLedger.verifications.size} verification attempts. Existing history was retained. Row issues: ${parsed.issues.size}."
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
                Text(
                    uiText(uiLanguage, "Offline chart research", "ऑफलाइन चार्ट रिसर्च", "ऑफलाइन चार्ट संशोधन"),
                    style = MaterialTheme.typography.titleMedium
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "home" to uiText(uiLanguage, "Home", "होम", "मुख्य"),
                        "sources" to uiText(uiLanguage, "Sources", "सोर्स", "स्रोत"),
                        "backup" to uiText(uiLanguage, "Backup", "बैकअप", "बॅकअप")
                    ).forEach { (key, label) ->
                        if (activeSection == key) Button(onClick = { activeSection = key }, modifier = Modifier.weight(1f)) { Text(label) }
                        else OutlinedButton(onClick = { activeSection = key }, modifier = Modifier.weight(1f)) { Text(label) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "research" to uiText(uiLanguage, "Research", "रिसर्च", "संशोधन"),
                        "audit" to uiText(uiLanguage, "Audit", "ऑडिट", "तपासणी"),
                        "settings" to uiText(uiLanguage, "Settings", "सेटिंग", "सेटिंग")
                    ).forEach { (key, label) ->
                        if (activeSection == key) Button(onClick = { activeSection = key }, modifier = Modifier.weight(1f)) { Text(label) }
                        else OutlinedButton(onClick = { activeSection = key }, modifier = Modifier.weight(1f)) { Text(label) }
                    }
                }
                importMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (activeSection == "settings") {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(uiText(uiLanguage, "App language", "ऐप की भाषा", "अॅपची भाषा"), style = MaterialTheme.typography.titleMedium)
                            Text(uiText(uiLanguage, "Choose the language for the main navigation and common controls.", "मुख्य मेन्यू और जरूरी बटन की भाषा चुनें।", "मुख्य मेन्यू आणि सामान्य बटणांची भाषा निवडा।"), style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("hinglish" to "Hinglish", "hindi" to "हिंदी", "marathi" to "मराठी").forEach { (code, label) ->
                                    if (uiLanguage == code) Button(onClick = { uiLanguage = code; languagePreferences.edit().putString("ui_language", code).apply() }, modifier = Modifier.weight(1f)) { Text(label) }
                                    else OutlinedButton(onClick = { uiLanguage = code; languagePreferences.edit().putString("ui_language", code).apply() }, modifier = Modifier.weight(1f)) { Text(label) }
                                }
                            }
                            Text(uiText(uiLanguage, "Technical names, imported source text and detailed audit evidence remain in their original form.", "तकनीकी नाम, आयात किए गए सोर्स टेक्स्ट और विस्तृत ऑडिट रिकॉर्ड मूल रूप में रहेंगे।", "तांत्रिक नावे, आयात केलेला स्रोत मजकूर आणि तपशीलवार ऑडिट पुरावे मूळ स्वरूपात राहतील।"), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (activeSection == "home") {
                Text(uiText(uiLanguage, "Quick start", "त्वरित शुरुआत", "जलद सुरुवात"), style = MaterialTheme.typography.titleMedium)
                Text(uiText(uiLanguage, "5 markets · Offline chart research", "5 मार्केट · ऑफलाइन चार्ट रिसर्च", "5 मार्केट · ऑफलाइन चार्ट संशोधन"), style = MaterialTheme.typography.bodySmall)
                Text(uiText(uiLanguage, "Paste a supported chart URL. RE-LAB detects the market from its source path.", "सपोर्टेड चार्ट URL डालें। RE-LAB सोर्स पाथ से मार्केट पहचानता है।", "समर्थित चार्ट URL पेस्ट करा. RE-LAB सोर्स पाथवरून मार्केट ओळखते."))
                Text(uiText(uiLanguage, "Aaj ka market research · Day / Night", "आज का मार्केट रिसर्च · डे / नाइट", "आजचे मार्केट संशोधन · डे / नाइट"), style = MaterialTheme.typography.titleLarge)
                Text(uiText(uiLanguage, "Har market ka latest result, next research candidates aur unka evidence yahin dikhega. Score chance % nahi hai; sirf historical pattern index hai.", "हर मार्केट का ताज़ा रिजल्ट, अगले रिसर्च कैंडिडेट और उनका आधार यहां दिखेगा। स्कोर जीतने की संभावना नहीं, सिर्फ पुराने पैटर्न का इंडेक्स है।", "प्रत्येक मार्केटचा ताजा निकाल, पुढील संशोधन उमेदवार आणि त्यांचा आधार येथे दिसेल. स्कोअर जिंकण्याची शक्यता नाही; तो फक्त ऐतिहासिक पॅटर्न इंडेक्स आहे."), style = MaterialTheme.typography.bodySmall)
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("ROW CROSS-CHECK · DATA QUALITY", style = MaterialTheme.typography.titleMedium)
                        Text("Rows checked: ${groups.size} market-date groups · Consensus: ${groups.count { it.state == ReconciliationState.CONSENSUS }} · Single-source: ${groups.count { it.state == ReconciliationState.SINGLE_SOURCE_UNVERIFIED }}", style = MaterialTheme.typography.bodySmall)
                        Text("Conflicts: ${conflicts} · No published result: ${groups.count { it.state == ReconciliationState.NO_PUBLISHED_RESULT }} · Invalid chart rows: ${chartBuild.invalidGroups.size}", style = MaterialTheme.typography.bodySmall)
                        Text("Repeated source-record IDs: ${repeatedSourceRecords.size} · Same result across dates review flags: ${repeatedPublishedResults.size}", style = MaterialTheme.typography.bodySmall)
                        Text("Conflict rows are never silently merged. Single-source rows stay unverified; original source rows remain preserved.", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (candidateSets.isEmpty()) {
                    Text(uiText(uiLanguage, "Abhi market sources register nahi hue. Sources tab check karein.", "अभी मार्केट सोर्स रजिस्टर नहीं हुए। Sources टैब देखें।", "अद्याप मार्केट स्रोत नोंदलेले नाहीत. Sources टॅब तपासा."))
                } else {
                    candidateSets.forEach { (open, close, jodi) ->
                        val market = open.market
                        val marketKind = when {
                            market.name.contains("NIGHT") || open.sessionId.equals("night", true) -> "NIGHT MARKET"
                            market.name.contains("DAY") || market.name.contains("MORNING") -> "DAY / MORNING MARKET"
                            else -> "DAY / MAIN MARKET"
                        }
                        val latest = researchRecords.filter { it.market == market && it.sessionId == open.sessionId }.maxByOrNull { it.date }
                        val latestStatus = latest?.let { row ->
                            groups.firstOrNull { it.market == market && it.sessionId == open.sessionId && it.date == row.date }?.state?.name
                                ?: "UNRECONCILED"
                        } ?: "NO ROW"
                        val pattern = patternAnalyses.firstOrNull { it.market == market && it.sessionId == open.sessionId }
                        val intelligence = intelligenceReports.firstOrNull { it.market == market && it.sessionId == open.sessionId }
                        val openDigits = open.candidates.groupBy { it.panna.sumOf(Char::digitToInt) % 10 }
                            .map { (digit, rows) -> digit to rows.maxOf { it.score } }
                            .sortedByDescending { it.second }.take(3)
                        val openDp = open.candidates.filter { it.pannaType == com.relab.core.PannaType.DP }.take(3)
                        val openTp = open.candidates.filter { it.pannaType == com.relab.core.PannaType.TP }.take(3)
                        val closeDp = close.candidates.filter { it.pannaType == com.relab.core.PannaType.DP }.take(3)
                        val closeTp = close.candidates.filter { it.pannaType == com.relab.core.PannaType.TP }.take(3)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("${market.name.replace('_', ' ')}  ·  $marketKind", style = MaterialTheme.typography.titleMedium)
                                Text("Session: ${open.sessionId}  ·  Target: ${open.targetDate}", style = MaterialTheme.typography.labelSmall)
                                Text(
                                    latest?.let { "Latest available result (${it.date}): ${it.openPanna} - ${it.jodi} - ${it.closePanna}" }
                                        ?: "Latest result: abhi verified / imported row available nahi hai",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (open.abstained || close.abstained) {
                                    Text("NEXT RESEARCH: Abhi candidate nahi — ${open.note}", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Text("OPEN PANNA research: ${open.candidates.take(3).joinToString("  ·  ") { "${it.panna} (index ${"%.1f".format(it.score)})" }}", style = MaterialTheme.typography.bodyMedium)
                                    Text("CLOSE PANNA research: ${close.candidates.take(3).joinToString("  ·  ") { "${it.panna} (index ${"%.1f".format(it.score)})" }}", style = MaterialTheme.typography.bodyMedium)
                                    Text("OPEN DIGIT research: ${openDigits.joinToString("  ·  ") { "${it.first} (index ${"%.1f".format(it.second)})" }.ifBlank { "Not enough evidence" }}", style = MaterialTheme.typography.bodyMedium)
                                    Text("OPEN DP: ${openDp.joinToString("  ·  ") { it.panna }.ifBlank { "No supported DP candidate" }}  |  OPEN TP: ${openTp.joinToString("  ·  ") { it.panna }.ifBlank { "No supported TP candidate" }}", style = MaterialTheme.typography.bodySmall)
                                    Text("CLOSE DP: ${closeDp.joinToString("  ·  ") { it.panna }.ifBlank { "No supported DP candidate" }}  |  CLOSE TP: ${closeTp.joinToString("  ·  ") { it.panna }.ifBlank { "No supported TP candidate" }}", style = MaterialTheme.typography.bodySmall)
                                    Text("JODI research: ${jodi.take(5).joinToString("  ·  ") { "${it.jodi} (index ${"%.1f".format(it.score)})" }.ifBlank { "Not enough paired evidence" }}", style = MaterialTheme.typography.bodyMedium)
                                }
                                Text(
                                    pattern?.let { "Pattern cross-check: ${it.acceptedRecords} usable rows · Open family ${it.openFamilySequences.take(2).joinToString { p -> "${p.pattern} (${p.count})" }.ifBlank { "not enough history" }} · Close family ${it.closeFamilySequences.take(2).joinToString { p -> "${p.pattern} (${p.count})" }.ifBlank { "not enough history" }}" }
                                        ?: "Pattern cross-check: history abhi insufficient",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    intelligence?.let { "Pattern shift: ${it.regime} · ${it.explanation}" } ?: "Pattern shift: more historical rows needed",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text("Latest row check: ${latestStatus} · Candidate index probability nahi hai; result guarantee nahi.", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(uiText(uiLanguage, "Self-learning prediction ledger", "सेल्फ-लर्निंग प्रेडिक्शन रिकॉर्ड", "सेल्फ-लर्निंग प्रेडिक्शन नोंद"), style = MaterialTheme.typography.titleMedium)
                        Text(uiText(uiLanguage, "Locked: ${predictionLedger.lockedPredictions.size} · Verification attempts: ${predictionLedger.verifications.size}", "लॉक: ${predictionLedger.lockedPredictions.size} · वेरिफिकेशन प्रयास: ${predictionLedger.verifications.size}", "लॉक: ${predictionLedger.lockedPredictions.size} · पडताळणी प्रयत्न: ${predictionLedger.verifications.size}"))
                        Text(uiText(uiLanguage, "GREEN/RED count only after independent publisher consensus. GREY stays provisional until newer source evidence arrives.", "GREEN/RED की गिनती स्वतंत्र पब्लिशर की सहमति के बाद ही होती है। नया सोर्स डेटा आने तक GREY अस्थायी रहता है।", "स्वतंत्र प्रकाशकांच्या सहमतीनंतरच GREEN/RED मोजले जातात. नवीन स्रोत डेटा येईपर्यंत GREY तात्पुरते राहते."), style = MaterialTheme.typography.bodySmall)
                        if (showLedgerDetails) {
                        predictionLedger.lockedPredictions.groupBy { Triple(it.market, it.sessionId, it.side) }.forEach { (key, _) ->
                            val performance = predictionLedger.performance(key.first, key.second, key.third)
                            Text("${key.first} · ${key.second} · ${key.third}: GREEN ${performance.green} / RED ${performance.red} / GREY ${performance.grey} · verified ${performance.verified}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(uiText(uiLanguage, "Recent locked predictions · latest verification", "हाल के लॉक किए गए रिसर्च · ताज़ा सत्यापन", "अलीकडील लॉक केलेले संशोधन · नवीन पडताळणी"), style = MaterialTheme.typography.titleSmall)
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
                        }
                        OutlinedButton(onClick = { showLedgerDetails = !showLedgerDetails }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (showLedgerDetails) uiText(uiLanguage, "Hide ledger details", "विवरण छिपाएं", "तपशील लपवा") else uiText(uiLanguage, "Show ledger details", "विवरण देखें", "तपशील पहा"))
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
                        ) { Text(uiText(uiLanguage, "Lock next-day research candidates", "अगले दिन के रिसर्च कैंडिडेट लॉक करें", "पुढील दिवसाचे संशोधन उमेदवार लॉक करा")) }
                    }
                }
                OutlinedTextField(
                    value = chartUrl,
                    onValueChange = { chartUrl = it },
                    label = { Text(uiText(uiLanguage, "Paste chart website link", "चार्ट वेबसाइट लिंक डालें", "चार्ट वेबसाइट लिंक पेस्ट करा")) },
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
                ) { Text(if (linkBusy) uiText(uiLanguage, "Fetching...", "फेच हो रहा है...", "फेच होत आहे...") else uiText(uiLanguage, "Auto-detect market & fetch", "मार्केट पहचानें और फेच करें", "मार्केट ओळखा आणि फेच करा")) }
                }
                if (activeSection == "sources") {
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(uiText(uiLanguage, "Chart source manager", "चार्ट सोर्स मैनेजर", "चार्ट स्रोत व्यवस्थापक"), style = MaterialTheme.typography.titleMedium)
                        Text(uiText(uiLanguage, "Each saved URL keeps its own history. Multiple pages from one publisher share one evidence identity.", "हर सेव URL का अपना इतिहास रहेगा। एक पब्लिशर के कई पेज एक ही सोर्स पहचान साझा करेंगे।", "प्रत्येक सेव केलेल्या URL चा स्वतंत्र इतिहास राहील. एका प्रकाशकाची अनेक पाने एकच स्रोत ओळख वापरतील."))
                        OutlinedTextField(sourceUrlInput, { sourceUrlInput = it }, label = { Text(uiText(uiLanguage, "Chart URL (HTTPS)", "चार्ट URL (HTTPS)", "चार्ट URL (HTTPS)")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(sourcePublisherInput, { sourcePublisherInput = it }, label = { Text(uiText(uiLanguage, "Publisher identity (same name across its pages)", "पब्लिशर की पहचान (उसके सभी पेज पर एक ही नाम)", "प्रकाशक ओळख (त्याच्या सर्व पेजवर एकच नाव)")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(sourceMarketInput, { sourceMarketInput = it.uppercase() }, label = { Text(uiText(uiLanguage, "Market (auto-detect if known; otherwise enter market)", "मार्केट (पहचान न हो तो नाम डालें)", "मार्केट (ओळखले नाही तर नाव भरा)")) }, placeholder = { Text("SRIDEVI / SRIDEVI_NIGHT / KALYAN / TIME_BAZAR / MAIN_BAZAR") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(sourceSessionInput, { sourceSessionInput = it }, label = { Text(uiText(uiLanguage, "Session ID (required if URL is not recognized)", "सेशन ID (URL पहचान न हो तो जरूरी)", "सेशन ID (URL ओळखले नाही तर आवश्यक)")) }, placeholder = { Text("main / night / evening") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
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
                                    val market = com.relab.core.Market.valueOf(marketName)
                                    val detectedSession = if (detectedMarket?.name?.endsWith("_NIGHT") == true) "night" else "main"
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
                        ) { Text(if (editingSourceUrl == null) uiText(uiLanguage, "Add chart source", "चार्ट सोर्स जोड़ें", "चार्ट स्रोत जोडा") else uiText(uiLanguage, "Save source changes", "सोर्स बदलाव सेव करें", "स्रोत बदल जतन करा")) }
                        if (editingSourceUrl != null) {
                            OutlinedButton(onClick = {
                                editingSourceUrl = null
                                sourceUrlInput = ""
                                sourcePublisherInput = ""
                                sourceMarketInput = ""
                                sourceSessionInput = ""
                            }) { Text(uiText(uiLanguage, "Cancel edit", "एडिट रद्द करें", "बदल रद्द करा")) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !linkBusy && marketSources.any { it.enabled },
                                onClick = {
                                    refreshRegisteredSources(marketSources, onSaveLinkObservations, { observations = it }, { importMessage = it }, { linkBusy = it }, { statuses -> sourceStatuses = sourceStatuses + statuses; onSaveSourceStatuses(sourceStatuses) })
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text(if (linkBusy) uiText(uiLanguage, "Refreshing…", "रिफ्रेश हो रहा है…", "रिफ्रेश होत आहे…") else uiText(uiLanguage, "Refresh all sources", "सभी सोर्स रिफ्रेश करें", "सर्व स्रोत रिफ्रेश करा")) }
                            OutlinedButton(
                                enabled = !linkBusy,
                                onClick = {
                                    discoverMatkaFlashMarketSources(
                                        marketSources, onSaveMarketSources,
                                        { marketSources = it }, { importMessage = it }, { linkBusy = it }
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text(uiText(uiLanguage, "Discover online markets", "ऑनलाइन मार्केट खोजें", "ऑनलाइन मार्केट शोधा")) }
                        }
                        val discoveredDisabled = marketSources.count {
                            it.sourceId == "matkaflash" && !it.enabled && it.note.contains("Discovered from MatkaFlash")
                        }
                        if (discoveredDisabled > 0) {
                            OutlinedButton(
                                enabled = !linkBusy,
                                onClick = {
                                    val enabledCount = discoveredDisabled
                                    marketSources = marketSources.map {
                                        if (it.sourceId == "matkaflash" && !it.enabled && it.note.contains("Discovered from MatkaFlash")) it.copy(enabled = true) else it
                                    }
                                    onSaveMarketSources(marketSources)
                                    importMessage = "Enabled $enabledCount discovered MatkaFlash markets. Refresh when ready; all pages from this publisher remain one evidence source."
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(uiText(uiLanguage, "Enable discovered MatkaFlash sources ($discoveredDisabled)", "खोजे गए MatkaFlash सोर्स चालू करें ($discoveredDisabled)", "शोधलेले MatkaFlash स्रोत सुरू करा ($discoveredDisabled)")) }
                        }
                        Text(uiText(uiLanguage, "Registered sources: ${marketSources.size} · Enabled: ${marketSources.count { it.enabled }}", "कुल सोर्स: ${marketSources.size} · चालू: ${marketSources.count { it.enabled }}", "नोंदणीकृत स्रोत: ${marketSources.size} · सुरू: ${marketSources.count { it.enabled }}"))
                        marketSources.forEach { source ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("${source.sourceId} · ${source.market.name}/${source.sessionId}", style = MaterialTheme.typography.titleSmall)
                                Text(source.url, style = MaterialTheme.typography.bodySmall)
                                sourceStatuses[source.url]?.let { Text(uiText(uiLanguage, "Last refresh: $it", "आखिरी रिफ्रेश: $it", "शेवटचे रिफ्रेश: $it"), style = MaterialTheme.typography.bodySmall) }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick = {
                                        editingSourceUrl = source.url
                                        sourceUrlInput = source.url
                                        sourcePublisherInput = source.sourceId
                                        sourceMarketInput = source.market.name
                                        sourceSessionInput = source.sessionId
                                    }) { Text(uiText(uiLanguage, "Edit", "बदलें", "बदला")) }
                                    OutlinedButton(onClick = {
                                        marketSources = UserMarketSourceRegistry.setEnabled(marketSources, source.url, !source.enabled)
                                        onSaveMarketSources(marketSources)
                                    }) { Text(if (source.enabled) uiText(uiLanguage, "Disable", "बंद करें", "बंद करा") else uiText(uiLanguage, "Enable", "चालू करें", "सुरू करा")) }
                                    OutlinedButton(onClick = {
                                        marketSources = UserMarketSourceRegistry.remove(marketSources, source.url)
                                        sourceStatuses = sourceStatuses - source.url
                                        onSaveSourceStatuses(sourceStatuses)
                                        onSaveMarketSources(marketSources)
                                    }) { Text(uiText(uiLanguage, "Remove", "हटाएं", "काढा")) }
                                    OutlinedButton(enabled = source.enabled && !linkBusy, onClick = {
                                        refreshRegisteredSources(listOf(source), onSaveLinkObservations, { observations = it }, { importMessage = it }, { linkBusy = it }, { statuses -> sourceStatuses = sourceStatuses + statuses; onSaveSourceStatuses(sourceStatuses) })
                                    }) { Text(uiText(uiLanguage, "Refresh", "रिफ्रेश", "रिफ्रेश")) }
                                }
                            }
                        }
                    }
                }
                }
                if (activeSection == "backup") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { picker.launch(arrayOf("text/*", "text/csv", "application/csv")) }) {
                            Text(uiText(uiLanguage, "Import CSV", "CSV इम्पोर्ट", "CSV आयात"))
                        }
                        OutlinedButton(
                            onClick = { bundleValidationPicker.launch(arrayOf("application/zip", "application/octet-stream")) }
                        ) { Text(uiText(uiLanguage, "Import verified bundle", "वेरिफाइड बंडल इम्पोर्ट", "पडताळलेला बंडल आयात")) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { bundleExportPicker.launch("relab-complete-research-bundle.zip") },
                            enabled = observations.isNotEmpty()
                        ) { Text(uiText(uiLanguage, "Export complete bundle", "पूरा बंडल एक्सपोर्ट", "पूर्ण बंडल एक्सपोर्ट")) }
                        OutlinedButton(
                            onClick = { exportPicker.launch("relab-source-backup.csv") },
                            enabled = observations.isNotEmpty()
                        ) { Text(uiText(uiLanguage, "Export backup", "बैकअप एक्सपोर्ट", "बॅकअप एक्सपोर्ट")) }
                    }
                    OutlinedButton(onClick = {
                        rawCsv = null
                        observations = emptyList()
                        onClearCsv()
                        importMessage = "Local database cleared."
                    }, enabled = observations.isNotEmpty()) { Text(uiText(uiLanguage, "Clear local dataset", "लोकल डेटा हटाएं", "स्थानिक डेटा साफ करा")) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { researchExportPicker.launch("relab-derived-consensus-research.csv") },
                        enabled = chartBuild.records.isNotEmpty()
                    ) { Text(uiText(uiLanguage, "Export research CSV", "रिसर्च CSV एक्सपोर्ट", "संशोधन CSV एक्सपोर्ट")) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { walkForwardExportPicker.launch("relab-walk-forward-audit.csv") },
                        enabled = patternAnalyses.any { it.walkForwardRows.isNotEmpty() }
                    ) { Text(uiText(uiLanguage, "Export walk-forward CSV", "वॉक-फॉरवर्ड CSV एक्सपोर्ट", "वॉक-फॉरवर्ड CSV एक्सपोर्ट")) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { manifestExportPicker.launch("relab-research-manifest.json") },
                        enabled = observations.isNotEmpty()
                    ) { Text(uiText(uiLanguage, "Export research manifest", "रिसर्च मेनिफेस्ट एक्सपोर्ट", "संशोधन मॅनिफेस्ट एक्सपोर्ट")) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { coverageExportPicker.launch("relab-historical-coverage-audit.csv") },
                        enabled = coverageReports.isNotEmpty()
                    ) { Text(uiText(uiLanguage, "Export coverage audit CSV", "कवरेज ऑडिट CSV एक्सपोर्ट", "कव्हरेज ऑडिट CSV एक्सपोर्ट")) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { timingExportPicker.launch("relab-source-publication-timing.csv") },
                        enabled = sourceTiming.isNotEmpty()
                    ) { Text(uiText(uiLanguage, "Export source timing CSV", "सोर्स टाइमिंग CSV एक्सपोर्ट", "स्रोत वेळ CSV एक्सपोर्ट")) }
                }
                }
                if (activeSection == "home") {
                Card(colors = CardDefaults.cardColors()) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(uiText(uiLanguage, "Local dataset", "लोकल डेटा", "स्थानिक डेटा"), style = MaterialTheme.typography.titleLarge)
                        Text(uiText(uiLanguage, "Records: ${observations.size}", "कुल रिकॉर्ड: ${observations.size}", "एकूण नोंदी: ${observations.size}"))
                        Text(uiText(uiLanguage, "Published: $published · Pending: $pending", "प्रकाशित: $published · बाकी: $pending", "प्रकाशित: $published · प्रलंबित: $pending"))
                        if (showDatasetDetails) {
                            Text(uiText(uiLanguage, "Malformed rows: $malformed · Conflicting dates: $conflicts", "गलत पंक्तियां: $malformed · विरोधी तारीखें: $conflicts", "अवैध ओळी: $malformed · विसंगत तारखा: $conflicts"))
                            Text(uiText(uiLanguage, "Reconciled date groups: ${groups.size}", "मिलाई गई तारीखें: ${groups.size}", "जुळवलेले तारीख गट: ${groups.size}"))
                            if (report == null) {
                                Text(uiText(uiLanguage, "No CSV imported yet. Open Backup to import a source CSV.", "अभी CSV इम्पोर्ट नहीं हुई। सोर्स CSV के लिए Backup खोलें।", "अजून CSV आयात केलेली नाही. स्रोत CSV साठी Backup उघडा."))
                                Text(
                                    "Required columns: market, session_id, date, source_id, retrieved_at, raw_text, status. " +
                                        "For a published result, include the result column too.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (report != null && report.issues.isNotEmpty()) {
                                Text(uiText(uiLanguage, "Import row issues: ${report.issues.size}", "इम्पोर्ट पंक्ति समस्याएं: ${report.issues.size}", "आयात ओळीतील समस्या: ${report.issues.size}"))
                                report.issues.take(5).forEach { issue ->
                                    Text("Row ${issue.rowNumber}: ${issue.message}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        OutlinedButton(onClick = { showDatasetDetails = !showDatasetDetails }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (showDatasetDetails) uiText(uiLanguage, "Hide data details", "डेटा विवरण छिपाएं", "डेटा तपशील लपवा") else uiText(uiLanguage, "Show data details", "डेटा विवरण देखें", "डेटा तपशील पहा"))
                        }
                    }
                }
                if (analyticsBusy) Text(uiText(uiLanguage, "Updating historical analytics in background…", "पुराने डेटा का विश्लेषण चल रहा है…", "ऐतिहासिक डेटाचे विश्लेषण सुरू आहे…"), style = MaterialTheme.typography.bodySmall)
                }
                if (activeSection == "home") {
                    Text("Market-wise next research · historical pattern shares", style = MaterialTheme.typography.titleLarge)
                    Text("Percentages are observed historical frequencies—not fixed odds or guaranteed predictions. Source gaps and stale data reduce confidence.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val marketInsightKeys = (researchRecords.map { it.market to it.sessionId } + marketSources.filter { it.enabled }.map { it.market to it.sessionId }).distinct().sortedWith(compareBy({ it.first.name }, { it.second }))
                    if (marketInsightKeys.isEmpty()) Text("No market history yet. Refresh registered internet sources to populate this dashboard.")
                    else marketInsightKeys.forEach { (market, sessionId) ->
                        val rows = researchRecords.filter { it.market == market && it.sessionId == sessionId }
                        val candidate = candidateSets.firstOrNull { it.first.market == market && it.first.sessionId == sessionId }
                        MarketNextInsightCard(market, sessionId, rows, candidate?.first, candidate?.second, candidate?.third.orEmpty(),
                            observations.filter { it.market == market && it.sessionId == sessionId }.maxOfOrNull { it.retrievedAt },
                            rows.maxOfOrNull { it.date })
                    }
                }
                if (activeSection == "research") {
                Text(uiText(uiLanguage, "Pattern research · exploratory + verified", "पैटर्न रिसर्च · खोज + सत्यापन", "पॅटर्न संशोधन · शोध + पडताळणी"), style = MaterialTheme.typography.titleLarge)
                Text(
                    uiText(uiLanguage, "Analysis input: ", "विश्लेषण इनपुट: ", "विश्लेषण इनपुट: ") + "${researchRecords.size} valid historical rows (${chartBuild.records.size} consensus + ${chartBuild.singleSourceUnverifiedGroups.size} single-source groups) · Conflicts excluded: ${chartBuild.conflictGroups.size} · Pending/missing excluded: ${chartBuild.noPublishedResultGroups.size} · Invalid excluded: ${chartBuild.invalidGroups.size}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (patternAnalyses.isEmpty()) {
                    Text(uiText(uiLanguage, "No valid published rows available for pattern analysis yet. Check the imported chart rows and source format.", "पैटर्न विश्लेषण के लिए अभी वैध प्रकाशित रिकॉर्ड नहीं हैं। इम्पोर्ट किए गए चार्ट रिकॉर्ड और सोर्स फॉर्मेट जांचें।", "पॅटर्न विश्लेषणासाठी अद्याप वैध प्रकाशित नोंदी नाहीत. आयात केलेल्या चार्ट नोंदी आणि स्रोत फॉरमॅट तपासा."))
                } else {
                    patternAnalyses.forEach { analysis -> PatternResearchCard(analysis, uiLanguage) }
                }
                Text(uiText(uiLanguage, "Market Intelligence · market-specific behaviour", "मार्केट इंटेलिजेंस · मार्केट अनुसार व्यवहार", "मार्केट इंटेलिजन्स · मार्केटनुसार वर्तन"), style = MaterialTheme.typography.titleLarge)
                Text(
                    uiText(uiLanguage, "Each market is analysed independently. Recent-vs-older distribution changes are descriptive alerts, not future-result predictions.", "हर मार्केट का अलग विश्लेषण होता है। हाल के और पुराने डेटा में बदलाव केवल जानकारी है, भविष्य के परिणाम की भविष्यवाणी नहीं।", "प्रत्येक मार्केटचे स्वतंत्र विश्लेषण होते. अलीकडील आणि जुन्या डेटामधील बदल ही केवळ माहिती आहे; भविष्यातील निकालाची भविष्यवाणी नाही."),
                    style = MaterialTheme.typography.bodySmall
                )
                if (intelligenceReports.isEmpty()) {
                    Text(uiText(uiLanguage, "Market intelligence will appear after valid published chart rows are available.", "वैध प्रकाशित चार्ट रिकॉर्ड आने पर मार्केट इंटेलिजेंस दिखेगा।", "वैध प्रकाशित चार्ट नोंदी उपलब्ध झाल्यावर मार्केट इंटेलिजन्स दिसेल."))
                } else {
                    intelligenceReports.forEach { MarketIntelligenceCard(it) }
                }
                Text(uiText(uiLanguage, "Self-learning reverse engineering · discovered rules", "सेल्फ-लर्निंग रिवर्स इंजीनियरिंग · मिले हुए नियम", "सेल्फ-लर्निंग रिव्हर्स इंजिनिअरिंग · सापडलेले नियम"), style = MaterialTheme.typography.titleLarge)
                Text(
                    uiText(uiLanguage, "Rules are mined separately per market/session from multi-source consensus records only. Support and baseline lift are historical evidence, not guaranteed prediction accuracy.", "नियम हर मार्केट/सेशन के लिए केवल कई स्वतंत्र सोर्स की सहमति वाले रिकॉर्ड से बनते हैं। सपोर्ट और बेसलाइन सुधार ऐतिहासिक सबूत हैं, गारंटीड सटीकता नहीं।", "नियम प्रत्येक मार्केट/सेशनसाठी अनेक स्वतंत्र स्रोतांच्या सहमतीच्या नोंदींवरून तयार होतात. सपोर्ट आणि बेसलाइनमधील वाढ हा ऐतिहासिक पुरावा आहे; हमखास अचूकता नाही."),
                    style = MaterialTheme.typography.bodySmall
                )
                if (selfLearningReports.isEmpty()) {
                    Text(uiText(uiLanguage, "Self-learning hypotheses will appear when enough valid historical rows are available.", "पर्याप्त वैध पुराने रिकॉर्ड मिलने पर सेल्फ-लर्निंग नियम दिखेंगे।", "पुरेशा वैध ऐतिहासिक नोंदी उपलब्ध झाल्यावर सेल्फ-लर्निंग नियम दिसतील."))
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
                                    Text(uiText(uiLanguage, "No rule met minimum historical support (${report.minimumSupport}).", "कोई नियम न्यूनतम पुराने डेटा सपोर्ट (${report.minimumSupport}) तक नहीं पहुंचा।", "कोणताही नियम किमान ऐतिहासिक डेटा सपोर्ट (${report.minimumSupport}) पर्यंत पोहोचला नाही."))
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
                Text(uiText(uiLanguage, "Next draw candidate research", "अगले रिजल्ट के कैंडिडेट", "पुढील निकालाचे उमेदवार"), style = MaterialTheme.typography.titleLarge)
                if (candidateSets.isEmpty()) {
                    Text(uiText(uiLanguage, "No usable published history yet. Candidates require at least 5 earlier valid rows; single-source results are exploratory and remain unverified.", "अभी उपयोगी प्रकाशित इतिहास नहीं है। कैंडिडेट के लिए कम से कम 5 पुराने वैध रिकॉर्ड चाहिए; एकल-स्रोत परिणाम खोजात्मक और अप्रमाणित रहते हैं।", "अद्याप वापरण्यायोग्य प्रकाशित इतिहास नाही. उमेदवारांसाठी किमान 5 आधीच्या वैध नोंदी आवश्यक आहेत; एक-स्रोत निकाल शोधात्मक व अपडताळलेले राहतात."))
                } else {
                    candidateSets.forEach { (open, close, jodi) ->
                        CandidateResearchCard(open, close, jodi)
                    }
                }
                Text(uiText(uiLanguage, "Candidate walk-forward backtest · chronological", "कैंडिडेट वॉक-फॉरवर्ड बैकटेस्ट · तारीख क्रम", "कॅन्डिडेट वॉक-फॉरवर्ड बॅकटेस्ट · कालक्रमानुसार"), style = MaterialTheme.typography.titleLarge)
                Text(
                    uiText(uiLanguage, "Consensus-only and single-source-inclusive exploratory backtests are shown separately. Every target is ranked using only eligible earlier dates.", "केवल सहमति वाले और एकल-स्रोत शामिल करने वाले खोजात्मक बैकटेस्ट अलग दिखते हैं। हर टारगेट की रैंकिंग केवल उससे पहले की योग्य तारीखों से होती है।", "फक्त सहमतीच्या आणि एक-स्रोत समाविष्ट केलेल्या शोधात्मक बॅकटेस्टचे निकाल वेगळे दाखवले जातात. प्रत्येक लक्ष्याचे रँकिंग फक्त त्याआधीच्या पात्र तारखांवर होते."),
                    style = MaterialTheme.typography.bodySmall
                )
                if (candidateWalkForwardReports.isEmpty()) {
                    Text(uiText(uiLanguage, "Candidate-specific backtest will appear when valid historical chart rows are available.", "वैध पुराने चार्ट रिकॉर्ड उपलब्ध होने पर कैंडिडेट बैकटेस्ट दिखेगा।", "वैध ऐतिहासिक चार्ट नोंदी उपलब्ध झाल्यावर उमेदवार बॅकटेस्ट दिसेल."))
                } else {
                    candidateWalkForwardReports.forEach { CandidateWalkForwardCard(it) }
                }
                }
                if (activeSection == "audit") {
                Text(uiText(uiLanguage, "Historical coverage audit", "पुराने डेटा की कवरेज जांच", "ऐतिहासिक डेटा कव्हरेज तपासणी"), style = MaterialTheme.typography.titleLarge)
                if (coverageReports.isEmpty()) {
                    Text(uiText(uiLanguage, "Coverage audit will appear after source data is imported.", "सोर्स डेटा इम्पोर्ट होने पर कवरेज ऑडिट दिखेगा।", "स्रोत डेटा आयात केल्यानंतर कव्हरेज तपासणी दिसेल."))
                } else {
                    coverageReports.forEach { coverage ->
                        CoverageReportCard(coverage, uiLanguage) { start, end ->
                            val key = "${coverage.market.name}:${coverage.sessionId}"
                            coverageRangeOverrides = coverageRangeOverrides + (key to (start to end))
                            onSaveCoverageRange(key, start, end)
                        }
                    }
                }
                Text(uiText(uiLanguage, "Per-URL publisher coverage", "हर URL की पब्लिशर कवरेज", "प्रत्येक URL ची प्रकाशक कव्हरेज"), style = MaterialTheme.typography.titleLarge)
                if (publisherCoverageReports.isEmpty()) {
                    Text(uiText(uiLanguage, "Add a chart URL to start tracking date-wise coverage for each publisher URL.", "हर पब्लिशर URL की तारीख के अनुसार कवरेज देखने के लिए चार्ट URL जोड़ें।", "प्रत्येक प्रकाशक URL चे तारखेनुसार कव्हरेज पाहण्यासाठी चार्ट URL जोडा."))
                } else {
                    publisherCoverageReports.forEach { coverage ->
                        PublisherCoverageReportCard(coverage, sourceStatuses)
                    }
                }
                Text(uiText(uiLanguage, "Repeated source-record audit", "दोहराए गए सोर्स रिकॉर्ड की जांच", "पुनरावृत्ती झालेल्या स्रोत नोंदींची तपासणी"), style = MaterialTheme.typography.titleLarge)
                if (repeatedSourceRecords.isEmpty()) {
                    Text(uiText(uiLanguage, "No repeated source-record IDs found. Records without a source record ID are not guessed or grouped.", "दोहराए गए सोर्स रिकॉर्ड ID नहीं मिले। जिन रिकॉर्ड में ID नहीं है, उनका अनुमान लगाकर समूह नहीं बनाया जाता।", "पुनरावृत्ती झालेले स्रोत-नोंद ID आढळले नाहीत. ID नसलेल्या नोंदींचा अंदाज लावून गट केला जात नाही."))
                } else {
                    DuplicateObservationAuditCard(repeatedSourceRecords, uiLanguage)
                }
                Text(uiText(uiLanguage, "Repeated result across dates · stale archive review", "अलग-अलग तारीखों पर दोहराया परिणाम · पुराने आर्काइव की जांच", "वेगवेगळ्या तारखांवरील पुनरावृत्ती निकाल · जुन्या संग्रहाची तपासणी"), style = MaterialTheme.typography.titleLarge)
                if (repeatedPublishedResults.isEmpty()) {
                    Text(uiText(uiLanguage, "No identical published result repeated across 3 or more distinct dates by the same source.", "एक ही सोर्स से 3 या अधिक अलग तारीखों पर समान प्रकाशित परिणाम नहीं मिला।", "एकाच स्रोताकडून 3 किंवा अधिक वेगवेगळ्या तारखांना समान प्रकाशित निकाल आढळला नाही."))
                } else {
                    RepeatedPublishedResultAuditCard(repeatedPublishedResults, uiLanguage)
                }
                Text(uiText(uiLanguage, "Publisher-specific chart history", "हर पब्लिशर का अलग चार्ट इतिहास", "प्रत्येक प्रकाशकाचा स्वतंत्र चार्ट इतिहास"), style = MaterialTheme.typography.titleLarge)
                // A separate chart/history is rendered for every URL. Publisher identity
                // remains shared across that publisher's URLs for evidence counting.
                val sourceChartGroups = observations.groupBy {
                    Triple(it.sourceId, it.sourceUrl.orEmpty(), it.market to it.sessionId)
                }.toList().sortedWith(compareBy({ it.first.third.first.name }, { it.first.third.second }, { it.first.first }, { it.first.second }))
                if (sourceChartGroups.isEmpty()) {
                    Text(uiText(uiLanguage, "Each registered URL's own chart history will appear here after supported rows are fetched.", "समर्थित रिकॉर्ड मिलने के बाद हर URL का अपना चार्ट इतिहास यहां दिखेगा।", "समर्थित नोंदी मिळाल्यानंतर प्रत्येक URL चा स्वतंत्र चार्ट इतिहास येथे दिसेल."))
                } else {
                    sourceChartGroups.forEach { (identity, rows) ->
                        Card {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("${identity.first} · ${identity.third.first.name}/${identity.third.second}", style = MaterialTheme.typography.titleSmall)
                                Text(uiText(uiLanguage, "URL: ${identity.second}", "URL: ${identity.second}", "URL: ${identity.second}"), style = MaterialTheme.typography.bodySmall)
                                Text(uiText(uiLanguage, "${rows.size} rows · ${rows.map { it.date }.distinct().size} distinct dates · latest ${rows.maxOf { it.date }}", "${rows.size} रिकॉर्ड · ${rows.map { it.date }.distinct().size} अलग तारीखें · नवीनतम ${rows.maxOf { it.date }}", "${rows.size} नोंदी · ${rows.map { it.date }.distinct().size} वेगवेगळ्या तारखा · नवीनतम ${rows.maxOf { it.date }}"), style = MaterialTheme.typography.bodySmall)
                                rows.sortedByDescending { it.date }.take(12).forEach { row ->
                                    Text("${row.date}: ${row.normalizedResult ?: row.rawText} · ${row.status}", style = MaterialTheme.typography.bodySmall)
                                }
                                if (rows.size > 12) Text(uiText(uiLanguage, "Showing latest 12 of ${rows.size} rows.", "${rows.size} में से नवीनतम 12 रिकॉर्ड दिखाए गए।", "${rows.size} पैकी नवीनतम 12 नोंदी दाखवल्या आहेत."), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Text(uiText(uiLanguage, "Recent source observations", "हाल के सोर्स रिकॉर्ड", "अलीकडील स्रोत नोंदी"), style = MaterialTheme.typography.titleLarge)
                if (groups.isEmpty()) {
                    Text(uiText(uiLanguage, "No source records loaded.", "कोई सोर्स रिकॉर्ड लोड नहीं हुआ।", "कोणत्याही स्रोत नोंदी लोड झालेल्या नाहीत."))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        groups.asReversed().take(30).forEach { group -> SourceGroupCard(group) }
                    }
                }
                }
                Text(uiText(uiLanguage, "Offline SQLite · Source provenance preserved · Conflicts never auto-resolved", "ऑफलाइन SQLite · सोर्स की जानकारी सुरक्षित · विरोध अपने-आप हल नहीं होते", "ऑफलाइन SQLite · स्रोताची माहिती जतन · विसंगती आपोआप सोडवल्या जात नाहीत"), style = MaterialTheme.typography.labelSmall)
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
private fun MarketNextInsightCard(
    market: com.relab.core.Market, sessionId: String, records: List<com.relab.core.ChartRecord>,
    open: com.relab.core.CandidateRanking?, close: com.relab.core.CandidateRanking?,
    jodi: List<com.relab.core.JodiCandidate>, latestRetrievedAt: java.time.Instant?, latestResultDate: LocalDate?
) {
    val valid = records.mapNotNull { record ->
        (com.relab.core.ChartMath.validate(record) as? com.relab.core.ValidationResult.Valid)?.let { record to it.features }
    }
    val features = valid.map { it.second }
    fun top(values: List<String>, denominator: Int): String {
        if (values.isEmpty() || denominator == 0) return "Insufficient history"
        val (pattern, count) = values.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).first()
        return "$pattern · ${"%.1f".format(count * 100.0 / denominator)}% (n=$count/$denominator)"
    }
    fun nextActiveDate(): LocalDate {
        var date = LocalDate.now().plusDays(1)
        repeat(14) { if (date.dayOfWeek in market.activeDays) return date else date = date.plusDays(1) }
        return LocalDate.now().plusDays(1)
    }
    val openDigit = top(features.map { it.openAnk.toString() }, features.size)
    val jodiPattern = top(features.map { "${it.openAnk}${it.closeAnk}" }, features.size)
    val halfOpen = top(valid.map { (record, feature) -> "${record.openPanna}-${feature.closeAnk}" }, valid.size)
    val halfClose = top(valid.map { (record, feature) -> "${feature.openAnk}-${record.closePanna}" }, valid.size)
    val fullSangam = top(valid.map { (record, _) -> "${record.openPanna}-${record.closePanna}" }, valid.size)
    val pannaTypes = valid.flatMap { listOf(it.first.openPanna to it.second.openType, it.first.closePanna to it.second.closeType) }
    val doublePannas = pannaTypes.filter { (_, type) -> type.name.contains("DOUBLE") || type.name == "DP" }.map { it.first }
    val triplePannas = pannaTypes.filter { (_, type) -> type.name.contains("TRIPLE") || type.name == "TP" }.map { it.first }
    val pannaDenominator = features.size * 2
    val ageHours = latestRetrievedAt?.let { java.time.Duration.between(it, java.time.Instant.now()).toHours().coerceAtLeast(0) } ?: Long.MAX_VALUE
    val freshnessLabel = when {
        latestRetrievedAt == null -> "NO SOURCE FETCH"
        ageHours >= 72 -> "STALE · ${ageHours}h"
        ageHours >= 24 -> "AGING · ${ageHours}h"
        else -> "RECENT · ${ageHours}h"
    }
    val freshnessColor = when {
        latestRetrievedAt == null || ageHours >= 72 -> MaterialTheme.colorScheme.error
        ageHours >= 24 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("$market · $sessionId", style = MaterialTheme.typography.titleMedium)
                Text(freshnessLabel, color = freshnessColor, style = MaterialTheme.typography.labelSmall)
            }
            Text("Next active date: ${nextActiveDate()} · History: ${features.size} valid rows")
            Text("Latest result date: ${latestResultDate ?: "—"} · Latest successful capture: ${latestRetrievedAt ?: "Never"}", style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.HorizontalDivider()
            Text("OPEN DIGIT", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary); Text(openDigit)
            Text("JODI · historical open/close ank pair", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary); Text(jodiPattern)
            Text("HALF SANGAM · Open panna + close ank", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary); Text(halfOpen)
            Text("HALF SANGAM · Open ank + close panna", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary); Text(halfClose)
            Text("DP · observed double-panna share", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary); Text(top(doublePannas, pannaDenominator))
            Text("TRIPLE PANNA · observed triple-panna share", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary); Text(top(triplePannas, pannaDenominator))
            Text("FULL SANGAM · exact open/close panna pair", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary); Text(fullSangam)
            if (open != null && close != null && !open.abstained && !close.abstained) {
                Text("Next research candidates · relative evidence index (not probability)", style = MaterialTheme.typography.titleSmall)
                Text("Open: ${open.candidates.take(3).joinToString { "${it.panna} (${"%.1f".format(it.score)})" }}")
                Text("Close: ${close.candidates.take(3).joinToString { "${it.panna} (${"%.1f".format(it.score)})" }}")
                Text("Jodi rank: ${jodi.take(3).joinToString { "${it.jodi} (${"%.1f".format(it.score)})" }}")
            } else Text(open?.note ?: "Candidate ranking unavailable until source history arrives.", style = MaterialTheme.typography.bodySmall)
            Text("Historical share = matching rows ÷ eligible rows. Descriptive evidence only, not a calibrated future chance. See Research and Audit for backtests, missing dates, publisher coverage and conflicts.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CoverageReportCard(report: CoverageReport, uiLanguage: String, onRangeApply: (LocalDate, LocalDate) -> Unit) {
    var startText by remember(report.market, report.sessionId, report.startDate) { mutableStateOf(report.startDate.toString()) }
    var endText by remember(report.market, report.sessionId, report.endDate) { mutableStateOf(report.endDate.toString()) }
    var rangeError by remember(report.market, report.sessionId) { mutableStateOf<String?>(null) }
    Card {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text("${report.market} · ${report.sessionId}", style = MaterialTheme.typography.titleMedium)
            Text(uiText(uiLanguage, "Coverage date range (YYYY-MM-DD)", "कवरेज तारीख सीमा (YYYY-MM-DD)", "कव्हरेज तारीख श्रेणी (YYYY-MM-DD)"), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = startText, onValueChange = { startText = it }, label = { Text(uiText(uiLanguage, "Start date", "शुरुआती तारीख", "सुरुवातीची तारीख")) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = endText, onValueChange = { endText = it }, label = { Text(uiText(uiLanguage, "End date", "अंतिम तारीख", "शेवटची तारीख")) }, singleLine = true, modifier = Modifier.weight(1f))
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
            }) { Text(uiText(uiLanguage, "Apply date range", "तारीख सीमा लागू करें", "तारीख श्रेणी लागू करा")) }
            rangeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text("Range: ${report.startDate} → ${report.endDate}")
            Text("Expected trading days: ${report.expectedTradingDays}")
            Text("Published: ${report.publishedDays} · Multi-source consensus: ${report.consensusDays} · Single-source unverified: ${report.singleSourceDays} · Conflicts: ${report.conflictDays}")
            Text("Pending: ${report.pendingDays} · Holidays: ${report.holidayDays}")
            Text("Source missing: ${report.sourceMissingDays} · Malformed only: ${report.malformedOnlyDays} · No observation: ${report.noObservationDays}")
            Text(uiText(uiLanguage, "Latest coverage records", "नवीनतम कवरेज रिकॉर्ड", "नवीनतम कव्हरेज नोंदी"), style = MaterialTheme.typography.titleSmall)
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
private fun DuplicateObservationAuditCard(groups: List<DuplicateObservationGroup>, uiLanguage: String) {
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
            Text(uiText(uiLanguage, "Audit only: no source rows are deleted, merged, or silently corrected.", "केवल ऑडिट: कोई source row delete, merge या चुपचाप correct नहीं होती।", "फक्त ऑडिट: कोणतीही source row delete, merge किंवा न सांगता correct केली जात नाही."), style = MaterialTheme.typography.labelSmall)
        }
    }
}


@Composable
private fun RepeatedPublishedResultAuditCard(groups: List<com.relab.core.RepeatedPublishedResultGroup>, uiLanguage: String) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(uiText(uiLanguage, "Review flags: ${groups.size}", "जांच संकेत: ${groups.size}", "तपासणी संकेत: ${groups.size}"))
            groups.take(20).forEach { group ->
                val dates = group.observations.map { it.date }.distinct().joinToString()
                Text("${group.market} · ${group.sessionId} · ${group.sourceId}", style = MaterialTheme.typography.titleSmall)
                Text("${group.result} repeated on ${group.distinctDates} dates", style = MaterialTheme.typography.bodySmall)
                Text(uiText(uiLanguage, "Dates: $dates", "तारीखें: $dates", "तारखा: $dates"), style = MaterialTheme.typography.bodySmall)
            }
            Text(uiText(uiLanguage, "Review signal only—not proof of stale data. Original observations remain preserved.", "यह केवल जांच का संकेत है, पुराने डेटा का प्रमाण नहीं। मूल रिकॉर्ड सुरक्षित रहते हैं।", "हा फक्त तपासणीचा संकेत आहे, जुना डेटा असल्याचा पुरावा नाही. मूळ नोंदी जतन राहतात."), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PatternResearchCard(analysis: MarketAnalysis, uiLanguage: String) {
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
            Text(uiText(uiLanguage, "Descriptive history only; walk-forward scores are baselines, not predictions.", "यह केवल ऐतिहासिक विश्लेषण है; walk-forward स्कोर आधार हैं, भविष्यवाणी नहीं।", "हे फक्त ऐतिहासिक विश्लेषण आहे; walk-forward स्कोअर आधार आहेत, भविष्यवाणी नाहीत."), style = MaterialTheme.typography.labelSmall)
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
                val sessionId = if (market.name.endsWith("_NIGHT")) "night" else "main"
                val report = com.relab.core.ChartParserRegistry.parse(
                    html, connection.url.toString(), market, sessionId, java.time.Instant.now()
                )
                val host = connection.url.host.lowercase().removePrefix("www.")
                val publisherId = host.substringBefore('.')
                val sourceRows = report.observations.map {
                    it.copy(sourceId = publisherId, sourceUrl = cleanUrl)
                }
                // Persist on the worker thread: SQLite writes and dataset merging must
                // never block the Compose/UI thread after a successful fetch.
                val merged = if (sourceRows.isNotEmpty()) save(sourceRows) else emptyList()
                mainHandler.post {
                    try {
                        if (sourceRows.isEmpty()) {
                            val detail = report.issues.firstOrNull()
                                ?: "No dated chart-result rows found. This page may be JavaScript-rendered or use an unsupported layout."
                            updateMessage("No records imported by ${report.parserId}: $detail Skipped rows: ${report.skippedRows}.")
                        } else {
                            updateObservations(merged)
                            updateMessage(
                                "${market.name}: fetched ${sourceRows.size} rows with ${report.parserId}; total saved observations ${merged.size}. " +
                                    "Issues ${report.issues.size}; skipped rows ${report.skippedRows}. Publisher: $publisherId."
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

 
private fun discoverMatkaFlashMarketSources(
    existing: List<MarketSource>,
    saveSources: (List<MarketSource>) -> Unit,
    updateSources: (List<MarketSource>) -> Unit,
    updateMessage: (String) -> Unit,
    updateBusy: (Boolean) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())
    updateBusy(true)
    updateMessage("Discovering dated market panel charts from MatkaFlash…")
    Thread {
        try {
            val connection = URL(com.relab.core.MatkaFlashMarketDirectoryAdapter.DIRECTORY_URL)
                .openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "RE-LAB-Android/0.1")
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            val discovered = try {
                val code = connection.responseCode
                require(code in 200..299) { "Directory returned HTTP $code" }
                val contentType = connection.contentType.orEmpty().lowercase()
                require(contentType.contains("text/html") || contentType.contains("application/xhtml+xml") || contentType.isBlank()) {
                    "Unsupported directory content type: $contentType"
                }
                val bytes = connection.inputStream.use { readBoundedBytes(it, 8_000_000L) }
                com.relab.core.MatkaFlashMarketDirectoryAdapter.discover(
                    bytes.toString(Charsets.UTF_8), connection.url.toString()
                )
            } finally {
                connection.disconnect()
            }
            var merged = existing
            var added = 0
            discovered.forEach { source ->
                val normalized = UserMarketSourceRegistry.normalizeUrl(source.url)
                if (merged.none { UserMarketSourceRegistry.normalizeUrl(it.url) == normalized }) {
                    merged = UserMarketSourceRegistry.add(merged, source)
                    added++
                }
            }
            mainHandler.post {
                updateSources(merged)
                saveSources(merged)
                updateMessage(
                    "MatkaFlash directory found ${discovered.size} dated panel sources; added $added new markets. " +
                        "New sources are disabled to avoid firing 100+ requests at once. Enable selected sources, then refresh."
                )
                updateBusy(false)
            }
        } catch (error: Exception) {
            mainHandler.post {
                updateMessage("Market discovery failed: ${error.message ?: "Unknown error"}")
                updateBusy(false)
            }
        }
    }.start()
}
