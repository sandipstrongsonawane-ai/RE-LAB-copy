package com.relab.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateWalkForwardCsvExporterTest {
    @Test
    fun exportsChronologicalMetricsAndExplicitExploratoryFlag() {
        val report = CandidateWalkForwardReport(
            market = Market.SRIDEVI,
            sessionId = "main",
            side = PannaSide.OPEN,
            minimumHistory = 5,
            topN = 10,
            totalTargetDates = 12,
            evaluatedDates = 7,
            insufficientHistoryDates = 5,
            top1Hits = 2,
            top3Hits = 3,
            top5Hits = 4,
            top10Hits = 5,
            meanReciprocalRank = 0.4,
            candidates = emptyList(),
            exploratory = false,
            warning = "Historical out-of-sample only",
            baselineMetrics = CandidateWalkForwardMetrics(7, 5, 1, 2, 3, 4, 0.3),
            learningEnabledMetrics = CandidateWalkForwardMetrics(7, 5, 2, 3, 4, 5, 0.4),
            feedbackAvailability = listOf(CandidateFeedbackAvailability(LocalDate.parse("2026-11-01"), 0))
        )
        val csv = CandidateWalkForwardCsvExporter.export(listOf(report))
        assertEquals(CandidateWalkForwardCsvExporter.HEADER, csv.substringBefore('\n'))
        assertTrue(csv.contains("SRIDEVI,main,OPEN,false,5,10,12,7,5,1,2,3,4,0.3,2,3,4,5,0.4"))
        assertTrue(csv.contains("Historical out-of-sample only"))
    }
}
