package com.relab.core

import java.time.LocalDate

enum class CoverageState {
    NO_OBSERVATION,
    HOLIDAY_REPORTED,
    PENDING,
    SOURCE_MISSING,
    MALFORMED_ONLY,
    SINGLE_SOURCE_UNVERIFIED,
    CONSENSUS,
    CONFLICT
}

data class DailyCoverage(
    val date: LocalDate,
    val state: CoverageState,
    val observationCount: Int,
    val publishedSourceCount: Int,
    val pendingSourceCount: Int,
    val holidaySourceCount: Int,
    val missingSourceCount: Int,
    val malformedSourceCount: Int,
    val distinctPublishedResults: List<String>
)

data class CoverageReport(
    val market: Market,
    val sessionId: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val expectedTradingDays: Int,
    val days: List<DailyCoverage>
) {
    val publishedDays: Int get() = days.count {
        it.state == CoverageState.SINGLE_SOURCE_UNVERIFIED || it.state == CoverageState.CONSENSUS
    }
    val singleSourceDays: Int get() = days.count { it.state == CoverageState.SINGLE_SOURCE_UNVERIFIED }
    val consensusDays: Int get() = days.count { it.state == CoverageState.CONSENSUS }
    val conflictDays: Int get() = days.count { it.state == CoverageState.CONFLICT }
    val holidayDays: Int get() = days.count { it.state == CoverageState.HOLIDAY_REPORTED }
    val pendingDays: Int get() = days.count { it.state == CoverageState.PENDING }
    val sourceMissingDays: Int get() = days.count { it.state == CoverageState.SOURCE_MISSING }
    val malformedOnlyDays: Int get() = days.count { it.state == CoverageState.MALFORMED_ONLY }
    val noObservationDays: Int get() = days.count { it.state == CoverageState.NO_OBSERVATION }
}

/**
 * Audits expected calendar coverage without interpreting a missing source entry as a holiday.
 * All source observations remain represented in per-day counts.
 */
object HistoricalCoverageAudit {
    fun analyze(
        market: Market,
        sessionId: String,
        startDate: LocalDate,
        endDate: LocalDate,
        observations: List<SourceObservation>
    ): CoverageReport {
        require(sessionId.isNotBlank()) { "Session ID is required" }
        require(!endDate.isBefore(startDate)) { "End date must not be before start date" }

        val relevant = observations.filter {
            it.market == market && it.sessionId == sessionId &&
                !it.date.isBefore(startDate) && !it.date.isAfter(endDate)
        }.groupBy { it.date }

        val days = generateSequence(startDate) { date ->
            if (date == endDate) null else date.plusDays(1)
        }.filter { it.dayOfWeek in market.activeDays }.map { date ->
            val rows = relevant[date].orEmpty()
            val published = rows.filter { it.status == ObservationStatus.PUBLISHED }
                .mapNotNull { it.normalizedResult }.distinct().sorted()
            val pending = rows.count { it.status == ObservationStatus.PENDING }
            val holidays = rows.count { it.status == ObservationStatus.HOLIDAY_CLOSED }
            val missing = rows.count { it.status == ObservationStatus.MISSING_FROM_SOURCE }
            val malformed = rows.count { it.status == ObservationStatus.MALFORMED }
            val publishedPublisherCount = rows
                .filter { it.status == ObservationStatus.PUBLISHED }
                .map { it.sourceId }
                .distinct()
                .size
            val state = when {
                published.size > 1 -> CoverageState.CONFLICT
                published.size == 1 && publishedPublisherCount >= 2 -> CoverageState.CONSENSUS
                published.size == 1 -> CoverageState.SINGLE_SOURCE_UNVERIFIED
                holidays > 0 -> CoverageState.HOLIDAY_REPORTED
                pending > 0 -> CoverageState.PENDING
                missing > 0 -> CoverageState.SOURCE_MISSING
                malformed > 0 -> CoverageState.MALFORMED_ONLY
                else -> CoverageState.NO_OBSERVATION
            }
            fun distinctPublisherCount(status: ObservationStatus): Int =
                rows.filter { it.status == status }.map { it.sourceId }.distinct().size

            DailyCoverage(
                date, state, rows.size,
                distinctPublisherCount(ObservationStatus.PUBLISHED),
                distinctPublisherCount(ObservationStatus.PENDING),
                distinctPublisherCount(ObservationStatus.HOLIDAY_CLOSED),
                distinctPublisherCount(ObservationStatus.MISSING_FROM_SOURCE),
                distinctPublisherCount(ObservationStatus.MALFORMED),
                published
            )
        }.toList()

        return CoverageReport(
            market, sessionId, startDate, endDate,
            days.size, days
        )
    }
}
