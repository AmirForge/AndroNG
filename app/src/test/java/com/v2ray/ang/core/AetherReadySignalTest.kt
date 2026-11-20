package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AetherReadySignalTest {
    @Test fun initialStartCanBecomeReadyAfterItsMonitorWasCreated() {
        assertTrue(AetherReadySignal.accepts(1, 1, reload = false, sameMonitor = false))
        assertTrue(AetherReadySignal.accepts(1, 1, reload = true, sameMonitor = true))
        assertFalse(AetherReadySignal.accepts(1, 1, reload = true, sameMonitor = false))
    }
    @Test fun stopOrNewStartRejectsAnAlreadyQueuedReadinessSignal() {
        assertFalse(AetherReadySignal.accepts(1, 2, reload = false, sameMonitor = false))
        assertFalse(AetherReadySignal.accepts(1, 2, reload = true, sameMonitor = true))
    }
}
