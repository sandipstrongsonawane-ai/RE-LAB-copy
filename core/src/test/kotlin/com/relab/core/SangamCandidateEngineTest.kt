package com.relab.core

import org.junit.Assert.*
import org.junit.Test

class SangamCandidateEngineTest {
    private fun candidate(panna: String, score: Double) = PannaCandidate(
        panna = panna,
        familySequence = panna.map { ChartMath.family(it.digitToInt()).toString() }.joinToString(""),
        pannaType = ChartMath.pannaType(panna),
        score = score,
        exactHistoricalCount = 1,
        familyHistoricalCount = 2,
        weekdayFamilyCount = 1,
        previousFamilyTransitionCount = 1
    )

    @Test fun derivesBothHalfSangamFormsAndFullSangam() {
        val result = SangamCandidateEngine.derive(
            listOf(candidate("277", 80.0)),
            listOf(candidate("249", 60.0))
        )
        assertEquals(2, result.halfSangam.size)
        assertTrue(result.halfSangam.any {
            it.kind == HalfSangamKind.OPEN_PANNA_CLOSE_ANK && it.label == "277-5"
        })
        assertTrue(result.halfSangam.any {
            it.kind == HalfSangamKind.OPEN_ANK_CLOSE_PANNA && it.label == "6-249"
        })
        assertEquals("277-249", result.fullSangam.single().label)
        assertEquals(48.0, result.fullSangam.single().score, 0.0001)
    }

    @Test fun aggregatesRepeatedHalfCombinationsWithoutLosingSupportCount() {
        val result = SangamCandidateEngine.derive(
            listOf(candidate("277", 80.0), candidate("118", 60.0)),
            listOf(candidate("249", 60.0), candidate("168", 40.0))
        )
        val openPannaCloseAnk = result.halfSangam.single {
            it.kind == HalfSangamKind.OPEN_PANNA_CLOSE_ANK && it.label == "277-5"
        }
        assertEquals(2, openPannaCloseAnk.supportingPairs)
        assertEquals(40.0, openPannaCloseAnk.score, 0.0001)
    }

    @Test fun emptyInputsAbstainAndInvalidLimitIsRejected() {
        assertTrue(SangamCandidateEngine.derive(emptyList(), listOf(candidate("249", 50.0))).fullSangam.isEmpty())
        try {
            SangamCandidateEngine.derive(listOf(candidate("277", 50.0)), listOf(candidate("249", 50.0)), 0)
            fail("Expected invalid limit")
        } catch (_: IllegalArgumentException) { }
    }
}
