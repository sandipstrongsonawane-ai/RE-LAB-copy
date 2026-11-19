package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTimingAnalyticsTest {
    private fun row(source: String, retrieved: String, published: String?, status: ObservationStatus = ObservationStatus.PUBLISHED) =
        SourceObservation(
            market = Market.SRIDEVI, sessionId = "main", date = LocalDate.parse("2026-11-16"),
            sourceId = source, retrievedAt = Instant.parse(retrieved),
            publishedAt = published?.let(Instant::parse), rawText = "source row",
            normalizedResult = if (status == ObservationStatus.PUBLISHED) "577-99-667" else null,
            status = status
        )

    @Test fun summarizesOnlyExplicitPublicationTimestampsAndNonNegativeLags() {
        val rows = listOf(
            row("publisher-a", "2026-11-16T10:00:00Z", "2026-11-16T09:00:00Z"),
            row("publisher-a", "2026-11-16T10:30:00Z", "2026-11-16T10:00:00Z"),
            row("publisher-a", "2026-11-16T10:00:00Z", null),
            row("publisher-a", "2026-11-16T10:00:00Z", "2026-11-16T11:00:00Z"),
            row("publisher-b", "2026-11-16T10:00:00Z", null, ObservationStatus.PENDING)
        )
        val summary = SourceTimingAnalytics.summarize(rows)
        val a = summary.first { it.sourceId == "publisher-a" }
        assertEquals(4, a.observations)
        assertEquals(4, a.publishedObservations)
        assertEquals(3, a.publishedTimestampCount)
        assertEquals(1, a.missingPublishedTimestampCount)
        assertEquals(1, a.negativeLagCount)
        assertEquals(45L, a.medianLagMinutes)
        assertEquals(30L, a.minimumLagMinutes)
        assertEquals(60L, a.maximumLagMinutes)
        val b = summary.first { it.sourceId == "publisher-b" }
        assertEquals(0, b.publishedObservations)
        assertEquals(null, b.medianLagMinutes)
    }

    @Test fun csvEscapesSessionAndSourceIdentifiers() {
        val row = row("publisher, \"A\"", "2026-11-16T10:00:00Z", null)
            .copy(sessionId = "main, evening")
        val csv = SourceTimingAnalytics.exportCsv(listOf(row))
        assertTrue(csv.contains("\"main, evening\",\"publisher, \"\"A\"\"\","))
    }
}
