package com.relab.core

enum class Market(val activeDays: Set<java.time.DayOfWeek>) {
    SRIDEVI(java.time.DayOfWeek.entries.toSet()),
    SRIDEVI_NIGHT(java.time.DayOfWeek.entries.toSet()),
    KALYAN(java.time.DayOfWeek.entries.toSet()),
    TIME_BAZAR(java.time.DayOfWeek.entries.toSet()),
    MAIN_BAZAR(setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.FRIDAY)),
    // Additional named markets are explicit identities; do not collapse them into a generic bucket.
    MILAN_DAY(java.time.DayOfWeek.entries.toSet()),
    KALYAN_MORNING(java.time.DayOfWeek.entries.toSet()),
    KALYAN_NIGHT(java.time.DayOfWeek.entries.toSet());

    companion object {
        /** Resolve only the five explicitly supported source chart paths. */
        fun fromSourceUrl(value: String): Market? {
            val path = runCatching { java.net.URI(value).path.lowercase(java.util.Locale.ROOT) }
                .getOrNull() ?: return null
            return when {
                path.endsWith("/sridevi-night-satta-penal-chart.php") -> SRIDEVI_NIGHT
                path.endsWith("/sridevi-satta-penal-chart.php") -> SRIDEVI
                path.endsWith("/kalyan-penal-chart.php") -> KALYAN
                path.endsWith("/time-bazar-panel-chart.php") -> TIME_BAZAR
                path.endsWith("/main-bazar-panel-chart.php") -> MAIN_BAZAR
                else -> null
            }
        }
    }
}

enum class PannaType { SP, DP, TP }

data class ChartRecord(
    val market: Market,
    val date: java.time.LocalDate,
    val openPanna: String,
    val jodi: String,
    val closePanna: String,
    val sourceId: String,
    val sourceRecordId: String? = null,
    val publishedAt: java.time.Instant? = null,
    val observedAt: java.time.Instant? = null,
    /** Distinguishes separate chart sessions under the same market name. */
    val sessionId: String = "main"
)

data class DerivedFeatures(
    val openAnk: Int,
    val closeAnk: Int,
    val openFamilySequence: List<Int>,
    val closeFamilySequence: List<Int>,
    val openJodiFamily: Int,
    val closeJodiFamily: Int,
    val openType: PannaType,
    val closeType: PannaType,
    val reverseOpenPanna: String,
    val reverseClosePanna: String
)

sealed interface ValidationResult {
    data class Valid(val features: DerivedFeatures) : ValidationResult
    data class Invalid(val reasons: List<String>) : ValidationResult
}

object ChartMath {
    fun family(digit: Int): Int {
        require(digit in 0..9) { "Digit must be 0..9" }
        return digit % 5
    }

    fun pannaType(panna: String): PannaType {
        require(panna.length == 3 && panna.all(Char::isDigit)) { "Panna must be exactly 3 digits" }
        return when (panna.toSet().size) {
            1 -> PannaType.TP
            2 -> PannaType.DP
            else -> PannaType.SP
        }
    }

    fun validate(record: ChartRecord): ValidationResult {
        val errors = mutableListOf<String>()
        if (record.openPanna.length != 3 || !record.openPanna.all(Char::isDigit)) errors += "Open Panna must contain exactly 3 digits"
        if (record.closePanna.length != 3 || !record.closePanna.all(Char::isDigit)) errors += "Close Panna must contain exactly 3 digits"
        if (record.jodi.length != 2 || !record.jodi.all(Char::isDigit)) errors += "Jodi must contain exactly 2 digits"
        if (record.sourceId.isBlank()) errors += "Source ID is required"
        if (record.date.dayOfWeek !in record.market.activeDays) errors += "Date is outside the market's configured weekdays"
        if (errors.isNotEmpty()) return ValidationResult.Invalid(errors)

        val openDigits = record.openPanna.map { it.digitToInt() }
        val closeDigits = record.closePanna.map { it.digitToInt() }
        val openAnk = openDigits.sum() % 10
        val closeAnk = closeDigits.sum() % 10
        val expectedJodi = "$openAnk$closeAnk"
        if (record.jodi != expectedJodi) {
            return ValidationResult.Invalid(listOf("Jodi mismatch: expected $expectedJodi from Panna sums"))
        }
        val openFamilies = openDigits.map(::family)
        val closeFamilies = closeDigits.map(::family)
        return ValidationResult.Valid(
            DerivedFeatures(
                openAnk = openAnk,
                closeAnk = closeAnk,
                openFamilySequence = openFamilies,
                closeFamilySequence = closeFamilies,
                openJodiFamily = openFamilies.sum() % 5,
                closeJodiFamily = closeFamilies.sum() % 5,
                openType = pannaType(record.openPanna),
                closeType = pannaType(record.closePanna),
                reverseOpenPanna = record.openPanna.reversed(),
                reverseClosePanna = record.closePanna.reversed()
            )
        )
    }
}
