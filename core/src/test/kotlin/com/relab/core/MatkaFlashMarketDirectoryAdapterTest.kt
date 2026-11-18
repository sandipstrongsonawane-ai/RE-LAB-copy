package com.relab.core

import org.junit.Assert.*
import org.junit.Test

class MatkaFlashMarketDirectoryAdapterTest {
    @Test fun discoversOnlyDatedMatkaFlashPanelChartLinks() {
        val html = """
            <html><body>
              <a href="/charts?type=panel">Panel Charts</a>
              <section><h2>Kalyan</h2><a href="/kalyan/panel-chart">Panel Chart</a></section>
              <section><h2>Puna Bazar</h2><a href="/puna-bazar/panel-chart">Panel Chart</a></section>
              <section><h2>Sapna</h2><p>No dated chart yet</p></section>
              <a href="https://example.com/other/panel-chart">Panel Chart</a>
              <a href="/kalyan/panel-chart">Panel Chart</a>
            </body></html>
        """.trimIndent()

        val sources = MatkaFlashMarketDirectoryAdapter.discover(html)

        assertEquals(2, sources.size)
        assertEquals(listOf("KALYAN", "PUNA_BAZAR"), sources.map { it.market.name })
        assertTrue(sources.all { it.sourceId == "matkaflash" && !it.enabled })
        assertEquals("PUNA_BAZAR", sources.single { it.market.name == "PUNA_BAZAR" }.market.name)
    }

    @Test fun rejectsUnrelatedDirectoryHostsAndPaths() {
        val html = """<a href="/foo/panel-chart">Panel Chart</a>"""
        assertThrows(IllegalArgumentException::class.java) {
            MatkaFlashMarketDirectoryAdapter.discover(html, "https://example.com/charts?type=panel")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MatkaFlashMarketDirectoryAdapter.discover(html, "https://matkaflash.com/archive")
        }
    }
}
