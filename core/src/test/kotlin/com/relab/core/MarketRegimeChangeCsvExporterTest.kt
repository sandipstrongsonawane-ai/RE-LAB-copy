package com.relab.core

import org.junit.Assert.assertTrue
import org.junit.Test

class MarketRegimeChangeCsvExporterTest {
    @Test
    fun exportsDeterministicHeaderValuesAndEscapedExplanation() {
        val finding = RegimeChangeFinding(
            Market.SRIDEVI, "main", "open", 20, 20,
            0.35, 0.004, 0.008, RegimeChangeStatus.SHIFT_DETECTED,
            "Recent shift, review weights\nnot a forecast"
        )
        val csv = MarketRegimeChangeCsvExporter.export(listOf(finding))
        assertTrue(csv.startsWith(MarketRegimeChangeCsvExporter.HEADER + "\n"))
        assertTrue(csv.contains("\"Recent shift, review weights\nnot a forecast\""))
        assertTrue(csv.contains("\"SRIDEVI\""))
        assertTrue(csv.contains("SHIFT_DETECTED"))
    }

    @Test
    fun emptyExportStillContainsStableHeader() {
        assertTrue(MarketRegimeChangeCsvExporter.export(emptyList()) == MarketRegimeChangeCsvExporter.HEADER + "\n")
    }
}
