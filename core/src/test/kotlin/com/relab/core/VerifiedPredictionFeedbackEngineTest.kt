package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class VerifiedPredictionFeedbackEngineTest {
    private fun prediction(date: String, side: PannaSide, candidates: List<String>) =
        LockedSidePrediction(
            Market.SRIDEVI, "main", LocalDate.parse(date), side, candidates,
            12, Instant.parse("2026-11-01T00:00:00Z"), "test"
        )

    private fun verification(
        prediction: LockedSidePrediction,
        grade: PredictionGrade,
        actual: String?,
        attempt: Int = 1
    ) = PredictionVerification(
        prediction.id, grade, actual, null, null,
        prediction.targetDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
        if (grade == PredictionGrade.GREY) ReconciliationState.SINGLE_SOURCE_UNVERIFIED else ReconciliationState.CONSENSUS,
        "test", attempt
    )

    @Test fun learnsFamilyFeedbackOnlyFromLatestTerminalEarlierOutcomes() {
        val hit = prediction("2026-10-01", PannaSide.OPEN, listOf("123", "678"))
        val miss = prediction("2026-10-02", PannaSide.OPEN, listOf("111"))
        val grey = prediction("2026-10-03", PannaSide.OPEN, listOf("222"))
        val future = prediction("2026-11-09", PannaSide.OPEN, listOf("333"))
        val ledger = PredictionLearningLedger(
            lockedPredictions = listOf(hit, miss, grey, future),
            verifications = listOf(
                verification(hit, PredictionGrade.GREY, null, 1),
                verification(hit, PredictionGrade.GREEN, "678", 2),
                verification(miss, PredictionGrade.RED, "222"),
                verification(grey, PredictionGrade.GREY, null),
                verification(future, PredictionGrade.GREEN, "333")
            )
        )

        val report = VerifiedPredictionFeedbackEngine.analyze(
            ledger, Market.SRIDEVI, "main", LocalDate.parse("2026-11-08"), PannaSide.OPEN
        )

        assertEquals(2, report.verifiedPredictions)
        val family123 = report.families.getValue("123")
        assertEquals(1, family123.exposure)
        assertEquals(1, family123.hits)
        assertEquals(2.0 / 3.0, family123.smoothedHitRate, 0.000001)
        val family111 = report.families.getValue("111")
        assertEquals(1, family111.exposure)
        assertEquals(0, family111.hits)
        assertEquals(1.0 / 3.0, family111.smoothedHitRate, 0.000001)
        assertEquals(0.5, report.baselineSmoothedHitRate, 0.000001)
    }


    @Test fun futureCandidateRankingReceivesBoundedVerifiedFeedbackAdjustment() {
        val history = listOf(
            "277-65-249", "578-07-368", "800-81-137",
            "566-73-148", "288-82-679", "279-81-236"
        ).flatMapIndexed { index, result ->
            val date = LocalDate.of(2026, 11, 1).plusDays(index.toLong())
            listOf("publisher-a", "publisher-b").map { source ->
                SourceObservation(
                    market = Market.SRIDEVI, sessionId = "main", date = date,
                    sourceId = source, sourceUrl = "https://$source.example/chart",
                    retrievedAt = Instant.parse("2026-11-17T10:00:00Z"),
                    rawText = result, normalizedResult = result,
                    status = ObservationStatus.PUBLISHED
                )
            }
        }
        val hit = prediction("2026-10-01", PannaSide.OPEN, listOf("578"))
        val miss = prediction("2026-10-02", PannaSide.OPEN, listOf("111"))
        val ledger = PredictionLearningLedger(
            lockedPredictions = listOf(hit, miss),
            verifications = listOf(
                verification(hit, PredictionGrade.GREEN, "578"),
                verification(miss, PredictionGrade.RED, "222")
            )
        )
        val target = LocalDate.parse("2026-11-08")
        val baseline = CandidateRankingEngine.rank(
            history, Market.SRIDEVI, "main", target, PannaSide.OPEN, limit = 100
        )
        val learned = CandidateRankingEngine.rank(
            history, Market.SRIDEVI, "main", target, PannaSide.OPEN,
            limit = 100, learningLedger = ledger
        )
        val baseCandidate = baseline.candidates.single { it.panna == "578" }
        val learnedCandidate = learned.candidates.single { it.panna == "578" }
        assertEquals(0, baseCandidate.verifiedFeedbackExposure)
        assertEquals(1, learnedCandidate.verifiedFeedbackExposure)
        assertTrue(learnedCandidate.score > baseCandidate.score)
        assertTrue(learnedCandidate.score - baseCandidate.score <= 5.0)
    }


    @Test fun excludesOutcomesVerifiedOnOrAfterTheTargetDateToPreventTemporalLeakage() {
        val prior = prediction("2026-10-01", PannaSide.OPEN, listOf("123"))
        val sameDay = verification(prior, PredictionGrade.GREEN, "678").copy(
            verifiedAt = Instant.parse("2026-11-08T00:00:00Z")
        )
        val nextDay = verification(prior, PredictionGrade.GREEN, "678").copy(
            verifiedAt = Instant.parse("2026-11-09T00:00:00Z")
        )
        val sameDayReport = VerifiedPredictionFeedbackEngine.analyze(
            PredictionLearningLedger(listOf(prior), listOf(sameDay)),
            Market.SRIDEVI, "main", LocalDate.parse("2026-11-08"), PannaSide.OPEN
        )
        assertEquals(0, sameDayReport.verifiedPredictions)
        val nextDayReport = VerifiedPredictionFeedbackEngine.analyze(
            PredictionLearningLedger(listOf(prior), listOf(nextDay)),
            Market.SRIDEVI, "main", LocalDate.parse("2026-11-10"), PannaSide.OPEN
        )
        assertEquals(1, nextDayReport.verifiedPredictions)
    }

    @Test fun marketSessionAndSideRemainIsolated() {
        val prior = prediction("2026-10-01", PannaSide.OPEN, listOf("123"))
        val ledger = PredictionLearningLedger(
            lockedPredictions = listOf(prior),
            verifications = listOf(verification(prior, PredictionGrade.GREEN, "123"))
        )
        val close = VerifiedPredictionFeedbackEngine.analyze(
            ledger, Market.SRIDEVI, "main", LocalDate.parse("2026-11-08"), PannaSide.CLOSE
        )
        val otherMarket = VerifiedPredictionFeedbackEngine.analyze(
            ledger, Market.KALYAN, "main", LocalDate.parse("2026-11-08"), PannaSide.OPEN
        )
        assertTrue(close.families.isEmpty())
        assertTrue(otherMarket.families.isEmpty())
    }
}
