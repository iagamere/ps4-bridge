package com.ps4bridge.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FmtTest {
    @Test fun etaIsStableForKnownRate() {
        assertEquals("1h 0m", Fmt.eta(60 * 60, 1.0))
    }

    @Test fun zeroSpeedHasUnknownEta() {
        assertEquals("--", Fmt.eta(1000, 0.0))
    }
}
