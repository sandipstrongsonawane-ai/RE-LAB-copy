package com.relab.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeekdayPatternCsvExporterTest {
    @Test
    fun exportsHeaderAndEveryFindingIncludingInsufficientHistory() {
        val finding = WeekdayPatternFinding(
            Market.KALYAN, "main", "open_ank", 12, 2, null, null, null,
            WeekdayPatternStatus.INSUFFICIENT_HISTORY, "Need more history, across weekdays"
        )
        val csv = WeekdayPatternCsvExporter.export(listOf(finding))
        val lines = csv.trimEnd().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("adjusted_q_value"))
        assertTrue(lines[1].contains("INSUFFICIENT_HISTORY"))
        assertTrue(lines[1].contains("\"\"Need more history, across weekdays\"\"") || lines[1].contains("\"Need more history, across weekdays\""))
    }
}
