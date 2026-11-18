package com.relab.core

class Market private constructor(
    val name: String,
    val activeDays: Set<java.time.DayOfWeek>
) {
    override fun equals(other: Any?): Boolean = other is Market && name == other.name
    override fun hashCode(): Int = name.hashCode()
    override fun toString(): String = name

    companion object {
        private val allDays = java.time.DayOfWeek.entries.toSet()

        val SRIDEVI = Market("SRIDEVI", allDays)
        val SRIDEVI_NIGHT = Market("SRIDEVI_NIGHT", allDays)
        val KALYAN = Market("KALYAN", java.time.DayOfWeek.entries.filter { it != java.time.DayOfWeek.SUNDAY }.toSet())
        val TIME_BAZAR = Market("TIME_BAZAR", java.time.DayOfWeek.entries.filter { it != java.time.DayOfWeek.SUNDAY }.toSet())
        val MAIN_BAZAR = Market(
            "MAIN_BAZAR",
            setOf(
                java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY,
                java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.THURSDAY,
                java.time.DayOfWeek.FRIDAY
            )
        )
        val MILAN_DAY = Market("MILAN_DAY", allDays)
        val KALYAN_MORNING = Market("KALYAN_MORNING", allDays)
        val KALYAN_NIGHT = Market("KALYAN_NIGHT", allDays)
        val TIME_BAZAR_DAY = Market("TIME_BAZAR_DAY", allDays)
        val NEW_TIME_BAZAR = Market("NEW_TIME_BAZAR", java.time.DayOfWeek.entries.filter { it != java.time.DayOfWeek.SUNDAY }.toSet())
        val MAIN_BAZAR_NIGHT = Market("MAIN_BAZAR_NIGHT", allDays)
        val CB = Market("CB", allDays)
        val MAIN_RATAN = Market("MAIN_RATAN", java.time.DayOfWeek.entries.filter { it in setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.FRIDAY) }.toSet())
        val SUNDAY_BAZAR = Market("SUNDAY_BAZAR", allDays)
        val GEETA_MORNING = Market("GEETA_MORNING", allDays)
        val MAIN_BAZAR_MORNING = Market("MAIN_BAZAR_MORNING", allDays)

        /** Known built-in markets for legacy UI compatibility; valueOf also resolves new names. */
        val entries: List<Market> = listOf(
            SRIDEVI, SRIDEVI_NIGHT, KALYAN, TIME_BAZAR, MAIN_BAZAR,
            MILAN_DAY, KALYAN_MORNING, KALYAN_NIGHT,
            TIME_BAZAR_DAY, NEW_TIME_BAZAR, MAIN_BAZAR_NIGHT,
            CB, MAIN_RATAN, SUNDAY_BAZAR, GEETA_MORNING, MAIN_BAZAR_MORNING
        )

        fun values(): Array<Market> = entries.toTypedArray()

        /**
         * Resolve a stable market identity. Unknown names are retained as their
         * own market instead of being mapped to a generic bucket or discarded.
         * New identities default to all weekdays until a source-specific calendar
         * is independently verified.
         */
        fun valueOf(value: String): Market {
            val normalized = value.trim()
                .uppercase(java.util.Locale.ROOT)
                .replace(Regex("[^A-Z0-9]+"), "_")
                .trim('_')
            require(normalized.isNotBlank()) { "Market name is required." }
            return entries.firstOrNull { it.name == normalized } ?: Market(normalized, allDays)
        }

        fun fromName(value: String): Market? = runCatching { valueOf(value) }.getOrNull()

        /** Resolve legacy chart paths and MatkaFlash's slug-based panel-chart directory. */
        fun fromSourceUrl(value: String): Market? {
            val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return null
            val path = uri.path?.lowercase(java.util.Locale.ROOT)?.trimEnd('/') ?: return null
            return when {
                path.endsWith("/sridevi-night-satta-penal-chart.php") -> SRIDEVI_NIGHT
                path.endsWith("/sridevi-satta-penal-chart.php") -> SRIDEVI
                path.endsWith("/kalyan-penal-chart.php") -> KALYAN
                path.endsWith("/time-bazar-panel-chart.php") -> TIME_BAZAR
                path.endsWith("/main-bazar-panel-chart.php") -> MAIN_BAZAR
                uri.host?.lowercase(java.util.Locale.ROOT)?.removePrefix("www.") == "matkaflash.com" &&
                    path.endsWith("/panel-chart") -> {
                    val slug = path.removeSuffix("/panel-chart").substringAfterLast('/')
                    fromName(slug)
                }
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
