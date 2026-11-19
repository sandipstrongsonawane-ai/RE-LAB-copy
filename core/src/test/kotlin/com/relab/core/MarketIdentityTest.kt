package com.relab.core

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek

class MarketIdentityTest {
    @Test fun unknownMarketNamesKeepTheirOwnStableIdentity() {
        val spaced = Market.valueOf("Puna Bazar")
        val underscored = Market.valueOf("PUNA_BAZAR")

        assertEquals("PUNA_BAZAR", spaced.name)
        assertEquals(spaced, underscored)
        assertNotEquals(spaced, Market.KALYAN)
        assertEquals(DayOfWeek.entries.toSet(), spaced.activeDays)
    }

    @Test fun knownMarketConstantsRemainBackwardCompatible() {
        assertSame(Market.KALYAN, Market.valueOf("Kalyan"))
        assertSame(Market.SRIDEVI, Market.valueOf("Sridevi"))
        assertEquals("KALYAN_MORNING", Market.valueOf("Kalyan Morning").name)
    }

    @Test fun detectsAnyMatkaFlashPanelChartMarketFromItsSlug() {
        assertEquals(
            "PUNA_BAZAR",
            Market.fromSourceUrl("https://matkaflash.com/puna-bazar/panel-chart")?.name
        )
        assertEquals(
            Market.KALYAN_NIGHT,
            Market.fromSourceUrl("https://matkaflash.com/kalyan-night/panel-chart")
        )
        assertEquals(
            Market.MILAN_DAY,
            Market.fromSourceUrl("https://matkaflash.com/milan-day/panel-chart")
        )
    }
}
