package com.relab.core

import java.time.Instant
import java.time.LocalDate

/** Portable, lossless CSV representation of locked predictions and every immutable verification attempt. */
object PredictionLedgerCsvCodec {
    private val header = listOf(
        "record_type", "prediction_id", "market", "session_id", "target_date", "side", "candidates",
        "training_records", "locked_at", "evidence_note", "attempt", "grade", "actual_panna",
        "actual_jodi", "matched_rank", "verified_at", "reconciliation_state", "explanation"
    )

    fun export(ledger: PredictionLearningLedger): String = buildString {
        appendLine(header.joinToString(",") { quote(it) })
        ledger.lockedPredictions.forEach { p ->
            appendLine(listOf(
                "PREDICTION", p.id, p.market.name, p.sessionId, p.targetDate.toString(), p.side.name,
                p.candidates.joinToString("|"), p.trainingRecords.toString(), p.lockedAt.toString(),
                p.evidenceNote, "", "", "", "", "", "", "", ""
            ).joinToString(",") { quote(it) })
        }
        ledger.verifications.forEach { v ->
            val p = ledger.lockedPredictions.single { it.id == v.predictionId }
            appendLine(listOf(
                "VERIFICATION", v.predictionId, p.market.name, p.sessionId, p.targetDate.toString(),
                p.side.name, "", "", "", "", v.attempt.toString(), v.grade.name,
                v.actualPanna.orEmpty(), v.actualJodi.orEmpty(), v.matchedRank?.toString().orEmpty(),
                v.verifiedAt.toString(), v.reconciliationState.name, v.explanation
            ).joinToString(",") { quote(it) })
        }
    }

    fun import(csv: String): PredictionLearningLedger {
        val rows = parse(csv).mapIndexed { index, row ->
            if (index == 0 && row.isNotEmpty()) listOf(row.first().removePrefix("\uFEFF")) + row.drop(1)
            else row
        }
        require(rows.isNotEmpty() && rows.first() == header) { "Prediction ledger CSV header is invalid." }
        val predictions = mutableListOf<LockedSidePrediction>()
        val verifications = mutableListOf<PredictionVerification>()
        rows.drop(1).forEachIndexed { index, row ->
            val rowNumber = index + 2
            require(row.size == header.size) { "Invalid prediction ledger row $rowNumber." }
            when (row[0]) {
                "PREDICTION" -> {
                    val prediction = LockedSidePrediction(
                        Market.valueOf(row[2]), row[3], LocalDate.parse(row[4]), PannaSide.valueOf(row[5]),
                        row[6].split("|").filter { it.isNotEmpty() }, row[7].toInt(),
                        Instant.parse(row[8]), row[9]
                    )
                    require(prediction.id == row[1]) { "Prediction identity mismatch at row $rowNumber." }
                    predictions += prediction
                }
                "VERIFICATION" -> {
                    val attempt = row[10].toInt()
                    require(attempt > 0) { "Verification attempt must be positive at row $rowNumber." }
                    verifications += PredictionVerification(
                        row[1], PredictionGrade.valueOf(row[11]), row[12].ifEmpty { null },
                        row[13].ifEmpty { null }, row[14].ifEmpty { null }?.toInt(),
                        Instant.parse(row[15]), ReconciliationState.valueOf(row[16]), row[17], attempt
                    )
                }
                else -> error("Unknown prediction ledger record type at row $rowNumber.")
            }
        }

        val predictionIds = predictions.map { it.id }
        require(predictionIds.size == predictionIds.toSet().size) {
            "Prediction ledger contains duplicate locked prediction IDs."
        }
        require(verifications.all { it.predictionId in predictionIds }) {
            "Prediction ledger contains a verification without its locked prediction."
        }
        val attemptKeys = verifications.map { it.predictionId to it.attempt }
        require(attemptKeys.size == attemptKeys.toSet().size) {
            "Prediction ledger contains duplicate verification attempts."
        }
        return PredictionLearningLedger(predictions, verifications)
    }

    private fun quote(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    /** Strict RFC-4180-style parser: after a closing quote only a delimiter or row ending is valid. */
    private fun parse(input: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var afterQuote = false
        var i = 0

        fun finishRow() {
            row += field.toString()
            field.setLength(0)
            if (row.any { it.isNotEmpty() }) rows += row
            row = mutableListOf()
            afterQuote = false
        }

        while (i < input.length) {
            val c = input[i]
            if (quoted) {
                if (c == '"' && i + 1 < input.length && input[i + 1] == '"') {
                    field.append('"')
                    i++
                } else if (c == '"') {
                    quoted = false
                    afterQuote = true
                } else {
                    field.append(c)
                }
            } else if (afterQuote) {
                when (c) {
                    ',' -> {
                        row += field.toString()
                        field.setLength(0)
                        afterQuote = false
                    }
                    '\n' -> finishRow()
                    '\r' -> {
                        require(i + 1 < input.length && input[i + 1] == '\n') {
                            "Malformed line ending after quoted ledger field."
                        }
                        finishRow()
                        i++
                    }
                    else -> error("Unexpected character after closing quote in prediction ledger CSV.")
                }
            } else {
                when (c) {
                    '"' -> {
                        require(field.isEmpty()) { "Malformed quoted ledger field." }
                        quoted = true
                    }
                    ',' -> {
                        row += field.toString()
                        field.setLength(0)
                    }
                    '\n' -> finishRow()
                    '\r' -> {
                        require(i + 1 < input.length && input[i + 1] == '\n') {
                            "Malformed ledger CSV line ending."
                        }
                        finishRow()
                        i++
                    }
                    else -> field.append(c)
                }
            }
            i++
        }

        require(!quoted) { "Unclosed quoted ledger field." }
        if (afterQuote || field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            if (row.any { it.isNotEmpty() }) rows += row
        }
        return rows
    }
}
