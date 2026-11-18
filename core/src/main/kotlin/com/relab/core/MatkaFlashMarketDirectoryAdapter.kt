package com.relab.core

import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale

/**
 * Discovers dated panel-chart pages from MatkaFlash's public all-market directory.
 * New market slugs are preserved as distinct dynamic Market identities.
 */
object MatkaFlashMarketDirectoryAdapter {
    const val DIRECTORY_URL = "https://matkaflash.com/charts?type=panel"

    fun discover(html: String, pageUrl: String = DIRECTORY_URL): List<MarketSource> {
        require(html.isNotBlank()) { "UNSUPPORTED: market directory page is empty." }
        val page = URI(pageUrl)
        require(page.host?.lowercase(Locale.ROOT)?.removePrefix("www.") == "matkaflash.com") {
            "Only the MatkaFlash market directory is supported."
        }
        require(page.path.trimEnd('/') == "/charts" && page.rawQuery.orEmpty().contains("type=panel")) {
            "URL is not the MatkaFlash panel-chart directory."
        }

        val document = Jsoup.parse(html, pageUrl)
        return document.select("a[href]").mapNotNull { link ->
            if (!link.text().trim().equals("Panel Chart", ignoreCase = true)) return@mapNotNull null
            val target = runCatching { URI(link.absUrl("href")) }.getOrNull() ?: return@mapNotNull null
            if (target.scheme?.lowercase(Locale.ROOT) != "https" ||
                target.host?.lowercase(Locale.ROOT)?.removePrefix("www.") != "matkaflash.com"
            ) return@mapNotNull null
            val path = target.path?.lowercase(Locale.ROOT)?.trimEnd('/') ?: return@mapNotNull null
            if (!path.endsWith("/panel-chart")) return@mapNotNull null
            val slug = path.removeSuffix("/panel-chart").substringAfterLast('/').trim()
            if (slug.isBlank() || !slug.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) return@mapNotNull null

            val market = Market.valueOf(slug)
            val session = if (market.name.endsWith("_NIGHT")) "night" else "main"
            MarketSource(
                market = market,
                sessionId = session,
                sourceId = "matkaflash",
                url = target.toASCIIString(),
                view = "panel-history",
                enabled = false,
                note = "Discovered from MatkaFlash panel directory; enable before refresh."
            )
        }.distinctBy { UserMarketSourceRegistry.normalizeUrl(it.url) }
            .sortedWith(compareBy<MarketSource> { it.market.name }.thenBy { it.sessionId })
    }
}
