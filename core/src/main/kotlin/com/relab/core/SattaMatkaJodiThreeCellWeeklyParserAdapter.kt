package com.relab.core

import org.jsoup.Jsoup
import java.net.URI
import java.time.Instant
import java.util.Locale

/**
 * SattaMatkaJodi's weekly tables expose each weekday as three adjacent cells:
 * open panna, jodi, close panna. The generic weekday-matrix adapter expects one
 * cell per day, so this host/layout-gated adapter delegates to the proven
 * three-cell HtmlChartImporter instead of misreading the open panna as a result.
 */
object SattaMatkaJodiThreeCellWeeklyParserAdapter : ChartParserAdapter {
    override val id: String = "sattamatkajodi-three-cell-weekly-v1"

    private val weekdayAliases = mapOf(
        "mon" to 1, "monday" to 1,
        "tue" to 2, "tues" to 2, "tuesday" to 2,
        "wed" to 3, "wednesday" to 3,
        "thu" to 4, "thur" to 4, "thurs" to 4, "thursday" to 4,
        "fri" to 5, "friday" to 5,
        "sat" to 6, "saturday" to 6,
        "sun" to 7, "sunday" to 7
    )
    private val dateToken = Regex("""\d{1,2}[/-]\d{1,2}[/-]\d{2,4}""")

    override fun supports(html: String, pageUrl: String): Boolean {
        val host = runCatching {
            URI(pageUrl).host?.lowercase(Locale.ROOT)?.removePrefix("www.")
        }.getOrNull()
        if (host != "sattamatkajodi.com") return false
        return runCatching {
            Jsoup.parse(html, pageUrl).select("table").any { table ->
                val rows = table.select("tr")
                val header = rows.firstOrNull()?.select("th,td")?.map {
                    it.text().trim().lowercase(Locale.ROOT)
                }.orEmpty()
                if (header.size !in 7..8 || header.firstOrNull() !in setOf("date", "date range")) {
                    false
                } else {
                    val days = header.drop(1).mapNotNull { weekdayAliases[it] }
                    days.size == header.size - 1 &&
                        days == (1..days.size).toList() &&
                        rows.drop(1).any { row ->
                            val cells = row.select("th,td").map { it.text().trim() }
                            cells.size == 1 + (days.size * 3) &&
                                dateToken.findAll(cells.firstOrNull().orEmpty()).count() == 2
                        }
                }
            }
        }.getOrDefault(false)
    }

    override fun parse(
        html: String,
        pageUrl: String,
        market: Market,
        sessionId: String,
        retrievedAt: Instant
    ): ChartParserResult {
        require(supports(html, pageUrl)) {
            "UNSUPPORTED: SattaMatkaJodi three-cell weekly table layout was not recognized."
        }
        val imported = HtmlChartImporter.import(html, pageUrl, market, sessionId, retrievedAt)
        return ChartParserResult(id, imported.observations, imported.skippedRows, imported.issues)
    }
}
