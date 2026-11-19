package com.relab.core

import java.time.LocalDate

enum class PublisherDateState {
    PUBLISHED,
    MULTIPLE_PUBLISHED_VALUES,
    PENDING,
    HOLIDAY_REPORTED,
    SOURCE_MISSING_REPORTED,
    MALFORMED,
    NO_ROW
}

data class PublisherDateCoverage(
    val date: LocalDate,
    val sourceId: String,
    val sourceUrl: String,
    val state: PublisherDateState,
    val observationCount: Int,
    val publishedResults: List<String>
)

data class PublisherCoverageReport(
    val market: Market,
    val sessionId: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val expectedTradingDays: Int,
    val registeredUrlCount: Int,
    val days: List<PublisherDateCoverage>
) {
    val expectedUrlDateEntries: Int get() = expectedTradingDays * registeredUrlCount
    val noRowEntries: Int get() = days.count { it.state == PublisherDateState.NO_ROW }
    val publishedEntries: Int get() = days.count {
        it.state == PublisherDateState.PUBLISHED || it.state == PublisherDateState.MULTIPLE_PUBLISHED_VALUES
    }
    val malformedEntries: Int get() = days.count { it.state == PublisherDateState.MALFORMED }
}

/**
 * URL-level coverage matrix. A row is attributed only to the exact registered
 * URL and publisher identity; another URL from the same publisher cannot fill
 * this URL's coverage gap or become independent corroboration.
 */
object PublisherCoverageAudit {
    fun analyze(
        market: Market,
        sessionId: String,
        startDate: LocalDate,
        endDate: LocalDate,
        sources: List<MarketSource>,
        observations: List<SourceObservation>
    ): PublisherCoverageReport {
        require(sessionId.isNotBlank()) { "Session ID is required" }
        require(!endDate.isBefore(startDate)) { "End date must not be before start date" }

        val expectedSources = sources.filter {
            it.enabled && it.market == market && it.sessionId == sessionId
        }.distinctBy { it.url }
        val tradingDates = generateSequence(startDate) { date ->
            if (date == endDate) null else date.plusDays(1)
        }.filter { it.dayOfWeek in market.activeDays }.toList()

        val rows = observations.filter {
            it.market == market && it.sessionId == sessionId &&
                !it.date.isBefore(startDate) && !it.date.isAfter(endDate)
        }
        val coverage = expectedSources.flatMap { source ->
            tradingDates.map { date ->
                val matching = rows.filter {
                    it.date == date && it.sourceId == source.sourceId && it.sourceUrl == source.url
                }
                val results = matching.filter { it.status == ObservationStatus.PUBLISHED }
                    .mapNotNull { it.normalizedResult }.distinct().sorted()
                val state = when {
                    results.size > 1 -> PublisherDateState.MULTIPLE_PUBLISHED_VALUES
                    results.size == 1 -> PublisherDateState.PUBLISHED
                    matching.any { it.status == ObservationStatus.PENDING } -> PublisherDateState.PENDING
                    matching.any { it.status == ObservationStatus.HOLIDAY_CLOSED } -> PublisherDateState.HOLIDAY_REPORTED
                    matching.any { it.status == ObservationStatus.MISSING_FROM_SOURCE } -> PublisherDateState.SOURCE_MISSING_REPORTED
                    matching.any { it.status == ObservationStatus.MALFORMED } -> PublisherDateState.MALFORMED
                    else -> PublisherDateState.NO_ROW
                }
                PublisherDateCoverage(
                    date, source.sourceId, source.url, state, matching.size, results
                )
            }
        }.sortedWith(compareBy<PublisherDateCoverage> { it.date }
            .thenBy { it.sourceId }.thenBy { it.sourceUrl })

        return PublisherCoverageReport(
            market, sessionId, startDate, endDate,
            tradingDates.size, expectedSources.size, coverage
        )
    }
}
