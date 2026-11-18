package com.relab.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossMarketAssociationCsvExporterTest {
    @Test
    fun exportsAssociationEvidenceAndCsvEscapedSessions() {
        val association = CrossMarketAssociation(
            firstMarket = Market.SRIDEVI,
            firstSessionId = "main,day",
            secondMarket = Market.KALYAN,
            secondSessionId = "night",
            lagDays = 1,
            metric = CrossMarketMetric.OPEN_FAMILY,
            pairedRecords = 42,
            mutualInformationBits = 0.25,
            normalizedMutualInformation = 0.31,
            empiricalPValue = 0.01,
            adjustedPValue = 0.04,
            statisticallyUnusual = true
        )
        val report = CrossMarketReverseEngineeringReport(
            seriesAnalyzed = 2,
            invalidRecordsSkipped = 1,
            pairComparisons = 1,
            associations = listOf(association),
            note = "Exploratory only"
        )

        val csv = CrossMarketAssociationCsvExporter.export(report)
        val lines = csv.lines()
        assertEquals(CrossMarketAssociationCsvExporter.HEADER, lines.first())
        assertEquals(2, lines.size)
        assertTrue(lines[1].startsWith("SRIDEVI,\"main,day\",KALYAN,night,1,OPEN_FAMILY,42"))
        assertTrue(lines[1].endsWith(",true"))
    }

    @Test
    fun nullReportProducesHeaderOnlyRatherThanInventingFindings() {
        assertEquals(
            CrossMarketAssociationCsvExporter.HEADER,
            CrossMarketAssociationCsvExporter.export(null)
        )
    }
}
