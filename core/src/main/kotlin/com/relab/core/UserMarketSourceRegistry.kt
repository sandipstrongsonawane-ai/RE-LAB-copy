package com.relab.core

/**
 * Pure registry operations for an unbounded collection of user-supplied chart URLs.
 * sourceId is the publisher identity, not the URL: two pages from one publisher
 * therefore remain one evidence source during reconciliation.
 */
object UserMarketSourceRegistry {
    fun add(existing: List<MarketSource>, source: MarketSource): List<MarketSource> {
        val normalized = normalizeUrl(source.url)
        require(existing.none { normalizeUrl(it.url) == normalized }) {
            "This exact chart URL is already registered."
        }
        require(source.sourceId.isNotBlank()) { "Publisher identity is required." }
        return existing + source.copy(url = normalized)
    }

    fun update(existing: List<MarketSource>, originalUrl: String, updated: MarketSource): List<MarketSource> {
        require(existing.any { it.url == originalUrl }) { "The source to update was not found." }
        val normalized = normalizeUrl(updated.url)
        require(existing.none { it.url != originalUrl && normalizeUrl(it.url) == normalized }) {
            "This exact chart URL is already registered."
        }
        return existing.map { if (it.url == originalUrl) updated.copy(url = normalized) else it }
    }

    fun setEnabled(existing: List<MarketSource>, url: String, enabled: Boolean): List<MarketSource> =
        existing.map { if (it.url == url) it.copy(enabled = enabled) else it }

    fun remove(existing: List<MarketSource>, url: String): List<MarketSource> =
        existing.filterNot { it.url == url }

    fun normalizeUrl(raw: String): String {
        val uri = java.net.URI(raw.trim()).normalize()
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "Chart sources must use an absolute HTTPS URL."
        }
        require(uri.userInfo == null) { "URLs containing embedded credentials are not allowed." }
        return uri.toASCIIString().trimEnd('/')
    }
}
