package com.v2ray.ang.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class UpstreamStateTest {
    @Test fun `network changes trigger handover and losing the old network does not clear the new one`() {
        val state = UpstreamState<String>()
        assertEquals(UpstreamState.Arrival.NONE, state.onAvailable("wifi"))
        assertEquals(UpstreamState.Arrival.NONE, state.onAvailable("wifi"))
        assertEquals(UpstreamState.Arrival.HANDOVER, state.onAvailable("mobile"))
        assertFalse(state.onLost("wifi")); assertTrue(state.available); assertEquals("mobile", state.network)
        assertTrue(state.onLost("mobile")); assertFalse(state.available)
        assertEquals(UpstreamState.Arrival.HANDOVER, state.onAvailable("mobile"))
    }
    @Test fun `recovery requested before the first network is not lost`() {
        val state = UpstreamState<String>()
        assertFalse(state.requestRecovery())
        assertEquals(UpstreamState.Arrival.RECOVERY, state.onAvailable("wifi"))
        assertEquals(UpstreamState.Arrival.NONE, state.onAvailable("wifi"))
        assertTrue(state.requestRecovery())
    }
    @Test fun `unregister clears offline recovery and old topology`() {
        val state = UpstreamState<String>()
        state.requestRecovery(); state.onAvailable("wifi"); state.onLost("wifi"); state.requestRecovery()
        state.clear()
        assertNull(state.network); assertFalse(state.available)
        assertEquals(UpstreamState.Arrival.NONE, state.onAvailable("mobile"))
    }
}
