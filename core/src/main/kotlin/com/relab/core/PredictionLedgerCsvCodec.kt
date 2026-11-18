package com.relab.core

import java.time.Instant
import java.time.LocalDate

/** Portable, lossless CSV representation of locked predictions and every immutable verification attempt. */
object PredictionLedgerCsvCodec {
    private val header = listOf("record_type","prediction_id","market","session_id","target_date","side","candidates","training_records","locked_at","evidence_note","attempt","grade","actual_panna","actual_jodi","matched_rank","verified_at","reconciliation_state","explanation")

    fun export(ledger: PredictionLearningLedger): String = buildString {
        appendLine(header.joinToString(",") { quote(it) })
        ledger.lockedPredictions.forEach { p ->
            appendLine(listOf("PREDICTION",p.id,p.market.name,p.sessionId,p.targetDate.toString(),p.side.name,
                p.candidates.joinToString("|"),p.trainingRecords.toString(),p.lockedAt.toString(),p.evidenceNote,
                "","","","","","","","").joinToString(",") { quote(it) })
        }
        ledger.verifications.forEach { v ->
            val p = ledger.lockedPredictions.single { it.id == v.predictionId }
            appendLine(listOf("VERIFICATION",v.predictionId,p.market.name,p.sessionId,p.targetDate.toString(),p.side.name,
                "","","","",v.attempt.toString(),v.grade.name,v.actualPanna.orEmpty(),v.actualJodi.orEmpty(),
                v.matchedRank?.toString().orEmpty(),v.verifiedAt.toString(),v.reconciliationState.name,v.explanation)
                .joinToString(",") { quote(it) })
        }
    }

    fun import(csv: String): PredictionLearningLedger {
        val rows = parse(csv)
        require(rows.isNotEmpty() && rows.first() == header) { "Prediction ledger CSV header is invalid." }
        val predictions = mutableListOf<LockedSidePrediction>()
        val verifications = mutableListOf<PredictionVerification>()
        rows.drop(1).forEachIndexed { index, row ->
            require(row.size == header.size) { "Invalid prediction ledger row ${index + 2}." }
            when (row[0]) {
                "PREDICTION" -> {
                    val p = LockedSidePrediction(Market.valueOf(row[2]),row[3],LocalDate.parse(row[4]),
                        PannaSide.valueOf(row[5]),row[6].split("|").filter { it.isNotEmpty() },
                        row[7].toInt(),Instant.parse(row[8]),row[9])
                    require(p.id == row[1]) { "Prediction identity mismatch at row ${index + 2}." }
                    predictions += p
                }
                "VERIFICATION" -> verifications += PredictionVerification(
                    row[1],PredictionGrade.valueOf(row[11]),row[12].ifEmpty { null },row[13].ifEmpty { null },
                    row[14].ifEmpty { null }?.toInt(),Instant.parse(row[15]),
                    ReconciliationState.valueOf(row[16]),row[17],row[10].toInt()
                )
                else -> error("Unknown prediction ledger record type at row ${index + 2}.")
            }
        }
        return PredictionLearningLedger(predictions,verifications)
    }

    private fun quote(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    private fun parse(input: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (quoted) {
                if (c == '"' && i + 1 < input.length && input[i + 1] == '"') { field.append('"'); i++ }
                else if (c == '"') quoted = false else field.append(c)
            } else when (c) {
                '"' -> { require(field.isEmpty()) { "Malformed quoted ledger field." }; quoted = true }
                ',' -> { row += field.toString(); field.setLength(0) }
                '\n' -> { row += field.toString().removeSuffix("\r"); field.setLength(0); if (row.any { it.isNotEmpty() }) rows += row; row = mutableListOf() }
                else -> field.append(c)
            }
            i++
        }
        require(!quoted) { "Unclosed quoted ledger field." }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString().removeSuffix("\r"); rows += row }
        return rows
    }
}
