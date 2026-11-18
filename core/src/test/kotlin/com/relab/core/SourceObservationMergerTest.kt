package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SourceObservationMergerTest {
    private fun observation(source: String, result: String, retrievedAt: String = "2026-11-17T10:00:00Z") =
        SourceObservation(
            market = Market.SRIDEVI,
            sessionId = "main",
            date = LocalDate.of(2026, 11, 17),
            sourceId = source,
            retrievedAt = Instant.parse(retrievedAt),
            rawText = result,
            normalizedResult = result,
            status = ObservationStatus.PUBLISHED
        )

    @Test fun appendsNewSourcesAndPreservesConflicts() {
        val first = observation("source-a", "115-73-229")
        val conflict = observation("source-b", "566-73-148")
        val result = SourceObservationMerger.append(listOf(first), listOf(conflict))

        assertEquals(1, result.addedCount)
        assertEquals(0, result.alreadyPresentCount)
        assertEquals(listOf(first, conflict), result.observations)
        assertEquals(
            ReconciliationState.CONFLICT,
            SourceReconciliation.reconcile(result.observations).single().state
        )
    }

    @Test fun exactReimportIsIdempotentButDuplicatesInsideFirstBatchRemain() {
        val duplicateA = observation("archive", "115-73-229")
        val duplicateB = duplicateA.copy(rawText = "115-73-229")
        val firstImport = SourceObservationMerger.append(emptyList(), listOf(duplicateA, duplicateB))
        assertEquals(2, firstImport.addedCount)
        assertEquals(2, firstImport.observations.size)

        val secondImport = SourceObservationMerger.append(firstImport.observations, listOf(duplicateA, duplicateB))
        assertEquals(0, secondImport.addedCount)
        assertEquals(2, secondImport.alreadyPresentCount)
        assertEquals(2, secondImport.observations.size)
    }

    @Test fun sameResultFromDifferentRetrievalTimesRemainsSeparateEvidence() {
        val earlier = observation("archive", "115-73-229")
        val later = observation("archive", "115-73-229", "2026-11-17T11:00:00Z")
        val result = SourceObservationMerger.append(listOf(earlier), listOf(later))

        assertEquals(1, result.addedCount)
        assertEquals(2, result.observations.size)
    }

    @Test fun multipleUrlsFromSamePublisherDoNotCountAsIndependentConsensus() {
        val first = observation("same-publisher.example", "115-73-229").copy(
            sourceUrl = "https://same-publisher.example/chart-a"
        )
        val second = first.copy(
            sourceUrl = "https://same-publisher.example/chart-b",
            retrievedAt = Instant.parse("2026-11-17T11:00:00Z")
        )

        val reconciled = SourceReconciliation.reconcile(listOf(first, second)).single()
        assertEquals(ReconciliationState.SINGLE_SOURCE_UNVERIFIED, reconciled.state)
        assertNull(reconciled.agreedResult)
        assertEquals(2, reconciled.observations.size)
    }

    @Test fun repeatedSourceRecordWithSameContentIsAuditedWithoutDeletingSnapshots() {
        val first = observation("archive", "115-73-229").copy(sourceRecordId = "2026-11-17")
        val later = first.copy(retrievedAt = Instant.parse("2026-11-17T11:00:00Z"))
        val audit = SourceObservationMerger.auditRepeatedSourceRecords(listOf(first, later))

        assertEquals(1, audit.size)
        assertEquals(DuplicateObservationState.REPEATED_SAME_CONTENT, audit.single().state)
        assertEquals(2, audit.single().observations.size)
    }

    @Test fun changedValueForSameSourceRecordIsFlaggedAndBothValuesRemain() {
        val first = observation("archive", "115-73-229").copy(sourceRecordId = "2026-11-17")
        val later = first.copy(
            retrievedAt = Instant.parse("2026-11-17T11:00:00Z"),
            rawText = "577-99-667",
            normalizedResult = "577-99-667"
        )
        val audit = SourceObservationMerger.auditRepeatedSourceRecords(listOf(first, later))

        assertEquals(1, audit.size)
        assertEquals(DuplicateObservationState.SOURCE_RECORD_CHANGED, audit.single().state)
        assertEquals(listOf("115-73-229", "577-99-667"), audit.single().observations.mapNotNull { it.normalizedResult })
    }

    @Test fun flagsSamePublishedResultAcrossDistinctDatesAsReviewSignal() {
        val first = observation("archive", "390-24-257").copy(date = LocalDate.of(2026, 11, 9))
        val second = first.copy(date = LocalDate.of(2026, 11, 16))
        val third = first.copy(date = LocalDate.of(2026, 11, 17))
        val audit = SourceObservationMerger.auditRepeatedPublishedResultsAcrossDates(listOf(first, second, third))
        assertEquals(1, audit.size)
        assertEquals("390-24-257", audit.single().result)
        assertEquals(3, audit.single().distinctDates)
        assertEquals(listOf(9, 16, 17), audit.single().observations.map { it.date.dayOfMonth })
    }

    @Test fun snapshotsOnSameDateDoNotInflateDistinctDateCount() {
        val first = observation("archive", "390-24-257")
        val second = first.copy(retrievedAt = Instant.parse("2026-11-17T11:00:00Z"))
        assertTrue(SourceObservationMerger.auditRepeatedPublishedResultsAcrossDates(listOf(first, second), 2).isEmpty())
    }

    @Test fun repeatedResultAuditSeparatesPublisherSessionAndPublishedStatus() {
        val first = observation("archive", "390-24-257")
        val rows = listOf(
            first,
            first.copy(date = LocalDate.of(2026, 11, 16)),
            first.copy(date = LocalDate.of(2026, 11, 15)),
            first.copy(sourceId = "other", date = LocalDate.of(2026, 11, 14)),
            first.copy(sessionId = "day", date = LocalDate.of(2026, 11, 13)),
            first.copy(date = LocalDate.of(2026, 11, 12), status = ObservationStatus.PENDING, normalizedResult = null)
        )
        val audit = SourceObservationMerger.auditRepeatedPublishedResultsAcrossDates(rows)
        assertEquals(1, audit.size)
        assertEquals("archive", audit.single().sourceId)
        assertEquals("main", audit.single().sessionId)
        try {
            SourceObservationMerger.auditRepeatedPublishedResultsAcrossDates(rows, 1)
            fail("Expected threshold validation")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("at least 2"))
        }
    }

    @Test fun observationsWithoutPublisherRecordIdentityAreNotGuessedAsDuplicates() {
        val first = observation("archive", "115-73-229")
        val later = first.copy(retrievedAt = Instant.parse("2026-11-17T11:00:00Z"))
        assertTrue(SourceObservationMerger.auditRepeatedSourceRecords(listOf(first, later)).isEmpty())
    }
}
