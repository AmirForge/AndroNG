package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DelayTestUrlTest {
    @Test
    fun `missing and legacy default URLs resolve to the HTTP endpoint`() {
        val expected = "http://www.gstatic.com/generate_204"
        assertEquals(expected, SettingsManager.resolveDelayTestUrl(null))
        assertEquals(expected, SettingsManager.resolveDelayTestUrl(""))
        assertEquals(expected, SettingsManager.resolveDelayTestUrl("https://www.gstatic.com/generate_204"))
        assertEquals(expected, SettingsManager.resolveDelayTestUrl(expected))
    }

    @Test
    fun `custom URLs keep their scheme and path`() {
        val customUrl = "https://www.gstatic.com/custom"
        assertEquals(customUrl, SettingsManager.resolveDelayTestUrl(customUrl))
    }
}
