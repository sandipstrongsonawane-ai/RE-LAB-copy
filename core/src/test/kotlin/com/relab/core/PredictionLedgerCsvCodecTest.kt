package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionLedgerCsvCodecTest {
    private fun fixture(): PredictionLearningLedger {
        val prediction = LockedSidePrediction(
            Market.SRIDEVI, "main", LocalDate.parse("2026-11-17"), PannaSide.OPEN,
            listOf("123", "456"), 8, Instant.parse("2026-11-17T11:00:00Z"),
            "frozen, \"fixture\"\nsecond line"
        )
        val attempt = PredictionVerification(
            prediction.id, PredictionGrade.GREY, null, null, null,
            Instant.parse("2026-11-17T12:00:00Z"), ReconciliationState.SINGLE_SOURCE_UNVERIFIED,
            "Awaiting, independent publisher.\nStill pending.", 1
        )
        return PredictionLearningLedger(listOf(prediction), listOf(attempt))
    }

    @Test
    fun roundTripsCommasQuotesAndEmbeddedNewlines() {
        val original = fixture()
        val restored = PredictionLedgerCsvCodec.import(PredictionLedgerCsvCodec.export(original))
        assertEquals(original, restored)
    }

    @Test
    fun acceptsUtf8BomBeforeFirstHeaderWithCrLfLineEndings() {
        val csv = "\uFEFF" + PredictionLedgerCsvCodec.export(fixture()).replace("\n", "\r\n")
        assertEquals(fixture(), PredictionLedgerCsvCodec.import(csv))
    }

    @Test
    fun rejectsCharactersAfterClosingQuotedField() {
        val csv = PredictionLedgerCsvCodec.export(fixture())
            .replace("\"frozen, \"\"fixture\"\"\nsecond line\"", "\"frozen, \"\"fixture\"\"\nsecond line\"x")
        val error = runCatching { PredictionLedgerCsvCodec.import(csv) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("Unexpected character after closing quote"))
    }

    @Test
    fun rejectsDuplicateVerificationAttempt() {
        val csv = PredictionLedgerCsvCodec.export(fixture())
        val verificationStart = csv.indexOf("\"VERIFICATION\"")
        val verificationRecord = csv.substring(verificationStart).trimEnd()
        val duplicated = csv + verificationRecord + "\n"
        val error = runCatching { PredictionLedgerCsvCodec.import(duplicated) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("duplicate verification attempts"))
    }

    @Test
    fun rejectsVerificationWithoutLockedPrediction() {
        val csv = PredictionLedgerCsvCodec.export(fixture())
        val predictionId = fixture().lockedPredictions.single().id
        val altered = csv.replace("\"VERIFICATION\",\"$predictionId\"", "\"VERIFICATION\",\"missing-prediction\"")
        val error = runCatching { PredictionLedgerCsvCodec.import(altered) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("without its locked prediction"))
    }

    @Test
    fun rejectsDuplicateLockedPredictionIdentity() {
        val csv = PredictionLedgerCsvCodec.export(fixture())
        val predictionStart = csv.indexOf("\"PREDICTION\"")
        val verificationStart = csv.indexOf("\"VERIFICATION\"")
        val predictionRecord = csv.substring(predictionStart, verificationStart).trimEnd()
        val duplicated = csv + predictionRecord + "\n"
        val error = runCatching { PredictionLedgerCsvCodec.import(duplicated) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("duplicate locked prediction IDs"))
    }
}
