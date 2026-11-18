package com.relab.app

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.relab.core.Market
import com.relab.core.PannaSide
import com.relab.core.PredictionGrade
import com.relab.core.PredictionVerification
import com.relab.core.ReconciliationState
import com.relab.core.ObservationStatus
import com.relab.core.SourceObservation
import com.relab.core.PredictionLearningLedger
import com.relab.core.LockedSidePrediction
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalObservationDatabaseInstrumentedTest {
    @Test
    fun lockedPredictionAndAppendOnlyVerificationSurviveDatabaseReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("relab_local_history.db")
        val lockedAt = Instant.parse("2026-11-18T04:00:00Z")
        val prediction = LockedSidePrediction(
            market = Market.SRIDEVI,
            sessionId = "main",
            targetDate = LocalDate.parse("2026-11-19"),
            side = PannaSide.OPEN,
            candidates = listOf("123", "456", "789"),
            trainingRecords = 24,
            lockedAt = lockedAt,
            evidenceNote = "instrumentation persistence fixture"
        )
        val firstAttempt = PredictionVerification(
            predictionId = prediction.id,
            grade = PredictionGrade.GREY,
            actualPanna = null,
            actualJodi = null,
            matchedRank = null,
            verifiedAt = lockedAt.plusSeconds(3600),
            reconciliationState = ReconciliationState.SINGLE_SOURCE_UNVERIFIED,
            explanation = "Only one publisher; provisional.",
            attempt = 1
        )
        val secondAttempt = firstAttempt.copy(
            grade = PredictionGrade.GREEN,
            actualPanna = "456",
            actualJodi = "15",
            matchedRank = 2,
            verifiedAt = lockedAt.plusSeconds(7200),
            reconciliationState = ReconciliationState.CONSENSUS,
            explanation = "Two independent publishers agree.",
            attempt = 2
        )

        LocalObservationDatabase(context).use { db ->
            db.appendLockedPrediction(prediction)
            db.appendPredictionVerification(firstAttempt)
            db.appendPredictionVerification(secondAttempt)
        }

        LocalObservationDatabase(context).use { reopened ->
            val ledger = reopened.loadPredictionLedger()
            assertEquals(listOf(prediction), ledger.lockedPredictions)
            assertEquals(listOf(firstAttempt, secondAttempt), ledger.verifications)
            val performance = ledger.performance(Market.SRIDEVI, "main", PannaSide.OPEN)
            assertEquals(1, performance.green)
            assertEquals(0, performance.red)
            assertEquals(0, performance.grey)
            assertEquals(1, performance.verified)
        }

        context.deleteDatabase("relab_local_history.db")
    }

    @Test
    fun duplicatePredictionAndOutOfOrderVerificationAreRejected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("relab_local_history.db")
        val prediction = LockedSidePrediction(
            Market.KALYAN, "main", LocalDate.parse("2026-11-19"), PannaSide.CLOSE,
            listOf("234"), 12, Instant.parse("2026-11-18T04:00:00Z"), "duplicate guard fixture"
        )
        LocalObservationDatabase(context).use { db ->
            db.appendLockedPrediction(prediction)
            assertThrows(SQLiteConstraintException::class.java) { db.appendLockedPrediction(prediction) }
            val invalidAttempt = PredictionVerification(
                prediction.id, PredictionGrade.GREY, null, null, null,
                Instant.parse("2026-11-18T05:00:00Z"),
                ReconciliationState.NO_PUBLISHED_RESULT, "attempt sequence guard", attempt = 2
            )
            assertThrows(IllegalArgumentException::class.java) {
                db.appendPredictionVerification(invalidAttempt)
            }
        }
        context.deleteDatabase("relab_local_history.db")
    }
    @Test
    fun failedBundleLedgerMergeRollsBackObservationImportToo() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("relab_local_history.db")
        val lockedAt = Instant.parse("2026-11-18T04:00:00Z")
        val existing = LockedSidePrediction(
            Market.SRIDEVI, "main", LocalDate.parse("2026-11-19"), PannaSide.OPEN,
            listOf("123"), 12, lockedAt, "existing frozen record"
        )
        val conflicting = existing.copy(
            candidates = listOf("456"),
            evidenceNote = "conflicting backup record"
        )
        val observation = SourceObservation(
            market = Market.SRIDEVI,
            sessionId = "main",
            date = LocalDate.parse("2026-11-18"),
            sourceId = "publisher-a",
            retrievedAt = lockedAt,
            publishedAt = null,
            rawText = "backup row",
            normalizedResult = null,
            status = ObservationStatus.MISSING_FROM_SOURCE
        )
        LocalObservationDatabase(context).use { db ->
            db.appendLockedPrediction(existing)
            assertThrows(IllegalArgumentException::class.java) {
                db.appendResearchBundle(
                    "backup-csv",
                    listOf(observation),
                    PredictionLearningLedger(listOf(conflicting), emptyList())
                )
            }
            assertEquals(emptyList<SourceObservation>(), db.loadObservations())
            assertEquals(listOf(existing), db.loadPredictionLedger().lockedPredictions)
        }
        context.deleteDatabase("relab_local_history.db")
    }

    @Test
    fun restoringSameResearchBundleTwiceDoesNotDuplicateObservationsOrLedgerHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("relab_local_history.db")
        val lockedAt = Instant.parse("2026-11-18T04:00:00Z")
        val prediction = LockedSidePrediction(
            Market.SRIDEVI, "main", LocalDate.parse("2026-11-19"), PannaSide.OPEN,
            listOf("123", "456"), 24, lockedAt, "idempotent bundle fixture"
        )
        val verification = PredictionVerification(
            prediction.id, PredictionGrade.GREY, null, null, null,
            lockedAt.plusSeconds(3600), ReconciliationState.SINGLE_SOURCE_UNVERIFIED,
            "Waiting for independent publisher evidence.", attempt = 1
        )
        val observation = SourceObservation(
            market = Market.SRIDEVI,
            sessionId = "main",
            date = LocalDate.parse("2026-11-18"),
            sourceId = "publisher-a",
            sourceRecordId = "bundle-row-1",
            sourceUrl = "https://publisher.example/chart",
            retrievedAt = lockedAt,
            publishedAt = null,
            rawText = "pending",
            normalizedResult = null,
            status = ObservationStatus.MISSING_FROM_SOURCE
        )
        val ledger = PredictionLearningLedger(listOf(prediction), listOf(verification))
        LocalObservationDatabase(context).use { db ->
            val first = db.appendResearchBundle("same-backup", listOf(observation), ledger)
            assertEquals(1, first.first.size)
            assertEquals(1, first.second.lockedPredictions.size)
            assertEquals(1, first.second.verifications.size)

            val second = db.appendResearchBundle("same-backup", listOf(observation), ledger)
            assertEquals(first.first, second.first)
            assertEquals(first.second, second.second)
            assertEquals(1, db.loadObservations().size)
            assertEquals(1, db.loadPredictionLedger().lockedPredictions.size)
            assertEquals(1, db.loadPredictionLedger().verifications.size)
        }
        context.deleteDatabase("relab_local_history.db")
    }

    @Test
    fun versionOneDatabaseMigratesWithoutDroppingObservationHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("relab_local_history.db")
        val path = context.getDatabasePath("relab_local_history.db")
        path.parentFile?.mkdirs()
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null)
        old.execSQL(
            """CREATE TABLE source_observations (
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
            )"""
        )
        old.execSQL("""CREATE TABLE app_metadata (
            metadata_key TEXT PRIMARY KEY NOT NULL,
            metadata_value TEXT NOT NULL
        )""")
        old.execSQL(
            """INSERT INTO source_observations
            (market, session_id, chart_date, source_id, source_record_id, source_url,
             retrieved_at, published_at, raw_text, normalized_result, status)
            VALUES ('SRIDEVI', 'main', '2026-11-16', 'old.publisher', 'legacy-1',
             'https://old.publisher/chart', '2026-11-16T12:00:00Z', NULL,
             'legacy raw cell', NULL, 'MISSING_FROM_SOURCE')"""
        )
        old.version = 1
        old.close()

        LocalObservationDatabase(context).use { migrated ->
            val observations = migrated.loadObservations()
            assertEquals(1, observations.size)
            assertEquals("legacy raw cell", observations.single().rawText)
            val tables = migrated.readableDatabase.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table'", null
            ).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertEquals(true, "locked_predictions" in tables)
            assertEquals(true, "prediction_verification_attempts" in tables)
        }
        context.deleteDatabase("relab_local_history.db")
    }

    @Test
    fun sourceImportIsIdempotentButIndependentConflictsAndPublisherCorrectionsAreRetained() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("relab_local_history.db")
        val capturedAt = Instant.parse("2026-11-18T10:00:00Z")
        val base = SourceObservation(
            market = Market.SRIDEVI, sessionId = "day",
            date = LocalDate.parse("2026-11-17"), sourceId = "publisher-a",
            sourceRecordId = "row-17", sourceUrl = "https://publisher-a.example/chart",
            retrievedAt = capturedAt, publishedAt = null, rawText = "012-07-000",
            normalizedResult = "012-07-000", status = ObservationStatus.PUBLISHED
        )
        val independentConflict = base.copy(
            sourceId = "publisher-b", sourceRecordId = "other-row-17",
            sourceUrl = "https://publisher-b.example/chart",
            rawText = "123-45-678", normalizedResult = "123-45-678"
        )
        val correctedSnapshot = base.copy(
            retrievedAt = capturedAt.plusSeconds(3600),
            rawText = "012-08-000", normalizedResult = "012-08-000"
        )
        LocalObservationDatabase(context).use { db ->
            val first = db.appendDataset("first-import", listOf(base, independentConflict, correctedSnapshot))
            assertEquals(3, first.size)
            val repeated = db.appendDataset("same-import", listOf(base, independentConflict, correctedSnapshot))
            assertEquals(first, repeated)
            assertEquals(3, db.loadObservations().size)
            val group = com.relab.core.SourceReconciliation.reconcile(db.loadObservations()).single()
            assertEquals(com.relab.core.ReconciliationState.CONFLICT, group.state)
            assertEquals(3, group.observations.size)
            assertEquals(setOf("012-07-000", "012-08-000", "123-45-678"),
                group.observations.mapNotNull { it.normalizedResult }.toSet())
        }
        context.deleteDatabase("relab_local_history.db")
    }

    @Test
    fun marketAndSessionQueriesReturnOnlyTheirOwnPartition() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("relab_local_history.db")
        val capturedAt = Instant.parse("2026-11-18T10:00:00Z")
        val date = LocalDate.parse("2026-11-17")
        val rows = listOf(
            SourceObservation(Market.SRIDEVI, "day", date, "publisher-a",
                retrievedAt = capturedAt, rawText = "012-07-000",
                normalizedResult = "012-07-000", status = ObservationStatus.PUBLISHED),
            SourceObservation(Market.SRIDEVI, "night", date, "publisher-a",
                retrievedAt = capturedAt, rawText = "123-45-678",
                normalizedResult = "123-45-678", status = ObservationStatus.PUBLISHED),
            SourceObservation(Market.KALYAN, "main", date, "publisher-a",
                retrievedAt = capturedAt, rawText = "456-78-901",
                normalizedResult = "456-78-901", status = ObservationStatus.PUBLISHED)
        )
        LocalObservationDatabase(context).use { db ->
            db.appendDataset("partition-fixture", rows)
            assertEquals(listOf("012-07-000"), db.loadObservations(Market.SRIDEVI, "day")
                .mapNotNull { it.normalizedResult })
            assertEquals(listOf("123-45-678"), db.loadObservations(Market.SRIDEVI, "night")
                .mapNotNull { it.normalizedResult })
            assertEquals(listOf("456-78-901"), db.loadObservations(Market.KALYAN, "main")
                .mapNotNull { it.normalizedResult })
            assertEquals(3, db.loadObservations().size)
        }
        context.deleteDatabase("relab_local_history.db")
    }

    @Test
    fun sourceHtmlSnapshotsRoundTripIdempotentlyAndClearWithDataset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("relab_local_history.db")
        val retrievedAt = Instant.parse("2026-11-18T22:30:00Z")
        val snapshot = com.relab.core.SourcePageSnapshot(
            sourceId = "publisher-a", market = Market.SRIDEVI, sessionId = "night",
            requestedUrl = "https://publisher-a.example/sridevi-night",
            finalUrl = "https://publisher-a.example/sridevi-night?view=archive",
            retrievedAt = retrievedAt, html = "<html><body>raw night archive</body></html>"
        )
        LocalObservationDatabase(context).use { db ->
            db.appendSourceSnapshots(listOf(snapshot))
            db.appendSourceSnapshots(listOf(snapshot))
            val restored = db.loadSourceSnapshots()
            assertEquals(1, restored.size)
            assertEquals(snapshot, restored.single())
            assertEquals(snapshot.sha256, restored.single().sha256)
            assertEquals(snapshot.utf8Bytes, restored.single().utf8Bytes)
            db.clearDataset()
            assertTrue(db.loadSourceSnapshots().isEmpty())
        }
        context.deleteDatabase("relab_local_history.db")
    }
}
