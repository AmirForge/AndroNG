package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class GalaxyTapSequenceTest {
    @Test fun `exactly seven quick taps open one easter egg`() {
        val taps = GalaxyTapSequence()
        repeat(6) { assertFalse(taps.tap(it * 100_000_000L)) }
        assertTrue(taps.tap(600_000_000L)); assertFalse(taps.tap(700_000_000L))
    }
    @Test fun `slow taps clock reversal and dismissal reset the sequence`() {
        val taps = GalaxyTapSequence()
        repeat(6) { assertFalse(taps.tap(it * 100_000_000L)) }
        assertFalse(taps.tap(2_000_000_000L))
        assertFalse(taps.tap(1_000_000_000L))
        taps.reset()
        repeat(6) { assertFalse(taps.tap(3_000_000_000L + it * 100_000_000L)) }
        assertTrue(taps.tap(3_600_000_000L))
    }
}
