package com.relab.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.relab.core.Market
import com.relab.core.PannaSide
import com.relab.core.PredictionGrade
import com.relab.core.PredictionVerification
import com.relab.core.ReconciliationState
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
            assertThrows(IllegalStateException::class.java) { db.appendLockedPrediction(prediction) }
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
}
