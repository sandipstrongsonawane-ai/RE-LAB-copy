package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class PredictionLearningCycleTest {
    private val retrieved = Instant.parse("2026-11-18T04:00:00Z")
    private val locked = Instant.parse("2026-11-18T04:05:00Z")
    private val verified = Instant.parse("2026-11-19T04:00:00Z")

    private fun observation(date: LocalDate, source: String, result: String) = SourceObservation(
        market = Market.SRIDEVI,
        sessionId = "main",
        date = date,
        sourceId = source,
        sourceUrl = "https://$source.example/chart",
        retrievedAt = retrieved,
        rawText = result,
        normalizedResult = result,
        status = ObservationStatus.PUBLISHED
    )

    private fun result(open: String, close: String): String {
        val openAnk = open.sumOf { it.digitToInt() } % 10
        val closeAnk = close.sumOf { it.digitToInt() } % 10
        return "$open-$openAnk$closeAnk-$close"
    }

    private fun history(): List<SourceObservation> {
        val start = LocalDate.of(2026, 10, 1)
        return (0 until 8).flatMap { index ->
            val date = start.plusDays(index.toLong())
            val value = if (index % 2 == 0) result("123", "456") else result("678", "901")
            listOf(observation(date, "publisher-a", value), observation(date, "publisher-b", value))
        }
    }

    @Test fun locksBeforeOutcomeAndGradesOnlyIndependentConsensus() {
        val target = LocalDate.of(2026, 10, 9)
        val (lockedLedger, prediction) = PredictionLearningCycle.lock(
            history(), Market.SRIDEVI, "main", target, PannaSide.OPEN, locked,
            limit = 10, minimumHistory = 5
        )
        assertEquals(8, prediction.trainingRecords)
        assertTrue(prediction.candidates.isNotEmpty())
        assertTrue(lockedLedger.verifications.isEmpty())

        val actual = result(prediction.candidates.first(), "456")
        val outcomes = listOf(
            observation(target, "publisher-a", actual),
            observation(target, "publisher-b", actual)
        )
        val (verifiedLedger, grade) = PredictionLearningCycle.verify(
            lockedLedger, prediction.id, outcomes, verified
        )
        assertEquals(PredictionGrade.GREEN, grade.grade)
        assertEquals(1, grade.matchedRank)
        assertEquals(1, verifiedLedger.performance(Market.SRIDEVI, "main", PannaSide.OPEN).green)
    }

    @Test fun singlePublisherAndConflictsAreGreyAndExcludedFromAccuracy() {
        val target = LocalDate.of(2026, 10, 9)
        val (ledger, prediction) = PredictionLearningCycle.lock(
            history(), Market.SRIDEVI, "main", target, PannaSide.CLOSE, locked,
            limit = 10, minimumHistory = 5
        )
        val oneSource = listOf(observation(target, "publisher-a", result("123", "456")))
        val (after, grade) = PredictionLearningCycle.verify(ledger, prediction.id, oneSource, verified)
        assertEquals(PredictionGrade.GREY, grade.grade)
        val performance = after.performance(Market.SRIDEVI, "main", PannaSide.CLOSE)
        assertEquals(1, performance.grey)
        assertEquals(0, performance.verified)
        assertNull(performance.hitRate)
    }

    @Test fun greyPredictionRechecksWhenHistoricalPublisherEvidenceChangesToConsensus() {
        val target = LocalDate.of(2026, 10, 9)
        val (ledger, prediction) = PredictionLearningCycle.lock(
            history(), Market.SRIDEVI, "main", target, PannaSide.OPEN, locked,
            limit = 10, minimumHistory = 5
        )
        val firstPublisher = observation(target, "publisher-a", result("123", "456"))
        val (afterGrey, firstAttempt) = PredictionLearningCycle.verify(
            ledger, prediction.id, listOf(firstPublisher), verified
        )
        assertEquals(PredictionGrade.GREY, firstAttempt.grade)
        assertFalse(PredictionLearningCycle.needsReverification(afterGrey, prediction.id, listOf(firstPublisher)))

        // This second publisher row was imported later, but its original capture timestamp is older.
        val historicalSecondPublisher = observation(target, "publisher-b", firstPublisher.normalizedResult!!)
            .copy(retrievedAt = retrieved.minusSeconds(3600))
        val updatedEvidence = listOf(firstPublisher, historicalSecondPublisher)
        assertTrue(PredictionLearningCycle.needsReverification(afterGrey, prediction.id, updatedEvidence))

        val (afterConsensus, secondAttempt) = PredictionLearningCycle.verify(
            afterGrey, prediction.id, updatedEvidence, verified.plusSeconds(60)
        )
        assertEquals(PredictionGrade.GREEN, secondAttempt.grade)
        assertEquals(2, secondAttempt.attempt)
        assertEquals(2, afterConsensus.verifications.size)
        assertEquals(PredictionGrade.GREY, afterConsensus.verifications.first().grade)
        assertEquals(PredictionGrade.GREEN, afterConsensus.verifications.last().grade)
    }

    @Test fun terminalPredictionGradesAreNotRecheckedAfterLaterEvidenceChanges() {
        val target = LocalDate.of(2026, 10, 9)
        val (ledger, prediction) = PredictionLearningCycle.lock(
            history(), Market.SRIDEVI, "main", target, PannaSide.OPEN, locked,
            limit = 10, minimumHistory = 5
        )
        val agreed = result(prediction.candidates.first(), "456")
        val consensus = listOf(
            observation(target, "publisher-a", agreed),
            observation(target, "publisher-b", agreed)
        )
        val (verifiedLedger, verification) = PredictionLearningCycle.verify(
            ledger, prediction.id, consensus, verified
        )
        assertEquals(PredictionGrade.GREEN, verification.grade)
        val changed = consensus + observation(target, "publisher-c", result("678", "901"))
        assertFalse(PredictionLearningCycle.needsReverification(verifiedLedger, prediction.id, changed))
    }

    @Test fun predictionCannotBeReplacedOrCreatedAfterTargetResultExists() {
        val target = LocalDate.of(2026, 10, 9)
        val (ledger, prediction) = PredictionLearningCycle.lock(
            history(), Market.SRIDEVI, "main", target, PannaSide.OPEN, locked,
            limit = 10, minimumHistory = 5
        )
        try {
            PredictionLearningCycle.lock(
                history(), Market.SRIDEVI, "main", target, PannaSide.OPEN, locked, ledger,
                limit = 10, minimumHistory = 5
            )
            fail("Expected duplicate lock rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("already locked"))
        }
        val targetResult = observation(target, "publisher-a", result("123", "456"))
        try {
            PredictionLearningCycle.lock(
                history() + targetResult, Market.SRIDEVI, "main", target, PannaSide.CLOSE, locked,
                limit = 10, minimumHistory = 5
            )
            fail("Expected post-result prediction rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("already present"))
        }
        assertEquals(prediction.id, ledger.lockedPredictions.single().id)
    }

    @Test fun greyVerificationCanBeRecheckedWithoutRewritingPriorAttempt() {
        val target = LocalDate.of(2026, 10, 9)
        val (ledger, prediction) = PredictionLearningCycle.lock(
            history(), Market.SRIDEVI, "main", target, PannaSide.OPEN, locked,
            limit = 10, minimumHistory = 5
        )
        val actual = result(prediction.candidates.first(), "456")
        val firstPublisher = observation(target, "publisher-a", actual)
        val (afterGrey, first) = PredictionLearningCycle.verify(
            ledger, prediction.id, listOf(firstPublisher), verified
        )
        assertEquals(PredictionGrade.GREY, first.grade)

        val consensus = listOf(firstPublisher, observation(target, "publisher-b", actual))
        val (rechecked, second) = PredictionLearningCycle.verify(
            afterGrey, prediction.id, consensus, verified.plusSeconds(60)
        )
        assertEquals(PredictionGrade.GREEN, second.grade)
        assertEquals(2, second.attempt)
        assertEquals(2, rechecked.verifications.size)
        assertEquals(PredictionGrade.GREY, rechecked.verifications.first().grade)
        assertEquals(PredictionGrade.GREEN, rechecked.verifications.last().grade)
        assertEquals(1, rechecked.performance(Market.SRIDEVI, "main", PannaSide.OPEN).green)
        assertEquals(1, afterGrey.verifications.size)
        assertEquals(1, afterGrey.verifications.single().attempt)
    }

    @Test fun ledgerRejectsGapsAndAttemptsAfterTerminalGrade() {
        val target = LocalDate.of(2026, 10, 9)
        val (_, prediction) = PredictionLearningCycle.lock(
            history(), Market.SRIDEVI, "main", target, PannaSide.OPEN, locked,
            limit = 10, minimumHistory = 5
        )
        val grey = PredictionVerification(
            prediction.id, PredictionGrade.GREY, null, null, null, verified,
            ReconciliationState.SINGLE_SOURCE_UNVERIFIED, "Provisional.", attempt = 1
        )
        val green = grey.copy(
            grade = PredictionGrade.GREEN, actualPanna = prediction.candidates.first(),
            matchedRank = 1, reconciliationState = ReconciliationState.CONSENSUS,
            explanation = "Consensus.", attempt = 2
        )
        val invalid = green.copy(attempt = 3)
        try {
            PredictionLearningLedger(listOf(prediction), listOf(grey, green, invalid))
            fail("Expected attempts after terminal grade to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("terminal"))
        }
        try {
            PredictionLearningLedger(listOf(prediction), listOf(grey, green.copy(attempt = 3)))
            fail("Expected missing attempt number to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("sequential"))
        }
    }

    @Test fun terminalGreenOrRedCannotBeVerifiedAgain() {
        val target = LocalDate.of(2026, 10, 9)
        val (ledger, prediction) = PredictionLearningCycle.lock(
            history(), Market.SRIDEVI, "main", target, PannaSide.OPEN, locked,
            limit = 10, minimumHistory = 5
        )
        val actual = result(prediction.candidates.first(), "456")
        val consensus = listOf(
            observation(target, "publisher-a", actual),
            observation(target, "publisher-b", actual)
        )
        val (verifiedLedger, first) = PredictionLearningCycle.verify(
            ledger, prediction.id, consensus, verified
        )
        assertEquals(PredictionGrade.GREEN, first.grade)
        try {
            PredictionLearningCycle.verify(verifiedLedger, prediction.id, consensus, verified.plusSeconds(60))
            fail("Expected terminal prediction verification to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("terminal"))
        }
        assertEquals(1, verifiedLedger.verifications.size)
    }
}
