package com.relab.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.relab.core.Market
import com.relab.core.PannaSide
import com.relab.core.LockedSidePrediction
import com.relab.core.PredictionVerification
import com.relab.core.PredictionGrade
import com.relab.core.PredictionLearningLedger
import com.relab.core.ReconciliationState
import com.relab.core.ObservationStatus
import com.relab.core.SourceObservation
import com.relab.core.SourceObservationMerger
import java.time.Instant
import java.time.LocalDate

/**
 * Durable, offline store for source observations.
 *
 * Every source observation is stored as its own row. No uniqueness constraint is
 * applied to market/session/date because competing and duplicate source records
 * are evidence that must remain available for reconciliation.
 */
class LocalObservationDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_OBSERVATIONS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                market TEXT NOT NULL,
                session_id TEXT NOT NULL,
                chart_date TEXT NOT NULL,
                source_id TEXT NOT NULL,
                source_record_id TEXT,
                source_url TEXT,
                retrieved_at TEXT NOT NULL,
                published_at TEXT,
                raw_text TEXT NOT NULL,
                normalized_result TEXT,
                status TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_observations_market_session_date " +
                "ON $TABLE_OBSERVATIONS(market, session_id, chart_date)"
        )
        createPredictionTables(db)
        db.execSQL(
            """
            CREATE TABLE $TABLE_METADATA (
                metadata_key TEXT PRIMARY KEY NOT NULL,
                metadata_value TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema changes must be additive. Never silently discard a user's history.
        if (oldVersion < 2) {
            createPredictionTables(db)
        }
    }

    fun replaceDataset(csv: String, observations: List<SourceObservation>) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.delete(TABLE_OBSERVATIONS, null, null)
            observations.forEach { observation ->
                val values = ContentValues().apply {
                    put("market", observation.market.name)
                    put("session_id", observation.sessionId)
                    put("chart_date", observation.date.toString())
                    put("source_id", observation.sourceId)
                    put("source_record_id", observation.sourceRecordId)
                    put("source_url", observation.sourceUrl)
                    put("retrieved_at", observation.retrievedAt.toString())
                    put("published_at", observation.publishedAt?.toString())
                    put("raw_text", observation.rawText)
                    put("normalized_result", observation.normalizedResult)
                    put("status", observation.status.name)
                }
                database.insertOrThrow(TABLE_OBSERVATIONS, null, values)
            }
            putMetadata(database, KEY_SOURCE_CSV, csv)
            putMetadata(database, KEY_IMPORTED_AT, Instant.now().toString())
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Appends another CSV import. Existing exact observations are not duplicated,
     * while distinct source values and separate retrieval snapshots are retained.
     */
    fun appendDataset(csv: String, incoming: List<SourceObservation>): List<SourceObservation> {
        val database = writableDatabase
        database.beginTransaction()
        try {
            val existing = readObservations(database)
            val merged = SourceObservationMerger.append(existing, incoming)
            merged.observations.drop(existing.size).forEach { observation ->
                val values = ContentValues().apply {
                    put("market", observation.market.name)
                    put("session_id", observation.sessionId)
                    put("chart_date", observation.date.toString())
                    put("source_id", observation.sourceId)
                    put("source_record_id", observation.sourceRecordId)
                    put("source_url", observation.sourceUrl)
                    put("retrieved_at", observation.retrievedAt.toString())
                    put("published_at", observation.publishedAt?.toString())
                    put("raw_text", observation.rawText)
                    put("normalized_result", observation.normalizedResult)
                    put("status", observation.status.name)
                }
                database.insertOrThrow(TABLE_OBSERVATIONS, null, values)
            }
            putMetadata(database, KEY_SOURCE_CSV, csv)
            putMetadata(database, KEY_IMPORTED_AT, Instant.now().toString())
            database.setTransactionSuccessful()
            return merged.observations
        } finally {
            database.endTransaction()
        }
    }

    fun loadObservations(): List<SourceObservation> = readObservations(readableDatabase)

    private fun readObservations(database: SQLiteDatabase): List<SourceObservation> {
        val result = mutableListOf<SourceObservation>()
        database.query(
            TABLE_OBSERVATIONS,
            arrayOf(
                "market", "session_id", "chart_date", "source_id", "source_record_id",
                "source_url", "retrieved_at", "published_at", "raw_text",
                "normalized_result", "status"
            ),
            null, null, null, null,
            "chart_date ASC, id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += SourceObservation(
                    market = Market.valueOf(cursor.getString(0)),
                    sessionId = cursor.getString(1),
                    date = LocalDate.parse(cursor.getString(2)),
                    sourceId = cursor.getString(3),
                    sourceRecordId = cursor.getString(4),
                    sourceUrl = cursor.getString(5),
                    retrievedAt = Instant.parse(cursor.getString(6)),
                    publishedAt = cursor.getString(7)?.let(Instant::parse),
                    rawText = cursor.getString(8),
                    normalizedResult = cursor.getString(9),
                    status = ObservationStatus.valueOf(cursor.getString(10))
                )
            }
        }
        return result
    }

    /**
     * Appends a frozen prediction. The unique primary key prevents historical replacement.
     */
    fun appendLockedPrediction(prediction: LockedSidePrediction) {
        val values = ContentValues().apply {
            put("prediction_id", prediction.id)
            put("market", prediction.market.name)
            put("session_id", prediction.sessionId)
            put("target_date", prediction.targetDate.toString())
            put("side", prediction.side.name)
            put("candidates", prediction.candidates.joinToString(","))
            put("training_records", prediction.trainingRecords)
            put("locked_at", prediction.lockedAt.toString())
            put("evidence_note", prediction.evidenceNote)
        }
        writableDatabase.insertOrThrow(TABLE_PREDICTIONS, null, values)
    }

    /**
     * Appends an immutable verification attempt. GREY is provisional and can be rechecked;
     * earlier attempts remain in the database and are never updated or deleted.
     */
    fun appendPredictionVerification(verification: PredictionVerification) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val predictionExists = db.query(
                TABLE_PREDICTIONS, arrayOf("prediction_id"), "prediction_id = ?",
                arrayOf(verification.predictionId), null, null, null
            ).use { it.moveToFirst() }
            require(predictionExists) { "Verification references an unknown locked prediction." }
            val latestAttempt = db.rawQuery(
                "SELECT COALESCE(MAX(attempt), 0) FROM $TABLE_VERIFICATIONS WHERE prediction_id = ?",
                arrayOf(verification.predictionId)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            require(verification.attempt == latestAttempt + 1) {
                "Verification attempt is out of sequence; reload the ledger and retry."
            }
            val values = ContentValues().apply {
                put("prediction_id", verification.predictionId)
                put("attempt", verification.attempt)
                put("grade", verification.grade.name)
                put("actual_panna", verification.actualPanna)
                put("actual_jodi", verification.actualJodi)
                if (verification.matchedRank == null) putNull("matched_rank") else put("matched_rank", verification.matchedRank)
                put("verified_at", verification.verifiedAt.toString())
                put("reconciliation_state", verification.reconciliationState.name)
                put("explanation", verification.explanation)
            }
            db.insertOrThrow(TABLE_VERIFICATIONS, null, values)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Atomically merges a portable ledger without replacing existing frozen history. */
    fun appendPredictionLedger(incoming: PredictionLearningLedger): PredictionLearningLedger {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = loadPredictionLedger()
            val existingPredictions = existing.lockedPredictions.associateBy { it.id }
            incoming.lockedPredictions.forEach { prediction ->
                val prior = existingPredictions[prediction.id]
                require(prior == null || prior == prediction) {
                    "Backup prediction conflicts with an existing locked prediction: ${prediction.id}"
                }
                if (prior == null) appendLockedPrediction(prediction)
            }
            val existingAttempts = existing.verifications.associateBy { it.predictionId to it.attempt }
            incoming.verifications.sortedWith(compareBy<PredictionVerification> { it.predictionId }.thenBy { it.attempt }).forEach { verification ->
                val prior = existingAttempts[verification.predictionId to verification.attempt]
                require(prior == null || prior == verification) {
                    "Backup verification conflicts with existing attempt ${verification.attempt} for ${verification.predictionId}"
                }
                if (prior == null) appendPredictionVerification(verification)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return loadPredictionLedger()
    }

    /** Atomically restores source observations and the append-only learning ledger. */
    fun appendResearchBundle(
        sourceCsv: String,
        incomingObservations: List<SourceObservation>,
        incomingLedger: PredictionLearningLedger
    ): Pair<List<SourceObservation>, PredictionLearningLedger> {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Nested transactions keep both operations under this outer commit boundary.
            val mergedObservations = appendDataset(sourceCsv, incomingObservations)
            val mergedLedger = appendPredictionLedger(incomingLedger)
            db.setTransactionSuccessful()
            return mergedObservations to mergedLedger
        } finally {
            db.endTransaction()
        }
    }

    fun loadPredictionLedger(): PredictionLearningLedger {
        val db = readableDatabase
        val predictions = mutableListOf<LockedSidePrediction>()
        db.query(TABLE_PREDICTIONS, null, null, null, null, null, "target_date ASC, prediction_id ASC").use { cursor ->
            while (cursor.moveToNext()) {
                predictions += LockedSidePrediction(
                    market = Market.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("market"))),
                    sessionId = cursor.getString(cursor.getColumnIndexOrThrow("session_id")),
                    targetDate = LocalDate.parse(cursor.getString(cursor.getColumnIndexOrThrow("target_date"))),
                    side = PannaSide.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("side"))),
                    candidates = cursor.getString(cursor.getColumnIndexOrThrow("candidates")).split(",").filter { it.isNotBlank() },
                    trainingRecords = cursor.getInt(cursor.getColumnIndexOrThrow("training_records")),
                    lockedAt = Instant.parse(cursor.getString(cursor.getColumnIndexOrThrow("locked_at"))),
                    evidenceNote = cursor.getString(cursor.getColumnIndexOrThrow("evidence_note"))
                )
            }
        }
        val verifications = mutableListOf<PredictionVerification>()
        db.query(TABLE_VERIFICATIONS, null, null, null, null, null, "prediction_id ASC, attempt ASC").use { cursor ->
            while (cursor.moveToNext()) {
                val rankIndex = cursor.getColumnIndexOrThrow("matched_rank")
                verifications += PredictionVerification(
                    predictionId = cursor.getString(cursor.getColumnIndexOrThrow("prediction_id")),
                    grade = PredictionGrade.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("grade"))),
                    actualPanna = cursor.getString(cursor.getColumnIndexOrThrow("actual_panna")),
                    actualJodi = cursor.getString(cursor.getColumnIndexOrThrow("actual_jodi")),
                    matchedRank = if (cursor.isNull(rankIndex)) null else cursor.getInt(rankIndex),
                    verifiedAt = Instant.parse(cursor.getString(cursor.getColumnIndexOrThrow("verified_at"))),
                    reconciliationState = ReconciliationState.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("reconciliation_state"))),
                    explanation = cursor.getString(cursor.getColumnIndexOrThrow("explanation")),
                    attempt = cursor.getInt(cursor.getColumnIndexOrThrow("attempt"))
                )
            }
        }
        return PredictionLearningLedger(predictions, verifications)
    }

    private fun createPredictionTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PREDICTIONS (
                prediction_id TEXT PRIMARY KEY NOT NULL,
                market TEXT NOT NULL,
                session_id TEXT NOT NULL,
                target_date TEXT NOT NULL,
                side TEXT NOT NULL,
                candidates TEXT NOT NULL,
                training_records INTEGER NOT NULL,
                locked_at TEXT NOT NULL,
                evidence_note TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_VERIFICATIONS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                prediction_id TEXT NOT NULL,
                attempt INTEGER NOT NULL,
                grade TEXT NOT NULL,
                actual_panna TEXT,
                actual_jodi TEXT,
                matched_rank INTEGER,
                verified_at TEXT NOT NULL,
                reconciliation_state TEXT NOT NULL,
                explanation TEXT NOT NULL,
                UNIQUE(prediction_id, attempt),
                FOREIGN KEY(prediction_id) REFERENCES $TABLE_PREDICTIONS(prediction_id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_prediction_target ON $TABLE_PREDICTIONS(market, session_id, target_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_verification_prediction ON $TABLE_VERIFICATIONS(prediction_id, attempt)")
    }

    fun loadOriginalCsv(): String? = readMetadata(KEY_SOURCE_CSV)

    fun clearDataset() {
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.delete(TABLE_OBSERVATIONS, null, null)
            database.delete(TABLE_METADATA, null, null)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun readMetadata(key: String): String? {
        readableDatabase.query(
            TABLE_METADATA,
            arrayOf("metadata_value"),
            "metadata_key = ?",
            arrayOf(key),
            null, null, null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun putMetadata(database: SQLiteDatabase, key: String, value: String) {
        val values = ContentValues().apply {
            put("metadata_key", key)
            put("metadata_value", value)
        }
        database.insertWithOnConflict(TABLE_METADATA, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        private const val DATABASE_NAME = "relab_local_history.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_OBSERVATIONS = "source_observations"
        private const val TABLE_METADATA = "app_metadata"
        private const val TABLE_PREDICTIONS = "locked_predictions"
        private const val TABLE_VERIFICATIONS = "prediction_verification_attempts"
        private const val KEY_SOURCE_CSV = "source_csv"
        private const val KEY_IMPORTED_AT = "imported_at"
    }
}
