package com.relab.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/**
 * Exact publisher response captured during refresh. The HTML is retained even when
 * the chart parser rejects the page, so parser failures remain auditable evidence.
 */
data class SourcePageSnapshot(
    val sourceId: String,
    val market: Market,
    val sessionId: String,
    val requestedUrl: String,
    val finalUrl: String,
    val retrievedAt: Instant,
    val html: String
) {
    val sha256: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest(html.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    val utf8Bytes: Int
        get() = html.toByteArray(StandardCharsets.UTF_8).size
}
