package com.relab.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkForwardCsvExporterTest {
    private fun row(day: Int, open: String, close: String, session: String = "main"): ChartRecord {
        val openAnk = open.sumOf { it.digitToInt() } % 10
        val closeAnk = close.sumOf { it.digitToInt() } % 10
        return ChartRecord(
            Market.SRIDEVI, LocalDate.of(2026, 11, day),
            open, "$openAnk$closeAnk", close, "fixture", sessionId = session
        )
    }

    @Test fun exportsChronologicalOpenAndCloseBaselinesWithCorrectnessFlags() {
        val analyses = PatternAnalysis.analyze(
            listOf(
                row(9, "277", "249"),
                row(10, "578", "368"),
                row(11, "277", "137")
            )
        )
        val csv = WalkForwardCsvExporter.export(analyses)
        val lines = csv.lines()

        assertEquals(WalkForwardCsvExporter.HEADER, lines.first())
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("SRIDEVI,main,2026-11-10,1,023,222,false"))
        val firstRow = lines[1].split(",")
        assertEquals(listOf("313", "244", "false", "244", "false", "244", "false"), firstRow.subList(11, 18))
        assertTrue(lines[2].startsWith("SRIDEVI,main,2026-11-11,2,222,023,false"))
    }

    @Test fun escapesSessionIdentityAndEmitsHeaderForNoEvaluatedRows() {
        val analysis = PatternAnalysis.analyze(
            listOf(row(9, "277", "249", "session, \"A\""))
        )
        assertEquals(WalkForwardCsvExporter.HEADER, WalkForwardCsvExporter.export(analysis))

        val twoRows = PatternAnalysis.analyze(
            listOf(
                row(9, "277", "249", "session, \"A\""),
                row(10, "578", "368", "session, \"A\"")
            )
        )
        assertTrue(WalkForwardCsvExporter.export(twoRows).lines()[1].startsWith("SRIDEVI,\"session, \"\"A\"\"\","))
    }
}
